package com.example.cs_agent_service.observability;

import com.example.cs_agent_service.service.resilience.CircuitBreaker;
import com.example.cs_agent_service.service.resilience.RetryExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 业务指标。
 *
 * <h2>标签基数纪律</h2>
 * 这个类里出现的每一个 tag 值都来自一个**固定的、代码里写死的小集合**：
 * hit/miss、success/error/timeout、1/2/3 之类。
 *
 * <p>绝对不要把用户问题、Idempotency-Key、request id、知识库 ID 或任何用户输入
 * 当作 tag 值。Micrometer 会为每一种 tag 组合创建一条独立时间序列：
 * 用问题内容当 tag，意味着每个新问题都在时序库里开一条新序列，几小时内就能
 * 把内存和后端存储撑爆。这是生产事故级别的错误，而且它在开发环境完全看不出来。
 * 高基数的东西属于日志，不属于指标。
 */
@Component
public class AgentMetrics implements RetryExecutor.AttemptListener {

    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter refusalNoHits;
    private final DistributionSummary retrievalHits;
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry, CircuitBreaker circuitBreaker) {
        this.registry = registry;

        this.cacheHit = Counter.builder("agent.cache.lookup")
                .description("chat 缓存查询结果")
                .tag("result", "hit")
                .register(registry);
        this.cacheMiss = Counter.builder("agent.cache.lookup")
                .description("chat 缓存查询结果")
                .tag("result", "miss")
                .register(registry);

        this.refusalNoHits = Counter.builder("agent.refusal")
                .description("拒答闸门触发次数")
                .tag("stage", "no_hits")
                .register(registry);

        this.retrievalHits = DistributionSummary.builder("agent.retrieval.hits")
                .description("每次检索返回的命中条数")
                .register(registry);

        Gauge.builder("agent.circuit.state", circuitBreaker, b -> b.getState().code())
                .description("熔断器状态：0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .tag("dependency", "dashscope")
                .register(registry);
    }

    public void cacheLookup(boolean hit) {
        (hit ? cacheHit : cacheMiss).increment();
    }

    public void refusal() {
        refusalNoHits.increment();
    }

    public void retrievalHits(int count) {
        retrievalHits.record(count);
    }

    /**
     * @param outcome success | error | timeout —— 固定集合
     * @param attempt 尝试序号，受 max-attempts 限制（默认最大 3），基数有界
     */
    public void llmCall(String outcome, int attempt, long durationNanos) {
        Timer.builder("agent.llm.call")
                .description("单次上游模型调用（每次重试各记一条）")
                .tag("outcome", outcome)
                .tag("attempt", Integer.toString(attempt))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** RetryExecutor 每结束一次尝试就回调这里。 */
    @Override
    public void onAttempt(String dependencyName, int attempt, String outcome, long durationNanos) {
        llmCall(outcome, attempt, durationNanos);
    }

    /** @param result new | replayed | conflict | mismatch | unavailable —— 固定集合 */
    public void idempotency(String result) {
        Counter.builder("agent.idempotency")
                .description("幂等键的处理结果")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /** @param reason circuit_open | retry_exhausted —— 固定集合 */
    public void degraded(String reason) {
        Counter.builder("agent.degraded")
                .description("降级返回次数")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
