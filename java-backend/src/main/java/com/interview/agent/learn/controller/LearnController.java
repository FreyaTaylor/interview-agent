package com.interview.agent.learn.controller;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.auth.CurrentUser;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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

    private static final Logger log = LoggerFactory.getLogger(LearnController.class);

    private final LearnContentService contentService;
    private final LearnQuestionService questionService;
    private final ExecutorService virtualThreadExecutor;

    public LearnController(LearnContentService contentService,
                           LearnQuestionService questionService,
                           ExecutorService virtualThreadExecutor) {
        this.contentService = contentService;
        this.questionService = questionService;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    @PostMapping("/content")
    public ContentView content(@Valid @RequestBody LearnAssetRequest req) {
        return contentService.resolveContent(req);
    }

    @PostMapping("/subtopic-content")
    public SubtopicView subtopicContent(@Valid @RequestBody SubtopicContentRequest req) {
        return contentService.resolveSubtopicContent(req);
    }

    /**
     * 子话题讲解正文 —— 流式（SSE）。保持 POST（约定：全 POST + body 传参）。
     *
     * <p>事件协议（每帧一个 event + 单行 JSON data）：
     * <ul>
     *   <li>{@code token} → {@code {"t":"增量文本"}}：逐段正文</li>
     *   <li>{@code done}  → 完整 {@link SubtopicView} JSON：落库后的最终视图</li>
     *   <li>{@code error} → {@code {"message":"..."}}：生成失败</li>
     * </ul>
     *
     * <p>为何用 worker 线程：登录态在请求线程的 ThreadLocal，SSE 需异步写；
     * 故请求线程先取 {@code userId}，再用 {@link CurrentUser#runWith} 在虚拟线程里还原身份。
     */
    @PostMapping(value = "/subtopic-content-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subtopicContentStream(@Valid @RequestBody SubtopicContentRequest req) {
        long userId = CurrentUser.id();                  // 必须在请求线程取
        SseEmitter emitter = new SseEmitter(300_000L);   // 5 分钟超时
        virtualThreadExecutor.execute(() -> CurrentUser.runWith(userId, () -> {
            try {
                SubtopicView view = contentService.streamSubtopicContent(req, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(Map.of("t", token)));
                    } catch (IOException e) {
                        // 客户端断开：抛出以中断流式（事务回滚，不落库）
                        throw new IllegalStateException("SSE 已断开", e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(view));
                emitter.complete();
            } catch (Exception e) {
                log.warn("[learn] 讲解流式生成失败 subtopicId={}: {}", req.subtopicId(), e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "讲解生成失败，请重试")));
                } catch (IOException ignore) {
                    // 客户端已断开，忽略
                }
                emitter.complete();
            }
        }));
        return emitter;
    }

    @PostMapping("/questions")
    public QuestionsView questions(@Valid @RequestBody LearnAssetRequest req) {
        return questionService.resolveQuestions(req);
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
    public Integer selfMastery(@Valid @RequestBody SelfMasteryRequest req) {
        return contentService.setSelfMastery(req.kpId(), req.selfMastery());
    }
}
