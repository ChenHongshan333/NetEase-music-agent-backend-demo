package com.example.cs_agent_service.service;

import com.example.cs_agent_service.config.CacheProperties;
import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.entity.KnowledgeBase;
import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.example.cs_agent_service.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拒答闸门：检索 0 命中时必须直接拒答，一次上游调用都不能发生。
 *
 * <p>这是本服务最重要的一条不变式。它同时是成本控制（不为答不出的问题付 token 费）
 * 和正确性控制（没有依据就不让模型开口，避免幻觉）。
 */
@ExtendWith(MockitoExtension.class)
class RefusalGateTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private LlmClient llmClient;

    @Mock
    private RedisCacheService cache;

    @Captor
    private ArgumentCaptor<Long> ttlCaptor;

    private CacheProperties cacheProps;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        cacheProps = new CacheProperties();
        cacheProps.setEnabled(true);
        cacheProps.setTtlSeconds(600);
        cacheProps.setRefusalTtlSeconds(30);

        chatService = new ChatService(
                knowledgeBaseService, llmClient, cache, cacheProps, new ObjectMapper());
    }

    @Test
    @DisplayName("检索 0 命中 → 拒答，且绝不调用 LLM，缓存 TTL = 30s")
    void refusesWithoutCallingLlm() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of());

        ChatResult result = chatService.chat("完全不存在的问题");

        assertThat(result.refused()).isTrue();
        assertThat(result.hits()).isZero();
        assertThat(result.answer()).isEqualTo(ChatService.REFUSAL_ANSWER);

        // 核心断言：上游一次都没被碰过
        verify(llmClient, never()).complete(any(), any());

        // 拒答用短 TTL，这样刚补进知识库的条目能在 30s 内生效
        verify(cache).set(anyString(), anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(30L);
    }

    @Test
    @DisplayName("检索有命中 → 调用 LLM 恰好一次，缓存 TTL = 600s")
    void callsLlmExactlyOnceWhenRetrievalHits() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseService.searchTop5(anyString()))
                .thenReturn(List.of(kb("黑胶VIP会员价格是多少？", "以 App 页面为准")));
        when(llmClient.complete(anyString(), anyString())).thenReturn("小云的回答");

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.refused()).isFalse();
        assertThat(result.hits()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("小云的回答");

        verify(llmClient, times(1)).complete(anyString(), anyString());

        verify(cache).set(anyString(), anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(600L);
    }

    @Test
    @DisplayName("检索命中时，知识库答案被拼进 prompt，用户问题也在")
    void groundsPromptOnRetrievedAnswers() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(knowledgeBaseService.searchTop5(anyString()))
                .thenReturn(List.of(kb("云贝有什么用？", "云贝是虚拟货币")));
        when(llmClient.complete(anyString(), anyString())).thenReturn("ok");

        chatService.chat("云贝干什么用");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).complete(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("已知信息：")
                .contains("云贝是虚拟货币")
                .contains("用户问题：云贝干什么用");
    }

    @Test
    @DisplayName("缓存命中 → 既不检索也不调 LLM")
    void cacheHitShortCircuitsEverything() {
        when(cache.get(anyString()))
                .thenReturn(Optional.of("{\"answer\":\"缓存里的答案\",\"hits\":3}"));

        ChatResult result = chatService.chat("会员多少钱");

        assertThat(result.cached()).isTrue();
        assertThat(result.answer()).isEqualTo("缓存里的答案");
        assertThat(result.hits()).isEqualTo(3);

        verify(knowledgeBaseService, never()).searchTop5(anyString());
        verify(llmClient, never()).complete(any(), any());
    }

    @Test
    @DisplayName("缓存关闭时不读不写缓存，但拒答闸门照旧生效")
    void refusalGateWorksWithCacheDisabled() {
        cacheProps.setEnabled(false);
        when(knowledgeBaseService.searchTop5(anyString())).thenReturn(List.of());

        ChatResult result = chatService.chat("完全不存在的问题");

        assertThat(result.refused()).isTrue();
        verify(llmClient, never()).complete(any(), any());
        verify(cache, never()).get(anyString());
        verify(cache, never()).set(anyString(), anyString(), eq(30L));
    }

    private static KnowledgeBase kb(String question, String answer) {
        KnowledgeBase k = new KnowledgeBase();
        k.setQuestion(question);
        k.setAnswer(answer);
        k.setActive(true);
        return k;
    }
}
