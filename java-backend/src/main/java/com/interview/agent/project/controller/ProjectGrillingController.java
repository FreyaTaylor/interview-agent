package com.interview.agent.project.controller;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.project.dto.AttemptDetailResponse;
import com.interview.agent.project.dto.AttemptFinishResponse;
import com.interview.agent.project.dto.AttemptIdRequest;
import com.interview.agent.project.dto.AttemptStartResponse;
import com.interview.agent.project.dto.AttemptTurnRequest;
import com.interview.agent.project.dto.AttemptTurnResponse;
import com.interview.agent.project.dto.AttemptsHistoryResponse;
import com.interview.agent.project.dto.DimensionItem;
import com.interview.agent.project.dto.ProfileResponse;
import com.interview.agent.project.dto.ProjectIdRequest;
import com.interview.agent.project.dto.ProjectListItem;
import com.interview.agent.project.dto.QuestionIdRequest;
import com.interview.agent.project.dto.TopicIdRequest;
import com.interview.agent.project.dto.TopicQuestionsResponse;
import com.interview.agent.project.service.ProjectAttemptService;
import com.interview.agent.project.service.ProjectGrillingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目拷打模块薄路由（S7.1 + S7.2 — 9 个端点全 POST + body，符合 java-style.md "API 形式"）。
 *
 * <p>读端点（S7.1）：
 * <ul>
 *   <li>POST /api/project-grilling/projects-list    body {}             — 列项目 + 准备度</li>
 *   <li>POST /api/project-grilling/profile-detail   body {project_id}   — 取项目画像（懒创建空记录）</li>
 *   <li>POST /api/project-grilling/dimensions-list  body {project_id}   — L2 话题列表 + 话题分</li>
 *   <li>POST /api/project-grilling/topic-questions  body {topic_id}     — L3 题目列表 + 题目分</li>
 * </ul>
 *
 * <p>Attempt 端点（S7.2 — 状态机 start → turn* → finish）：
 * <ul>
 *   <li>POST /api/project-grilling/attempt-start    body {question_id}              — 创建 in_progress</li>
 *   <li>POST /api/project-grilling/attempt-turn     body {attempt_id, user_answer}  — 单轮评估 + 可选追问</li>
 *   <li>POST /api/project-grilling/attempt-finish   body {attempt_id}               — 综合评分</li>
 *   <li>POST /api/project-grilling/attempt-detail   body {attempt_id}               — 完整作答快照</li>
 *   <li>POST /api/project-grilling/attempts-history body {question_id}              — 该题作答历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/project-grilling")
public class ProjectGrillingController {

    private final ProjectGrillingService service;
    private final ProjectAttemptService attemptService;

    public ProjectGrillingController(ProjectGrillingService service,
                                     ProjectAttemptService attemptService) {
        this.service = service;
        this.attemptService = attemptService;
    }

    // ===== S7.1 读端点 =====

    @PostMapping("/projects-list")
    public ApiResponse<List<ProjectListItem>> projectsList() {
        return ApiResponse.success(service.listProjects());
    }

    @PostMapping("/profile-detail")
    public ApiResponse<ProfileResponse> profileDetail(@Valid @RequestBody ProjectIdRequest req) {
        return ApiResponse.success(service.profileDetail(req.projectId()));
    }

    @PostMapping("/dimensions-list")
    public ApiResponse<List<DimensionItem>> dimensionsList(@Valid @RequestBody ProjectIdRequest req) {
        return ApiResponse.success(service.dimensionsList(req.projectId()));
    }

    @PostMapping("/topic-questions")
    public ApiResponse<TopicQuestionsResponse> topicQuestions(@Valid @RequestBody TopicIdRequest req) {
        return ApiResponse.success(service.topicQuestions(req.topicId()));
    }

    // ===== S7.2 attempt 端点 =====

    @PostMapping("/attempt-start")
    public ApiResponse<AttemptStartResponse> attemptStart(@Valid @RequestBody QuestionIdRequest req) {
        return ApiResponse.success(attemptService.start(req.questionId()));
    }

    @PostMapping("/attempt-turn")
    public ApiResponse<AttemptTurnResponse> attemptTurn(@Valid @RequestBody AttemptTurnRequest req) {
        return ApiResponse.success(attemptService.turn(req.attemptId(), req.userAnswer()));
    }

    @PostMapping("/attempt-finish")
    public ApiResponse<AttemptFinishResponse> attemptFinish(@Valid @RequestBody AttemptIdRequest req) {
        return ApiResponse.success(attemptService.finish(req.attemptId()));
    }

    @PostMapping("/attempt-detail")
    public ApiResponse<AttemptDetailResponse> attemptDetail(@Valid @RequestBody AttemptIdRequest req) {
        return ApiResponse.success(attemptService.detail(req.attemptId()));
    }

    @PostMapping("/attempts-history")
    public ApiResponse<AttemptsHistoryResponse> attemptsHistory(@Valid @RequestBody QuestionIdRequest req) {
        return ApiResponse.success(attemptService.history(req.questionId(), 0));
    }
}
