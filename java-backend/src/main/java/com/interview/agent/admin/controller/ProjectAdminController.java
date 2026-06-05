package com.interview.agent.admin.controller;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateProjectNodeReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.ProjectFromTextReq;
import com.interview.agent.admin.dto.ProjectFromTextResp;
import com.interview.agent.admin.dto.ProjectNodeView;
import com.interview.agent.admin.dto.UpdateProjectNodeReq;
import com.interview.agent.admin.service.ProjectAdminService;
import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin —— 项目树节点 CRUD + from-text（S6）。
 *
 * <p>路径前缀：/api/admin/project-nodes
 *
 * <p>遵循 java-style §3.3：<b>全 POST + body 传参</b>，不用 @GetMapping / @PathVariable。
 * 与前端 OutlinerEditor 调用契约严格对齐（与知识树路由同型，共享 DTO IdReq）。
 */
@RestController
@RequestMapping("/api/admin/project-nodes")
public class ProjectAdminController {

    private final ProjectAdminService service;

    public ProjectAdminController(ProjectAdminService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public ApiResponse<List<ProjectNodeView>> list() {
        return ApiResponse.success(service.listAll());
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateProjectNodeReq req) {
        return ApiResponse.success(service.create(req));
    }

    @PostMapping("/batch-sort")
    public ApiResponse<Map<String, Object>> batchSort(@RequestBody BatchSortReq req) {
        return ApiResponse.success(service.batchSort(req));
    }

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> update(@RequestBody UpdateProjectNodeReq req) {
        return ApiResponse.success(service.update(req));
    }

    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody DeleteNodeReq req) {
        return ApiResponse.success(service.delete(req));
    }

    @PostMapping("/from-text")
    public ApiResponse<ProjectFromTextResp> fromText(@RequestBody ProjectFromTextReq req) {
        return ApiResponse.success(service.createFromText(req));
    }
}
