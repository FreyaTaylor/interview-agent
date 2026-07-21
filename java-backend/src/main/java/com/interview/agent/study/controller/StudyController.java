package com.interview.agent.study.controller;

import com.interview.agent.study.dto.AttemptDetailResponse;
import com.interview.agent.study.dto.AttemptFinishResponse;
import com.interview.agent.study.dto.AttemptIdRequest;
import com.interview.agent.study.dto.AttemptStartResponse;
import com.interview.agent.study.dto.AttemptTurnRequest;
import com.interview.agent.study.dto.AttemptTurnResponse;
import com.interview.agent.study.dto.AttemptsHistoryRequest;
import com.interview.agent.study.dto.AttemptsHistoryResponse;
import com.interview.agent.study.dto.QuestionIdRequest;
import com.interview.agent.study.service.StudyAttemptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Study 模块薄路由（S3 一期）。全 POST + body 参数（仓库 java-style.md "API 形式"）。
 *
 * <p>题目列表 / 重生由 Learn 统一提供：{@code POST /api/learn/questions} body {kp_id, action}。
 * {@code QuestionItemView} 已带 {@code question_score}（Learn 反向依赖 ScoreAggregateService 计算），
 * 前端 ExamPage 直接消费。
 *
 * <ul>
 *   <li>POST /api/study/attempt-start    body {question_id}                  — 创建 in_progress</li>
 *   <li>POST /api/study/attempt-turn     body {attempt_id, user_answer}      — 单轮评估 + 可选追问</li>
 *   <li>POST /api/study/attempt-finish   body {attempt_id}                   — 综合评分 + 刷 mastery</li>
 *   <li>POST /api/study/attempt-detail   body {attempt_id}                   — 完整作答详情</li>
 *   <li>POST /api/study/attempts-history body {question_id, limit?}          — 该题作答历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/study")
public class StudyController {

    private final StudyAttemptService attemptService;

    public StudyController(StudyAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @PostMapping("/attempt-start")
    public AttemptStartResponse attemptStart(@Valid @RequestBody QuestionIdRequest req) {
        return attemptService.start(req.questionId());
    }

    @PostMapping("/attempt-turn")
    public AttemptTurnResponse attemptTurn(@Valid @RequestBody AttemptTurnRequest req) {
        return attemptService.turn(req.attemptId(), req.userAnswer());
    }

    @PostMapping("/attempt-finish")
    public AttemptFinishResponse attemptFinish(@Valid @RequestBody AttemptIdRequest req) {
        return attemptService.finish(req.attemptId());
    }

    @PostMapping("/attempt-detail")
    public AttemptDetailResponse attemptDetail(@Valid @RequestBody AttemptIdRequest req) {
        return attemptService.detail(req.attemptId());
    }

    @PostMapping("/attempts-history")
    public AttemptsHistoryResponse attemptsHistory(@Valid @RequestBody AttemptsHistoryRequest req) {
        return attemptService.history(req.questionId(),
                req.limit() == null ? 0 : req.limit());
    }
}
