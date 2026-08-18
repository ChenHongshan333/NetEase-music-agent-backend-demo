package com.example.cs_agent_service.service.resilience;

import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.service.llm.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机测试用可推进的 Clock，不用 Thread.sleep —— 否则光验证 OPEN 到期
 * 就要等 30 秒，还会因为调度抖动而 flaky。
 */
class CircuitBreakerTest {

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) { this.millis = millis; }

        void advance(long delta) { this.millis += delta; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private ResilienceProperties.Breaker props;
    private MutableClock clock;
    private CircuitBreaker breaker;
    private AtomicInteger upstreamCalls;

    @BeforeEach
    void setUp() {
        props = new ResilienceProperties.Breaker();
        props.setSlidingWindowSize(20);
        props.setMinimumCalls(10);
        props.setFailureRateThreshold(50);
        props.setOpenDurationMs(30_000);
        props.setHalfOpenPermittedCalls(3);

        clock = new MutableClock(1_000_000L);
        breaker = new CircuitBreaker("dep", props, clock);
        upstreamCalls = new AtomicInteger();
    }

    private String succeed() {
        return breaker.execute(() -> {
            upstreamCalls.incrementAndGet();
            return "ok";
        });
    }

    private void failRetryable() {
        breaker.execute(() -> {
            upstreamCalls.incrementAndGet();
            throw new LlmException("boom", true, 503);
        });
    }

    private void failQuietly() {
        try {
            failRetryable();
        } catch (LlmException expected) {
            // 预期
        }
    }

    private void driveToOpen() {
        for (int i = 0; i < props.getMinimumCalls(); i++) {
            failQuietly();
        }
    }

    @Test
    @DisplayName("初始状态是 CLOSED，调用直通")
    void startsClosed() {
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(succeed()).isEqualTo("ok");
        assertThat(upstreamCalls).hasValue(1);
    }

    @Test
    @DisplayName("样本不足 minimum-calls 时不判定，避免冷启动误熔断")
    void doesNotOpenBeforeMinimumCalls() {
        for (int i = 0; i < props.getMinimumCalls() - 1; i++) {
            failQuietly();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(upstreamCalls).hasValue(props.getMinimumCalls() - 1);
    }

    @Test
    @DisplayName("失败率达阈值 → 转 OPEN，后续调用抛 CircuitOpenException 且上游零调用")
    void opensAndStopsCallingUpstream() {
        driveToOpen();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsAtOpen = upstreamCalls.get();

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(this::succeed).isInstanceOf(CircuitOpenException.class);
        }

        assertThat(upstreamCalls)
                .as("OPEN 状态下必须零上游调用")
                .hasValue(callsAtOpen);
    }

    @Test
    @DisplayName("失败率未达阈值 → 保持 CLOSED")
    void staysClosedBelowThreshold() {
        // 4 失败 + 16 成功 = 20%，低于 50%
        for (int i = 0; i < 4; i++) {
            failQuietly();
        }
        for (int i = 0; i < 16; i++) {
            succeed();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("不可重试的失败（400）不计入失败率，我们自己的 bug 不该熔断上游")
    void nonRetryableFailuresDoNotOpenTheCircuit() {
        for (int i = 0; i < 30; i++) {
            try {
                breaker.execute(() -> {
                    upstreamCalls.incrementAndGet();
                    throw new LlmException("bad request", false, 400);
                });
            } catch (LlmException expected) {
                // 预期
            }
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(upstreamCalls)
                .as("400 不该熔断，所以每一次都应该真的打到上游")
                .hasValue(30);
    }

    @Test
    @DisplayName("OPEN 未到期 → 仍然拒绝；到期 → 进入 HALF_OPEN")
    void transitionsToHalfOpenAfterOpenDuration() {
        driveToOpen();

        clock.advance(props.getOpenDurationMs() - 1);
        assertThatThrownBy(this::succeed).isInstanceOf(CircuitOpenException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(1);
        assertThat(succeed()).isEqualTo("ok");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("HALF_OPEN 只放行配置数量的探测，其余按 OPEN 拒绝")
    void halfOpenLimitsProbes() {
        driveToOpen();
        clock.advance(props.getOpenDurationMs());

        int callsBefore = upstreamCalls.get();

        // 前 2 个探测放行（第 3 个会让它闭合，单独测）
        succeed();
        succeed();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(upstreamCalls).hasValue(callsBefore + 2);

        // 第 3 个名额留着不用，改成验证超出名额的请求被拒
        props.setHalfOpenPermittedCalls(2);
        assertThatThrownBy(this::succeed).isInstanceOf(CircuitOpenException.class);
        assertThat(upstreamCalls)
                .as("超出探测名额的请求不得触达上游")
                .hasValue(callsBefore + 2);
    }

    @Test
    @DisplayName("HALF_OPEN 探测全部成功 → CLOSED")
    void halfOpenClosesWhenAllProbesSucceed() {
        driveToOpen();
        clock.advance(props.getOpenDurationMs());

        for (int i = 0; i < props.getHalfOpenPermittedCalls(); i++) {
            succeed();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 闭合后窗口是干净的：不该带着熔断前的失败历史立刻再次跳闸
        succeed();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN 任一探测失败 → 回到 OPEN 并重置计时器")
    void halfOpenReopensOnAnyProbeFailure() {
        driveToOpen();
        clock.advance(props.getOpenDurationMs());

        succeed();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        failQuietly();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 计时器被重置：又要重新等满一个 openDuration
        clock.advance(props.getOpenDurationMs() - 1);
        assertThatThrownBy(this::succeed).isInstanceOf(CircuitOpenException.class);

        clock.advance(1);
        assertThat(succeed()).isEqualTo("ok");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("OPEN 状态下拒绝是即时的，不消耗任何上游资源")
    void openRejectionCarriesDependencyName() {
        driveToOpen();

        assertThatThrownBy(this::succeed)
                .isInstanceOf(CircuitOpenException.class)
                .satisfies(e -> assertThat(((CircuitOpenException) e).getDependencyName())
                        .isEqualTo("dep"));
    }

    @Test
    @DisplayName("滑动窗口只看最近 N 次：旧的失败会被挤出去")
    void slidingWindowForgetsOldResults() {
        props.setSlidingWindowSize(10);
        props.setMinimumCalls(10);
        breaker = new CircuitBreaker("dep", props, clock);

        // 4 次失败（40%，不足以跳闸），再用 10 次成功把它们全部挤出窗口
        for (int i = 0; i < 4; i++) {
            failQuietly();
        }
        for (int i = 0; i < 10; i++) {
            succeed();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 此时窗口里全是成功。再来 4 次失败仍是 40%，不该跳闸——
        // 如果旧失败没被挤出去，这里就会累计到 80% 而误熔断。
        for (int i = 0; i < 4; i++) {
            failQuietly();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("State.code() 供 gauge 使用：0=CLOSED 1=HALF_OPEN 2=OPEN")
    void stateCodesAreStable() {
        assertThat(CircuitBreaker.State.CLOSED.code()).isZero();
        assertThat(CircuitBreaker.State.HALF_OPEN.code()).isEqualTo(1);
        assertThat(CircuitBreaker.State.OPEN.code()).isEqualTo(2);
    }
}
