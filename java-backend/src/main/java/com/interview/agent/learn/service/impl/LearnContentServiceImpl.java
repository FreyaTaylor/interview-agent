package com.interview.agent.learn.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.ContentView;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.dto.SubtopicContentRequest;
import com.interview.agent.learn.dto.SubtopicView;
import com.interview.agent.learn.entity.KnowledgeSubtopic;
import com.interview.agent.learn.mapper.KnowledgeSubtopicMapper;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.service.LearnContentService;
import com.interview.agent.learn.service.LearnHelper;
import com.interview.agent.learn.service.RubricGenService;
import com.interview.agent.prompts.PromptKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 讲解服务实现（S4 重构）：取 / 生成 / 重生知识点子话题列表。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>FETCH：先查 {@code knowledge_subtopic}；无则调 LLM（prompt key={@value #GEN_PROMPT_KEY}）
 *       生成 JSON 列表并批量落库；解析时校验数量与字段，缺失触发重试</li>
 *   <li>REGENERATE：删 {@code knowledge_subtopic}，重生</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>节点不存在 → 40400；action 未知 → 40001；LLM 全部重试失败 → 50000</li>
 *   <li>本服务<b>只</b>动 {@code knowledge_subtopic}；题目永远不动</li>
 * </ul>
 */
@Service
public class LearnContentServiceImpl implements LearnContentService {

    private static final int GEN_MAX_RETRY = 3;
    private static final int GEN_MAX_TOKENS = 4096;
    private static final double GEN_TEMPERATURE = 0.3;
    /** 一次生成至少返回的子话题数（少于此视为模型偷懒，触发重试）。 */
    private static final int MIN_SUBTOPICS = 3;

    private static final TypeReference<List<Map<String, Object>>> SUBTOPIC_LIST =
            new TypeReference<>() {};

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeSubtopicMapper subtopicMapper;
    private final LearnHelper helper;
    private final LlmInvoker llmInvoker;
    private final StudyQuestionMapper questionMapper;
    private final RubricGenService rubricGenService;

    public LearnContentServiceImpl(KnowledgeNodeMapper nodeMapper,
                                   KnowledgeSubtopicMapper subtopicMapper,
                                   LearnHelper helper,
                                   LlmInvoker llmInvoker,
                                   StudyQuestionMapper questionMapper,
                                   RubricGenService rubricGenService) {
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
        this.questionMapper = questionMapper;
        this.rubricGenService = rubricGenService;
    }

    /**
     * 讲解总入口。
     * <ol>
     *   <li>Step 1: 解析 action（未知抛 40001）</li>
     *   <li>Step 2: FETCH → {@link #fetchContent}；REGENERATE → {@link #forceRegenerate}</li>
     * </ol>
     * <p><b>事务边界必须在此</b>：内部对 {@code fetchContent}/{@code forceRegenerate} 的调用是
     * self-invocation，会绕过 Spring 代理使其 {@code @Transactional} 失效；故事务注解上移到这个
     * 由 Controller 经代理调用的公开入口，保证 {@code pg_advisory_xact_lock} 持有到提交、真正串行化。
     */
    @Override
    @Transactional
    public ContentView resolveContent(LearnAssetRequest req) {
        // Step 1: 解析 action
        LearnAssetRequest.Action action = req.resolvedAction();
        // Step 2: 分发
        return switch (action) {
            case FETCH -> fetchContent(req.kpId());
            case REGENERATE -> forceRegenerate(req.kpId());
        };
    }

    /** S3 用：缺则生成，存则跳过。 */
    @Override
    @Transactional
    public void ensureContent(long kpId) {
        fetchContent(kpId);
    }

    /**
     * Step B：单子话题深度正文懒生成 / 重生。
     * <ul>
     *   <li>FETCH：{@code ready} 直接返；{@code pending} 则生成正文、置 ready。</li>
     *   <li>REGENERATE：无论状态都重生正文（不动目标题）。</li>
     * </ul>
     * 事务级 advisory 锁（按 subtopicId）防并发重复生成。{@code @Transactional} 必须在公开入口。
     */
    @Override
    @Transactional
    public SubtopicView resolveSubtopicContent(SubtopicContentRequest req) {
        LearnAssetRequest.Action action = req.resolvedAction();
        KnowledgeSubtopic s = subtopicMapper.findById(req.subtopicId())
                .orElseThrow(() -> new BizException(40400, "子话题不存在"));

        // FETCH 且已 ready → 直接返
        if (action == LearnAssetRequest.Action.FETCH && isReady(s)) {
            return toViewWithQuestions(s);
        }

        // 取子话题级锁串行化；拿锁后重查（FETCH 下若已被并发生成则直接返）
        subtopicMapper.acquireSubtopicContentLock(Math.toIntExact(s.id()));
        s = subtopicMapper.findById(req.subtopicId())
                .orElseThrow(() -> new BizException(40400, "子话题不存在"));
        if (action == LearnAssetRequest.Action.FETCH && isReady(s)) {
            return toViewWithQuestions(s);
        }

        // 生成正文 + 置 ready
        String body = generateSubtopicBody(s);
        subtopicMapper.updateBody(s.id(), s.kpId(), body);
        return toViewWithQuestions(subtopicMapper.findById(s.id())
                .orElseThrow(() -> new BizException(50000, "正文写入后回读失败")));
    }

    private static boolean isReady(KnowledgeSubtopic s) {
        return "ready".equals(s.contentStatus()) && s.bodyMd() != null && !s.bodyMd().isBlank();
    }

    /**
     * 调 LLM 产单子话题深度正文（“答案先 → 讲解后”两步）。
     * <ol>
     *   <li>B1 采分点先：为每道目标题补 rubric + 分点范例答案（学考同源，{@link RubricGenService}）；</li>
     *   <li>B2 讲解后：聚合采分点为 {@code answer_points} 约束，生成“必须讲清每个采分点”的讲解。</li>
     * </ol>
     */
    private String generateSubtopicBody(KnowledgeSubtopic s) {
        // B1 答案先：逐题补齐 rubric + 范例答案（已有则跳过，幂等）
        List<StudyQuestion> qs = questionMapper.findBySubtopic(s.id());
        for (StudyQuestion q : qs) {
            rubricGenService.ensureRubric(q);
        }
        // 重查拿到刚落库的 rubric，用于组装采分点约束
        qs = questionMapper.findBySubtopic(s.id());
        String targets = qs.isEmpty()
                ? "（无）"
                : qs.stream().map(q -> "- " + q.content()).collect(java.util.stream.Collectors.joining("\n"));
        // B2 讲解后：以采分点为契约约束讲解
        Map<String, Object> vars = Map.of(
                "title", s.title(),
                "category_path", helper.categoryPath(s.kpId()),
                "target_questions", targets,
                "answer_points", buildAnswerPoints(qs)
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.LEARN_SUBTOPIC_CONTENT, vars,
                GEN_TEMPERATURE, GEN_MAX_TOKENS, GEN_MAX_RETRY);
        return llmInvoker.invoke(spec, LearnContentServiceImpl::parseBody)
                .orElseThrow(() -> new BizException(50000, "子话题正文生成失败，请重试"));
    }

    /** 把各目标题的 rubric 采分点拼成“讲解必须讲清”的契约文本。 */
    private static String buildAnswerPoints(List<StudyQuestion> qs) {
        StringBuilder sb = new StringBuilder();
        for (StudyQuestion q : qs) {
            List<String> kps = keyPoints(q.rubricTemplate());
            if (kps.isEmpty()) {
                continue;
            }
            sb.append("【题目】").append(q.content()).append('\n');
            sb.append("必须讲清的采分点：").append(String.join(" / ", kps)).append("\n\n");
        }
        return sb.length() == 0 ? "（无）" : sb.toString().strip();
    }

    /** 从 rubric_template(jsonb list of {key_point,score}) 提取 key_point 列表。 */
    private static List<String> keyPoints(Object rubric) {
        List<String> out = new ArrayList<>();
        if (rubric instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object kp = m.get("key_point");
                    if (kp != null && !kp.toString().isBlank()) {
                        out.add(kp.toString().strip());
                    }
                }
            }
        }
        return out;
    }

    /** jsonb 范例答案 → List&lt;String&gt;（兼容数组与单字符串；空则空列表）。 */
    private static List<String> toStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e != null && !e.toString().isBlank()) {
                    out.add(e.toString().strip());
                }
            }
            return out;
        }
        if (o instanceof String s && !s.isBlank()) {
            return List.of(s.strip());
        }
        return List.of();
    }

    /** jsonb rubric_template → List&lt;{key_point, score}&gt;（前端渲染采分点表；空则空列表）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toRubricList(Object o) {
        if (o instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>(l.size());
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    /** raw → 正文：去首尾空白与可能的 markdown 围栏；过短视为无效触发重试。 */
    private static String parseBody(String raw) {
        String body = raw == null ? "" : raw.strip();
        if (body.startsWith("```")) {
            body = body.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").strip();
        }
        if (body.length() < 20) {
            throw new IllegalStateException("正文过短：" + body.length());
        }
        return body;
    }

    /**
     * 删除单条子话题（需校验属于本 KP）。
     */
    @Override
    @Transactional
    public void deleteSubtopic(long kpId, long subtopicId) {
        // Step 1: 校验节点存在
        nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));
        // Step 2: 按 (id, kp_id) 删；若 0 行受影响 → 该 id 不存在或不属于本 KP
        int n = subtopicMapper.deleteById(subtopicId, kpId);
        if (n == 0) {
            throw new BizException(40400, "子话题不存在或不属于该知识点");
        }
    }

    /**
     * 设置/清除自评掌握度（与答题派生掌握度 mastery_level 各自独立）。
     * <p>selfMastery 为 null → 清除；非 null → clamp 到 [0,100] 后写库。
     */
    @Override
    @Transactional
    public Integer setSelfMastery(long kpId, Integer selfMastery) {
        // Step 1: 校验节点存在
        nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));
        // Step 2: clamp + 写库（null 表示清除自评）
        Short val = selfMastery == null
                ? null
                : (short) Math.max(0, Math.min(100, selfMastery));
        nodeMapper.updateSelfMastery(kpId, CurrentUser.id(), val);
        return val == null ? null : val.intValue();
    }

    /**
     * 取子话题；无则生成并落库。
     * <ol>
     *   <li>Step 1: 校验节点</li>
     *   <li>Step 2: 命中即返（generated=false）</li>
     *   <li>Step 3: 并发兜底 + LLM 生成 + 批量落库</li>
     * </ol>
     */
    @Transactional
    protected ContentView fetchContent(long kpId) {
        // Step 1: 校验
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));

        // Step 2: 命中即返
        List<KnowledgeSubtopic> existing = subtopicMapper.findByKp(kpId);
        if (!existing.isEmpty()) {
            return buildView(node, existing, false);
        }

        // Step 3: 取 KP 级 advisory 锁串行化生成，防并发重复生成（前端可能双触发 FETCH）。
        // 拿到锁后再查一次：若前一个生成事务已提交，这里即可看到数据、直接返回，实现幂等。
        subtopicMapper.acquireGenLock(kpId);
        List<KnowledgeSubtopic> afterLock = subtopicMapper.findByKp(kpId);
        if (!afterLock.isEmpty()) {
            return buildView(node, afterLock, false);
        }
        return generateAndPersist(node);
    }

    /**
     * 强制重生：清子话题，重新调 LLM。
     * <ol>
     *   <li>Step 1: 校验节点</li>
     *   <li>Step 2: 删 {@code knowledge_subtopic}</li>
     *   <li>Step 3: 调 LLM 生成并落库</li>
     * </ol>
     */
    @Transactional
    protected ContentView forceRegenerate(long kpId) {
        // Step 1: 校验
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));

        // Step 2: 取 KP 级 advisory 锁，与并发的 fetch/regenerate 串行化，防重复生成
        subtopicMapper.acquireGenLock(kpId);

        // Step 3: 清子话题
        subtopicMapper.deleteByKp(kpId);

        // Step 4: 重新生成
        return generateAndPersist(node);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * Step A：生成【子话题 + 目标题】清单并落库，返回 generated=true 的 view。
     * <ol>
     *   <li>调 LLM 产 {@code [{title, importance, target_questions[]}]}（{@link #parseSubtopics} 校验）</li>
     *   <li>title 归一化去重</li>
     *   <li>逐条落 pending 子话题（body 空）+ 其目标题（{@code study_question}，带 subtopic_id、空 rubric）</li>
     * </ol>
     * 正文由 Step B 点击懒生成；rubric 由 study 首次答题懒生成。
     */
    private ContentView generateAndPersist(KnowledgeNode node) {
        List<Map<String, Object>> items = dedupByTitle(generateSubtopics(node));
        int sort = 1;
        for (Map<String, Object> it : items) {
            String title = it.get("title").toString().strip();
            List<TargetQ> qs = dedupQuestions(targetQuestions(it));
            // 初始化自动过滤：只含扩展(ext)题、无高频(core)题的子话题不落库（非高频，不值得单列）
            if (qs.stream().noneMatch(q -> "core".equals(q.tier()))) {
                continue;
            }
            long subtopicId = subtopicMapper.insertPending(node.id(), title, sort);
            int qsort = 1;
            for (TargetQ q : qs) {
                questionMapper.insertForSubtopic(subtopicId, q.content(), q.tier(), qsort);
                qsort++;
            }
            sort++;
        }
        return buildView(node, subtopicMapper.findByKp(node.id()), true);
    }

    /**
     * 调 LLM 产 JSON 子话题清单（含 target_questions）。
     * <p>parser 校验：必须是 list、长度 ≥ {@value #MIN_SUBTOPICS}、每项有 title 且非空。
     */
    private List<Map<String, Object>> generateSubtopics(KnowledgeNode node) {
        Map<String, Object> vars = Map.of(
                "knowledge_point", node.name(),
                "category_path", helper.categoryPath(node.id())
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.LEARN_SUBTOPICS_GEN, vars,
                GEN_TEMPERATURE, GEN_MAX_TOKENS, GEN_MAX_RETRY);
        return llmInvoker.invoke(spec, this::parseSubtopics)
                .orElseThrow(() -> new BizException(50000, "知识子话题生成失败，请重试"));
    }

    /** LLM raw → 子话题 list 的解析器：校验非空 list、长度 ≥ {@value #MIN_SUBTOPICS}、每项 title 非空。 */
    private List<Map<String, Object>> parseSubtopics(String raw) {
        List<Map<String, Object>> parsed = JsonUtil.extractJson(raw, SUBTOPIC_LIST);
        if (parsed == null || parsed.size() < MIN_SUBTOPICS) {
            throw new IllegalStateException("子话题数量不足: "
                    + (parsed == null ? 0 : parsed.size()));
        }
        for (Map<String, Object> it : parsed) {
            Object t = it.get("title");
            if (t == null || t.toString().isBlank()) {
                throw new IllegalStateException("存在 title 为空的子话题");
            }
        }
        return parsed;
    }

    /** 一道目标题的生成结果：题干 + tier（core/ext）。 */
    private record TargetQ(String content, String tier) {}

    /** 取一个子话题的 target_questions；兼容对象 {q,tier} 与旧字符串（字符串默认 core）。 */
    private static List<TargetQ> targetQuestions(Map<String, Object> it) {
        if (!(it.get("target_questions") instanceof List<?> l)) {
            return List.of();
        }
        List<TargetQ> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) {
                Object q = m.get("q");
                if (q == null || q.toString().isBlank()) {
                    continue;
                }
                String tier = "ext".equalsIgnoreCase(String.valueOf(m.get("tier"))) ? "ext" : "core";
                out.add(new TargetQ(q.toString().strip(), tier));
            } else if (o != null && !o.toString().isBlank()) {
                out.add(new TargetQ(o.toString().strip(), "core"));
            }
        }
        return out;
    }

    /** 目标题归一化精确去重（去空格/标点/大小写后相同的合并），保序；保留首个的 tier。 */
    private static List<TargetQ> dedupQuestions(List<TargetQ> qs) {
        Map<String, TargetQ> seen = new LinkedHashMap<>();
        for (TargetQ q : qs) {
            String key = q.content().toLowerCase().replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]", "");
            seen.putIfAbsent(key, q);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 代码层精确去重兜底：title 归一化（去空格 / 标点 / 大小写）后完全相同的只保留第一条。
     * <p>防"换皮完全同名"漏网；语义近似的合并交给 refine prompt。保序（LinkedHashMap）。
     */
    private static List<Map<String, Object>> dedupByTitle(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            Object t = it.get("title");
            if (t == null) {
                continue;
            }
            String key = t.toString()
                    .toLowerCase()
                    .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]", "");
            seen.putIfAbsent(key, it);
        }
        return new ArrayList<>(seen.values());
    }

    /** importance 已废弃（子话题不再有星级）。 */

    private ContentView buildView(KnowledgeNode node, List<KnowledgeSubtopic> rows, boolean generated) {
        List<SubtopicView> views = new ArrayList<>(rows.size());
        for (KnowledgeSubtopic s : rows) {
            views.add(toViewWithQuestions(s));
        }
        // mastery_level 由 study/finish 钩子写到 knowledge_node.mastery_level，
        // 从未学过为 null → 视图按 0 渲染（前端"未掌握"色块）。
        // last_studied_at 暂未持久化（V12 未加列），统一返 null。
        int mastery = node.masteryLevel() == null ? 0 : node.masteryLevel().intValue();
        Integer self = node.selfMastery() == null ? null : node.selfMastery().intValue();
        return new ContentView(node.id(), node.name(), views, mastery, self, null, generated);
    }

    /** 带目标题的子话题视图：额外查该子话题的 study_question 作为 target_questions。 */
    private SubtopicView toViewWithQuestions(KnowledgeSubtopic s) {
        List<StudyQuestion> qs = questionMapper.findBySubtopic(s.id());
        List<SubtopicView.TargetQuestion> targets = new ArrayList<>(qs.size());
        boolean isHot = false;
        for (StudyQuestion q : qs) {
            String tier = q.tier() == null ? "core" : q.tier();
            if ("core".equals(tier)) {
                isHot = true;
            }
            targets.add(new SubtopicView.TargetQuestion(q.id(), q.content(), tier,
                    toStringList(q.recommendedAnswer()), toRubricList(q.rubricTemplate())));
        }
        return new SubtopicView(
                s.id(),
                s.title(),
                s.bodyMd(),
                s.sortOrder() == null ? 0 : s.sortOrder(),
                s.contentStatus() == null ? "ready" : s.contentStatus(),
                s.masteryLevel() == null ? null : s.masteryLevel().intValue(),
                isHot,
                targets
        );
    }

}
