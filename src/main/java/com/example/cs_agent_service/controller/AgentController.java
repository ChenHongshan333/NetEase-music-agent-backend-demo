package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只做 HTTP 适配：校验入参 → 调 ChatService → 映射状态码。
 * 检索、拼 prompt、调 LLM 一律不在这里。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 与 CreateKnowledgeBaseRequest.question 的 @Size(max = 500) 对齐。 */
    static final int MAX_QUESTION_LENGTH = 500;

    private final ChatService chatService;

    public AgentController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/chat")
    @Operation(summary = "智能客服问答测试")
    public ResponseEntity<Map<String, Object>> chat(@RequestParam("question") String question) {
        if (question == null || question.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        if (question.trim().length() > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "question 长度不能超过 " + MAX_QUESTION_LENGTH + " 字符");
        }

        ChatResult result = chatService.chat(question);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", result.answer());
        body.put("hits", result.hits());

        return ResponseEntity.ok(body);
    }
}
