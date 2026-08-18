package com.example.cs_agent_service.service;

import com.example.cs_agent_service.config.CacheProperties;
import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.observability.AgentMetrics;
import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.example.cs_agent_service.service.llm.LlmClient;
import com.example.cs_agent_service.service.llm.LlmException;
import com.example.cs_agent_service.service.resilience.CircuitBreaker;
import com.example.cs_agent_service.service.resilience.RetryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上游故障时的降级行为：重试 → 熔断 → 降级结果，且降级结果绝不进缓存。
 *
 * <p>这里用的是真实的 RetryExecutor 和 CircuitBreaker（只把时钟和 sleep 换掉），
 * 因为要验证的正是它们串起来之后的效果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatDegradationTest {

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) { this.millis = millis; }

        void advance(long delta) { this.millis += delta; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private LlmClient llmClient;

    @Mock
    private RedisCacheService cache;

    private CacheProperties cacheProps;
    private ResilienceProperties resilienceProps;
    private MutableClock clock;
    private CircuitBreaker breaker;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        cacheProps = new CacheProperties();
        cacheProps.setEnabled(true);
        cacheProps.setTtlSeconds(600);
        cacheProps.setRefusalTtlSeconds(30);

        resilienceProps = new ResilienceProperties();
        clock = new MutableClock(1_000_000L);

        // sleep 只推进虚拟时钟，测试不会真的等
        RetryExecutor retryExecutor = new RetryExecutor(
                resilienceProps.getRetry(), clock, clock::advance, bound -> bound);
        breaker = new CircuitBreaker("dashscope", resilienceProps.getCircuitBreaker(), clock);
        AgentMetrics metrics = new AgentMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), breaker);

        chatService = new ChatService(knowledgeBaseService, llmClient, cache, cacheProps,
                new ObjectMapper(), retryExecutor, breaker, resilienceProps, clock, metrics);

        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseService.searchTop5(anyString()))
                .thenReturn(List.of(kb("黑胶VIP会员价格是多少？", "以 App 页面为准")));
    }

    @Test
    @DisplayName("重试耗尽 → 降级结果，且不写入正常答案缓存")
    void retryExhaustedDegradesWithoutPoisoningCache() {
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("upstream 503", true, 503));

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.degraded()).isTrue();
        assertThat(result.refused()).isFalse();
        assertThat(result.answer()).isEqualTo(ChatService.DEGRADED_ANSWER);
        assertThat(result.hits()).isEqualTo(1);

        // 3 次尝试（初次 + 2 次重试）
        verify(llmClient, times(3)).complete(anyString(), anyString());

        // 核心断言：一次上游抖动不能把"我暂时坏了"锁进缓存 600 秒
        verify(cache, never()).set(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("不可重试的失败（400）→ 只调一次，同样降级且不写缓存")
    void nonRetryableFailureDegradesImmediately() {
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("bad request", false, 400));

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.degraded()).isTrue();
        verify(llmClient, times(1)).complete(anyString(), anyString());
        verify(cache, never()).set(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("熔断打开后 → 降级且零上游调用，重试循环根本不进入")
    void circuitOpenDegradesWithoutTouchingUpstream() {
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("upstream 503", true, 503));

        // 熔断在外层、重试在内层，所以一次 chat 只给熔断器贡献 **一个** 样本
        // （"这次调用整体失败了"），而不是 3 个。minimum-calls=10 因此意味着
        // 10 次失败的请求 —— 底下是 30 次上游尝试。
        for (int i = 0; i < resilienceProps.getCircuitBreaker().getMinimumCalls(); i++) {
            chatService.chat("会员多少钱");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsAtOpen = org.mockito.Mockito.mockingDetails(llmClient).getInvocations().size();

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.degraded()).isTrue();
        assertThat(result.answer()).isEqualTo(ChatService.DEGRADED_ANSWER);
        assertThat(org.mockito.Mockito.mockingDetails(llmClient).getInvocations())
                .as("熔断打开时不得有任何上游调用")
                .hasSize(callsAtOpen);
        verify(cache, never()).set(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("拒答仍然照常缓存：降级不写缓存这条规则只针对降级")
    void refusalStillGetsCached() {
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of());

        ChatResult result = chatService.chat("不存在的问题");

        assertThat(result.refused()).isTrue();
        assertThat(result.degraded()).isFalse();
        verify(cache).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(30L));
    }

    @Test
    @DisplayName("上游恢复 → 正常答案照常写缓存")
    void successfulAnswerIsCached() {
        when(llmClient.complete(anyString(), anyString())).thenReturn("小云的回答");

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.degraded()).isFalse();
        assertThat(result.answer()).isEqualTo("小云的回答");
        verify(cache).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(600L));
    }

    private static KnowledgeBase kb(String question, String answer) {
        KnowledgeBase k = new KnowledgeBase();
        k.setQuestion(question);
        k.setAnswer(answer);
        k.setActive(true);
        return k;
    }
}
