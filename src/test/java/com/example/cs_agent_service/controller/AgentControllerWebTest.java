package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只测 HTTP 适配层：参数校验与响应形状。业务链路由 RefusalGateTest 负责。
 */
@WebMvcTest(AgentController.class)
class AgentControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    @DisplayName("happy path → 200，响应体含 answer 与 hits")
    void happyPath() throws Exception {
        when(chatService.chat(anyString()))
                .thenReturn(ChatResult.answered("小云的回答", 3, false));

        mockMvc.perform(get("/api/agent/chat").param("question", "会员多少钱"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("小云的回答"))
                .andExpect(jsonPath("$.hits").value(3));
    }

    @Test
    @DisplayName("拒答 path → 200 且 hits == 0（不是 4xx：这是一个成功的\"我不知道\"）")
    void refusalPath() throws Exception {
        when(chatService.chat(anyString()))
                .thenReturn(ChatResult.refused(ChatService.REFUSAL_ANSWER, false));

        mockMvc.perform(get("/api/agent/chat").param("question", "不存在的问题"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").value(0))
                .andExpect(jsonPath("$.answer").value(ChatService.REFUSAL_ANSWER));
    }

    @Test
    @DisplayName("缺少 question 参数 → 400")
    void missingQuestionParam() throws Exception {
        mockMvc.perform(get("/api/agent/chat"))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).chat(anyString());
    }

    @Test
    @DisplayName("question 为空字符串 / 纯空白 → 400，且不进入业务层")
    void blankQuestion() throws Exception {
        mockMvc.perform(get("/api/agent/chat").param("question", ""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/agent/chat").param("question", "   "))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).chat(anyString());
    }

    @Test
    @DisplayName("question 超过 500 字符 → 400（与知识库 question 字段长度上限对齐）")
    void overlongQuestion() throws Exception {
        String tooLong = "会".repeat(AgentController.MAX_QUESTION_LENGTH + 1);

        mockMvc.perform(get("/api/agent/chat").param("question", tooLong))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).chat(anyString());
    }

    @Test
    @DisplayName("question 正好 500 字符 → 放行")
    void boundaryQuestionLength() throws Exception {
        when(chatService.chat(anyString())).thenReturn(ChatResult.answered("ok", 1, false));
        String atLimit = "会".repeat(AgentController.MAX_QUESTION_LENGTH);

        mockMvc.perform(get("/api/agent/chat").param("question", atLimit))
                .andExpect(status().isOk());
    }
}
