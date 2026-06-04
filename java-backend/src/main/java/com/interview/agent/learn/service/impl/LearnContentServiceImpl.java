package com.interview.agent.learn.service.impl;

import com.interview.agent.common.BizException;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.ContentView;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.entity.KnowledgeContent;
import com.interview.agent.learn.mapper.KnowledgeContentMapper;
import com.interview.agent.learn.mapper.LearnChatMapper;
import com.interview.agent.learn.service.LearnContentService;
import com.interview.agent.learn.service.LearnHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 讲解服务实现：取/生成/重生 知识点 Markdown 讲解。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>FETCH：先查 {@code knowledge_content}；无则调 LLM（prompt key={@value #GEN_PROMPT_KEY}）
 *       生成并落库；生成时校验必选模块 {@link #REQUIRED_TITLES}，缺失触发重试</li>
 *   <li>REGENERATE：删 {@code knowledge_content} + {@code learn_chat}（清掉绑在旧讲解上的对话），重生</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>节点不存在 → 40400；action 未知 → 40001（由 record 抛）；LLM 全部重试失败 → 50000</li>
 *   <li>并发：fetch 做了二次查兜底，避免重复 insert</li>
 *   <li>本服务<b>只</b>动 {@code knowledge_content} + {@code learn_chat}；题目永远不动</li>
 * </ul>
 */
@Service
public class LearnContentServiceImpl implements LearnContentService {

    private static final String GEN_PROMPT_KEY = "learn/content-gen";
    private static final int GEN_MAX_RETRY = 3;
    private static final int GEN_MAX_TOKENS = 4096;
    private static final double GEN_TEMPERATURE = 0.3;
    /** 讲解必须包含的章节标题；缺一即视为本次生成无效，触发下一轮重试。 */
    private static final String[] REQUIRED_TITLES = {"一句话概述", "核心原理"};

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeContentMapper contentMapper;
    private final LearnChatMapper chatMapper;
    private final LearnHelper helper;
    private final LlmInvoker llmInvoker;

    public LearnContentServiceImpl(KnowledgeNodeMapper nodeMapper,
                                   KnowledgeContentMapper contentMapper,
                                   LearnChatMapper chatMapper,
                                   LearnHelper helper,
                                   LlmInvoker llmInvoker) {
        this.nodeMapper = nodeMapper;
        this.contentMapper = contentMapper;
        this.chatMapper = chatMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
    }

    /**
     * 讲解总入口。
     * <ol>
     *   <li>Step 1: 解析 action（未知抛 40001）</li>
     *   <li>Step 2: FETCH → {@link #fetchContent}；REGENERATE → {@link #forceRegenerate}</li>
     * </ol>
     */
    @Override
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
    public void ensureContent(long kpId) {
        fetchContent(kpId);
    }

    /**
     * 取讲解；无则生成讲解并落库。
     * <ol>
     *   <li>Step 1: 校验节点</li>
     *   <li>Step 2: 命中即返（generated=false）</li>
     *   <li>Step 3: 并发兜底二次查 → 生成并落库</li>
     * </ol>
     */
    @Transactional
    protected ContentView fetchContent(long kpId) {
        // Step 1: 校验
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));

        // Step 2: 命中即返
        Optional<KnowledgeContent> existing = contentMapper.findByKpId(kpId);
        if (existing.isPresent()) {
            return buildView(node, existing.get().content(), false);
        }

        // Step 3: 并发兜底 + 生成 + 落库
        Optional<KnowledgeContent> concurrent = contentMapper.findByKpId(kpId);
        if (concurrent.isPresent()) {
            return buildView(node, concurrent.get().content(), false);
        }
        return generateAndPersist(node);
    }

    /**
     * 强制重生讲解：清讲解 + 对话，重新调 LLM。
     * <ol>
     *   <li>Step 1: 校验节点</li>
     *   <li>Step 2: 删 {@code knowledge_content} + {@code learn_chat}</li>
     *   <li>Step 3: 调 LLM 生成并落库</li>
     * </ol>
     */
    @Transactional
    protected ContentView forceRegenerate(long kpId) {
        // Step 1: 校验
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));

        // Step 2: 清讲解 + 对话
        contentMapper.deleteByKpId(kpId);
        chatMapper.deleteByKpId(kpId);

        // Step 3: 重新生成
        return generateAndPersist(node);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 调 LLM 生成讲解 Markdown 并落库；返回 generated=true 的 view。
     * <p>parser 里校验必选模块，缺失抛 IllegalStateException 触发 LlmInvoker 重试；
     * 全部 {@value #GEN_MAX_RETRY} 次失败 → 抛 BizException 50000。
     */
    private ContentView generateAndPersist(KnowledgeNode node) {
        Map<String, Object> vars = Map.of(
                "knowledge_point", node.name(),
                "category_path", helper.categoryPath(node.id())
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(GEN_PROMPT_KEY, vars,
                GEN_TEMPERATURE, GEN_MAX_TOKENS, GEN_MAX_RETRY);
        String content = llmInvoker.invoke(spec, raw -> {
            String c = raw.strip();
            String missing = findMissing(c);
            if (missing != null) {
                throw new IllegalStateException("缺少必选模块: " + missing);
            }
            return c;
        }).orElseThrow(() -> new BizException(50000, "知识讲解生成失败，请重试"));

        contentMapper.insertIfAbsent(node.id(), content);
        return buildView(node, content, true);
    }

    /** 必选章节缺失检测：找到一个就返回它的名字；全部存在返 null。 */
    private String findMissing(String content) {
        for (String t : REQUIRED_TITLES) {
            if (!content.contains(t)) {
                return t;
            }
        }
        return null;
    }

    /** ContentView 构造器；mastery_level / last_studied_at 暂硬编码（S3 由 QaAggregate 注入）。 */
    private ContentView buildView(KnowledgeNode node, String content, boolean generated) {
        return new ContentView(node.id(), node.name(), content, 0, null, generated);
    }
}
