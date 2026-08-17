package com.example.cs_agent_service.service;

import com.example.cs_agent_service.service.cache.RedisCacheService;
import com.example.cs_agent_service.service.llm.StubLlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 降级演练（对应 README 的 Degradation Drill）：Redis 挂了，读路径必须继续服务。
 *
 * <p>缓存对 chat 读路径是**可选依赖**。它故障时最坏的结果是多查一次数据库、
 * 多调一次上游——所以正确的降级方向是 fail open：放行。
 * 断言重点是异常不能冒泡到 controller 层，HTTP 必须仍是 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "agent.cache.enabled=true")
class CacheDegradationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 注意：这里注入的是会抛异常的 mock，而不是让真实 RedisCacheService 去连不存在的 Redis。 */
    @MockitoBean
    private RedisCacheService cache;

    @Test
    @DisplayName("cache.get 抛异常 → 按 cache miss 走完整链路，返回 200 与正确答案")
    void getFailureDegradesToMiss() throws Exception {
        when(cache.get(anyString())).thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(get("/api/agent/chat").param("question", "黑胶VIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(StubLlmClient.STUB_ANSWER))
                .andExpect(jsonPath("$.hits").value(org.hamcrest.Matchers.greaterThan(0)));

        // 确认真的走到了检索 + 上游，而不是被别的分支绕过
        verify(cache).get(anyString());
    }

    @Test
    @DisplayName("cache.set 抛异常 → 答案仍正常返回，异常被吞掉不外泄")
    void setFailureIsSwallowed() throws Exception {
        when(cache.get(anyString())).thenReturn(java.util.Optional.empty());
        doThrow(new RuntimeException("redis down")).when(cache).set(anyString(), anyString(), anyLong());

        mockMvc.perform(get("/api/agent/chat").param("question", "云贝"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(StubLlmClient.STUB_ANSWER));

        verify(cache).set(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Redis 抛异常时拒答闸门行为不变：仍是 200 + hits=0 + 拒答话术")
    void refusalGateSurvivesRedisFailure() throws Exception {
        when(cache.get(anyString())).thenThrow(new RuntimeException("redis down"));
        doThrow(new RuntimeException("redis down")).when(cache).set(anyString(), anyString(), anyLong());

        String nonsense = "zz-" + UUID.randomUUID();

        mockMvc.perform(get("/api/agent/chat").param("question", nonsense))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").value(0))
                .andExpect(jsonPath("$.answer").value(ChatService.REFUSAL_ANSWER));
    }
}
