package com.example.cs_agent_service.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 实现。注意这里**没有** try/catch —— 异常必须冒出去，
 * 好让切面把它变成 503（fail closed）。见 {@link IdempotencyStore} 的说明。
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key, value, ttl);
        if (acquired == null) {
            // 只有在 pipeline / 事务里才会返回 null。拿不到明确答案就当作失败，
            // 由调用方走 fail closed —— 猜"我大概拿到了执行权"是最危险的选项。
            throw new IllegalStateException("SETNX returned no result for " + key);
        }
        return acquired;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }
}
