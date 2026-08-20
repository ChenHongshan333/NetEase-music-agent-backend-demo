package com.example.cs_agent_service.service.resilience;

import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.service.llm.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryExecutorTest {

    /** 可手动推进的时钟，避免用 Thread.sleep 让测试变慢变飘。 */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) { this.millis = millis; }

        void advance(long delta) { this.millis += delta; }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private ResilienceProperties.Retry props;
    private MutableClock clock;
    private List<Long> sleeps;

    /** 让退避取到上界，这样 sleeps 里记录的就是 cap 本身，便于断言。 */
    private final LongUnaryOperator maxJitter = bound -> bound;

    @BeforeEach
    void setUp() {
        props = new ResilienceProperties.Retry();
        clock = new MutableClock(1_000_000L);
        sleeps = new ArrayList<>();
    }

    private RetryExecutor executor(LongUnaryOperator jitter) {
        return new RetryExecutor(props, clock, ms -> {
            sleeps.add(ms);
            clock.advance(ms);
        }, jitter);
    }

    private long farDeadline() {
        return clock.millis() + 10 * 60 * 1000L;
    }

    private static LlmException retryable(int status) {
        return new LlmException("upstream " + status, true, status);
    }

    private static LlmException nonRetryable(int status) {
        return new LlmException("upstream " + status, false, status);
    }

    @Test
    @DisplayName("首次成功 → 不重试，不退避")
    void succeedsWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();

        String result = executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            return "ok";
        }, farDeadline());

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("可重试异常 → 尝试到 max-attempts 后抛出，上游恰好被调用 3 次")
    void retriesUntilMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            throw retryable(503);
        }, farDeadline()))
                .isInstanceOf(LlmException.class);

        assertThat(calls).hasValue(3);
        // 3 次尝试之间只有 2 次退避
        assertThat(sleeps).hasSize(2);
    }

    @Test
    @DisplayName("不可重试异常 → 上游只被调用 1 次，一次退避都不浪费")
    void doesNotRetryNonRetryable() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            throw nonRetryable(400);
        }, farDeadline()))
                .isInstanceOf(LlmException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("中途成功 → 停止重试并返回结果")
    void stopsRetryingOnceSuccessful() {
        AtomicInteger calls = new AtomicInteger();

        String result = executor(maxJitter).execute("dep", () -> {
            if (calls.incrementAndGet() < 2) {
                throw retryable(500);
            }
            return "recovered";
        }, farDeadline());

        assertThat(result).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    @DisplayName("退避是指数增长的，且被 max-backoff 截断")
    void backoffGrowsExponentiallyAndIsCapped() {
        props.setMaxAttempts(6);
        props.setBaseBackoffMs(200);
        props.setMaxBackoffMs(2000);

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            throw retryable(500);
        }, farDeadline()))
                .isInstanceOf(LlmException.class);

        // 200, 400, 800, 1600, 然后被 cap 在 2000
        assertThat(sleeps).containsExactly(200L, 400L, 800L, 1600L, 2000L);
    }

    @Test
    @DisplayName("full jitter：退避恒落在 [0, cap]，且不是固定值")
    void backoffUsesFullJitter() {
        props.setMaxAttempts(2);
        props.setBaseBackoffMs(200);
        props.setMaxBackoffMs(2000);

        // 用真实随机源采样。断言分布性质，不断言具体值。
        LongUnaryOperator realJitter =
                bound -> bound <= 0 ? 0L : ThreadLocalRandom.current().nextLong(bound + 1);

        List<Long> observed = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            sleeps = new ArrayList<>();
            try {
                executor(realJitter).execute("dep", () -> {
                    throw retryable(500);
                }, farDeadline());
            } catch (LlmException expected) {
                // 预期
            }
            observed.addAll(sleeps);
        }

        long cap = 200L;  // attempt 1 的 cap = base * 2^0
        assertThat(observed).hasSize(200);
        assertThat(observed).allSatisfy(ms ->
                assertThat(ms).isBetween(0L, cap));

        // 固定退避（thundering herd 的成因）会让所有样本相等。抖动必须打散它们。
        assertThat(observed.stream().distinct().count())
                .as("full jitter 必须产生多个不同的退避值，否则就退化成了固定退避")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("429 带 Retry-After → 采用该值（秒），并受 cap 约束")
    void honoursRetryAfterHeader() {
        props.setMaxAttempts(2);
        props.setMaxBackoffMs(2000);

        LlmException tooManyRequests =
                new LlmException("429", true, 429, 1L, null);

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            throw tooManyRequests;
        }, farDeadline()))
                .isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    @DisplayName("Retry-After 超过 cap → 被 cap 截断，不会睡到天荒地老")
    void clampsOversizedRetryAfter() {
        props.setMaxAttempts(2);
        props.setMaxBackoffMs(2000);

        LlmException tooManyRequests =
                new LlmException("429", true, 429, 3600L, null);

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            throw tooManyRequests;
        }, farDeadline()))
                .isInstanceOf(LlmException.class);

        assertThat(sleeps).containsExactly(2000L);
    }

    @Test
    @DisplayName("deadline 已过 → 不再重试，直接抛出")
    void abandonsRetryPastDeadline() {
        AtomicInteger calls = new AtomicInteger();
        long alreadyPassed = clock.millis() - 1;

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            throw retryable(503);
        }, alreadyPassed))
                .isInstanceOf(LlmException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("预算不够再打一次 → 放弃重试，不白烧上游配额")
    void abandonsRetryWhenBudgetCannotFitAnotherCall() {
        props.setAssumedCallMs(8000);
        AtomicInteger calls = new AtomicInteger();

        // 剩余预算 1s，但一次调用假定要 8s：重试注定超时，客户端早已断开。
        long tightDeadline = clock.millis() + 1000;

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            throw retryable(503);
        }, tightDeadline))
                .isInstanceOf(LlmException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("预算充足时正常重试，说明上一条不是被别的原因挡下的")
    void retriesWhenBudgetIsSufficient() {
        props.setAssumedCallMs(8000);
        AtomicInteger calls = new AtomicInteger();

        long roomyDeadline = clock.millis() + 30_000;

        assertThatThrownBy(() -> executor(maxJitter).execute("dep", () -> {
            calls.incrementAndGet();
            throw retryable(503);
        }, roomyDeadline))
                .isInstanceOf(LlmException.class);

        assertThat(calls).hasValue(3);
    }
}
