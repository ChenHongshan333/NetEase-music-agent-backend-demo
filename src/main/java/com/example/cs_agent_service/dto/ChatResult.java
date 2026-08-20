package com.example.cs_agent_service.dto;

/**
 * chat 链路的内部结果。控制器据此决定 HTTP 状态码与响应头。
 *
 * @param answer   最终答案（可能是正常答案、拒答话术或降级话术）
 * @param hits     检索命中条数
 * @param refused  是否走了拒答闸门（检索 0 命中，未调用 LLM）
 * @param degraded 是否是降级结果（熔断打开 / 重试耗尽）
 * @param cached   是否来自缓存
 */
public record ChatResult(
        String answer,
        int hits,
        boolean refused,
        boolean degraded,
        boolean cached
) {

    public static ChatResult answered(String answer, int hits, boolean cached) {
        return new ChatResult(answer, hits, false, false, cached);
    }

    public static ChatResult refused(String answer, boolean cached) {
        return new ChatResult(answer, 0, true, false, cached);
    }

    public static ChatResult degraded(String answer, int hits) {
        return new ChatResult(answer, hits, false, true, false);
    }
}
