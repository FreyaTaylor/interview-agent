package com.interview.agent.admin.controller;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateKnowledgeNodeReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.KnowledgeNodeView;
import com.interview.agent.admin.dto.UpdateKnowledgeNodeReq;
import com.interview.agent.admin.service.KnowledgeAdminService;
import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin —— 知识树节点 CRUD（S1）。
 *
 * <p>路径前缀：/api/admin/tree-nodes
 *
 * <p>遵循 java-style §3.3：<b>全 POST + body 传参</b>，不用 @GetMapping / @PathVariable。
 * 5 个端点以路径动词区分，无混淆：
 * <ul>
 *   <li>POST /list — 拉整棵树</li>
 *   <li>POST /create — 新增节点</li>
 *   <li>POST /batch-sort — 批量改 sort_order</li>
 *   <li>POST /update — 部分更新（id 在 body）</li>
 *   <li>POST /delete — 递归删除（id 在 body）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/tree-nodes")
public class KnowledgeAdminController {

    private final KnowledgeAdminService service;

    public KnowledgeAdminController(KnowledgeAdminService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public ApiResponse<List<KnowledgeNodeView>> list() {
        return ApiResponse.success(service.listAll());
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateKnowledgeNodeReq req) {
        return ApiResponse.success(service.create(req));
    }

    @PostMapping("/batch-sort")
    public ApiResponse<Map<String, Object>> batchSort(@RequestBody BatchSortReq req) {
        return ApiResponse.success(service.batchSort(req));
    }

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> update(@RequestBody UpdateKnowledgeNodeReq req) {
        return ApiResponse.success(service.update(req));
    }

    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody DeleteNodeReq req) {
        return ApiResponse.success(service.delete(req));
    }

    @PostMapping("/delete-children")
    public ApiResponse<Map<String, Object>> deleteChildren(@RequestBody DeleteNodeReq req) {
        return ApiResponse.success(service.deleteChildren(req));
    }
}
