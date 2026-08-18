package com.example.cs_agent_service.observability;

import com.example.cs_agent_service.config.CacheProperties;
import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.service.ChatService;
import com.example.cs_agent_service.service.KnowledgeBaseService;
import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.example.cs_agent_service.service.llm.LlmClient;
import com.example.cs_agent_service.service.llm.LlmException;
import com.example.cs_agent_service.service.resilience.CircuitBreaker;
import com.example.cs_agent_service.service.resilience.RetryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentMetricsTest {

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000L;

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

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;
    private CircuitBreaker breaker;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();

        CacheProperties cacheProps = new CacheProperties();
        cacheProps.setEnabled(true);

        ResilienceProperties resilienceProps = new ResilienceProperties();
        MutableClock clock = new MutableClock();

        breaker = new CircuitBreaker("dashscope", resilienceProps.getCircuitBreaker(), clock);
        metrics = new AgentMetrics(registry, breaker);

        RetryExecutor retryExecutor = new RetryExecutor(
                resilienceProps.getRetry(), clock, clock::advance, bound -> bound, metrics);

        chatService = new ChatService(knowledgeBaseService, llmClient, cache, cacheProps,
                new ObjectMapper(), retryExecutor, breaker, resilienceProps, clock, metrics);

        when(cache.get(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("拒答链路：agent.refusal 计 1，agent.llm.call 计 0")
    void refusalPathRecordsRefusalAndNoLlmCall() {
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of());

        chatService.chat("完全不存在的问题");

        assertThat(counter("agent.refusal", "stage", "no_hits")).isEqualTo(1.0);
        assertThat(registry.find("agent.llm.call").timers())
                .as("拒答不该产生任何上游调用指标")
                .isEmpty();
        assertThat(counter("agent.cache.lookup", "result", "miss")).isEqualTo(1.0);
        assertThat(registry.get("agent.retrieval.hits").summary().totalAmount()).isZero();
    }

    @Test
    @DisplayName("正常链路：cache miss + retrieval hits + 一次 success 的 llm.call")
    void successPathRecordsTimerAndHits() {
        when(knowledgeBaseService.searchTop5(anyString()))
                .thenReturn(List.of(kb("q1"), kb("q2")));
        when(llmClient.complete(anyString(), anyString())).thenReturn("答案");

        chatService.chat("会员多少钱");

        assertThat(registry.get("agent.retrieval.hits").summary().totalAmount()).isEqualTo(2.0);
        assertThat(registry.get("agent.llm.call")
                .tags("outcome", "success", "attempt", "1").timer().count()).isEqualTo(1);

        // agent.refusal 在构造函数里就注册好了（这样 dashboard 从服务启动起就有序列，
        // 不会因为"还没触发过"而出现断点），所以这里断言计数为 0 而不是 meter 不存在。
        assertThat(registry.get("agent.refusal").counter().count()).isZero();
    }

    @Test
    @DisplayName("缓存命中：cache.lookup{result=hit} 计 1，且没有检索与上游指标")
    void cacheHitRecordsHit() {
        when(cache.get(anyString()))
                .thenReturn(Optional.of("{\"answer\":\"缓存答案\",\"hits\":2}"));

        chatService.chat("会员多少钱");

        assertThat(counter("agent.cache.lookup", "result", "hit")).isEqualTo(1.0);
        assertThat(registry.get("agent.retrieval.hits").summary().count())
                .as("缓存命中直接短路，不该走检索")
                .isZero();
        assertThat(registry.find("agent.llm.call").timers()).isEmpty();
    }

    @Test
    @DisplayName("重试耗尽：每次尝试各一条 llm.call，attempt 标签为 1/2/3，并计一次 degraded")
    void retryExhaustedRecordsPerAttemptAndDegraded() {
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of(kb("q1")));
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("boom", true, 503));

        chatService.chat("会员多少钱");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(registry.get("agent.llm.call")
                    .tags("outcome", "error", "attempt", Integer.toString(attempt))
                    .timer().count())
                    .as("attempt %d 应有一条记录", attempt)
                    .isEqualTo(1);
        }
        assertThat(counter("agent.degraded", "reason", "retry_exhausted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("熔断状态 gauge：CLOSED=0，跳闸后变 2")
    void circuitStateGaugeTracksState() {
        assertThat(registry.get("agent.circuit.state")
                .tag("dependency", "dashscope").gauge().value()).isZero();

        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of(kb("q1")));
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("boom", true, 503));

        // 第 10 次失败请求才让熔断跳闸，所以这 10 次记的都是 retry_exhausted
        for (int i = 0; i < 10; i++) {
            chatService.chat("会员多少钱");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 跳闸之后再来一次，这一次才会记成 circuit_open
        chatService.chat("会员多少钱");
        assertThat(registry.get("agent.circuit.state")
                .tag("dependency", "dashscope").gauge().value()).isEqualTo(2.0);
        assertThat(counter("agent.degraded", "reason", "circuit_open"))
                .as("熔断打开后的请求应记 circuit_open 而不是 retry_exhausted")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("超时被单独归类为 outcome=timeout，而不是笼统的 error")
    void timeoutsAreClassifiedSeparately() {
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of(kb("q1")));
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmException("read timeout", true, null,
                        new java.net.SocketTimeoutException("timeout")));

        chatService.chat("会员多少钱");

        assertThat(registry.get("agent.llm.call")
                .tags("outcome", "timeout", "attempt", "1").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("标签基数守卫：没有任何 tag 值来自用户输入")
    void noHighCardinalityTags() {
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of(kb("q1")));
        when(llmClient.complete(anyString(), anyString())).thenReturn("答案");

        // 用 50 个各不相同的问题打一遍
        for (int i = 0; i < 50; i++) {
            chatService.chat("独一无二的问题-" + i);
        }
        metrics.idempotency("new");
        metrics.idempotency("replayed");

        List<String> tagValues = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue)
                .distinct()
                .toList();

        // 允许出现的取值是一个写死的小集合。任何用户输入混进来都会让这条断言失败。
        assertThat(tagValues).allSatisfy(v -> assertThat(v).isIn(
                "hit", "miss", "no_hits", "success", "error", "timeout",
                "1", "2", "3", "dashscope",
                "new", "replayed", "conflict", "mismatch", "unavailable",
                "circuit_open", "retry_exhausted"));

        assertThat(tagValues).noneSatisfy(v -> assertThat(v).contains("独一无二的问题"));
    }

    private double counter(String name, String tagKey, String tagValue) {
        var c = registry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0.0 : c.count();
    }

    private static KnowledgeBase kb(String question) {
        KnowledgeBase k = new KnowledgeBase();
        k.setQuestion(question);
        k.setAnswer("答案");
        k.setActive(true);
        return k;
    }
}
