package com.example.cs_agent_service.service.resilience;

/**
 * 熔断器处于 OPEN（或 HALF_OPEN 且探测名额已满）时抛出。
 *
 * <p>抛这个异常的调用**没有碰过上游**，也没有 sleep。这正是熔断的意义：
 * 在依赖已经明显不健康时，快速失败比排队等超时便宜得多。
 */
public class CircuitOpenException extends RuntimeException {

    private final String dependencyName;

    public CircuitOpenException(String dependencyName, String message) {
        super(message);
        this.dependencyName = dependencyName;
    }

    public String getDependencyName() {
        return dependencyName;
    }
}
