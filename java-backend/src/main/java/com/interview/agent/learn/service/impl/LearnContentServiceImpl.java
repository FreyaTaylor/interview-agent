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
import com.interview.agent.learn.dto.SubtopicView;
import com.interview.agent.learn.entity.KnowledgeSubtopic;
import com.interview.agent.learn.mapper.KnowledgeSubtopicMapper;
import com.interview.agent.learn.mapper.LearnChatMapper;
import com.interview.agent.learn.service.LearnContentService;
import com.interview.agent.learn.service.LearnHelper;
import com.interview.agent.learn.service.LearnSubtopicsProperties;
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
 *   <li>REGENERATE：删 {@code knowledge_subtopic} + {@code learn_chat}（清掉绑在旧讲解上的对话），重生</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>节点不存在 → 40400；action 未知 → 40001；LLM 全部重试失败 → 50000</li>
 *   <li>本服务<b>只</b>动 {@code knowledge_subtopic} + {@code learn_chat}；题目永远不动</li>
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
    private final LearnChatMapper chatMapper;
    private final LearnHelper helper;
    private final LlmInvoker llmInvoker;
    private final LearnSubtopicsProperties subtopicsProps;

    public LearnContentServiceImpl(KnowledgeNodeMapper nodeMapper,
                                   KnowledgeSubtopicMapper subtopicMapper,
                                   LearnChatMapper chatMapper,
                                   LearnHelper helper,
                                   LlmInvoker llmInvoker,
                                   LearnSubtopicsProperties subtopicsProps) {
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
        this.chatMapper = chatMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
        this.subtopicsProps = subtopicsProps;
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
     * 删除单条子话题（需校验属于本 KP）。
     * <p>{@code learn_chat.quoted_subtopic_id} 上的 FK 是 ON DELETE SET NULL，无需手动清。
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
     * 强制重生：清子话题 + 对话，重新调 LLM。
     * <ol>
     *   <li>Step 1: 校验节点</li>
     *   <li>Step 2: 先删 {@code learn_chat}（不然 FK 会把 quoted_subtopic_id SET NULL，无所谓），再删 {@code knowledge_subtopic}</li>
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

        // Step 3: 清对话 + 子话题
        chatMapper.deleteByKpId(kpId);
        subtopicMapper.deleteByKp(kpId);

        // Step 4: 重新生成
        return generateAndPersist(node);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 生成子话题并落库，返回 generated=true 的 view。
     * <ol>
     *   <li>Step 1: {@link #generateSubtopics} 调 LLM 产候选列表（旧逻辑）</li>
     *   <li>Step 2（仅 two-step 策略）: {@link #refineSubtopics} 二次调 LLM 审校去重+补全，
     *       再 {@link #dedupByTitle} 做 title 归一化精确去重兜底</li>
     *   <li>Step 3: 批量落库</li>
     * </ol>
     * 策略由 {@link LearnSubtopicsProperties#twoStep()} 决定，默认 single 保持历史行为。
     */
    private ContentView generateAndPersist(KnowledgeNode node) {
        // Step 1: 生成候选
        List<Map<String, Object>> items = generateSubtopics(node);

        // Step 2: two-step 策略下审校去重 + 补全
        if (subtopicsProps.twoStep()) {
            items = refineSubtopics(node, items);
            items = dedupByTitle(items);
        }

        // Step 3: 批量落库
        int sort = 1;
        for (Map<String, Object> it : items) {
            String title = it.get("title").toString().strip();
            String body = String.valueOf(it.getOrDefault("body_md", "")).strip();
            int importance = clampImportance(it.get("importance"));
            subtopicMapper.insert(node.id(), title, body, importance, sort, "initial");
            sort++;
        }
        List<KnowledgeSubtopic> inserted = subtopicMapper.findByKp(node.id());
        return buildView(node, inserted, true);
    }

    /**
     * Step 1：调 LLM 产 JSON 子话题候选列表。
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

    /**
     * Step 2（two-step）：把第一步候选喂给审校 prompt，合并语义重复 + 补齐遗漏 + 输出最终清单。
     * <p>审校失败（重试耗尽）时降级返回第一步结果，避免整体失败。
     */
    private List<Map<String, Object>> refineSubtopics(KnowledgeNode node, List<Map<String, Object>> firstPass) {
        Map<String, Object> vars = Map.of(
                "knowledge_point", node.name(),
                "category_path", helper.categoryPath(node.id()),
                "subtopics_json", JsonUtil.toJson(firstPass)
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.LEARN_SUBTOPICS_REFINE, vars,
                GEN_TEMPERATURE, GEN_MAX_TOKENS, GEN_MAX_RETRY);
        // 审校失败不阻断：降级用第一步结果
        return llmInvoker.invoke(spec, this::parseSubtopics).orElse(firstPass);
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

    /** importance：缺省 3；非整数尝试转 int；超 [1,5] 截断。 */
    private static int clampImportance(Object raw) {
        int v = 3;
        if (raw instanceof Number n) {
            v = n.intValue();
        } else if (raw != null) {
            try {
                v = Integer.parseInt(raw.toString().strip());
            } catch (NumberFormatException ignored) {
                // 落默认 3
            }
        }
        if (v < 1) {
            return 1;
        }
        if (v > 5) {
            return 5;
        }
        return v;
    }

    private ContentView buildView(KnowledgeNode node, List<KnowledgeSubtopic> rows, boolean generated) {
        List<SubtopicView> views = new ArrayList<>(rows.size());
        for (KnowledgeSubtopic s : rows) {
            views.add(toView(s));
        }
        // mastery_level 由 study/finish 钩子写到 knowledge_node.mastery_level，
        // 从未学过为 null → 视图按 0 渲染（前端"未掌握"色块）。
        // last_studied_at 暂未持久化（V12 未加列），统一返 null。
        int mastery = node.masteryLevel() == null ? 0 : node.masteryLevel().intValue();
        Integer self = node.selfMastery() == null ? null : node.selfMastery().intValue();
        return new ContentView(node.id(), node.name(), views, mastery, self, null, generated);
    }

    static SubtopicView toView(KnowledgeSubtopic s) {
        return new SubtopicView(
                s.id(),
                s.title(),
                s.bodyMd(),
                s.importance() == null ? 3 : s.importance().intValue(),
                followupsAsList(s.followups()),
                s.sortOrder() == null ? 0 : s.sortOrder(),
                s.source()
        );
    }

    /** JSONB 读出是 Object（底层 List 或 String）；这里统一收拢为 List。脱类型失败走空 List 兑底。 */
    @SuppressWarnings("unchecked")
    static java.util.List<java.util.Map<String, Object>> followupsAsList(Object raw) {
        if (raw instanceof java.util.List<?> list) {
            return (java.util.List<java.util.Map<String, Object>>) list;
        }
        return java.util.List.of();
    }
}
