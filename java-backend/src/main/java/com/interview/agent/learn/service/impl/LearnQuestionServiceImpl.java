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
    private static final String LEAF = "leaf";
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

    public LearnQuestionServiceImpl(KnowledgeNodeMapper nodeMapper,
                                    KnowledgeSubtopicMapper subtopicMapper,
                                    StudyQuestionMapper questionMapper,
                                    LearnHelper helper,
                                    LlmInvoker llmInvoker,
                                    com.interview.agent.study.service.ScoreAggregateService scoreAggregate) {
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
        this.questionMapper = questionMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
        this.scoreAggregate = scoreAggregate;
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

        // Step 2: 命中即返
        List<QuestionItemView> existing = loadLeafQuestions(node);
        if (!existing.isEmpty()) {
            return new QuestionsView(node.id(), node.name(), node.nodeType(), existing, false);
        }

        // Step 3: 生成并落库（失败 swallow，返空列表给前端）
        try {
            generateAndPersist(node, DEFAULT_QUESTION_COUNT, null);
        } catch (Exception e) {
            log.warn("[Learn] 生成 study_question 失败 kpId={}: {}", kpId, e.getMessage());
        }
        return new QuestionsView(node.id(), node.name(), node.nodeType(), loadLeafQuestions(node), true);
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

        // Step 3: 按上限补足
        List<StudyQuestion> remaining = questionMapper.findByKpId(kpId);
        int need = Math.min(REGENERATE_BATCH, Math.max(0, REGENERATE_CAP - remaining.size()));
        if (need > 0) {
            List<String> existingQs = remaining.stream().map(StudyQuestion::content).toList();
            generateAndPersist(node, need, existingQs);
        }
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
            questionMapper.insert(node.id(), q.question(), q.rubric(), q.recommendedAnswer(), base + idx);
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

    /** 叶子节点查全部题目转 DTO，并附题目分（无 finished 记录为 null）。 */
    private List<QuestionItemView> loadLeafQuestions(KnowledgeNode node) {
        List<StudyQuestion> rows = questionMapper.findByKpId(node.id());
        List<QuestionItemView> out = new ArrayList<>(rows.size());
        for (StudyQuestion r : rows) {
            Integer score = scoreAggregate.questionScore(r.id());
            out.add(new QuestionItemView(r.id(), r.content(), r.sortOrder(), r.recommendedAnswer(), score));
        }
        return out;
    }

    /** LLM 直接产出的题目项，未落库；{@code rubric}/{@code recommendedAnswer} 直接喂 JSONB。 */
    private record QuestionItem(String question, Object rubric, Object recommendedAnswer) {}
}
