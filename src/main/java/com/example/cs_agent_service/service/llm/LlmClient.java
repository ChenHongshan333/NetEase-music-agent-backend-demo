package com.example.cs_agent_service.service.llm;

/**
 * 大模型调用的抽象。实现类只负责"把 prompt 发出去、把文本拿回来"，
 * 不做重试、不做熔断、不做缓存——这些由 ChatService / resilience 层负责。
 */
public interface LlmClient {

    /**
     * @throws LlmException 调用失败（含超时、非 2xx、响应解析失败）
     */
    String complete(String systemPrompt, String userPrompt);
}
