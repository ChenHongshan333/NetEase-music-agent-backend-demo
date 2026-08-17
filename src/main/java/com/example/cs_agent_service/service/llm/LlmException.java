package com.example.cs_agent_service.service.llm;

/**
 * LLM 调用失败。
 *
 * <p>{@code retryable} 是整个韧性链路的判定依据：RetryExecutor 只重试
 * {@code retryable == true} 的失败，CircuitBreaker 也只把这类失败计入失败率。
 * 把"上游抖动"（可重试）与"我们请求写错了"（不可重试）分开，是为了避免
 * 自己的 bug 触发熔断——那会把一个确定性错误放大成整体不可用。
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    /** 上游返回的 HTTP 状态码；网络层异常（连接失败、超时）时为 null。 */
    private final Integer httpStatus;

    /** 上游 429 返回的 Retry-After 秒数；不存在时为 null。 */
    private final Long retryAfterSeconds;

    public LlmException(String message, boolean retryable, Integer httpStatus) {
        this(message, retryable, httpStatus, null, null);
    }

    public LlmException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        this(message, retryable, httpStatus, null, cause);
    }

    public LlmException(String message, boolean retryable, Integer httpStatus,
                        Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * HTTP 状态码 → 是否可重试。
     *
     * <p>429 / 5xx / 408 是上游侧的瞬时状态，重试有意义；
     * 其余 4xx 是确定性拒绝（鉴权错、参数错、模型名写错），重试只是白烧配额。
     */
    public static boolean isRetryableStatus(int status) {
        if (status == 429 || status == 408) {
            return true;
        }
        return status >= 500 && status <= 599;
    }
}
