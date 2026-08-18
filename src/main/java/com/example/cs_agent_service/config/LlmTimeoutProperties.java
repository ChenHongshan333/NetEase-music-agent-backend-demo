package com.example.cs_agent_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 调用的超时预算。
 *
 * <p>默认值必须显式给出：OkHttp 的默认 readTimeout 是 10s 且 callTimeout 为 0（不限），
 * 意味着上游变慢时一个 servlet 线程会被白占十秒甚至更久。超时不是"防御性配置"，
 * 它决定了在故障期间我们能撑住多少并发。
 */
@ConfigurationProperties(prefix = "agent.llm.timeout")
public class LlmTimeoutProperties {

    private long connectMs = 2000;
    private long readMs = 8000;
    private long writeMs = 2000;

    /** 整体调用上限（含重定向），兜底用，必须设置。 */
    private long callMs = 12000;

    public long getConnectMs() { return connectMs; }
    public void setConnectMs(long connectMs) { this.connectMs = connectMs; }

    public long getReadMs() { return readMs; }
    public void setReadMs(long readMs) { this.readMs = readMs; }

    public long getWriteMs() { return writeMs; }
    public void setWriteMs(long writeMs) { this.writeMs = writeMs; }

    public long getCallMs() { return callMs; }
    public void setCallMs(long callMs) { this.callMs = callMs; }
}
