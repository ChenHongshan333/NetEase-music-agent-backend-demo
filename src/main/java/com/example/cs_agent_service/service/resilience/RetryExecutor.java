package com.example.cs_agent_service.service.resilience;

import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.service.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

/**
 * 手写的退避重试。不依赖任何外部库。
 */
@Component
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    /** 抽出来只为可测：真实实现是 Thread::sleep，测试里换成记录调用的假实现。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 每次尝试结束时的回调，用来埋 {@code agent.llm.call}。
     *
     * <p>做成回调而不是直接注入 AgentMetrics：重试本身是通用机制，
     * 不该知道"agent 指标"这种业务概念。
     */
    @FunctionalInterface
    public interface AttemptListener {
        void onAttempt(String dependencyName, int attempt, String outcome, long durationNanos);

        AttemptListener NOOP = (dep, attempt, outcome, nanos) -> { };
    }

    private final ResilienceProperties.Retry props;
    private final Clock clock;
    private final Sleeper sleeper;

    /** bound -> [0, bound] 之间的一个值。抽出来是为了测试能固定住随机性。 */
    private final LongUnaryOperator jitter;

    private final AttemptListener attemptListener;

    @Autowired
    public RetryExecutor(ResilienceProperties properties, Clock clock, AttemptListener attemptListener) {
        this(properties.getRetry(), clock, Thread::sleep,
                bound -> bound <= 0 ? 0L : ThreadLocalRandom.current().nextLong(bound + 1),
                attemptListener);
    }

    /** 全依赖注入版本：测试用它换掉时钟、sleep 与随机源。 */
    public RetryExecutor(ResilienceProperties.Retry props, Clock clock, Sleeper sleeper, LongUnaryOperator jitter) {
        this(props, clock, sleeper, jitter, AttemptListener.NOOP);
    }

    public RetryExecutor(ResilienceProperties.Retry props, Clock clock, Sleeper sleeper,
                         LongUnaryOperator jitter, AttemptListener attemptListener) {
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.attemptListener = attemptListener;
    }

    /**
     * @param dependencyName      日志用的依赖名
     * @param call                真正的调用
     * @param deadlineEpochMillis 这一整个阶段的截止时刻（epoch millis）
     */
    public <T> T execute(String dependencyName, Supplier<T> call, long deadlineEpochMillis) {
        int maxAttempts = Math.max(1, props.getMaxAttempts());

        for (int attempt = 1; ; attempt++) {
            long startedNanos = System.nanoTime();
            try {
                T result = call.get();
                attemptListener.onAttempt(dependencyName, attempt, "success", System.nanoTime() - startedNanos);
                return result;
            } catch (LlmException e) {
                attemptListener.onAttempt(dependencyName, attempt, classify(e), System.nanoTime() - startedNanos);

                // 不可重试的失败立刻抛出。把一次尝试花在必然重现的 400/401 上
                // 只是白白拖长用户等待，并且多烧一次上游配额。
                if (!e.isRetryable()) {
                    log.warn("[retry] dep={} attempt={}/{} non-retryable status={} err={}",
                            dependencyName, attempt, maxAttempts, e.getHttpStatus(), e.toString());
                    throw e;
                }

                if (attempt >= maxAttempts) {
                    log.warn("[retry] dep={} attempt={}/{} exhausted status={} err={}",
                            dependencyName, attempt, maxAttempts, e.getHttpStatus(), e.toString());
                    throw e;
                }

                long backoffMs = nextBackoffMs(attempt, e);

                // Deadline 检查：如果"退避 + 再打一次"注定会冲出总预算，就别退避了直接抛。
                // 到那个时候客户端早已超时断开，继续重试只是替一个没人接的响应烧上游配额。
                long now = clock.millis();
                long projectedFinish = now + backoffMs + props.getAssumedCallMs();
                if (projectedFinish > deadlineEpochMillis) {
                    log.warn("[retry] dep={} attempt={}/{} giving up: projected finish {}ms past deadline",
                            dependencyName, attempt, maxAttempts, projectedFinish - deadlineEpochMillis);
                    throw e;
                }

                log.warn("[retry] dep={} attempt={}/{} retryable status={} backoff={}ms err={}",
                        dependencyName, attempt, maxAttempts, e.getHttpStatus(), backoffMs, e.toString());

                sleepQuietly(backoffMs, e);
            }
        }
    }

    /**
     * Full jitter：{@code sleep = random(0, min(cap, base * 2^attempt))}。
     *
     * <p>关键是那个 random，不是指数。固定的 {@code base * 2^attempt} 会让同一时刻被同一次
     * 故障打中的所有客户端在同一时刻醒来，重试请求以整齐的波次砸向刚要恢复的上游，
     * 把它再次打垮——重试风暴（thundering herd）。随机化把这些波次摊平成均匀负载。
     * 代价只是单个请求的退避时长不确定，而我们本来也不在乎单次退避睡多久。
     */
    private long nextBackoffMs(int attempt, LlmException e) {
        long exponential = props.getBaseBackoffMs() << Math.min(attempt - 1, 30);
        long cap = Math.min(props.getMaxBackoffMs(), Math.max(0, exponential));

        Long retryAfterSeconds = e.getRetryAfterSeconds();
        if (retryAfterSeconds != null) {
            // 上游明确说了"X 秒后再来"（429 + Retry-After）就照办，仍然受 cap 约束。
            // 这里刻意不加 jitter：服务端给的是一个具体时刻，抢在它之前醒来只会再吃一个 429。
            return Math.min(retryAfterSeconds * 1000L, props.getMaxBackoffMs());
        }

        return jitter.applyAsLong(cap);
    }

    /** outcome 只有 success / timeout / error 三种取值，保证 tag 基数有界。 */
    private static String classify(LlmException e) {
        if (Integer.valueOf(408).equals(e.getHttpStatus())) {
            return "timeout";
        }
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof java.io.InterruptedIOException) {
                // SocketTimeoutException 是它的子类，读超时和 callTimeout 都走这里
                return "timeout";
            }
        }
        return "error";
    }

    private void sleepQuietly(long backoffMs, LlmException cause) {
        try {
            sleeper.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
