package com.example.cs_agent_service.service.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redis;

    public RedisCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> get(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return Optional.ofNullable(v);
        } catch (Exception e) {
            // 降级：缓存挂了也不能影响主链路
            log.warn("[cache] redis GET failed, degrade to miss. key={}", key, e);
            return Optional.empty();
        }
    }

    public void set(String key, String value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            // 降级：写失败不影响主链路
            log.warn("[cache] redis SET failed, degrade ignore. key={}", key, e);
        }
    }
}
