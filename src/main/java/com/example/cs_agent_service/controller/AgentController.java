package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.config.CacheProperties;
import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.service.KnowledgeBaseService;
import com.example.cs_agent_service.service.ai.DashScopeClient;
import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DashScopeClient dashScopeClient;
    private final RedisCacheService cache;
    private final CacheProperties cacheProps;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);


    public AgentController(
            KnowledgeBaseService knowledgeBaseService,
            DashScopeClient dashScopeClient,
            RedisCacheService cache,
            CacheProperties cacheProps,
            ObjectMapper objectMapper
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.dashScopeClient = dashScopeClient;
        this.cache = cache;
        this.cacheProps = cacheProps;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/chat")
    @Operation(summary = "智能客服问答测试")
    public Map<String, Object> chat(@RequestParam("question") String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }

        String q = question.trim();
        log.info("[chat] q='{}' cacheEnabled={}", q, cacheProps.isEnabled());
        String cacheKey = buildCacheKey(q);

        // 1) Cache lookup (hit -> return immediately)
        if (cacheProps.isEnabled()) {
            Optional<String> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = objectMapper.readValue(cached.get(), Map.class);
                    log.info("[chat] cache=HIT key={}", cacheKey);
                    return resp;
                } catch (Exception ignored) {

                    // 解析失败视为 cache miss，继续走主链路
                }
            }
            log.info("[chat] cache=MISS key={}", cacheKey);
        }

        // 2) Retrieval
        List<KnowledgeBase> hits = knowledgeBaseService.searchTop5(q);

        // 3) Refusal gate (hits == 0 -> no LLM call)
        if (hits == null || hits.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("answer", "抱歉，小云暂时还没学会这个问题");
            resp.put("hits", 0);

            // refusal cache: very short TTL (optional)
            writeCacheSafely(cacheKey, resp, cacheProps.getRefusalTtlSeconds());
            log.info("[chat] cache=REFUSAL key={}", cacheKey);
            return resp;
        }

        // 4) Prompt assembly
        String systemMsg = """
            你是网易云音乐智能客服小云，请用亲切活泼的语气回答。
            必须优先基于【已知信息】回答；
            如果已知信息不足，就回答：'抱歉，小云暂时还没学会这个问题'。
            不要编造事实。
        """.trim();

        StringBuilder known = new StringBuilder();
        known.append("已知信息：\n");
        for (int i = 0; i < hits.size(); i++) {
            String ans = hits.get(i).getAnswer();
            if (ans == null) ans = "";
            known.append("[").append(i + 1).append("] ").append(ans).append("\n");
        }
        known.append("用户问题：").append(q);
        
        // 5) LLM inference
        log.info("[chat] retrieval hits={} llm=CALL", hits.size());
        String answer = dashScopeClient.call(systemMsg, known.toString());

        // 6) Response
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("answer", answer);
        resp.put("hits", hits.size());

        // 7) Write-back cache with normal TTL
        writeCacheSafely(cacheKey, resp, cacheProps.getTtlSeconds()); 
        return resp;
    }

    /**
     * Cache key: versioned + sha256(question.trim()) to avoid special chars / huge keys.
     */
    private String buildCacheKey(String question) {
        String normalized = question.trim();
        return "agent:chat:v1:" + sha256Hex(normalized);
    }

    private String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // extreme fallback: still works, just less safe for redis key
            return s;
        }
    }

    /**
     * Never break main flow: cache errors must be ignored.
     */
    private void writeCacheSafely(String key, Map<String, Object> resp, long ttlSeconds) {
        if (!cacheProps.isEnabled()) return;
        if (ttlSeconds <= 0) return;

        try {
            String json = objectMapper.writeValueAsString(resp);
            log.info("[chat] cache=WRITE key={} ttl={}s", key, ttlSeconds);
            cache.set(key, json, ttlSeconds);
        } catch (Exception ignored) {
            // ignore all to keep main flow stable
        }
    }
}
