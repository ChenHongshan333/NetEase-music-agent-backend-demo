package com.example.cs_agent_service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求打上 request id：读 {@code X-Request-Id}，没有就生成一个，
 * 放进 MDC 供日志输出，并原样写回响应头。
 *
 * <p>排在最前面，这样它之后的所有日志行（包括异常处理器写的）都带得上 id。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** 16 位十六进制足够在单机日志里区分请求，比完整 UUID 短一半。 */
    private static final int ID_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 必须清。servlet 容器复用线程，MDC 是 ThreadLocal —— 不清的话
            // 这个 id 会泄漏到下一个恰好落在同一线程上的请求，
            // 于是日志把两个无关请求串成一个，排查时彻底误导人。
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            String trimmed = incoming.trim();
            // 上游传进来的值会被原样写进日志，限个长度免得日志被撑爆
            return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }
}
