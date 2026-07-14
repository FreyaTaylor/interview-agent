package com.interview.agent.learn.service.impl;

import com.interview.agent.common.BizException;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.dto.QuestionItemView;
import com.interview.agent.learn.dto.QuestionsView;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.learn.service.LearnQuestionService;
import com.interview.agent.study.mapper.QuestionAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目服务实现：读取叶子知识点的高频面试题（供答题页）。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>FETCH：非叶子返空；叶子确保 Step A（讲解生成）已产出目标题后，只读「讲解已展开」子话题下的 core 题</li>
 *   <li>REGENERATE：非叶子抛 40001；删未作答题（题目由 Step A 产出，本处不再单独 LLM 出题）</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>节点不存在 → 40400；非叶子触发 regenerate → 40001</li>
 *   <li>本服务<b>只</b>动 {@code study_question}（tree_node question）；讲解只读，对话不动</li>
 * </ul>
 */
@Service
public class LearnQuestionServiceImpl implements LearnQuestionService {

    private static final String LEAF = "knowledge_point";

    private final KnowledgeNodeMapper nodeMapper;
    private final StudyQuestionMapper questionMapper;
    private final com.interview.agent.study.service.ScoreAggregateService scoreAggregate;
    private final QuestionAttemptMapper attemptMapper;
    private final com.interview.agent.learn.service.LearnContentService contentService;

    public LearnQuestionServiceImpl(KnowledgeNodeMapper nodeMapper,
                                    StudyQuestionMapper questionMapper,
                                    com.interview.agent.study.service.ScoreAggregateService scoreAggregate,
                                    QuestionAttemptMapper attemptMapper,
                                    com.interview.agent.learn.service.LearnContentService contentService) {
        this.nodeMapper = nodeMapper;
        this.questionMapper = questionMapper;
        this.scoreAggregate = scoreAggregate;
        this.attemptMapper = attemptMapper;
        this.contentService = contentService;
    }

    /**
     * 题目总入口。
     * <ol>
     *   <li>Step 1: 解析 action</li>
     *   <li>Step 2: FETCH → {@link #fetchQuestions}；REGENERATE → {@link #forceRegenerate}</li>
     * </ol>
     */
    @Override
    public QuestionsView resolveQuestions(LearnAssetRequest req) {
        // Step 1: 解析 action
        LearnAssetRequest.Action action = req.resolvedAction();
        // Step 2: 分发
        return switch (action) {
            case FETCH -> fetchQuestions(req.kpId());
            case REGENERATE -> forceRegenerate(req.kpId());
        };
    }

    /** S3 用：叶子无题则生成；非叶子跳过。 */
    @Override
    public void ensureQuestions(long kpId) {
        fetchQuestions(kpId);
    }

    /**
     * 删除单道题：校验题目存在且属于本 KP，先清其作答记录（多态逻辑外键）再删题。
     * <p>question_attempt 是多态逻辑外键（无数据库 FK），必须应用层手动清理，否则产生孤儿作答行。
     */
    @Override
    @Transactional
    public void deleteQuestion(long kpId, long questionId) {
        // Step 1: 校验题目存在且属于本 KP（防越权）
        questionMapper.findById(questionId)
                .filter(q -> q.knowledgePointId() != null && q.knowledgePointId() == kpId)
                .orElseThrow(() -> new BizException(40400, "题目不存在或不属于该知识点"));
        // Step 2: 先删作答记录，再删题
        attemptMapper.deleteByStudyQuestion(questionId);
        questionMapper.deleteByIdAndKp(questionId, kpId);
    }

    /** 切换单题 tier（core/ext）：校验值域，按 kp 归属更新，0 行受影响 → 越权/不存在。 */
    @Override
    @Transactional
    public void setQuestionTier(long kpId, long questionId, String tier) {
        String t = "ext".equalsIgnoreCase(tier) ? "ext" : "core";
        int n = questionMapper.updateTier(questionId, kpId, t);
        if (n == 0) {
            throw new BizException(40400, "题目不存在或不属于该知识点");
        }
    }

    /**
     * 取题目；非叶子返空；叶子确保 Step A 已产出目标题后只读。
     * <ol>
     *   <li>Step 1: 校验 + 非叶子早退</li>
     *   <li>Step 2: ensureContent（Step A 讲解生成一并产出目标题）后加载 core 题</li>
     * </ol>
     */
    @Transactional
    protected QuestionsView fetchQuestions(long kpId) {
        // Step 1: 校验 + 非叶子早退
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));
        if (!LEAF.equals(node.nodeType())) {
            return new QuestionsView(node.id(), node.name(), node.nodeType(), List.of(), false);
        }

        // Step 2: 确保 Step A 已跑（讲解生成会一并产出目标题 = study_question），再只读
        boolean hadBefore = questionMapper.existsByKpId(kpId);
        contentService.ensureContent(kpId);
        List<QuestionItemView> qs = loadLeafQuestions(node);
        return new QuestionsView(node.id(), node.name(), node.nodeType(), qs, !hadBefore && !qs.isEmpty());
    }

    /**
     * 强制重生题目：删未作答题（题目由 Step A 讲解生成产出，本处不再单独 LLM 出题）。
     * <ol>
     *   <li>Step 1: 校验节点是叶子（非叶子抛 40001）</li>
     *   <li>Step 2: 删未作答题后重新加载</li>
     * </ol>
     */
    @Transactional
    protected QuestionsView forceRegenerate(long kpId) {
        // Step 1: 校验
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));
        if (!LEAF.equals(node.nodeType())) {
            throw new BizException(40001, "非叶子节点不支持生成题目");
        }

        // Step 2: 删未作答
        questionMapper.deleteUnattemptedByKpId(kpId);

        // Step 3: 目标题驱动重构后，题目由 Step A（讲解生成）产出，此处不再单独用 LLM 生成题目。
        //   若需彻底重生题目，走"重新生成讲解"（content regenerate）整体重建。
        return new QuestionsView(node.id(), node.name(), node.nodeType(), loadLeafQuestions(node), true);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 叶子节点查题目转 DTO，只取高频(core)题（答题=高频题），并附题目分（无 finished 记录为 null）。
     *  <p>只出「讲解已展开(ready)子话题」下的题：答题只考展开讲解过的内容。 */
    private List<QuestionItemView> loadLeafQuestions(KnowledgeNode node) {
        List<StudyQuestion> rows = questionMapper.findAnswerableByKpId(node.id());
        List<QuestionItemView> out = new ArrayList<>(rows.size());
        for (StudyQuestion r : rows) {
            if (!"core".equals(r.tier())) {
                continue;   // 答题页只出高频题；扩展题不进答题范围
            }
            Integer score = scoreAggregate.questionScore(r.id());
            out.add(new QuestionItemView(r.id(), r.content(), r.sortOrder(), r.recommendedAnswer(), score));
        }
        return out;
    }
}
