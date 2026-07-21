package com.interview.agent.text.controller;

import com.interview.agent.text.dto.TextCorrectRequest;
import com.interview.agent.text.dto.TextCorrectResponse;
import com.interview.agent.text.service.TextCorrectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用文本工具 API（一期仅 1 个接口）。
 *
 * <ul>
 *   <li>POST /api/text/correct  body {text, context?}  — ASR 同音错别字纠正</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/text")
public class TextController {

    private final TextCorrectService correctService;

    public TextController(TextCorrectService correctService) {
        this.correctService = correctService;
    }

    @PostMapping("/correct")
    public TextCorrectResponse correct(@Valid @RequestBody TextCorrectRequest req) {
        String corrected = correctService.correct(req.text(), req.context());
        return new TextCorrectResponse(corrected);
    }
}
