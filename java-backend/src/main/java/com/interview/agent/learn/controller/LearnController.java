package com.interview.agent.learn.controller;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.learn.dto.ContentView;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.dto.QuestionDeleteRequest;
import com.interview.agent.learn.dto.QuestionsView;
import com.interview.agent.learn.dto.QuestionTierRequest;
import com.interview.agent.learn.dto.SelfMasteryRequest;
import com.interview.agent.learn.dto.SubtopicContentRequest;
import com.interview.agent.learn.dto.SubtopicDeleteRequest;
import com.interview.agent.learn.dto.SubtopicView;
import com.interview.agent.learn.service.LearnContentService;
import com.interview.agent.learn.service.LearnQuestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learn 模块薄路由（S4 一期）。全 POST + body 参数（仓库 java-style.md "API 形式"）。
 * <ul>
 *   <li>POST /api/learn/content       body {kp_id, action} — 讲解（fetch/regenerate）</li>
 *   <li>POST /api/learn/questions     body {kp_id, action} — 题目（fetch/regenerate）</li>
 *   <li>POST /api/learn/question-delete body {kp_id, question_id} — 删除单道题</li>
 *   <li>POST /api/learn/subtopic-delete body {kp_id, subtopic_id} — 删除单个子话题</li>
 *   <li>POST /api/learn/self-mastery   body {kp_id, self_mastery} — 设置/清除自评掌握度</li>
 * </ul>
 *
 * <p>本类严格薄层：每个方法体一行委托，逻辑全部下沉到两个职责单一的 Service
 * （{@link LearnContentService} / {@link LearnQuestionService}）。
 */
@RestController
@RequestMapping("/api/learn")
public class LearnController {

    private final LearnContentService contentService;
    private final LearnQuestionService questionService;

    public LearnController(LearnContentService contentService,
                           LearnQuestionService questionService) {
        this.contentService = contentService;
        this.questionService = questionService;
    }

    @PostMapping("/content")
    public ApiResponse<ContentView> content(@Valid @RequestBody LearnAssetRequest req) {
        return ApiResponse.success(contentService.resolveContent(req));
    }

    @PostMapping("/subtopic-content")
    public ApiResponse<SubtopicView> subtopicContent(@Valid @RequestBody SubtopicContentRequest req) {
        return ApiResponse.success(contentService.resolveSubtopicContent(req));
    }

    @PostMapping("/questions")
    public ApiResponse<QuestionsView> questions(@Valid @RequestBody LearnAssetRequest req) {
        return ApiResponse.success(questionService.resolveQuestions(req));
    }

    @PostMapping("/question-delete")
    public ApiResponse<Void> deleteQuestion(@Valid @RequestBody QuestionDeleteRequest req) {
        questionService.deleteQuestion(req.kpId(), req.questionId());
        return ApiResponse.success(null);
    }

    @PostMapping("/question-tier")
    public ApiResponse<Void> setQuestionTier(@Valid @RequestBody QuestionTierRequest req) {
        questionService.setQuestionTier(req.kpId(), req.questionId(), req.tier());
        return ApiResponse.success(null);
    }

    @PostMapping("/subtopic-delete")
    public ApiResponse<Void> deleteSubtopic(@Valid @RequestBody SubtopicDeleteRequest req) {
        contentService.deleteSubtopic(req.kpId(), req.subtopicId());
        return ApiResponse.success(null);
    }

    @PostMapping("/self-mastery")
    public ApiResponse<Integer> selfMastery(@Valid @RequestBody SelfMasteryRequest req) {
        return ApiResponse.success(contentService.setSelfMastery(req.kpId(), req.selfMastery()));
    }
}
