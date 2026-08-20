package com.example.cs_agent_service.idempotency;

/**
 * 存在 Redis 里的幂等记录。
 *
 * @param state        IN_PROGRESS 或 COMPLETED
 * @param requestHash  规范化请求内容的 sha256，用于识别"同 key 不同 body"
 * @param httpStatus   已完成时的响应状态码
 * @param responseBody 已完成时的响应体（原样 JSON 文本，回放时逐字节吐回去）
 * @param createdAt    ISO-8601 时间戳，排查问题用
 */
public record IdempotencyRecord(
        State state,
        String requestHash,
        Integer httpStatus,
        String responseBody,
        String createdAt
) {

    public enum State {
        IN_PROGRESS, COMPLETED
    }

    public static IdempotencyRecord inProgress(String requestHash, String createdAt) {
        return new IdempotencyRecord(State.IN_PROGRESS, requestHash, null, null, createdAt);
    }

    public IdempotencyRecord completed(int httpStatus, String responseBody, String createdAt) {
        return new IdempotencyRecord(State.COMPLETED, requestHash, httpStatus, responseBody, createdAt);
    }
}
