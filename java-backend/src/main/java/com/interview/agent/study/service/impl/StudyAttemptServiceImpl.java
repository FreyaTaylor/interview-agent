package com.interview.agent.study.service.impl;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.learn.service.RubricGenService;
import com.interview.agent.study.dto.AttemptDetailResponse;
import com.interview.agent.study.dto.AttemptFinishResponse;
import com.interview.agent.study.dto.AttemptStartResponse;
import com.interview.agent.study.dto.AttemptTurnResponse;
import com.interview.agent.study.dto.AttemptsHistoryResponse;
import com.interview.agent.study.entity.QuestionAttempt;
import com.interview.agent.study.mapper.QuestionAttemptMapper;
import com.interview.agent.study.service.ScoreAggregateService;
import com.interview.agent.study.service.StudyAttemptService;
import com.interview.agent.study.service.StudyQaStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StudyAttemptService} 实现 —— Study attempt 状态机编排。
 *
 * <p>用户从登录上下文注入（{@link CurrentUser}）。
 *
 * <p>事务边界：start / turn / finish 每个端点一个事务；finish 内含写 attempt + 刷 KP mastery。
 * dialog 是 {@code List<Map<String,Object>>}（来自 JsonbTypeHandler），原地累加后 UPDATE 整段 JSONB。
 */
@Service
public class StudyAttemptServiceImpl implements StudyAttemptService {

    private static final int DEFAULT_HISTORY_LIMIT = 10;

    private final QuestionAttemptMapper attemptMapper;
    private final StudyQuestionMapper questionMapper;
    private final StudyQaStrategy strategy;
    private final ScoreAggregateService scoreAggregate;
    private final RubricGenService rubricGenService;

    public StudyAttemptServiceImpl(QuestionAttemptMapper attemptMapper,
                                   StudyQuestionMapper questionMapper,
                                   StudyQaStrategy strategy,
                                   ScoreAggregateService scoreAggregate,
                                   RubricGenService rubricGenService) {
        this.attemptMapper = attemptMapper;
        this.questionMapper = questionMapper;
        this.strategy = strategy;
        this.scoreAggregate = scoreAggregate;
        this.rubricGenService = rubricGenService;
    }

    // ============================================================
    // start
    // ============================================================

    /**
     * 开始作答。
     * <ol>
     *   <li>Step 1: 校验题目存在</li>
     *   <li>Step 2: 同题已有 in_progress → 40901</li>
     *   <li>Step 3: 装初始 dialog（只含主问题）→ INSERT</li>
     * </ol>
     */
    @Override
    @Transactional
    public AttemptStartResponse start(long questionId) {
        // Step 1
        StudyQuestion q = questionMapper.findById(questionId, CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "题目不存在"));

        // Step 1.5: rubric 懒生成 —— 目标题驱动重构后，Step A 产的题无 rubric，首次答题时补齐
        q = ensureRubric(q);

        // Step 2
        long userId = CurrentUser.id();
        attemptMapper.findInProgress(userId, questionId).ifPresent(existing -> {
            throw new BizException(40901, "该题已有进行中的作答（attempt_id=" + existing.id() + "）");
        });

        // Step 3
        List<Object> dialog = new ArrayList<>();
        dialog.add(turnItem("agent", "question", q.content(), null));
        long attemptId = attemptMapper.insertStudyInProgress(userId, questionId, dialog);

        return new AttemptStartResponse(attemptId, questionId, q.content(), dialog, 1, MAX_STEPS);
    }

    /**
     * Rubric 懒生成兜底：题的 rubric 为空时补齐（学考同源，复用 {@link RubricGenService}）。
     * <p>已开讲解的题 Step B 已填 → 直接跳过；未开讲解 / 直挂 KP 的题在此懒补，保证评分有依据。
     * <p>生成失败降级：保持空 rubric（评分退化为自由打分，但不阻断答题）。
     */
    private StudyQuestion ensureRubric(StudyQuestion q) {
        if (rubricGenService.ensureRubric(q)) {
            return questionMapper.findById(q.id(), CurrentUser.id()).orElse(q);
        }
        return q;
    }

    // ============================================================
    // turn
    // ============================================================

    /**
     * 单轮：追加 user 答案 → 算 prior/allowed → 调 per-turn → 5 条状态机硬校正 →
     * 追加 feedback(+ covered/mastery) + 可选 follow_up(+ follow_up_type) → 累加 follow_up_count → UPDATE。
     *
     * <p>{@code current_step} 计算口径：dialog 中 {@code type=question} 与 {@code type=follow_up} 的总数，
     * 即"已抛出的提问轮数"。新增 follow_up 后下一轮 current_step 自增 1。
     *
     * <p>追问决策状态机详见
     * <a href="../../../../../../../docs/modules/S3-study.md">S3-study.md §4.1</a>。
     */
    @Override
    @Transactional
    public AttemptTurnResponse turn(long attemptId, String userAnswer) {
        // Step 1: 加载 + 校验
        QuestionAttempt attempt = loadOrThrow(attemptId);
        requireInProgress(attempt);
        StudyQuestion q = questionMapper.findById(attempt.questionId(), CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "题目不存在"));

        // Step 2: 追加 user answer
        List<Object> dialog = mutableDialog(attempt.dialog());
        dialog.add(turnItem("user", "answer", userAnswer, null));

        // Step 3: 计算 prior / allowed 追问类型
        //   - horizontal 一次封顶（漏点提醒只问一次）
        //   - deep_dive 可重复（mastery=high 时持续深挖；受 MAX_FOLLOW_UPS 总轮数兜底）
        List<String> priorTypes = collectPriorFollowUpTypes(dialog);
        List<String> allowedTypes = new ArrayList<>();
        if (!priorTypes.contains("horizontal")) allowedTypes.add("horizontal");
        allowedTypes.add("deep_dive");

        // Step 4: 调 per-turn LLM；current_step = 本轮提问数（包含主问 + 已出过的追问）
        int currentStep = countQuestions(dialog);
        StudyQaStrategy.PerTurn pt = strategy.perTurn(
                q.content(), q.rubricTemplate(), dialog, currentStep, MAX_STEPS,
                priorTypes, allowedTypes);

        // Step 5: 5 条状态机硬校正 —— 与 Python qa_engine.py 完全对齐
        int followUpCount = attempt.followUpCount();
        String followUpType = pt.followUpType();
        String followUpQuestion = pt.followUpQuestion();
        boolean canFinish = pt.canFinish();

        // 5.1 类型必须在 allowed 列表里
        if (followUpType != null && !allowedTypes.contains(followUpType)) {
            followUpType = null;
            followUpQuestion = null;
        }
        // 5.2 deep_dive 仅在 mastery=high 时允许
        if ("deep_dive".equals(followUpType) && !"high".equals(pt.mastery())) {
            followUpType = null;
            followUpQuestion = null;
        }
        // 5.3 horizontal 仅在 covered=false 时有意义
        if ("horizontal".equals(followUpType) && pt.covered()) {
            followUpType = null;
            followUpQuestion = null;
        }
        // 5.4 type 与 question 必须同生同灭
        if (followUpType == null || followUpQuestion == null) {
            followUpType = null;
            followUpQuestion = null;
        }
        // 5.5 触达上限 → 强制结束
        if (followUpCount >= MAX_FOLLOW_UPS) {
            followUpType = null;
            followUpQuestion = null;
            canFinish = true;
        }
        // canFinish 兜底：若上面把追问清空了，且无更多漏点（covered=true 或 horizontal 已问过且 mastery≠high）→ 标记可结束
        if (followUpQuestion == null) {
            canFinish = canFinish || pt.covered() || !allowedTypes.contains("deep_dive") || !"high".equals(pt.mastery());
        }

        // Step 6: 追加 feedback 项（含 covered / mastery）
        Map<String, Object> feedbackItem = turnItem("agent", "feedback", pt.feedback(), pt.hits());
        feedbackItem.put("covered", pt.covered());
        feedbackItem.put("mastery", pt.mastery());
        dialog.add(feedbackItem);

        // Step 7: 追加 follow_up（含 follow_up_type）
        if (followUpQuestion != null) {
            Map<String, Object> fu = turnItem("agent", "follow_up", followUpQuestion, null);
            fu.put("follow_up_type", followUpType);
            dialog.add(fu);
            followUpCount += 1;
        }

        // Step 8: 落库
        attemptMapper.updateTurn(attemptId, CurrentUser.id(), dialog, followUpCount);

        // Step 9: 返
        int nextStep = countQuestions(dialog);
        return new AttemptTurnResponse(
                attemptId, dialog,
                new AttemptTurnResponse.TurnRubric(pt.feedback(), pt.hits(), pt.covered(), pt.mastery()),
                followUpType, followUpQuestion, canFinish, nextStep, MAX_STEPS);
    }

    /** 扫描 dialog 收集已经出现过的 follow_up_type（按顺序，可重复）。 */
    @SuppressWarnings("unchecked")
    private static List<String> collectPriorFollowUpTypes(List<Object> dialog) {
        List<String> out = new ArrayList<>();
        for (Object o : dialog) {
            if (o instanceof Map<?, ?> m && "follow_up".equals(m.get("type"))) {
                Object t = m.get("follow_up_type");
                if (t != null) out.add(t.toString());
            }
        }
        return out;
    }

    // ============================================================
    // finish
    // ============================================================

    /**
     * 综合评分。幂等：若已 finished，按当前 DB 值直接返。
     *
     * <p>副作用同事务：
     * <ol>
     *   <li>UPDATE attempt 状态 + final_score + rubric_result + overall_summary</li>
     *   <li>{@link ScoreAggregateService#refreshKpMastery} 写 knowledge_node.mastery_level + study_count++</li>
     * </ol>
     */
    @Override
    @Transactional
    public AttemptFinishResponse finish(long attemptId) {
        QuestionAttempt attempt = loadOrThrow(attemptId);
        StudyQuestion q = questionMapper.findById(attempt.questionId(), CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "题目不存在"));
        long kpId = q.knowledgePointId();

        // 幂等
        if ("finished".equals(attempt.status())) {
            return new AttemptFinishResponse(
                    attemptId, attempt.status(),
                    attempt.finalScore() == null ? 0 : attempt.finalScore(),
                    attempt.rubricResult(), attempt.overallSummary(),
                    kpId, scoreAggregate.kpMastery(kpId));
        }

        // Step 1: LLM 综合评分
        List<Object> dialog = mutableDialog(attempt.dialog());
        StudyQaStrategy.FinalScore fs = strategy.finalScore(q.content(), q.rubricTemplate(), dialog);

        // Step 2: 写收尾
        int affected = attemptMapper.finish(attemptId, CurrentUser.id(), fs.finalScore(), fs.rubricResult(), fs.overallSummary());
        if (affected == 0) {
            // 并发 finish 竞争：再读一遍按 finished 路径返
            QuestionAttempt latest = loadOrThrow(attemptId);
            return new AttemptFinishResponse(
                    attemptId, latest.status(),
                    latest.finalScore() == null ? 0 : latest.finalScore(),
                    latest.rubricResult(), latest.overallSummary(),
                    kpId, scoreAggregate.kpMastery(kpId));
        }

        // Step 3: 刷 KP 掌握度
        Integer mastery = scoreAggregate.refreshKpMastery(kpId);
        // Step 3.5: 刷所属子话题掌握度（目标题驱动重构后题目归属子话题；历史题 subtopicId 可空则跳过）
        if (q.subtopicId() != null) {
            scoreAggregate.refreshSubtopicMastery(q.subtopicId());
        }

        return new AttemptFinishResponse(
                attemptId, "finished", fs.finalScore(),
                fs.rubricResult(), fs.overallSummary(),
                kpId, mastery);
    }

    // ============================================================
    // detail / history
    // ============================================================

    @Override
    public AttemptDetailResponse detail(long attemptId) {
        QuestionAttempt a = loadOrThrow(attemptId);
        StudyQuestion q = questionMapper.findById(a.questionId(), CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "题目不存在"));
        return new AttemptDetailResponse(
                a.id(), a.questionId(), q.content(), a.status(),
                mutableDialog(a.dialog()),
                a.finalScore() == null ? null : (int) a.finalScore(),
                a.rubricResult(), a.overallSummary(),
                a.followUpCount(), MAX_STEPS,
                a.finishedAt(), a.createdAt());
    }

    @Override
    public AttemptsHistoryResponse history(long questionId, int limit) {
        int eff = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 50);
        List<QuestionAttempt> rows = attemptMapper.findRecent(CurrentUser.id(), questionId, eff);
        List<AttemptsHistoryResponse.Item> items = new ArrayList<>(rows.size());
        for (QuestionAttempt a : rows) {
            items.add(new AttemptsHistoryResponse.Item(
                    a.id(), a.status(),
                    a.finalScore() == null ? null : (int) a.finalScore(),
                    a.followUpCount(), a.finishedAt(), a.createdAt()));
        }
        return new AttemptsHistoryResponse(questionId, items);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private QuestionAttempt loadOrThrow(long attemptId) {
        return attemptMapper.findById(attemptId, CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "作答记录不存在"));
    }

    private static void requireInProgress(QuestionAttempt a) {
        if (!"in_progress".equals(a.status())) {
            throw new BizException(40001, "作答已结束（status=" + a.status() + "）");
        }
    }

    /** 把 dialog 装成可变 ArrayList；null / 类型异常返空。 */
    @SuppressWarnings("unchecked")
    private static List<Object> mutableDialog(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return new ArrayList<>();
    }

    /** 构造一条 dialog 项；hits 为 null 时不放该字段。 */
    private static Map<String, Object> turnItem(String role, String type, String content, List<Object> hits) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("type", type);
        m.put("content", content == null ? "" : content);
        if (hits != null) {
            m.put("hits", hits);
        }
        return m;
    }

    /** 数 dialog 里 question / follow_up 数 = 已出过的提问轮数。 */
    private static int countQuestions(List<Object> dialog) {
        int n = 0;
        for (Object o : dialog) {
            if (o instanceof Map<?, ?> m) {
                Object type = m.get("type");
                if ("question".equals(type) || "follow_up".equals(type)) n++;
            }
        }
        return n;
    }
}
