package com.interview.agent.learn.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.dto.QuestionItemView;
import com.interview.agent.learn.dto.QuestionsView;
import com.interview.agent.learn.entity.KnowledgeSubtopic;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.mapper.KnowledgeSubtopicMapper;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.learn.service.LearnHelper;
import com.interview.agent.learn.service.LearnQuestionService;
import com.interview.agent.prompts.PromptKeys;
import com.interview.agent.study.mapper.QuestionAttemptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 题目服务实现：取/生成/重生 叶子节点高频面试题。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>FETCH：非叶子返空；叶子无题则调 LLM（prompt key={@value #GEN_PROMPT_KEY}）生成 {@value #DEFAULT_QUESTION_COUNT} 道</li>
 *   <li>REGENERATE：非叶子抛 40001；删未作答题保留已作答，按 {@value #REGENERATE_CAP} 上限每批补 {@value #REGENERATE_BATCH} 道，
 *       剩余题作为"避免重复"提示传给 LLM</li>
 * </ol>
 *
 * <h3>LLM 调用细节</h3>
 * <ul>
 *   <li>讲解上下文超长截断到 {@value #CONTEXT_LIMIT} 字（防 token 爆）</li>
 *   <li>"避免重复"提示最多列 {@value #AVOID_LIST_LIMIT} 条</li>
 *   <li>maxRetry=1（生成失败不重试，吞掉返空）</li>
 *   <li>JSON shape 兼容：rubric 非 list 转空 list；recommended_answer 兼容 list/string/null</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>节点不存在 → 40400；非叶子触发 regenerate → 40001</li>
 *   <li>FETCH 时生成失败会 swallow（log.warn），返回空题目列表 + generated=true</li>
 *   <li>本服务<b>只</b>动 {@code study_question}；讲解只读，对话不动</li>
 * </ul>
 */
@Service
public class LearnQuestionServiceImpl implements LearnQuestionService {

    private static final Logger log = LoggerFactory.getLogger(LearnQuestionServiceImpl.class);
    private static final String LEAF = "knowledge_point";
    private static final int DEFAULT_QUESTION_COUNT = 5;
    private static final int REGENERATE_BATCH = 5;
    private static final int REGENERATE_CAP = 15;

    private static final int GEN_MAX_TOKENS = 4096;
    private static final double GEN_TEMPERATURE = 0.4;
    private static final int CONTEXT_LIMIT = 3000;
    private static final int AVOID_LIST_LIMIT = 10;

    private static final TypeReference<Map<String, Object>> RESP_TYPE = new TypeReference<>() {};

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeSubtopicMapper subtopicMapper;
    private final StudyQuestionMapper questionMapper;
    private final LearnHelper helper;
    private final LlmInvoker llmInvoker;
    private final com.interview.agent.study.service.ScoreAggregateService scoreAggregate;
    private final QuestionAttemptMapper attemptMapper;
    private final com.interview.agent.learn.service.LearnContentService contentService;

    public LearnQuestionServiceImpl(KnowledgeNodeMapper nodeMapper,
                                    KnowledgeSubtopicMapper subtopicMapper,
                                    StudyQuestionMapper questionMapper,
                                    LearnHelper helper,
                                    LlmInvoker llmInvoker,
                                    com.interview.agent.study.service.ScoreAggregateService scoreAggregate,
                                    QuestionAttemptMapper attemptMapper,
                                    com.interview.agent.learn.service.LearnContentService contentService) {
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
        this.questionMapper = questionMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
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
     * 取题目；非叶子返空；叶子无题则生成 {@value #DEFAULT_QUESTION_COUNT} 道。
     * <ol>
     *   <li>Step 1: 校验 + 非叶子早退</li>
     *   <li>Step 2: 命中即返</li>
     *   <li>Step 3: 生成并落库（失败 swallow）</li>
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
     * 强制重生题目：删未作答题，按 {@value #REGENERATE_CAP} 上限每批新增 {@value #REGENERATE_BATCH} 道。
     * <ol>
     *   <li>Step 1: 校验节点是叶子（非叶子抛 40001）</li>
     *   <li>Step 2: 删未作答题</li>
     *   <li>Step 3: 算缺口；&gt;0 则调 LLM 补足，剩余题作为"避免重复"提示传入</li>
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

    /**
     * 装上下文 + 调 LLM + 解析 JSON + 按 max(sort_order)+1 起始顺序落库。
     * <p>caller 决定是否吞异常；本方法不主动 try/catch。空列表直接跳过 insert。
     */
    private void generateAndPersist(KnowledgeNode node, int count, List<String> existingQs) {
        // Step 1: 准备讲解上下文（拼接子话题 body_md，截断） + 避免重复块
        String content = renderSubtopicsAsContext(node.id());
        if (content.length() > CONTEXT_LIMIT) {
            content = content.substring(0, CONTEXT_LIMIT);
        }
        String avoidSection = buildAvoidSection(existingQs);
        String categoryPath = helper.categoryPath(node.id());
        String path = categoryPath.isBlank() ? "（未提供）" : categoryPath;

        // Step 2: 装 vars
        Map<String, Object> vars = new HashMap<>();
        vars.put("knowledge_point", node.name());
        vars.put("category_path", path);
        vars.put("count", count);
        vars.put("content", content);
        vars.put("avoid_section", avoidSection);

        // Step 3: 调 LLM；失败返空
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.LEARN_QUESTION_GEN, vars, GEN_TEMPERATURE, GEN_MAX_TOKENS, 1);
        List<QuestionItem> items = llmInvoker.invoke(spec, LearnQuestionServiceImpl::parseQuestions)
                .orElse(List.of());
        if (items.isEmpty()) {
            return;
        }

        // Step 4: 顺序落库
        int base = questionMapper.maxSortOrder(node.id()) + 1;
        int idx = 0;
        for (QuestionItem q : items) {
            questionMapper.insert(node.id(), q.question(), q.rubric(), q.recommendedAnswer(), "generated", null, base + idx);
            idx++;
        }
    }

    /** raw JSON → QuestionItem 列表；结构异常返空（不抛，避免触发"再重试一次"——本场景 maxRetry=1）。 */
    private static List<QuestionItem> parseQuestions(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, RESP_TYPE);
        Object qs = data.get("questions");
        if (!(qs instanceof List<?> rawList)) {
            return List.of();
        }
        List<QuestionItem> out = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String q = asString(m.get("question"));
            if (q.isBlank()) continue;
            Object rubric = m.get("rubric") instanceof List<?> l ? l : List.of();
            Object rec = normalizeRecommendedAnswer(m.get("recommended_answer"));
            out.add(new QuestionItem(q, rubric, rec));
        }
        return out;
    }

    private static String buildAvoidSection(List<String> existing) {
        if (existing == null || existing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## ⚠ 避免重复以下已存在题目（请从**不同角度**出题）\n");
        int limit = Math.min(existing.size(), AVOID_LIST_LIMIT);
        for (int i = 0; i < limit; i++) {
            sb.append("- ").append(existing.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 把该 KP 的全部子话题拼成"讲解上下文"喂给出题 prompt。
     * 格式：每个子话题以 {@code #### title} 起头 + body_md；空 KP 返空串。
     */
    private String renderSubtopicsAsContext(long kpId) {
        List<KnowledgeSubtopic> rows = subtopicMapper.findByKp(kpId);
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeSubtopic s : rows) {
            sb.append("#### ").append(s.title()).append("\n\n");
            if (s.bodyMd() != null && !s.bodyMd().isBlank()) {
                sb.append(s.bodyMd().strip()).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    private static String asString(Object v) {
        return v == null ? "" : v.toString().strip();
    }

    /** recommended_answer 兼容 list 与 string：list 去空 trim，string trim；其它返 null。 */
    private static Object normalizeRecommendedAnswer(Object v) {
        if (v instanceof List<?> list) {
            List<String> kept = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                String s = item.toString().strip();
                if (!s.isEmpty()) kept.add(s);
            }
            return kept.isEmpty() ? null : kept;
        }
        if (v instanceof String s) {
            String t = s.strip();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

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

    /** LLM 直接产出的题目项，未落库；{@code rubric}/{@code recommendedAnswer} 直接喂 JSONB。 */
    private record QuestionItem(String question, Object rubric, Object recommendedAnswer) {}
}
