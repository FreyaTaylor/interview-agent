package com.interview.agent.admin.controller;

import com.interview.agent.admin.dto.CreateTreeFromGenerateReq;
import com.interview.agent.admin.dto.CreateTreeFromTextReq;
import com.interview.agent.admin.dto.TreeGenResp;
import com.interview.agent.admin.service.TreeGenService;
import com.interview.agent.common.ApiResponse;
import com.interview.agent.common.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

/**
 * Admin —— 知识树生成（S5，一期范围：from-text + from-generate）。
 *
 * <p>路径前缀：/api/admin/trees
 *
 * <p>后续可扩展端点：
 * <ul>
 *   <li>POST /{rootId}/optimize  LLM 全树重构</li>
 *   <li>POST /merge     合并两棵树</li>
 * </ul>
 *
 * <p>所有响应都包成统一 {@link ApiResponse}；业务异常由 GlobalExceptionHandler 兜底。
 */
@RestController
@RequestMapping("/api/admin/trees")
public class TreeGenController {

    /** 截图 / .mm 文件大小上限：10MB（与 Python 一致）。 */
    private static final long MAX_UPLOAD_SIZE = 10L * 1024 * 1024;

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

    /**
     * 上传思维导图 / 大纲截图，调 qwen-vl 视觉解析为知识树。前端"截图导入"Tab 调用。
     *
     * <p>限制：必须是图片、≤10MB。文件转 base64 后交给 service。
     */
    @PostMapping("/from-image")
    public ApiResponse<TreeGenResp> createFromImage(@RequestParam("file") MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BizException(40001, "请上传图片文件");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new BizException(40001, "图片不能超过10MB");
        }
        byte[] bytes = readBytes(file);
        String imageBase64 = Base64.getEncoder().encodeToString(bytes);
        return ApiResponse.success(treeGenService.createFromImage(imageBase64, contentType));
    }

    /**
     * 导入 FreeMind / 幕布导出的 .mm 文件（XML 解析，不走 LLM）。前端".mm 导入"Tab 调用。
     *
     * <p>限制：文件名以 .mm 结尾、≤10MB。
     */
    @PostMapping("/from-mm")
    public ApiResponse<TreeGenResp> createFromMm(@RequestParam("file") MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".mm")) {
            throw new BizException(40001, "请上传 .mm 文件");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new BizException(40001, "文件不能超过10MB");
        }
        return ApiResponse.success(treeGenService.createFromMm(readBytes(file)));
    }

    /** 读取上传文件字节，IO 异常转 50000。 */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException(50000, "读取上传文件失败: " + e.getMessage(), e);
        }
    }
}
