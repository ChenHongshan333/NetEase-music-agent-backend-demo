package com.example.cs_agent_service.idempotency;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存版 store。比 mock 好写：{@code putIfAbsent} 的原子语义正是并发测试要验的东西，
 * 用 ConcurrentHashMap 天然就有，而用 mock 得自己伪造，反倒可能把 bug 一起伪造进去。
 *
 * <p>不实现 TTL —— 测试跑不到 24 小时。
 */
public class FakeIdempotencyStore implements IdempotencyStore {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    /** 打开后所有操作都抛异常，用来模拟 Redis 挂掉。 */
    private final AtomicBoolean broken = new AtomicBoolean(false);

    public void breakStore() {
        broken.set(true);
    }

    public void repair() {
        broken.set(false);
    }

    public int size() {
        return data.size();
    }

    public Optional<String> peek(String key) {
        return Optional.ofNullable(data.get(key));
    }

    private void failIfBroken() {
        if (broken.get()) {
            throw new IllegalStateException("simulated store outage");
        }
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        failIfBroken();
        return data.putIfAbsent(key, value) == null;
    }

    @Override
    public Optional<String> get(String key) {
        failIfBroken();
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        failIfBroken();
        data.put(key, value);
    }

    @Override
    public void delete(String key) {
        failIfBroken();
        data.remove(key);
    }
}
