package com.interview.agent.interview.exp.controller;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.interview.exp.dto.CreateInterviewExpNodeReq;
import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.dto.UpdateInterviewExpNodeReq;
import com.interview.agent.interview.exp.service.InterviewExpAdminService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin —— 面经树节点 CRUD。
 *
 * <p>路径前缀：/api/admin/interview-exp-nodes（前端 OutlinerEditor 套 apiPrefix="interview-exp-nodes"）。
 * 全 POST + body 传参，端点与知识树 tree-nodes 对齐。
 */
@RestController
@RequestMapping("/api/admin/interview-exp-nodes")
public class InterviewExpAdminController {

    private final InterviewExpAdminService service;

    public InterviewExpAdminController(InterviewExpAdminService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<InterviewExpNodeView> list() {
        return service.listAll();
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody CreateInterviewExpNodeReq req) {
        return service.create(req);
    }

    @PostMapping("/batch-sort")
    public Map<String, Object> batchSort(@RequestBody BatchSortReq req) {
        return service.batchSort(req);
    }

    @PostMapping("/update")
    public Map<String, Object> update(@RequestBody UpdateInterviewExpNodeReq req) {
        return service.update(req);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteNodeReq req) {
        return service.delete(req);
    }

    @PostMapping("/delete-children")
    public Map<String, Object> deleteChildren(@RequestBody DeleteNodeReq req) {
        return service.deleteChildren(req);
    }
}
