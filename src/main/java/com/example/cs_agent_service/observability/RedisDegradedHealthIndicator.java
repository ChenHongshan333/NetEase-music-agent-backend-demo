package com.example.cs_agent_service.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 的健康检查，**故意不会把整体 health 标成 DOWN**。
 *
 * <p>Boot 自带的那个会：Redis 一挂，{@code /actuator/health} 立刻返回 503。
 * 在 K8s / 负载均衡后面这意味着实例被摘掉 —— 可是此时这个实例明明还能正确服务，
 * 读路径的缓存故障已经被降级成"多查一次数据库"。把它摘掉不但没救到任何人，
 * 反而让剩下的实例承担全部流量，把一次 Redis 抖动放大成一次全站故障。
 *
 * <p>所以这里返回 UP，并在 details 里标注 {@code redis: DEGRADED}，
 * 让监控看得见、让编排系统别乱动。
 *
 * <p>数据库不一样：它是必需依赖，Boot 默认的 DataSource 健康检查照常把整体标 DOWN。
 */
@Component("redis")
public class RedisDegradedHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisDegradedHealthIndicator.class);

    private final StringRedisTemplate redis;

    public RedisDegradedHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return Health.up()
                    .withDetail("redis", "UP")
                    .withDetail("ping", pong)
                    .build();
        } catch (Exception e) {
            log.warn("[health] redis unreachable; reporting UP/DEGRADED so the instance stays in rotation", e);
            return Health.up()
                    .withDetail("redis", "DEGRADED")
                    .withDetail("impact", "chat read path serves without cache; write endpoints "
                            + "requiring Idempotency-Key fail closed with 503")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
