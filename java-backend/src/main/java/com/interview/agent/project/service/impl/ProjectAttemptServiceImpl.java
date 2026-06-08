package com.interview.agent.project.service.impl;

import com.interview.agent.common.BizException;
import com.interview.agent.project.dto.AttemptDetailResponse;
import com.interview.agent.project.dto.AttemptFinishResponse;
import com.interview.agent.project.dto.AttemptStartResponse;
import com.interview.agent.project.dto.AttemptTurnResponse;
import com.interview.agent.project.dto.AttemptsHistoryResponse;
import com.interview.agent.project.entity.Project;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.entity.ProjectUserProfile;
import com.interview.agent.project.mapper.ProjectAttemptMapper;
import com.interview.agent.project.mapper.ProjectMapper;
import com.interview.agent.project.mapper.ProjectNodeMapper;
import com.interview.agent.project.mapper.ProjectUserProfileMapper;
import com.interview.agent.project.service.ProjectAttemptService;
import com.interview.agent.project.service.ProjectGrillingStrategy;
import com.interview.agent.project.service.ProjectProfileService;
import com.interview.agent.study.entity.QuestionAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ProjectAttemptService} 实现 —— 项目拷打 attempt 状态机编排（v2「面试官自由追问」模式）。
 *
 * <p>USER_ID 固定 1（一期单用户）。事务边界：start / turn / finish 每端点一个事务。
 *
 * <p>实现要点（详见 S7-project-grilling.md §8）：
 * <ol>
 *   <li>调 {@link ProjectGrillingStrategy} 完成 per-turn 决策与 final-score 综合评分</li>
 *   <li>turn 只保留 1 条硬兜底：follow_up_count &gt;= 6 时强制 next_question=null</li>
 *   <li>dialog 项 schema：feedback 项写 note/gaps_found/signals；follow_up 项仅 content</li>
 *   <li>finish 写 dimensions 4 维 + 加权 final_score（rubric_result 留空）</li>
 *   <li>finish 后 fire-and-forget 触发画像抽取（@Async + 乐观锁）</li>
 * </ol>
 */
@Service
public class ProjectAttemptServiceImpl implements ProjectAttemptService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAttemptServiceImpl.class);
    private static final long USER_ID = 1L;
    private static final int DEFAULT_HISTORY_LIMIT = 10;

    private final ProjectAttemptMapper attemptMapper;
    private final ProjectNodeMapper nodeMapper;
    private final ProjectMapper projectMapper;
    private final ProjectUserProfileMapper profileMapper;
    private final ProjectGrillingStrategy strategy;
    private final ProjectProfileService profileService;

    public ProjectAttemptServiceImpl(ProjectAttemptMapper attemptMapper,
                                     ProjectNodeMapper nodeMapper,
                                     ProjectMapper projectMapper,
                                     ProjectUserProfileMapper profileMapper,
                                     ProjectGrillingStrategy strategy,
                                     ProjectProfileService profileService) {
        this.attemptMapper = attemptMapper;
        this.nodeMapper = nodeMapper;
        this.projectMapper = projectMapper;
        this.profileMapper = profileMapper;
        this.strategy = strategy;
        this.profileService = profileService;
    }

    // ============================================================
    // start
    // ============================================================

    /**
     * 开始作答。
     * <ol>
     *   <li>校验 L3 叶子题目存在</li>
     *   <li>同题有 in_progress → 40901</li>
     *   <li>装初始 dialog（仅主问题）→ INSERT</li>
     * </ol>
     */
    @Override
    @Transactional
    public AttemptStartResponse start(long questionId) {
        ProjectNode leaf = loadLeaf(questionId);
        String topicName = loadTopicName(leaf.parentId());

        attemptMapper.findInProgress(USER_ID, questionId).ifPresent(existing -> {
            throw new BizException(40901, "该题已有进行中的作答（attempt_id=" + existing.id() + "）");
        });

        List<Object> dialog = new ArrayList<>();
        dialog.add(turnItem("agent", "question", leaf.name(), null));
        long attemptId = attemptMapper.insertProjectInProgress(USER_ID, questionId, dialog);

        return new AttemptStartResponse(attemptId, questionId, leaf.name(), topicName, dialog, 1, MAX_STEPS);
    }

    // ============================================================
    // turn
    // ============================================================

    /**
     * 单轮（v2 面试官自由追问）：
     * <ol>
     *   <li>追加 user/answer 项到 dialog</li>
     *   <li>调 {@link ProjectGrillingStrategy#perTurn}（带 is_last_round 提示）</li>
     *   <li>唯一硬规则：{@code follow_up_count >= MAX_FOLLOW_UPS} → 强制 nextQuestion=null</li>
     *   <li>追加 feedback 项（note + gaps_found + signals）+ 可选 follow_up 项（无 follow_up_type）</li>
     *   <li>UPDATE attempt（dialog + follow_up_count）</li>
     * </ol>
     */
    @Override
    @Transactional
    public AttemptTurnResponse turn(long attemptId, String userAnswer) {
        QuestionAttempt attempt = loadOrThrow(attemptId);
        requireInProgress(attempt);
        ProjectNode leaf = loadLeaf(attempt.questionId());
        ProjectNode topic = leaf.parentId() == null ? null : nodeMapper.findById(leaf.parentId()).orElse(null);
        String topicName = topic == null ? "" : topic.name();
        Project project = loadProjectFromTopic(topic);
        ProjectUserProfile profile = loadOrCreateProfile(project);

        List<Object> dialog = mutableDialog(attempt.dialog());
        dialog.add(turnItem("user", "answer", userAnswer, null));

        int currentStep = countQuestions(dialog);
        // is_last_round：如果本轮再追问就触上限（followUpCount + 1 == MAX_FOLLOW_UPS）
        boolean isLastRound = attempt.followUpCount() + 1 >= MAX_FOLLOW_UPS;

        ProjectGrillingStrategy.PerTurnV2 pt = strategy.perTurn(
                project, profile, leaf, topicName, dialog,
                currentStep, MAX_STEPS, isLastRound);

        // 唯一硬兜底：触达上限 → 强制不再追问
        int followUpCount = attempt.followUpCount();
        String nextQuestion = pt.nextQuestion();
        String wrapUpReason = pt.wrapUpReason();
        if (followUpCount >= MAX_FOLLOW_UPS) {
            nextQuestion = null;
            if (wrapUpReason == null || wrapUpReason.isBlank()) {
                wrapUpReason = "已触达追问上限，自动收尾";
            }
        }

        // 追加 feedback 项（v2 schema：note / gaps_found / signals）
        Map<String, Object> feedbackItem = new LinkedHashMap<>();
        feedbackItem.put("role", "agent");
        feedbackItem.put("type", "feedback");
        feedbackItem.put("note", pt.interviewerNote() == null ? "" : pt.interviewerNote());
        feedbackItem.put("gaps_found", pt.gapsFound() == null ? new ArrayList<>() : pt.gapsFound());
        feedbackItem.put("signals", pt.signals());
        dialog.add(feedbackItem);

        // 追加 follow_up 项（v2 schema：无 follow_up_type 字段）
        if (nextQuestion != null) {
            dialog.add(turnItem("agent", "follow_up", nextQuestion, null));
            followUpCount += 1;
        }

        attemptMapper.updateTurn(attemptId, dialog, followUpCount);

        int nextStep = countQuestions(dialog);
        boolean canFinish = nextQuestion == null;
        return new AttemptTurnResponse(
                attemptId, dialog,
                pt.interviewerNote(),
                pt.gapsFound() == null ? new ArrayList<>() : pt.gapsFound(),
                nextQuestion, wrapUpReason,
                canFinish, nextStep, MAX_STEPS);
    }

    // ============================================================
    // finish
    // ============================================================

    /**
     * 综合评分（v2 多维度）。幂等：若已 finished，按当前 DB 值返。
     *
     * <p>同事务内：写 attempt（final_score / rubric_result / overall_summary / design_issues / extension_qa）。
     * <ul>
     *   <li>{@code final_score} 由后端按 dimensions 加权计算（权重 0.3/0.3/0.25/0.15）</li>
     *   <li>{@code rubric_result} 列存 {@code {"dimensions": {...}}}（v1 行为是 list；v2 复用此列省去 schema 迁移）</li>
     *   <li>{@code design_issues} 由 LLM 基于整段 dialog 重新提炼（去重 + 归类）</li>
     * </ul>
     *
    * <p>异步副作用：finish 提交后 fire-and-forget 触发画像抽取。
     */
    @Override
    @Transactional
    public AttemptFinishResponse finish(long attemptId) {
        QuestionAttempt attempt = loadOrThrow(attemptId);
        ProjectNode leaf = loadLeaf(attempt.questionId());
        ProjectNode topic = leaf.parentId() == null ? null : nodeMapper.findById(leaf.parentId()).orElse(null);
        String topicName = topic == null ? "" : topic.name();
        Project project = loadProjectFromTopic(topic);

        if ("finished".equals(attempt.status())) {
            DimRubric dr = splitDimensionsAndRubric(attempt.rubricResult());
            return new AttemptFinishResponse(
                    attemptId, attempt.status(),
                    attempt.finalScore() == null ? 0 : attempt.finalScore(),
                    dr.dimensions, dr.legacyRubric,
                    attempt.overallSummary(),
                    attempt.designIssues(), attempt.extensionQa(),
                    mutableDialog(attempt.dialog()));
        }

        List<Object> dialog = mutableDialog(attempt.dialog());
        ProjectGrillingStrategy.FinalScoreV2 fs = strategy.finalScore(project, leaf, topicName, dialog);

        // dimensions 包进 rubric_result 列：{"dimensions": {...}}（复用 JSONB 列省 schema 迁移）
        Map<String, Object> rubricPayload = new LinkedHashMap<>();
        rubricPayload.put("dimensions", fs.dimensions());

        int affected = attemptMapper.finish(attemptId, fs.finalScore(), rubricPayload,
                fs.overallSummary(), fs.designIssues(), fs.extensionQa());
        if (affected == 0) {
            // 并发 finish 竞争：再读一遍
            QuestionAttempt latest = loadOrThrow(attemptId);
            DimRubric dr = splitDimensionsAndRubric(latest.rubricResult());
            return new AttemptFinishResponse(
                    attemptId, latest.status(),
                    latest.finalScore() == null ? 0 : latest.finalScore(),
                    dr.dimensions, dr.legacyRubric,
                    latest.overallSummary(),
                    latest.designIssues(), latest.extensionQa(),
                    mutableDialog(latest.dialog()));
        }

        // S7.3 fire-and-forget 异步抽取画像：注册 afterCommit 钩子，保证 @Async
        // 线程读到的是已提交的 attempt 状态（避免脏读 / 与画像表的 version 冲突）。
        if (project != null) {
            String firstAnswer = extractFirstAnswer(dialog);
            scheduleProfileExtract(project.id(), topicName, leaf.name(), firstAnswer,
                    fs.overallSummary(), List.of());
        }

        return new AttemptFinishResponse(
                attemptId, "finished", fs.finalScore(),
                fs.dimensions(), null,
                fs.overallSummary(),
                fs.designIssues(), fs.extensionQa(), dialog);
    }

    /** 在事务提交后异步触发画像抽取；若已无事务上下文则立即同步触发（@Async 自身仍异步）。 */
    private void scheduleProfileExtract(long projectId, String topic, String question, String answer,
                                        String summary, List<String> missedKeyPoints) {
        Runnable task = () -> {
            try {
                profileService.extractAndApply(projectId, topic, question, answer,
                        summary, missedKeyPoints, USER_ID);
            } catch (Exception e) {
                log.warn("画像异步抽取触发失败 project={}: {}", projectId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { task.run(); }
            });
        } else {
            task.run();
        }
    }

    /** 从 dialog 取第一个 user/answer 文本；无则返空。 */
    private static String extractFirstAnswer(List<Object> dialog) {
        for (Object o : dialog) {
            if (o instanceof Map<?, ?> m && "answer".equals(m.get("type"))) {
                Object c = m.get("content");
                return c == null ? "" : c.toString();
            }
        }
        return "";
    }

    /** finalScore 列存储结构区分：v2 是 {dimensions: {...}}；v1 历史是 list；其他视为 null。 */
    private record DimRubric(Map<String, Integer> dimensions, Object legacyRubric) {}

    @SuppressWarnings("unchecked")
    private static DimRubric splitDimensionsAndRubric(Object rubricColumn) {
        if (rubricColumn instanceof Map<?, ?> m) {
            Object dims = m.get("dimensions");
            if (dims instanceof Map<?, ?> dm) {
                Map<String, Integer> out = new LinkedHashMap<>();
                out.put("fact_clarity", toIntOrZero(dm.get("fact_clarity")));
                out.put("design_quality", toIntOrZero(dm.get("design_quality")));
                out.put("depth", toIntOrZero(dm.get("depth")));
                out.put("communication", toIntOrZero(dm.get("communication")));
                return new DimRubric(out, null);
            }
            return new DimRubric(null, null);
        }
        if (rubricColumn instanceof List<?>) {
            return new DimRubric(null, rubricColumn);
        }
        return new DimRubric(null, null);
    }

    private static int toIntOrZero(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    // ============================================================
    // detail / history
    // ============================================================

    @Override
    public AttemptDetailResponse detail(long attemptId) {
        QuestionAttempt a = loadOrThrow(attemptId);
        ProjectNode leaf = loadLeaf(a.questionId());
        ProjectNode topic = leaf.parentId() == null ? null : nodeMapper.findById(leaf.parentId()).orElse(null);
        DimRubric dr = splitDimensionsAndRubric(a.rubricResult());
        return new AttemptDetailResponse(
                a.id(), a.questionId(), leaf.name(),
                topic == null ? "" : topic.name(),
                a.status(), mutableDialog(a.dialog()),
                a.finalScore() == null ? null : (int) a.finalScore(),
                dr.dimensions, dr.legacyRubric,
                a.overallSummary(),
                a.designIssues(), a.extensionQa(),
                a.followUpCount(), MAX_STEPS,
                a.finishedAt(), a.createdAt());
    }

    @Override
    public AttemptsHistoryResponse history(long questionId, int limit) {
        int eff = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 50);
        List<QuestionAttempt> rows = attemptMapper.findRecent(questionId, eff);
        List<AttemptsHistoryResponse.Item> items = new ArrayList<>(rows.size());
        for (QuestionAttempt a : rows) {
            DimRubric dr = splitDimensionsAndRubric(a.rubricResult());
            items.add(new AttemptsHistoryResponse.Item(
                    a.id(), a.status(),
                    a.finalScore() == null ? null : (int) a.finalScore(),
                    a.followUpCount(),
                    mutableDialog(a.dialog()),
                    dr.dimensions, dr.legacyRubric,
                    a.overallSummary(),
                    a.designIssues(), a.extensionQa(),
                    a.finishedAt(), a.createdAt()));
        }
        return new AttemptsHistoryResponse(questionId, items);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private QuestionAttempt loadOrThrow(long attemptId) {
        return attemptMapper.findById(attemptId)
                .orElseThrow(() -> new BizException(40400, "作答记录不存在"));
    }

    private ProjectNode loadLeaf(long questionId) {
        ProjectNode leaf = nodeMapper.findById(questionId)
                .orElseThrow(() -> new BizException(40400, "题目不存在"));
        if (leaf.level() != 3) {
            throw new BizException(40001, "题目必须是 L3 叶子节点");
        }
        return leaf;
    }

    private String loadTopicName(Long topicId) {
        if (topicId == null) return "";
        return nodeMapper.findById(topicId).map(ProjectNode::name).orElse("");
    }

    /** L2 topic → L1 root → 反查 project。topic 为 null 或孤儿返 null。 */
    private Project loadProjectFromTopic(ProjectNode topic) {
        if (topic == null || topic.parentId() == null) {
            return null;
        }
        // root → project 反查：listByUser 全表扫一遍，挑 root_node_id 命中那条。
        // 项目数量级 < 50，扫表代价可忽略；省去单独写一条 SQL。
        long rootId = topic.parentId();
        for (Project p : projectMapper.listByUser(USER_ID)) {
            if (p.rootNodeId() != null && p.rootNodeId() == rootId) {
                return p;
            }
        }
        return null;
    }

    /** 取或懒创建 profile；project 为 null 时返 null（prompt 渲染会回退"暂无画像"）。 */
    private ProjectUserProfile loadOrCreateProfile(Project project) {
        if (project == null) {
            return null;
        }
        return profileMapper.findByProjectUser(project.id(), USER_ID).orElseGet(() -> {
            profileMapper.ensureRowExists(project.id(), USER_ID);
            return profileMapper.findByProjectUser(project.id(), USER_ID).orElse(null);
        });
    }

    private static void requireInProgress(QuestionAttempt a) {
        if (!"in_progress".equals(a.status())) {
            throw new BizException(40001, "作答已结束（status=" + a.status() + "）");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutableDialog(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return new ArrayList<>();
    }

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
