package com.example.cs_agent_service.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * DashScope（通义千问 OpenAI 兼容模式）实现。
 * 通过 {@code agent.llm.provider=dashscope} 装配。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeLlmClient implements LlmClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public DashScopeLlmClient(
            OkHttpClient http,
            ObjectMapper objectMapper,
            @Value("${agent.llm.base-url}") String baseUrl,
            @Value("${agent.llm.api-key}") String apiKey,
            @Value("${agent.llm.model}") String model,
            @Value("${agent.llm.temperature:0.3}") double temperature
    ) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        // 配置缺失是部署错误，不是上游故障 —— 不可重试。
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("${")) {
            throw new LlmException("DashScope apiKey 未配置：请设置环境变量 DASHSCOPE_API_KEY", false, null);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LlmException("DashScope baseUrl 未配置：agent.llm.base-url", false, null);
        }

        String url = baseUrl + "/chat/completions";
        String requestJson = buildRequestJson(systemPrompt, userPrompt);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestJson.getBytes(StandardCharsets.UTF_8), JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();

            if (response.code() != 200) {
                boolean retryable = LlmException.isRetryableStatus(response.code());
                throw new LlmException(
                        "DashScope 请求失败: status=" + response.code() + ", body=" + snippet(body),
                        retryable,
                        response.code(),
                        parseRetryAfter(response.header("Retry-After")),
                        null);
            }

            return extractContent(body);
        } catch (IOException e) {
            // 网络层异常（连接失败 / 读超时 / callTimeout）一律可重试。
            throw new LlmException("DashScope 请求异常: " + e.getMessage(), true, null, e);
        }
    }

    private String buildRequestJson(String systemPrompt, String userPrompt) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("temperature", temperature);

            ArrayNode messages = root.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);
            messages.addObject()
                    .put("role", "user")
                    .put("content", userPrompt);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 我们自己拼错了请求体，重试无意义。
            throw new LlmException("DashScope 请求体序列化失败: " + e.getMessage(), false, null, e);
        }
    }

    /**
     * 响应结构不符合预期 → 不可重试。上游返回了 200，说明它"工作正常"，
     * 是契约变了或模型返回了非文本内容，重试拿到的还是同样的东西。
     */
    private String extractContent(String rawJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new LlmException("DashScope 响应解析失败，raw=" + snippet(rawJson), false, 200, e);
        }

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmException("DashScope 响应缺少 choices 字段，raw=" + snippet(rawJson), false, 200);
        }

        JsonNode message = choices.get(0).get("message");
        if (message == null || message.isNull()) {
            throw new LlmException("DashScope 响应缺少 choices[0].message，raw=" + snippet(rawJson), false, 200);
        }

        JsonNode content = message.get("content");
        if (content == null || content.isNull() || !content.isTextual()) {
            throw new LlmException("DashScope 响应缺少 choices[0].message.content，raw=" + snippet(rawJson), false, 200);
        }

        return content.asText();
    }

    private static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            // Retry-After 也允许 HTTP-date 格式，这里不支持，直接忽略。
            return null;
        }
    }

    private static String normalizeBaseUrl(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "null";
        }
        int max = 800;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
