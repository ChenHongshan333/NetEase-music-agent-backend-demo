package com.example.cs_agent_service.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等记录的存储。
 *
 * <p>与 {@code RedisCacheService} 刻意相反：**这里的方法一律不吞异常**。
 * 读路径的缓存挂了可以当 miss 继续服务；写路径的幂等存储挂了就必须停下来，
 * 因为此时我们无法保证"同一个 key 只执行一次"，而重复写入的代价高于短暂不可用。
 */
public interface IdempotencyStore {

    /**
     * SETNX + EX。
     *
     * @return true 表示 key 之前不存在、当前调用拿到了执行权
     */
    boolean putIfAbsent(String key, String value, Duration ttl);

    Optional<String> get(String key);

    /** 覆盖写（把 IN_PROGRESS 改成 COMPLETED），同时刷新 TTL。 */
    void put(String key, String value, Duration ttl);

    void delete(String key);
}
