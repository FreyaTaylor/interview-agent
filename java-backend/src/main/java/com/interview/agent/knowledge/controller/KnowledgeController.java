package com.interview.agent.knowledge.controller;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.knowledge.dto.KnowledgeTreeNodeView;
import com.interview.agent.knowledge.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识树查询 API（S2，对应 Python 端 backend.api.knowledge）。
 *
 * 路径：/api/knowledge
 *   GET /tree → 完整嵌套知识树
 *
 * 调用方：ExamPage 左侧树、Learn 页选点、Study 推荐入口。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping("/tree")
    public ApiResponse<List<KnowledgeTreeNodeView>> getTree() {
        return ApiResponse.success(service.buildTree());
    }
}
