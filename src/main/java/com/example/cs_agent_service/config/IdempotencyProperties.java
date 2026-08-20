package com.example.cs_agent_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.idempotency")
public class IdempotencyProperties {

    /** 幂等记录保留时长。24 小时足够覆盖任何合理的客户端重试窗口。 */
    private long ttlHours = 24;

    /** Idempotency-Key 的最大长度，超过直接 400。 */
    private int maxKeyLength = 255;

    public long getTtlHours() { return ttlHours; }
    public void setTtlHours(long ttlHours) { this.ttlHours = ttlHours; }

    public int getMaxKeyLength() { return maxKeyLength; }
    public void setMaxKeyLength(int maxKeyLength) { this.maxKeyLength = maxKeyLength; }
}
