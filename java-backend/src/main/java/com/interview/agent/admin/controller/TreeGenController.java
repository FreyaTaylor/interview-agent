package com.interview.agent.admin.controller;

import com.interview.agent.admin.dto.CreateTreeFromGenerateReq;
import com.interview.agent.admin.dto.CreateTreeFromTextReq;
import com.interview.agent.admin.dto.TreeGenResp;
import com.interview.agent.admin.service.TreeGenService;
import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin —— 知识树生成（S5，一期范围：from-text + from-generate）。
 *
 * <p>路径前缀：/api/admin/trees
 *
 * <p>后续可扩展端点：
 * <ul>
 *   <li>POST /from-mm   .mm 文件导入（XML 解析，无 LLM）</li>
 *   <li>POST /{rootId}/optimize  LLM 全树重构</li>
 *   <li>POST /merge     合并两棵树</li>
 *   <li>POST /from-image  多模态截图解析</li>
 * </ul>
 *
 * <p>所有响应都包成统一 {@link ApiResponse}；业务异常由 GlobalExceptionHandler 兜底。
 */
@RestController
@RequestMapping("/api/admin/trees")
public class TreeGenController {

    private final TreeGenService treeGenService;

    public TreeGenController(TreeGenService treeGenService) {
        this.treeGenService = treeGenService;
    }

    /** 文本/Markdown 导入 → LLM 解析为树。前端"文本导入"Tab 调用。 */
    @PostMapping("/from-text")
    public ApiResponse<TreeGenResp> createFromText(@RequestBody CreateTreeFromTextReq req) {
        return ApiResponse.success(treeGenService.createFromText(req));
    }

    /** 给定根名 + 需求描述，LLM 一次生成完整知识树。前端"LLM 生成"Tab 调用。 */
    @PostMapping("/from-generate")
    public ApiResponse<TreeGenResp> createFromGenerate(@RequestBody CreateTreeFromGenerateReq req) {
        return ApiResponse.success(treeGenService.createFromGenerate(req));
    }
}
