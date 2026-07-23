package com.interview.agent.interview.exp.controller;

import com.interview.agent.common.BizException;
import com.interview.agent.interview.exp.dto.InterviewExpFromTextReq;
import com.interview.agent.interview.exp.dto.InterviewExpParseResult;
import com.interview.agent.interview.exp.service.InterviewExpParseService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;

/**
 * Admin —— 面经解析入口（新增面经）。
 *
 * <p>路径前缀：/api/admin/interview-exp
 * <ul>
 *   <li>POST /from-text  — 粘贴文本解析</li>
 *   <li>POST /from-image — 上传截图（OCR）解析</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/interview-exp")
public class InterviewExpParseController {

    /** 上传图片大小上限 10MB。 */
    private static final long MAX_UPLOAD_SIZE = 10 * 1024 * 1024;

    private final InterviewExpParseService parseService;

    public InterviewExpParseController(InterviewExpParseService parseService) {
        this.parseService = parseService;
    }

    @PostMapping("/from-text")
    public InterviewExpParseResult fromText(@RequestBody InterviewExpFromTextReq req) {
        return parseService.parseFromText(req.text());
    }

    @PostMapping("/from-image")
    public InterviewExpParseResult fromImage(@RequestParam("file") MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BizException(40001, "请上传图片文件");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new BizException(40001, "图片不能超过10MB");
        }
        String imageBase64 = Base64.getEncoder().encodeToString(readBytes(file));
        return parseService.parseFromImage(imageBase64, contentType);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException(50000, "读取上传文件失败");
        }
    }
}
