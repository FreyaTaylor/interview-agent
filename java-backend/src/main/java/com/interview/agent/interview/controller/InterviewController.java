package com.interview.agent.interview.controller;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.interview.dto.CheckDuplicateRequest;
import com.interview.agent.interview.dto.CheckDuplicateResponse;
import com.interview.agent.interview.dto.DeleteResponse;
import com.interview.agent.interview.dto.FinalizeRequest;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.InterviewHistoryDetailResponse;
import com.interview.agent.interview.dto.InterviewHistoryItem;
import com.interview.agent.interview.dto.PreviewParseResponse;
import com.interview.agent.interview.dto.RecalibrateBody;
import com.interview.agent.interview.dto.RecordIdRequest;
import com.interview.agent.interview.dto.SaveDraftRequest;
import com.interview.agent.interview.dto.SaveDraftResponse;
import com.interview.agent.interview.dto.UpdateMetaBody;
import com.interview.agent.interview.dto.UpdateMetaResponse;
import com.interview.agent.interview.dto.UploadAudioResponse;
import com.interview.agent.interview.dto.UploadTextRequest;
import com.interview.agent.interview.service.InterviewAsrService;
import com.interview.agent.interview.service.InterviewBasicService;
import com.interview.agent.interview.service.InterviewParseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 面试复盘模块路由（S8a）。
 *
 * <p>历史接口遵循 RESTful 风格（对齐 Python 与 React 前端契约）：
 * <ul>
 *   <li>POST /preview-parse / /finalize / /parse / /check-duplicate / /overwrite / /draft</li>
 *   <li>GET /history（列表） / GET /history/{recordId}（详情）</li>
 *   <li>POST /history/{recordId}/recalibrate（继续校准）</li>
 *   <li>DELETE /history/{recordId}（删除） / PATCH /history/{recordId}（改公司/岗位）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewParseService parseService;
    private final InterviewBasicService basicService;
    private final InterviewAsrService asrService;

    public InterviewController(InterviewParseService parseService,
                               InterviewBasicService basicService,
                               InterviewAsrService asrService) {
        this.parseService = parseService;
        this.basicService = basicService;
        this.asrService = asrService;
    }

    @PostMapping("/preview-parse")
    public ApiResponse<PreviewParseResponse> previewParse(@Valid @RequestBody UploadTextRequest req) {
        return ApiResponse.success(parseService.previewParse(req.text()));
    }

    @PostMapping("/finalize")
    public ApiResponse<FinalizeResponse> finalizeRoute(@Valid @RequestBody FinalizeRequest req) {
        return ApiResponse.success(parseService.finalizeInterview(
                req.turns(), req.groups(), req.company(), req.position()));
    }

    @PostMapping("/parse")
    public ApiResponse<FinalizeResponse> parseRoute(@Valid @RequestBody UploadTextRequest req) {
        return ApiResponse.success(parseService.parseInterview(req.text(), req.company(), req.position()));
    }

    @GetMapping("/history")
    public ApiResponse<List<InterviewHistoryItem>> historyList() {
        return ApiResponse.success(basicService.historyList());
    }

    @GetMapping("/history/{recordId}")
    public ApiResponse<InterviewHistoryDetailResponse> historyDetail(@PathVariable long recordId) {
        return ApiResponse.success(basicService.historyDetail(recordId));
    }

    @PostMapping("/check-duplicate")
    public ApiResponse<CheckDuplicateResponse> checkDuplicate(@Valid @RequestBody CheckDuplicateRequest req) {
        return ApiResponse.success(basicService.checkDuplicate(req.text()));
    }

    @PostMapping("/overwrite")
    public ApiResponse<DeleteResponse> overwrite(@Valid @RequestBody RecordIdRequest req) {
        return ApiResponse.success(basicService.overwrite(req.recordId()));
    }

    @PostMapping("/draft")
    public ApiResponse<SaveDraftResponse> draft(@Valid @RequestBody SaveDraftRequest req) {
        return ApiResponse.success(basicService.saveDraft(
                req.recordId(), req.turns(), req.groups(), req.company(), req.position()));
    }

    @PostMapping("/history/{recordId}/recalibrate")
    public ApiResponse<FinalizeResponse> historyRecalibrate(@PathVariable long recordId,
                                                            @Valid @RequestBody RecalibrateBody req) {
        return ApiResponse.success(basicService.historyRecalibrate(recordId, req.turns(), req.groups()));
    }

    @DeleteMapping("/history/{recordId}")
    public ApiResponse<DeleteResponse> historyDelete(@PathVariable long recordId) {
        return ApiResponse.success(basicService.historyDelete(recordId));
    }

    @PatchMapping("/history/{recordId}")
    public ApiResponse<UpdateMetaResponse> historyUpdateMeta(@PathVariable long recordId,
                                                             @RequestBody UpdateMetaBody req) {
        return ApiResponse.success(basicService.updateMeta(recordId, req.company(), req.position()));
    }

    @PostMapping("/upload-audio")
    public ApiResponse<UploadAudioResponse> uploadAudio(@RequestParam("file") MultipartFile file) {
        String text = asrService.transcribe(file);
        return ApiResponse.success(new UploadAudioResponse(text));
    }
}
