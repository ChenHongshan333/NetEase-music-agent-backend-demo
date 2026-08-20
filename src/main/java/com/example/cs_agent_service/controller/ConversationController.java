package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.dto.ConversationResponse;
import com.example.cs_agent_service.dto.CreateConversationRequest;
import com.example.cs_agent_service.idempotency.Idempotent;
import com.example.cs_agent_service.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.cs_agent_service.dto.AddMessageRequest;
import com.example.cs_agent_service.dto.MessageResponse;
import java.util.List;


@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/messages")
    @Idempotent
    @Operation(summary = "追加会话消息",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = KnowledgeBaseController.IDEMPOTENCY_DOC))
    public ResponseEntity<MessageResponse> addMessage(
            @PathVariable Long id, @Valid @RequestBody AddMessageRequest req) {
        return ResponseEntity.ok(service.addMessage(id, req));
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> listMessages(@PathVariable Long id) {
        return service.listMessages(id);
    }

    @PostMapping
    @Idempotent
    @Operation(summary = "创建会话",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = KnowledgeBaseController.IDEMPOTENCY_DOC))
    public ResponseEntity<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @GetMapping("/{id}")
    public ConversationResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/close")
    public ConversationResponse close(@PathVariable Long id) {
        return service.close(id);
    }
}
