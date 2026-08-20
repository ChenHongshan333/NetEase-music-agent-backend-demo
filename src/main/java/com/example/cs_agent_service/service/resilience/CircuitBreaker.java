package com.example.cs_agent_service.service.resilience;

import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.service.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 手写的三态熔断器。不依赖任何外部库。
 *
 * <pre>
 * CLOSED ──(失败率超阈值)──> OPEN ──(openDuration 到期)──> HALF_OPEN
 *    ↑                                                       │
 *    └────────(探测全部成功)──────────────────────────────────┘
 *                                   │
 *                     (任一探测失败) └──> OPEN（重置计时器）
 * </pre>
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED, HALF_OPEN, OPEN;

        /** 给 Micrometer gauge 用：0=CLOSED, 1=HALF_OPEN, 2=OPEN。 */
        public int code() {
            return ordinal();
        }
    }

    private final String dependencyName;
    private final ResilienceProperties.Breaker props;
    private final Clock clock;

    // 所有可变状态都在这把锁下读写。状态机的每次转换都要同时看窗口统计、
    // 半开名额和计时器，用一把锁把它们绑在一起，比几个各自原子的字段更容易证明正确。
    private final ReentrantLock lock = new ReentrantLock();

    /** 计数型滑动窗口，true = 失败。固定长度 + 环形游标，不分配、不增长。 */
    private final boolean[] window;
    private int cursor;
    private int recorded;
    private int failures;

    private State state = State.CLOSED;
    private long openedAtMillis;
    private int halfOpenPermitsIssued;
    private int halfOpenSuccesses;

    @Autowired
    public CircuitBreaker(ResilienceProperties properties, Clock clock) {
        this("dashscope", properties.getCircuitBreaker(), clock);
    }

    public CircuitBreaker(String dependencyName, ResilienceProperties.Breaker props, Clock clock) {
        this.dependencyName = dependencyName;
        this.props = props;
        this.clock = clock;
        this.window = new boolean[Math.max(1, props.getSlidingWindowSize())];
    }

    public <T> T execute(Supplier<T> call) {
        acquirePermission();

        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (LlmException e) {
            // 只有可重试的失败才算"上游不健康"。400/401 说明上游活着、在正常应答，
            // 是我们的请求或配置错了——让自己的 bug 打开熔断，等于把一个确定性错误
            // 放大成整个依赖不可用，而且它永远不会自愈。
            if (e.isRetryable()) {
                onFailure();
            } else {
                onNonSignal();
            }
            throw e;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    /** 给 gauge 用。只读当前状态，不触发任何转换。 */
    public State getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    private void acquirePermission() {
        lock.lock();
        try {
            if (state == State.OPEN) {
                if (clock.millis() - openedAtMillis < props.getOpenDurationMs()) {
                    throw new CircuitOpenException(dependencyName,
                            "circuit is OPEN for " + dependencyName + "; upstream not called");
                }
                toHalfOpen();
            }

            if (state == State.HALF_OPEN) {
                // 半开时只放行固定数量的探测。放行全部等于没有半开：上游还没缓过来，
                // 我们就把积压的全部流量一次性砸回去，它会立刻再次倒下。
                if (halfOpenPermitsIssued >= props.getHalfOpenPermittedCalls()) {
                    throw new CircuitOpenException(dependencyName,
                            "circuit is HALF_OPEN for " + dependencyName
                                    + " and probe quota is exhausted; upstream not called");
                }
                halfOpenPermitsIssued++;
            }
        } finally {
            lock.unlock();
        }
    }

    private void onSuccess() {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= props.getHalfOpenPermittedCalls()) {
                    toClosed();
                }
                return;
            }
            record(false);
        } finally {
            lock.unlock();
        }
    }

    private void onFailure() {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                // 任一探测失败就立刻回到 OPEN 并重置计时器。上游还没好，再等一个周期。
                toOpen();
                return;
            }
            record(true);
            if (shouldOpen()) {
                toOpen();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 不可重试的失败：既不是上游不健康的证据，也不该拖住半开探测。
     * 记成一次成功——上游确实应答了，这正是熔断器要测的东西。
     */
    private void onNonSignal() {
        onSuccess();
    }

    private boolean shouldOpen() {
        if (recorded < props.getMinimumCalls()) {
            return false;
        }
        return failures * 100 >= props.getFailureRateThreshold() * recorded;
    }

    private void record(boolean failure) {
        if (recorded == window.length && window[cursor]) {
            failures--;
        }
        window[cursor] = failure;
        if (failure) {
            failures++;
        }
        cursor = (cursor + 1) % window.length;
        if (recorded < window.length) {
            recorded++;
        }
    }

    private void resetWindow() {
        java.util.Arrays.fill(window, false);
        cursor = 0;
        recorded = 0;
        failures = 0;
    }

    private void toOpen() {
        state = State.OPEN;
        openedAtMillis = clock.millis();
        halfOpenPermitsIssued = 0;
        halfOpenSuccesses = 0;
        resetWindow();
        log.warn("[circuit] dep={} -> OPEN for {}ms", dependencyName, props.getOpenDurationMs());
    }

    private void toHalfOpen() {
        state = State.HALF_OPEN;
        halfOpenPermitsIssued = 0;
        halfOpenSuccesses = 0;
        log.info("[circuit] dep={} -> HALF_OPEN, allowing {} probe(s)",
                dependencyName, props.getHalfOpenPermittedCalls());
    }

    private void toClosed() {
        state = State.CLOSED;
        halfOpenPermitsIssued = 0;
        halfOpenSuccesses = 0;
        resetWindow();
        log.info("[circuit] dep={} -> CLOSED", dependencyName);
    }
}
