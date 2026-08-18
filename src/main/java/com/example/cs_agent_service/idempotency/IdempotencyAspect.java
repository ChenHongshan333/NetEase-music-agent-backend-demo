package com.example.cs_agent_service.idempotency;

import com.example.cs_agent_service.config.IdempotencyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code Idempotency-Key} 的实现。
 *
 * <p>用 {@code @Around} 切面而不是 {@code HandlerInterceptor}：拦截器拿不到返回对象，
 * 要抓响应体就得包一层 response wrapper 再把字节流复制出来，脏且容易漏掉边界情况。
 * 切面直接拿到 {@code ResponseEntity}，序列化即可。
 */
@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);

    public static final String HEADER_KEY = "Idempotency-Key";
    public static final String HEADER_REPLAYED = "Idempotency-Replayed";

    /**
     * 规范化用的 mapper：属性名排序 + map 按 key 排序。
     *
     * <p>hash 的是**反序列化之后再重新序列化**的对象，不是原始请求字节。
     * 直接 hash 字节的话，客户端把 {@code {"a":1,"b":2}} 换成 {@code {"b":2,"a":1}}
     * 重发同一个请求，就会被误判成"同 key 不同 body"而收到 422。
     */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final Clock clock;

    /** 序列化响应体用容器里那一个 mapper，保证回放出去的字节与首次响应一致。 */
    private final JsonMapper responseMapper;

    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
                             Clock clock, JsonMapper responseMapper) {
        this.store = store;
        this.props = props;
        this.clock = clock;
        this.responseMapper = responseMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();
        }

        String idempotencyKey = request.getHeader(HEADER_KEY);

        // 没带头就直接放行：这个特性是可选增强，不能让既有调用方一夜之间全部 400。
        if (idempotencyKey == null) {
            return pjp.proceed();
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > props.getMaxKeyLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    HEADER_KEY + " must be non-blank and at most " + props.getMaxKeyLength() + " characters");
        }

        String storageKey = buildStorageKey(request, idempotencyKey);
        String requestHash = hashRequest(pjp.getArgs());
        Duration ttl = Duration.ofHours(props.getTtlHours());
        String now = Instant.now(clock).toString();

        boolean acquired;
        try {
            acquired = store.putIfAbsent(storageKey,
                    CANONICAL.writeValueAsString(IdempotencyRecord.inProgress(requestHash, now)), ttl);
        } catch (Exception e) {
            // FAIL CLOSED。存储不可用时我们无法保证只执行一次，而重复写入的代价
            // 高于短暂不可用。这与 chat 读路径的 fail open 是故意相反的。
            log.error("[idem] store unavailable, rejecting write. key={}", storageKey, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Idempotency store unavailable; write rejected to avoid duplicate execution");
        }

        if (!acquired) {
            return handleExisting(storageKey, requestHash);
        }

        try {
            Object result = pjp.proceed();
            complete(storageKey, requestHash, statusOf(result), bodyOf(result), ttl, now);
            return result;

        } catch (ResponseStatusException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // 4xx 是确定性结果（找不到、状态冲突、参数不合法）。重放它是正确的：
                // 重试同一个请求本来也只会再次得到同一个 4xx。
                complete(storageKey, requestHash, e.getStatusCode().value(), errorBody(e), ttl, now);
            } else {
                // 5xx 可能只是瞬时故障，必须让客户端带同一个 key 重试有机会成功。
                deleteQuietly(storageKey);
            }
            throw e;

        } catch (Throwable t) {
            // 未预期异常一律按 5xx 处理：删 key，把重试的机会还给客户端。
            deleteQuietly(storageKey);
            throw t;
        }
    }

    private Object handleExisting(String storageKey, String requestHash) {
        Optional<String> raw;
        try {
            raw = store.get(storageKey);
        } catch (Exception e) {
            log.error("[idem] store unavailable on read. key={}", storageKey, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Idempotency store unavailable; write rejected to avoid duplicate execution");
        }

        if (raw.isEmpty()) {
            // SETNX 与 GET 之间 key 过期了。极罕见，但没有记录就等于没有执行权的证据，
            // 只能让客户端重试 —— 不能假设"大概没执行过"就放行。
            return conflict("A concurrent request with this " + HEADER_KEY
                    + " is being processed; please retry");
        }

        IdempotencyRecord record = CANONICAL.readValue(raw.get(), IdempotencyRecord.class);

        if (!requestHash.equals(record.requestHash())) {
            log.warn("[idem] key reused with a different payload. key={}", storageKey);
            return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                    HEADER_KEY + " reused with a different request payload");
        }

        if (record.state() == IdempotencyRecord.State.COMPLETED) {
            log.info("[idem] replaying stored response. key={} status={}", storageKey, record.httpStatus());
            return replay(record);
        }

        // IN_PROGRESS。服务端绝不轮询等待：轮询会把 servlet 线程占住，高并发下
        // 直接打满线程池，把一个局部竞争放大成整体不可用。让客户端重试才是对的。
        return conflict("A request with this " + HEADER_KEY + " is currently in progress");
    }

    private ResponseEntity<?> replay(IdempotencyRecord record) {
        HttpStatus status = HttpStatus.valueOf(record.httpStatus());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header(HEADER_REPLAYED, "true");

        if (record.responseBody() == null || record.responseBody().isBlank()) {
            return builder.build();
        }

        // body 是已经序列化好的 JSON 文本。用 String 作为 body 让 Spring 走
        // StringHttpMessageConverter，原样写出去，不会被再序列化一次变成 JSON 字符串。
        return builder.contentType(MediaType.APPLICATION_JSON).body(record.responseBody());
    }

    private void complete(String storageKey, String requestHash, int status, String body,
                          Duration ttl, String createdAt) {
        try {
            IdempotencyRecord completed = IdempotencyRecord
                    .inProgress(requestHash, createdAt)
                    .completed(status, body, createdAt);
            store.put(storageKey, CANONICAL.writeValueAsString(completed), ttl);
        } catch (Exception e) {
            // 业务操作已经成功了，这里再抛就会让客户端以为失败并重试，反而制造重复。
            // 记下来即可：最坏结果是这个 key 停在 IN_PROGRESS 直到 TTL 过期，
            // 客户端重试会拿到 409 而不是重复执行。
            log.error("[idem] failed to persist COMPLETED record. key={}", storageKey, e);
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            store.delete(storageKey);
        } catch (Exception e) {
            log.error("[idem] failed to delete key after failure. key={}", storageKey, e);
        }
    }

    /**
     * 409 / 422 用返回 ResponseEntity 而不是抛 ResponseStatusException。
     *
     * <p>原因很实在：Spring 6 之后 {@code ResponseStatusException.getHeaders()} 返回的是
     * 不可变集合，往上面 add 会抛 UnsupportedOperationException —— 而 409 按规格必须带
     * {@code Retry-After}。返回 ResponseEntity 顺带也让响应体完全受控。
     */
    private static ResponseEntity<Object> conflict(String reason) {
        HttpHeaders headers = jsonHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        return problem(HttpStatus.CONFLICT, reason, headers);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String reason) {
        return problem(status, reason, jsonHeaders());
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String reason, HttpHeaders headers) {
        Object body = java.util.Map.of("status", status.value(), "error", reason);
        return new ResponseEntity<>(body, headers, status);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private int statusOf(Object result) {
        if (result instanceof ResponseEntity<?> re) {
            return re.getStatusCode().value();
        }
        return HttpStatus.OK.value();
    }

    private String bodyOf(Object result) {
        Object body = result instanceof ResponseEntity<?> re ? re.getBody() : result;
        return body == null ? null : responseMapper.writeValueAsString(body);
    }

    private String errorBody(ResponseStatusException e) {
        return CANONICAL.writeValueAsString(
                java.util.Map.of(
                        "status", e.getStatusCode().value(),
                        "error", String.valueOf(e.getReason())));
    }

    /**
     * key 必须包含 method 与 route pattern。否则同一个 Idempotency-Key 打到不同接口
     * 会互相串：客户端用同一个 key 先建会话再发消息，第二个请求会回放第一个的响应。
     */
    private String buildStorageKey(HttpServletRequest request, String idempotencyKey) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern != null ? pattern.toString() : request.getRequestURI();
        return "idem:" + request.getMethod() + ":" + route + ":" + idempotencyKey;
    }

    /**
     * 对参数做规范化 JSON 后取 sha256。路径变量也算在内 —— 同一个 key 打到
     * {@code DELETE /api/knowledge/1} 和 {@code /api/knowledge/2} 必须被识别为不同请求。
     */
    private String hashRequest(Object[] args) {
        List<Object> serializable = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                serializable.add(null);
            } else if (isFrameworkArgument(arg)) {
                // servlet / binding 之类的框架对象不是请求内容，序列化它们只会炸
                continue;
            } else {
                serializable.add(arg);
            }
        }
        return sha256Hex(CANONICAL.writeValueAsString(serializable));
    }

    private static boolean isFrameworkArgument(Object arg) {
        String name = arg.getClass().getName();
        return name.startsWith("jakarta.servlet.")
                || name.startsWith("org.springframework.validation.")
                || name.startsWith("org.springframework.web.");
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
