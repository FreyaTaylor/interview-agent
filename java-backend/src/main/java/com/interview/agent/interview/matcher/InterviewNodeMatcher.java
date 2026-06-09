package com.interview.agent.interview.matcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.mapper.UserAnswerEmbeddingMapper;
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
 *   <li>{@code interview_storage.py::update_knowledge_weights / store_answer_embeddings}</li>
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
    private final UserAnswerEmbeddingMapper answerEmbeddingMapper;
    private final LlmInvoker llmInvoker;

    public InterviewNodeMatcher(KnowledgeNodeMapper knowledgeMapper,
                                ProjectNodeMapper projectMapper,
                                EmbeddingService embeddingService,
                                UserAnswerEmbeddingMapper answerEmbeddingMapper,
                                LlmInvoker llmInvoker) {
        this.knowledgeMapper = knowledgeMapper;
        this.projectMapper = projectMapper;
        this.embeddingService = embeddingService;
        this.answerEmbeddingMapper = answerEmbeddingMapper;
        this.llmInvoker = llmInvoker;
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
                            knowledgeMapper.findById(nodeId).map(KnowledgeNode::name).orElse(null));
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
                            projectMapper.findById(leafId).map(ProjectNode::name).orElse(null));
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
        String candLines = candidates.stream()
                .map(c -> String.format("- id=%d, name=%s (距离=%.3f)", c.id(), c.name(), c.distance()))
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

    /** 懒创建/复用「未命名知识点」根节点（level=1, category, sort_order=9999）。 */
    private long getOrCreateUnnamedKnowledgeRoot() {
        long userId = CurrentUser.id();
        return knowledgeMapper.findIdByLevelAndName((short) 1, UNNAMED_KNOWLEDGE, userId)
                .orElseGet(() -> knowledgeMapper.insertWithoutEmbedding(
                        userId, null, UNNAMED_KNOWLEDGE, (short) 1, "category",
                        DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER, false));
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
            emb = embeddingService.embedToLiteral(name);
        } catch (Exception e) {
            log.warn("未命名知识点 embedding 失败 name={}: {}", name, e.getMessage());
        }
        if (emb != null) {
            return knowledgeMapper.insertWithEmbedding(
                    userId, rootId, name, (short) 2, "leaf",
                    DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER, false, emb);
        }
        return knowledgeMapper.insertWithoutEmbedding(
                userId, rootId, name, (short) 2, "leaf",
                DEFAULT_INTERVIEW_WEIGHT, ORPHAN_SORT_ORDER, false);
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
        List<ProjectNode> roots = projectMapper.findRoots().stream()
                .filter(r -> r.userId() != null && r.userId() == userId)
                .toList();

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
        return projectMapper.findIdByLevelAndName((short) 1, UNNAMED_PROJECT)
                .orElseGet(() -> projectMapper.insertWithoutEmbedding(
                        CurrentUser.id(), null, UNNAMED_PROJECT, (short) 1, "category", ORPHAN_SORT_ORDER));
    }

    /** Step 2: 话题 —— 精确名匹配 → LLM 语义匹配 → 新建 level=2。 */
    private long matchOrCreateTopic(long rootId, String llmTopic) {
        String topic = str(llmTopic).strip();
        if (topic.isEmpty()) {
            topic = "通用";
        }
        List<ProjectNode> siblings = projectMapper.findChildrenByLevel(rootId, (short) 2);

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
        return projectMapper.insertWithoutEmbedding(CurrentUser.id(), rootId, topic, (short) 2, "category", 0);
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

        Optional<NodeMatch> row = projectMapper.findNearestLeafUnderTopic(topicId, vec);
        if (row.isPresent() && row.get().distance() <= QUESTION_DISTANCE_MAX) {
            // 命中：累积表述（用 " \ " 分隔，避免重复添加同一文本）
            NodeMatch hit = row.get();
            String existingName = hit.name() == null ? "" : hit.name();
            if (!existingName.contains(text)) {
                projectMapper.updateName(hit.id(), existingName + " \\ " + text);
            }
            return hit.id();
        }
        // 未命中：新建 leaf（带 embedding）
        return projectMapper.insertWithEmbedding(CurrentUser.id(), topicId, text, (short) 3, "leaf", 0, vec);
    }

    /** embedding 不可用时的兜底：精确名匹配，否则新建（无 embedding）。 */
    private long fallbackExactMatch(long topicId, String text) {
        List<ProjectNode> siblings = projectMapper.findChildrenByLevel(topicId, (short) 3);
        for (ProjectNode s : siblings) {
            if (str(s.name()).strip().equals(text)) {
                return s.id();
            }
        }
        return projectMapper.insertWithoutEmbedding(CurrentUser.id(), topicId, text, (short) 3, "leaf", 0);
    }

    // ============================================================
    // 副作用：知识权重 + 用户回答向量
    // ============================================================

    /** 复刻 update_knowledge_weights：命中知识节点 interview_weight +1（上限 5）。 */
    public void updateKnowledgeWeights(List<Map<String, Object>> scoredGroups) {
        for (Map<String, Object> g : scoredGroups) {
            if ("knowledge".equals(str(g.get("type")))) {
                Long nodeId = toLong(g.get("matched_node_id"));
                if (nodeId != null) {
                    knowledgeMapper.bumpInterviewWeight(nodeId, CurrentUser.id());
                }
            }
        }
    }

    /** 复刻 store_answer_embeddings：knowledge/project 类用户回答向量化入 user_answer_embedding。 */
    public void storeAnswerEmbeddings(List<Map<String, Object>> scoredGroups) {
        for (Map<String, Object> g : scoredGroups) {
            String userAnswer = str(g.get("user_answer")).strip();
            if (userAnswer.isEmpty()) {
                continue;
            }
            String gType = str(g.get("type"));
            if (!"knowledge".equals(gType) && !"project".equals(gType)) {
                continue;
            }

            String kpName;
            if ("project".equals(gType)) {
                kpName = str(g.get("project_name")) + " · " + str(g.get("topic"));
            } else {
                String kp = str(g.get("knowledge_point"));
                String matched = str(g.get("matched_node_name"));
                kpName = !kp.isEmpty() ? kp : matched;   // 复刻 kp or matched_node_name or ""
            }

            String questionsText = asList(g.get("questions")).stream()
                    .map(this::str)
                    .collect(Collectors.joining(" | "));

            // 得分仅 knowledge 评分结果里有 total_score
            Integer score = null;
            Object srObj = g.get("score_result");
            if (srObj instanceof Map<?, ?> sr && "knowledge".equals(String.valueOf(sr.get("type")))) {
                score = toInt(sr.get("total_score"));
            }

            Long kpId = toLong(g.get("matched_node_id"));
            String text = "问题: " + questionsText + "\n回答: " + userAnswer;
            String vec = null;
            try {
                vec = embeddingService.embedToLiteral(text);
            } catch (Exception e) {
                log.warn("回答向量化失败，降级为不带向量写入: {}", e.getMessage());
            }
            if (vec != null) {
                answerEmbeddingMapper.insertWithEmbedding(
                        kpId, "interview", kpName, questionsText, userAnswer, vec, score);
            } else {
                answerEmbeddingMapper.insertWithoutEmbedding(
                        kpId, "interview", kpName, questionsText, userAnswer, score);
            }
        }
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
