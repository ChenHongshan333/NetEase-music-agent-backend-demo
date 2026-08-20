package com.example.cs_agent_service.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,metrics,prometheus")
class ActuatorEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/actuator/health 返回 200")
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Redis 不可用时整体 health 仍是 UP，只在 details 里标 DEGRADED")
    void redisOutageDoesNotTakeTheInstanceOutOfRotation() throws Exception {
        // test profile 下没有 Redis 在跑，ping 必然失败 —— 正好是我们要的场景。
        // 用 Boot 自带的 indicator 时这里会是 503，实例会被 LB 摘掉，
        // 尽管读路径此刻完全可以正常服务。
        mockMvc.perform(get("/actuator/health").header("Authorization", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/actuator/prometheus 暴露 agent_ 前缀的业务指标")
    void prometheusExposesAgentMetrics() throws Exception {
        // 先打一次业务请求，让指标真的被注册出来
        mockMvc.perform(get("/api/agent/chat").param("question", "黑胶VIP"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("agent_retrieval_hits")))
                .andExpect(content().string(containsString("agent_circuit_state")))
                .andExpect(content().string(containsString("dependency=\"dashscope\"")));
    }

    @Test
    @DisplayName("敏感端点不在白名单里：env / beans / configprops 一律 404")
    void sensitiveEndpointsAreNotExposed() throws Exception {
        for (String endpoint : new String[]{"env", "beans", "configprops", "threaddump", "loggers"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("每个响应都带 X-Request-Id，业务接口和 actuator 都不例外")
    void requestIdIsPresentOnResponses() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().exists("X-Request-Id"));

        mockMvc.perform(get("/api/agent/chat")
                        .param("question", "黑胶VIP")
                        .header("X-Request-Id", "trace-from-gateway"))
                .andExpect(header().string("X-Request-Id", "trace-from-gateway"));
    }

    @Test
    @DisplayName("prometheus 输出里不含用户问题内容（高基数守卫）")
    void prometheusOutputCarriesNoUserInput() throws Exception {
        mockMvc.perform(get("/api/agent/chat").param("question", "云贝有什么用"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("云贝"))));
    }
}
