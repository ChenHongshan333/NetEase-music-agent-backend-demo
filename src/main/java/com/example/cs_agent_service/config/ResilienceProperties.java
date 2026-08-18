package com.example.cs_agent_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重试与熔断的参数。全部可配置，默认值写在这里。
 */
@ConfigurationProperties(prefix = "agent.resilience")
public class ResilienceProperties {

    /**
     * 单次 chat 请求分给"调用 LLM"这一整个阶段的总预算（含所有重试与退避）。
     * RetryExecutor 用它算 deadline：预算花光就不再重试。
     */
    private long requestBudgetMs = 25000;

    private final Retry retry = new Retry();
    private final Breaker circuitBreaker = new Breaker();

    public long getRequestBudgetMs() { return requestBudgetMs; }
    public void setRequestBudgetMs(long requestBudgetMs) { this.requestBudgetMs = requestBudgetMs; }

    public Retry getRetry() { return retry; }
    public Breaker getCircuitBreaker() { return circuitBreaker; }

    public static class Retry {

        /** 总尝试次数（初次 + 重试），不是"重试次数"。 */
        private int maxAttempts = 3;

        private long baseBackoffMs = 200;

        /** 退避上限，同时也是 Retry-After 的上限。 */
        private long maxBackoffMs = 2000;

        /**
         * 估算的单次调用耗时，用于 deadline 判断。应与依赖的 read timeout 对齐
         * （见 {@link LlmTimeoutProperties#getReadMs()}）。
         */
        private long assumedCallMs = 8000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getBaseBackoffMs() { return baseBackoffMs; }
        public void setBaseBackoffMs(long baseBackoffMs) { this.baseBackoffMs = baseBackoffMs; }

        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }

        public long getAssumedCallMs() { return assumedCallMs; }
        public void setAssumedCallMs(long assumedCallMs) { this.assumedCallMs = assumedCallMs; }
    }

    public static class Breaker {

        /** 计数型滑动窗口：只记最近 N 次结果。 */
        private int slidingWindowSize = 20;

        /** 窗口内样本不足时不做判定，避免冷启动时几次失败就误熔断。 */
        private int minimumCalls = 10;

        /** 失败率百分比阈值。 */
        private int failureRateThreshold = 50;

        private long openDurationMs = 30000;

        /** HALF_OPEN 允许放行的探测请求数。 */
        private int halfOpenPermittedCalls = 3;

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

        public int getMinimumCalls() { return minimumCalls; }
        public void setMinimumCalls(int minimumCalls) { this.minimumCalls = minimumCalls; }

        public int getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(int failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

        public long getOpenDurationMs() { return openDurationMs; }
        public void setOpenDurationMs(long openDurationMs) { this.openDurationMs = openDurationMs; }

        public int getHalfOpenPermittedCalls() { return halfOpenPermittedCalls; }
        public void setHalfOpenPermittedCalls(int halfOpenPermittedCalls) { this.halfOpenPermittedCalls = halfOpenPermittedCalls; }
    }
}
