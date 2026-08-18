package com.example.cs_agent_service.controller;

import com.example.cs_agent_service.dto.CreateKnowledgeBaseRequest;
import com.example.cs_agent_service.dto.KnowledgeBaseResponse;
import com.example.cs_agent_service.dto.UpdateKnowledgeBaseRequest;
import com.example.cs_agent_service.idempotency.Idempotent;
import com.example.cs_agent_service.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "知识库管理", description = "网易云音乐客服知识库API")
public class KnowledgeBaseController {

    /**
     * Idempotency-Key 只做文档声明，不绑定成方法参数：绑定的话它会进入
     * {@code pjp.getArgs()}，从而混进 requestHash，让"请求内容"的定义变得含糊。
     */
    static final String IDEMPOTENCY_DOC =
            "可选。同一个 key 重复提交只会执行一次，之后的请求回放首次响应并带上 "
                    + "Idempotency-Replayed: true。同 key 不同请求体 → 422；"
                    + "上一次仍在处理中 → 409；记录保留 24 小时。";

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @Idempotent
    @Operation(summary = "创建知识库条目", description = "添加新的客服知识库问答",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = IDEMPOTENCY_DOC))
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取知识库详情", description = "根据ID获取单个知识库条目详情")
    public KnowledgeBaseResponse get(@PathVariable @Parameter(description = "知识库ID") Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Idempotent
    @Operation(summary = "更新知识库条目", description = "修改已有的知识库问答内容",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = IDEMPOTENCY_DOC))
    public ResponseEntity<KnowledgeBaseResponse> update(
            @PathVariable @Parameter(description = "知识库ID") Long id,
            @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @GetMapping
    @Operation(summary = "查询知识库列表", description = "获取知识库列表，支持关键词搜索")
    public List<KnowledgeBaseResponse> list(
            @RequestParam(required = false)
            @Parameter(description = "搜索关键词，模糊匹配问题和关键词字段")
            String q) {
        return service.list(q);
    }

    @DeleteMapping("/{id}")
    @Idempotent
    @Operation(summary = "软删除知识库条目", description = "将知识库条目标记为不可用（软删除）",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = IDEMPOTENCY_DOC))
    public ResponseEntity<Void> deactivate(
            @PathVariable @Parameter(description = "知识库ID") Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
