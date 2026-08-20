package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.config.ResilienceProperties;
import com.example.cs_agent_service.dto.ChatResult;
import com.example.cs_agent_service.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
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

    /** 降级时告诉客户端多久之后再来，取熔断器的 OPEN 时长——正好是它下次探测上游的时刻。 */
    private final long retryAfterSeconds;

    public AgentController(ChatService chatService, ResilienceProperties resilienceProps) {
        this.chatService = chatService;
        this.retryAfterSeconds =
                Math.max(1, resilienceProps.getCircuitBreaker().getOpenDurationMs() / 1000);
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

        // 客户端必须能区分"我不知道这个问题"和"我暂时坏了"：
        // 前者（200 + 拒答）重试多少次结果都一样，后者（503）重试是有意义的。
        // 混成同一个 200 会让调用方无从决策，只能要么全不重试、要么盲目重试。
        if (result.degraded()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .body(body);
        }

        return ResponseEntity.ok(body);
    }
}
