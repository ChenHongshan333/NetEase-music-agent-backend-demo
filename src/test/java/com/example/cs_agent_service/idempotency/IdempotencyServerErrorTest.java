package com.example.cs_agent_service.idempotency;

import com.example.cs_agent_service.dto.CreateKnowledgeBaseRequest;
import com.example.cs_agent_service.dto.KnowledgeBaseResponse;
import com.example.cs_agent_service.service.KnowledgeBaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 5xx 与 4xx 的差异化处理 —— 这是整个幂等实现里最容易做错的一处。
 *
 * <p>4xx 是确定性结果，重放它是正确的；5xx 可能只是瞬时故障，
 * 必须删掉 key，让客户端带同一个 key 重试有机会成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyServerErrorTest.FakeStoreConfig.class)
class IdempotencyServerErrorTest {

    @TestConfiguration
    static class FakeStoreConfig {
        @Bean
        @Primary
        FakeIdempotencyStore fakeIdempotencyStore() {
            return new FakeIdempotencyStore();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeIdempotencyStore store;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    private static final String BODY = """
            {"question":"5xx 测试","answer":"答案","keywords":"kw"}
            """;

    @Test
    @DisplayName("业务抛 5xx ResponseStatusException → 500 响应，key 被删除")
    void serverErrorResponseReleasesTheKey() throws Exception {
        when(knowledgeBaseService.create(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "downstream boom"));

        int before = store.size();

        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, "key-5xx-rse")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError());

        assertThat(store.size())
                .as("5xx 之后 key 必须被删掉，否则客户端重试只会拿到回放的错误")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("业务抛未预期异常 → key 被删除；同 key 重试能真正执行成功")
    void unexpectedExceptionReleasesTheKeySoRetryCanSucceed() throws Exception {
        String key = "key-5xx-raw";

        when(knowledgeBaseService.create(any()))
                .thenThrow(new RuntimeException("transient boom"))
                .thenReturn(response());

        int before = store.size();

        // MockMvc 会把没有 HandlerExceptionResolver 处理的异常原样抛出，
        // 而不是渲染成 500 响应。真实容器里这就是一个 500。
        assertThatThrownBy(() -> mockMvc.perform(post("/api/knowledge")
                .header(IdempotencyAspect.HEADER_KEY, key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)))
                .rootCause()
                .hasMessage("transient boom");

        assertThat(store.size())
                .as("未预期异常同样要释放 key")
                .isEqualTo(before);

        // 同一个 key 重试 —— 这次应该真的执行，而不是被 409 或回放挡下
        mockMvc.perform(post("/api/knowledge")
                        .header(IdempotencyAspect.HEADER_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyAspect.HEADER_REPLAYED));

        verify(knowledgeBaseService, times(2)).create(any(CreateKnowledgeBaseRequest.class));
    }

    private static KnowledgeBaseResponse response() {
        return new KnowledgeBaseResponse(1L, "5xx 测试", "答案", "kw", true, LocalDateTime.now());
    }
}
