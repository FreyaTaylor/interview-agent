package com.interview.agent.interview.matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.mapper.ProjectNodeMapper;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 面试分组 → 知识树/项目树节点匹配 + 副作用（权重 / 回答向量）。
 *
 * <p>忠实复刻 Python：
 * <ul>
 *   <li>{@code interview_matcher.py::match_nodes}（知识 embedding 匹配 + 占位叶子；项目三级匹配）</li>
 *   <li>{@code embedding_match_skill.py::match_nearest_knowledge_node}（pgvector 召回 + LLM rerank）</li>
 *   <li>{@code project_node_matcher.py}（root/topic LLM 语义匹配；question embedding 匹配）</li>
 *   <li>{@code interview_storage.py::update_knowledge_weights}</li>
 * </ul>
 */
@Component
public class InterviewNodeMatcher {

    private static final Logger log = LoggerFactory.getLogger(InterviewNodeMatcher.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {
    };

    // ===== 常量（与 Python 完全一致）=====
    private static final String UNNAMED_KNOWLEDGE = "未命名知识点";
    private static final String UNNAMED_PROJECT = "未命名项目";
    private static final short DEFAULT_INTERVIEW_WEIGHT = 3;   // knowledge_node.interview_weight DB 默认
    private static final int ORPHAN_SORT_ORDER = 9999;
    // 知识 embedding 召回
    private static final int KNOWLEDGE_TOP_K = 5;
    private static final double KNOWLEDGE_DISTANCE_THRESHOLD = 0.5;   // 进 rerank 的距离上限
    private static final double KNOWLEDGE_FALLBACK_DISTANCE = 0.25;   // LLM 失败时直接采用的距离上限
    // 项目问题 embedding 匹配：cosine 相似度阈值 0.85 ⇒ 距离 ≤ 0.15
    private static final double QUESTION_SIM_THRESHOLD = 0.85;
    private static final double QUESTION_DISTANCE_MAX = 1.0 - QUESTION_SIM_THRESHOLD;

    private final KnowledgeNodeMapper knowledgeMapper;
    private final ProjectNodeMapper projectMapper;
    private final EmbeddingService embeddingService;
    private final LlmInvoker llmInvoker;
    private final com.interview.agent.learn.service.RubricGenService rubricGenService;
    private final com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper linkMapper;

    public InterviewNodeMatcher(KnowledgeNodeMapper knowledgeMapper,
                                ProjectNodeMapper projectMapper,
                                EmbeddingService embeddingService,
                                LlmInvoker llmInvoker,
                                com.interview.agent.learn.service.RubricGenService rubricGenService,
                                com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper linkMapper) {
        this.knowledgeMapper = knowledgeMapper;
        this.projectMapper = projectMapper;
        this.embeddingService = embeddingService;
        this.llmInvoker = llmInvoker;
        this.rubricGenService = rubricGenService;
        this.linkMapper = linkMapper;
    }

    // ============================================================
    // P2：面试真题 ↔ 知识点 关联（召回 top-k + 写关联表快照）
    // ============================================================

    /** 与文本相关的知识点召回结果：id + name + 相似度(1-距离)。 */
    public record KpRecall(long id, String name, float similarity) {
    }

    /** 召回与文本相关的 top-k 知识点（过滤距离阈值，相似度=1-距离）；embedding 失败返空。 */
    public List<KpRecall> recallRelatedKnowledgePoints(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return List.of();
        }
        String vec;
        try {
            vec = embeddingService.embedToLiteral(t);
        } catch (Exception e) {
            log.warn("关联召回 embedding 失败，跳过: {}", e.getMessage());
            return List.of();
        }
        return knowledgeMapper.findNearestLeaves(CurrentUser.id(), vec, KNOWLEDGE_TOP_K).stream()
                .filter(r -> r.distance() <= KNOWLEDGE_DISTANCE_THRESHOLD)
                .map(r -> new KpRecall(r.id(), r.name(), (float) (1.0 - r.distance())))
                .toList();
    }

    /** 给一道面试知识题写「相关知识点」关联快照（source=recall + 相似度）；幂等（upsert）。 */
    public void linkRelatedKnowledge(long interviewKnowledgeQuestionId, String kpText) {
        long userId = CurrentUser.id();
        for (KpRecall r : recallRelatedKnowledgePoints(kpText)) {
            linkMapper.upsert(userId, interviewKnowledgeQuestionId, r.id(), r.name(), "recall", r.similarity());
        }
    }

    // ============================================================
    // match_nodes —— 给每个 group 补 matched_node_id / matched_project_id
    // ============================================================

    /**
     * 复刻 match_nodes：
     * <ul>
     *   <li>knowledge：embedding 召回叶子 → 命中即用；未命中且有 kp → 在「未命名知识点」下建占位叶子</li>
     *   <li>project：3 级匹配（项目根 → 话题 → 问题叶子）</li>
     *   <li>algorithm/hr/other：不匹配节点</li>
     * </ul>
     */
    public List<Map<String, Object>> matchNodes(List<Map<String, Object>> groups) {
        List<Map<String, Object>> enriched = new ArrayList<>(groups.size());
        for (Map<String, Object> src : groups) {
            Map<String, Object> g = new LinkedHashMap<>(src);
            String type = str(g.get("type"));
            if ("knowledge".equals(type)) {
                String kp = str(g.get("knowledge_point")).strip();
                Long nodeId = kp.isEmpty() ? null : matchNearestKnowledgeNode(kp);
                if (nodeId == null && !kp.isEmpty()) {
                    // 未命中 → 在「未命名知识点」下新建占位叶子
                    try {
                        nodeId = createOrphanLeaf(kp);
                        log.info("知识点未匹配，在「未命名知识点」下创建占位叶子: {} → id={}", kp, nodeId);
                    } catch (Exception e) {
                        log.warn("创建未命名知识点叶子失败 kp={}: {}", kp, e.getMessage());
                        nodeId = null;
                    }
                }
                g.put("matched_node_id", nodeId);
                g.put("matched_node_name", null);
                if (nodeId != null) {
                    g.put("matched_node_name",
                            knowledgeMapper.findById(nodeId, CurrentUser.id()).map(KnowledgeNode::name).orElse(null));
                }
            } else if ("project".equals(type)) {
                // 3 级匹配：项目根 → 话题 → 问题叶子
                String projectName = str(g.get("project_name"));
                String topic = str(g.get("topic"));
                if (topic.isBlank()) {
                    topic = "通用";
                }
                List<Object> questions = asList(g.get("questions"));
                String mainQuestion = questions.isEmpty() ? "" : str(questions.get(0));
                try {
                    long rootId = matchOrCreateProjectRoot(projectName);
                    long topicId = matchOrCreateTopic(rootId, topic);
                    long leafId = matchOrCreateQuestion(topicId, mainQuestion);
                    g.put("matched_project_id", leafId);   // 指向叶子（问题节点）
                    g.put("matched_project_name",
                            projectMapper.findById(leafId, CurrentUser.id()).map(ProjectNode::name).orElse(null));
                } catch (Exception e) {
                    log.warn("项目题匹配失败 name={} topic={}: {}", projectName, topic, e.getMessage());
                    g.put("matched_project_id", null);
                    g.put("matched_project_name", null);
                }
            }
            // algorithm/hr/other 不需要匹配节点 —— 走独立聚合表
            enriched.add(g);
        }
        return enriched;
    }

    // ============================================================
    // 知识：embedding 召回 + LLM rerank
    // ============================================================

    /** 复刻 match_nearest_knowledge_node：pgvector top_k → 距离过滤 → LLM rerank。 */
    private Long matchNearestKnowledgeNode(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return null;
        }
        String vec;
        try {
            vec = embeddingService.embedToLiteral(t);
        } catch (Exception e) {
            log.warn("embedding 获取失败，跳过匹配: {}", e.getMessage());
            return null;
        }

        List<NodeMatch> rows = knowledgeMapper.findNearestLeaves(CurrentUser.id(), vec, KNOWLEDGE_TOP_K);
        if (rows.isEmpty()) {
            return null;
        }
        // 过滤距离过大的候选
        List<NodeMatch> candidates = rows.stream()
                .filter(r -> r.distance() <= KNOWLEDGE_DISTANCE_THRESHOLD)
                .toList();
        if (candidates.isEmpty()) {
            log.info("无候选满足距离阈值 {}，原始最佳距离={}", KNOWLEDGE_DISTANCE_THRESHOLD,
                    String.format("%.3f", rows.get(0).distance()));
            return null;
        }

        // LLM rerank（向量近不等于语义对，必须 LLM 兜一道）
        // 候选带「父路径 / 名」，给 LLM"域"上下文，避免跨技术域错配（如 Spring 事务→Redis 事务）
        String candLines = candidates.stream()
                .map(c -> String.format("- id=%d, name=%s (距离=%.3f)", c.id(), pathName(c), c.distance()))
                .collect(Collectors.joining("\n"));
        String textForPrompt = t.length() > 500 ? t.substring(0, 500) : t;
        Set<Long> validIds = candidates.stream().map(NodeMatch::id).collect(Collectors.toSet());

        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_MATCH_KNOWLEDGE_RERANK,
                Map.of("text", textForPrompt, "candidates", candLines),
                0.0, 256, 1);
        // parser 不抛异常：JSON 解析失败 / 都不匹配 → LongHolder(null)；只有 LLM 调用本身异常才返 empty
        Optional<LongHolder> res = llmInvoker.invoke(spec, raw -> {
            Map<String, Object> data = tryExtractJson(raw);
            if (data == null) {
                return new LongHolder(null);
            }
            Object nid = data.get("node_id");
            if (nid == null) {
                return new LongHolder(null);
            }
            Long id = toLong(nid);
            if (id == null) {
                return new LongHolder(null);
            }
            if (!validIds.contains(id)) {
                log.warn("LLM 返回了非候选 id={}，丢弃", id);
                return new LongHolder(null);
            }
            return new LongHolder(id);
        });
        if (res.isEmpty()) {
            // LLM 调用失败 → 降级：最近候选距离 < 0.25 用之，否则放弃
            NodeMatch best = candidates.get(0);
            return best.distance() < KNOWLEDGE_FALLBACK_DISTANCE ? best.id() : null;
        }
        return res.get().value();   // 可能为 null（LLM 判定都不匹配）
    }

    /** 候选展示名：有父路径则拼「父 / 名」（如 {@code Redis / 事务与lua脚本}），否则仅名。 */
    private static String pathName(NodeMatch c) {
        String name = c.name() == null ? "" : c.name();
        String path = c.path();
        return (path == null || path.isBlank()) ? name : path + " / " + name;
    }

    /** 懒创建/复用「未命名知识点」根节点（level=1, category, sort_order=9999）。 */
    private long getOrCreateUnnamedKnowledgeRoot() {
        long userId = CurrentUser.id();
        return knowledgeMapper.findIdByLevelAndName((short) 1, UNNAMED_KNOWLEDGE, userId)
                .orElseGet(() -> knowledgeMapper.insertWithoutEmbedding(
                        userId, null, UNNAMED_KNOWLEDGE, (short) 1, "category",
                        DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER));
    }

    /** 在「未命名知识点」根下新建占位叶子；同名复用。 */
    private long createOrphanLeaf(String name) {
        long userId = CurrentUser.id();
        long rootId = getOrCreateUnnamedKnowledgeRoot();
        Optional<Long> existing = knowledgeMapper.findChildIdByName(rootId, name, userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String emb = null;
        try {
            // embedding 文本用「父路径 / 名」，与建树(TreeGenServiceImpl)一致，保证后续匹配同分布
            emb = embeddingService.embedToLiteral(UNNAMED_KNOWLEDGE + " / " + name);
        } catch (Exception e) {
            log.warn("未命名知识点 embedding 失败 name={}: {}", name, e.getMessage());
        }
        if (emb != null) {
            return knowledgeMapper.insertWithEmbedding(
                    userId, rootId, name, (short) 2, "knowledge_point",
                    DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER, emb);
        }
        return knowledgeMapper.insertWithoutEmbedding(
                userId, rootId, name, (short) 2, "knowledge_point",
                DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER);
    }

    // ============================================================
    // 项目：3 级匹配
    // ============================================================

    /** Step 1: 项目根 —— 精确名匹配 → LLM 语义匹配 → 「未命名项目」。 */
    private long matchOrCreateProjectRoot(String llmProjectName) {
        String name = str(llmProjectName).strip();
        if (name.isEmpty()) {
            return getOrCreateUnnamedProjectRoot();
        }
        // 所有项目根（当前用户, level=1）
        long userId = CurrentUser.id();
        List<ProjectNode> roots = projectMapper.findRoots(userId);

        // 1) 精确匹配
        String norm = normalize(name);
        for (ProjectNode r : roots) {
            if (normalize(r.name()).equals(norm)) {
                return r.id();
            }
        }
        // 2) LLM 语义匹配（候选剔除所有「未命名项目」占位根）
        List<ProjectNode> candidates = roots.stream()
                .filter(r -> !str(r.name()).startsWith(UNNAMED_PROJECT))
                .toList();
        if (!candidates.isEmpty()) {
            String catalog = candidates.stream()
                    .map(r -> String.format("- id=%d, name=%s", r.id(), r.name()))
                    .collect(Collectors.joining("\n"));
            Long pid = llmMatchId(PromptKeys.INTERVIEW_MATCH_PROJECT_ROOT,
                    Map.of("catalog", catalog, "name", name));
            if (pid != null) {
                Long finalPid = pid;
                if (candidates.stream().anyMatch(r -> r.id().equals(finalPid))) {
                    return pid;
                }
            }
        }
        // 3) 都失败 → 未命名项目
        return getOrCreateUnnamedProjectRoot();
    }

    /** 复用全局唯一的「未命名项目」根节点。 */
    private long getOrCreateUnnamedProjectRoot() {
        return projectMapper.findIdByLevelAndName((short) 1, UNNAMED_PROJECT, CurrentUser.id())
                .orElseGet(() -> projectMapper.insertWithoutEmbedding(
                        CurrentUser.id(), null, UNNAMED_PROJECT, (short) 1, "project", ORPHAN_SORT_ORDER));
    }

    /** Step 2: 话题 —— 精确名匹配 → LLM 语义匹配 → 新建 level=2。 */
    private long matchOrCreateTopic(long rootId, String llmTopic) {
        String topic = str(llmTopic).strip();
        if (topic.isEmpty()) {
            topic = "通用";
        }
        List<ProjectNode> siblings = projectMapper.findChildrenByLevel(rootId, (short) 2, CurrentUser.id());

        // 1) 精确匹配
        String norm = normalize(topic);
        for (ProjectNode s : siblings) {
            if (normalize(s.name()).equals(norm)) {
                return s.id();
            }
        }
        // 2) LLM 语义匹配
        if (!siblings.isEmpty()) {
            String catalog = siblings.stream()
                    .map(s -> String.format("- id=%d, name=%s", s.id(), s.name()))
                    .collect(Collectors.joining("\n"));
            Long tid = llmMatchId(PromptKeys.INTERVIEW_MATCH_PROJECT_TOPIC,
                    Map.of("catalog", catalog, "topic", topic));
            if (tid != null) {
                Long finalTid = tid;
                if (siblings.stream().anyMatch(s -> s.id().equals(finalTid))) {
                    return tid;
                }
            }
        }
        // 3) 新建
        return projectMapper.insertWithoutEmbedding(CurrentUser.id(), rootId, topic, (short) 2, "topic", 0);
    }

    /** Step 3: 问题叶子 —— embedding 相似度匹配；命中累积表述，未命中新建 level=3。 */
    private long matchOrCreateQuestion(long topicId, String questionText) {
        String text = str(questionText).strip();
        if (text.isEmpty()) {
            text = "(无题目内容)";   // 兜底：避免空叶子
        }
        String vec;
        try {
            vec = embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            // embedding 失败 → 退化为精确名匹配
            return fallbackExactMatch(topicId, text);
        }

        Optional<NodeMatch> row = projectMapper.findNearestLeafUnderTopic(topicId, vec, CurrentUser.id());
        if (row.isPresent() && row.get().distance() <= QUESTION_DISTANCE_MAX) {
            // 命中：累积表述（用 " \ " 分隔，避免重复添加同一文本）
            NodeMatch hit = row.get();
            String existingName = hit.name() == null ? "" : hit.name();
            if (!existingName.contains(text)) {
                projectMapper.updateName(hit.id(), CurrentUser.id(), existingName + " \\ " + text);
            }
            return hit.id();
        }
        // 未命中：新建 question 叶子（带 embedding）
        return projectMapper.insertWithEmbedding(CurrentUser.id(), topicId, text, (short) 3, "question", 0, vec);
    }

    /** embedding 不可用时的兜底：精确名匹配，否则新建（无 embedding）。 */
    private long fallbackExactMatch(long topicId, String text) {
        List<ProjectNode> siblings = projectMapper.findChildrenByLevel(topicId, (short) 3, CurrentUser.id());
        for (ProjectNode s : siblings) {
            if (str(s.name()).strip().equals(text)) {
                return s.id();
            }
        }
        return projectMapper.insertWithoutEmbedding(CurrentUser.id(), topicId, text, (short) 3, "question", 0);
    }

    // ============================================================
    // 副作用：知识权重 + 用户回答向量
    // ============================================================

    /** 复刻 update_knowledge_weights：命中知识节点 interview_weight +1（上限 5）。
     *  <p>三模块解耦（P4）：不再把真题落到知识树（真题留面试模块，经 interview_question_kp_link 关联）；
     *  仅保留权重 +1 与「错题本(performance)」计算（供复盘页展示）。 */
    public void updateKnowledgeWeights(List<Map<String, Object>> scoredGroups) {
        for (Map<String, Object> g : scoredGroups) {
            if (!"knowledge".equals(str(g.get("type")))) {
                continue;
            }
            Long nodeId = toLong(g.get("matched_node_id"));
            if (nodeId != null) {
                knowledgeMapper.bumpInterviewWeight(nodeId, CurrentUser.id());
            }
            attachPerformance(g, nodeId);
        }
    }

    /**
     * 计算并附上「错题本(performance)」：主问 + 追问链 + 当时回答 → interview/rubric-gen，
     * 取 performance 附到 group（finalize 随 parsed_questions 落库、复盘页展示）。
     * <p>P4：只算错题本，<b>不再</b>把真题落到知识树。best-effort，失败不阻断 finalize。
     */
    private void attachPerformance(Map<String, Object> g, Long nodeId) {
        try {
            List<Object> qs = asList(g.get("questions"));
            if (qs.isEmpty()) {
                return;
            }
            String mainQuestion = questionText(qs.get(0));
            if (mainQuestion.isEmpty()) {
                return;
            }
            List<String> followUps = new java.util.ArrayList<>();
            for (int i = 1; i < qs.size(); i++) {
                String f = questionText(qs.get(i));
                if (!f.isEmpty()) {
                    followUps.add(f);
                }
            }
            String userAnswer = str(g.get("user_answer"));
            var rubric = rubricGenService.generateInterviewRubric(
                    nodeId == null ? 0L : nodeId, mainQuestion, followUps, userAnswer);
            if (rubric.performance() != null) {
                g.put("performance", rubric.performance());
            }
        } catch (Exception e) {
            log.warn("错题本(performance)生成失败，跳过: {}", e.getMessage());
        }
    }

    /** 从 questions 元素取题干（兼容对象 {q/question} 与纯字符串）。 */
    private String questionText(Object o) {
        if (o instanceof Map<?, ?> m) {
            Object qv = m.get("q");
            if (qv == null) {
                qv = m.get("question");
            }
            return str(qv).strip();
        }
        return str(o).strip();
    }

    // ============================================================
    // helpers
    // ============================================================

    /** LLM 语义匹配返回 {"id": ...}；调用失败/解析失败/无 id → null（由 caller 走兜底）。 */
    private Long llmMatchId(String promptKey, Map<String, Object> vars) {
        LlmInvoker.Spec spec = new LlmInvoker.Spec(promptKey, vars, 0.0, 128, 1);
        return llmInvoker.invoke(spec, raw -> {
            Map<String, Object> data = tryExtractJson(raw);
            if (data == null) {
                return new LongHolder(null);
            }
            return new LongHolder(toLong(data.get("id")));
        }).map(LongHolder::value).orElse(null);
    }

    /** 解析 LLM JSON；失败返 null（不抛，避免触发 LlmInvoker 重试/降级误判）。 */
    private static Map<String, Object> tryExtractJson(String raw) {
        try {
            return JsonUtil.extractJson(raw, JSON_OBJ);
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object o) {
        if (o instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    /** name 归一化：小写 + 去空格（复刻 Python .lower().replace(" ", "")）。 */
    private static String normalize(String name) {
        return (name == null ? "" : name).toLowerCase().replace(" ", "");
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.strip());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** 可空 Long 容器：区分「LLM 明确返回 null/无匹配」与「LLM 调用失败」。 */
    private record LongHolder(Long value) {
    }
}
