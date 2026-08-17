package com.example.cs_agent_service.service;

import com.example.cs_agent_service.config.CacheProperties;
import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.example.cs_agent_service.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * chat 链路的编排者：缓存 → 检索 → 拒答闸门 → 拼 prompt → 调 LLM → 回写缓存。
 *
 * <p>控制器只做 HTTP 适配，业务顺序全部在这里，这样才能被单元测试逐段验证。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public static final String REFUSAL_ANSWER = "抱歉，小云暂时还没学会这个问题";

    private static final String CACHE_KEY_PREFIX = "agent:chat:v1:";

    private static final String SYSTEM_PROMPT = """
            你是网易云音乐智能客服小云，请用亲切活泼的语气回答。
            必须优先基于【已知信息】回答；
            如果已知信息不足，就回答：'抱歉，小云暂时还没学会这个问题'。
            不要编造事实。
            """.trim();

    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmClient llmClient;
    private final RedisCacheService cache;
    private final CacheProperties cacheProps;
    private final ObjectMapper objectMapper;

    public ChatService(
            KnowledgeBaseService knowledgeBaseService,
            LlmClient llmClient,
            RedisCacheService cache,
            CacheProperties cacheProps,
            ObjectMapper objectMapper
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.llmClient = llmClient;
        this.cache = cache;
        this.cacheProps = cacheProps;
        this.objectMapper = objectMapper;
    }

    public ChatResult chat(String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        String q = question.trim();
        log.info("[chat] q='{}' cacheEnabled={}", q, cacheProps.isEnabled());
        String cacheKey = buildCacheKey(q);

        // 1) 缓存查询（命中直接返回）
        Optional<ChatResult> cached = readCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        // 2) 检索
        List<KnowledgeBase> hits = knowledgeBaseService.searchTop5(q);

        // 3) 拒答闸门：0 命中绝不调用 LLM
        if (hits == null || hits.isEmpty()) {
            ChatResult result = ChatResult.refused(REFUSAL_ANSWER, false);
            // 拒答用很短的 TTL：刚补进知识库的条目要能快速生效
            writeCacheSafely(cacheKey, result, cacheProps.getRefusalTtlSeconds());
            log.info("[chat] cache=REFUSAL key={}", cacheKey);
            return result;
        }

        // 4) 拼 prompt
        String userPrompt = buildUserPrompt(q, hits);

        // 5) 调 LLM
        log.info("[chat] retrieval hits={} llm=CALL", hits.size());
        String answer = llmClient.complete(SYSTEM_PROMPT, userPrompt);

        // 6) 回写缓存并返回
        ChatResult result = ChatResult.answered(answer, hits.size(), false);
        writeCacheSafely(cacheKey, result, cacheProps.getTtlSeconds());
        return result;
    }

    private String buildUserPrompt(String q, List<KnowledgeBase> hits) {
        StringBuilder known = new StringBuilder();
        known.append("已知信息：\n");
        for (int i = 0; i < hits.size(); i++) {
            String ans = hits.get(i).getAnswer();
            if (ans == null) {
                ans = "";
            }
            known.append("[").append(i + 1).append("] ").append(ans).append("\n");
        }
        known.append("用户问题：").append(q);
        return known.toString();
    }

    private Optional<ChatResult> readCache(String cacheKey) {
        if (!cacheProps.isEnabled()) {
            return Optional.empty();
        }

        // RedisCacheService 内部已把异常降级为 Optional.empty()，这里的 try 兜的是反序列化失败。
        Optional<String> raw = cache.get(cacheKey);
        if (raw.isEmpty()) {
            log.info("[chat] cache=MISS key={}", cacheKey);
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(raw.get(), Map.class);
            String answer = String.valueOf(payload.get("answer"));
            int hits = payload.get("hits") instanceof Number n ? n.intValue() : 0;
            log.info("[chat] cache=HIT key={}", cacheKey);
            return Optional.of(hits == 0
                    ? ChatResult.refused(answer, true)
                    : ChatResult.answered(answer, hits, true));
        } catch (Exception ignored) {
            // 解析失败视为 cache miss，继续走主链路
            log.info("[chat] cache=MISS(unparsable) key={}", cacheKey);
            return Optional.empty();
        }
    }

    /**
     * 缓存 key：版本前缀 + sha256(trim 后的问题)，避免特殊字符与超长 key。
     */
    private String buildCacheKey(String question) {
        return CACHE_KEY_PREFIX + sha256Hex(question.trim());
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
            // 极端兜底：仍能工作，只是 redis key 不够安全
            return s;
        }
    }

    /**
     * 缓存写失败绝不能打断主链路。
     */
    private void writeCacheSafely(String key, ChatResult result, long ttlSeconds) {
        if (!cacheProps.isEnabled() || ttlSeconds <= 0) {
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answer", result.answer());
            payload.put("hits", result.hits());
            String json = objectMapper.writeValueAsString(payload);
            log.info("[chat] cache=WRITE key={} ttl={}s", key, ttlSeconds);
            cache.set(key, json, ttlSeconds);
        } catch (Exception ignored) {
            // ignore all to keep main flow stable
        }
    }
}
