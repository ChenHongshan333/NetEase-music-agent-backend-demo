package com.example.cs_agent_service.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("没有 X-Request-Id → 生成一个，写回响应头，并放进 MDC")
    void generatesIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        FilterChain chain = (req, res) -> seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).isNotBlank().hasSize(16);
        assertThat(seenInMdc.get())
                .as("过滤器链执行期间 MDC 里必须有 id，否则日志打不出来")
                .isEqualTo(header);
    }

    @Test
    @DisplayName("带了 X-Request-Id → 原样回传，链路 id 得以跨服务串联")
    void propagatesIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "upstream-trace-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("upstream-trace-42");
        assertThat(seenInMdc.get()).isEqualTo("upstream-trace-42");
    }

    @Test
    @DisplayName("空白的 X-Request-Id 视为没带")
    void blankIncomingIdIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank().hasSize(16);
    }

    @Test
    @DisplayName("超长的上游 id 被截断，避免日志被撑爆")
    void oversizedIncomingIdIsTruncated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "x".repeat(500));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSize(64);
    }

    @Test
    @DisplayName("请求结束后 MDC 被清空 —— 线程池复用不会把 id 泄漏给下一个请求")
    void mdcIsClearedAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                .as("MDC 是 ThreadLocal，不清就会串到复用同一线程的下一个请求上")
                .isNull();
    }

    @Test
    @DisplayName("链路抛异常时 MDC 同样被清空（清理在 finally 里）")
    void mdcIsClearedEvenWhenChainThrows() {
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), boom))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
