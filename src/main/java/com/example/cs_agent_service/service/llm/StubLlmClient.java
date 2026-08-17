package com.example.cs_agent_service.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 假的 LLM：返回固定文本，可注入固定延迟。
 *
 * <p>两个用途：
 * <ol>
 *   <li>dev profile 下零外部依赖启动（不需要 DASHSCOPE_API_KEY）</li>
 *   <li>压测时把上游延迟固定成一个已知常数，这样测出来的是"服务自身的并发承载能力"，
 *       而不是"上游今天心情如何"</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "stub")
public class StubLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubLlmClient.class);

    public static final String STUB_ANSWER =
            "（stub 回答）你好，我是网易云音乐智能客服小云~ 这是本地 stub 模式返回的固定答案。";

    private final long delayMs;

    public StubLlmClient(@Value("${agent.llm.stub.delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;
        log.info("[llm] provider=stub delayMs={}", delayMs);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("stub 调用被中断", true, null, e);
            }
        }
        return STUB_ANSWER;
    }
}
