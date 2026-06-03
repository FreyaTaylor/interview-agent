package com.interview.agent.admin.controller;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateKnowledgeNodeReq;
import com.interview.agent.admin.dto.KnowledgeNodeView;
import com.interview.agent.admin.dto.UpdateKnowledgeNodeReq;
import com.interview.agent.admin.service.KnowledgeAdminService;
import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin —— 知识树节点 CRUD（S1）。
 *
 * 路径前缀：/api/admin/tree-nodes
 *
 * 注意路径冲突顺序：Spring MVC 字面量段优先于 PathVariable，
 *                  所以 /batch-sort 和 /{id} 共存没问题。
 */
@RestController
@RequestMapping("/api/admin/tree-nodes")
public class KnowledgeAdminController {

    private final KnowledgeAdminService service;

    public KnowledgeAdminController(KnowledgeAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeNodeView>> list() {
        return ApiResponse.success(service.listAll());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateKnowledgeNodeReq req) {
        return ApiResponse.success(service.create(req));
    }

    @PutMapping("/batch-sort")
    public ApiResponse<Map<String, Object>> batchSort(@RequestBody BatchSortReq req) {
        return ApiResponse.success(service.batchSort(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable long id,
            @RequestBody UpdateKnowledgeNodeReq req) {
        return ApiResponse.success(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        return ApiResponse.success(service.delete(id));
    }
}
