package com.interview.agent.learn.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.ChatHistoryItem;
import com.interview.agent.learn.dto.ChatReplyView;
import com.interview.agent.learn.dto.SubtopicView;
import com.interview.agent.learn.entity.KnowledgeSubtopic;
import com.interview.agent.learn.entity.LearnChat;
import com.interview.agent.learn.mapper.KnowledgeSubtopicMapper;
import com.interview.agent.learn.mapper.LearnChatMapper;
import com.interview.agent.learn.service.LearnChatService;
import com.interview.agent.learn.service.LearnHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 探索对话服务实现（S4 重构）。
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>取 KP + 现有子话题概览（id, title, importance）+ 最近 {@value #HISTORY_PROMPT_LIMIT} 轮对话</li>
 *   <li>若 quotedSubtopicId 非空，单独装入 prompt（带 title + body_md），引导 LLM 偏向 append_followup</li>
 *   <li>调 LLM (prompt={@value #CHAT_PROMPT_KEY})；解析 JSON，校验 action 合法</li>
 *   <li>落 learn_chat：user 行（携 quoted_subtopic_id + quoted_text） + assistant 行</li>
 *   <li>按 action 分发：append_followup → SQL append；new_subtopic → INSERT；none → 无副作用</li>
 * </ol>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>append_followup 但用户未引用 / 引用 id 不存在 → 退化为 none（保留回复）</li>
 *   <li>LLM 一次失败即报 50000，不做重试（避免重复 append）</li>
 * </ul>
 */
@Service
public class LearnChatServiceImpl implements LearnChatService {

    private static final Logger log = LoggerFactory.getLogger(LearnChatServiceImpl.class);

    private static final String CHAT_PROMPT_KEY = "learn/chat";
    private static final int HISTORY_PROMPT_LIMIT = 10;
    private static final double CHAT_TEMPERATURE = 0.3;
    private static final int CHAT_MAX_TOKENS = 4096;
    private static final int SUBTOPIC_SUMMARY_LIMIT = 80;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final String ACTION_APPEND = "append_followup";
    private static final String ACTION_NEW = "new_subtopic";
    private static final String ACTION_NONE = "none";

    private static final TypeReference<Map<String, Object>> RESP_TYPE = new TypeReference<>() {};

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeSubtopicMapper subtopicMapper;
    private final LearnChatMapper chatMapper;
    private final LearnHelper helper;
    private final LlmInvoker llmInvoker;

    public LearnChatServiceImpl(KnowledgeNodeMapper nodeMapper,
                                KnowledgeSubtopicMapper subtopicMapper,
                                LearnChatMapper chatMapper,
                                LearnHelper helper,
                                LlmInvoker llmInvoker) {
        this.nodeMapper = nodeMapper;
        this.subtopicMapper = subtopicMapper;
        this.chatMapper = chatMapper;
        this.helper = helper;
        this.llmInvoker = llmInvoker;
    }

    /**
     * 单轮对话总入口。
     */
    @Override
    @Transactional
    public ChatReplyView chat(long kpId, String message, Long quotedSubtopicId, String quotedText) {
        // Step 1: 校验节点
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));

        // Step 2: 取子话题总览 + 引用子话题详情
        List<KnowledgeSubtopic> all = subtopicMapper.findByKp(kpId);
        KnowledgeSubtopic quotedSt = null;
        if (quotedSubtopicId != null) {
            quotedSt = subtopicMapper.findById(quotedSubtopicId)
                    .filter(s -> s.kpId() != null && s.kpId().equals(kpId))
                    .orElse(null);
            if (quotedSt == null) {
                log.warn("[LearnChat] 引用 subtopic 不存在或不属于本 KP: kpId={} stId={}",
                        kpId, quotedSubtopicId);
            }
        }

        // Step 3: 拼历史
        String history = renderHistory(chatMapper.findRecent(kpId, HISTORY_PROMPT_LIMIT));

        // Step 4: 调 LLM
        Map<String, Object> vars = new HashMap<>();
        vars.put("knowledge_point", node.name());
        vars.put("category_path", helper.categoryPath(kpId));
        vars.put("subtopics_overview", renderOverview(all));
        vars.put("quoted_subtopic", renderQuoted(quotedSt));
        vars.put("quoted_text", quotedText == null ? "（无）" : quotedText.strip());
        vars.put("history", history);
        vars.put("user_input", message.strip());

        LlmInvoker.Spec spec = new LlmInvoker.Spec(CHAT_PROMPT_KEY, vars,
                CHAT_TEMPERATURE, CHAT_MAX_TOKENS, 1);
        Map<String, Object> resp = llmInvoker.invoke(spec,
                raw -> JsonUtil.extractJson(raw, RESP_TYPE))
                .orElseThrow(() -> new BizException(50000, "对话失败，请重试"));

        String reply = strOrBlank(resp.get("reply"));
        if (reply.isBlank()) {
            throw new BizException(50000, "LLM 未返回 reply 字段");
        }
        String action = strOrBlank(resp.get("action"));

        // Step 5: 落 learn_chat（无论 action 如何）
        chatMapper.insert(kpId, "user", message.strip(),
                quotedText == null || quotedText.isBlank() ? null : quotedText,
                quotedSt == null ? null : quotedSt.id());
        chatMapper.insert(kpId, "assistant", reply, null, null);

        // Step 6: 按 action 分发
        //   - append_followup：LLM 必须输出 followup_question / followup_answer（已在 prompt 强约束），
        //     直接拿原始 user_input + reply 落库会产生"这个是对的吗 / 完整对话流水"之类不像面试题的脏数据
        return switch (action) {
            case ACTION_APPEND -> doAppend(reply, resp, quotedSt);
            case ACTION_NEW -> doNew(reply, resp, node.id(), all.size());
            default -> ChatReplyView.none(reply);
        };
    }

    @Override
    public List<ChatHistoryItem> getChatHistory(long kpId) {
        List<LearnChat> rows = chatMapper.findByKpId(kpId);
        List<ChatHistoryItem> out = new ArrayList<>(rows.size());
        for (LearnChat c : rows) {
            out.add(new ChatHistoryItem(
                    c.role(),
                    c.content(),
                    c.quotedText(),
                    c.quotedSubtopicId(),
                    c.createdAt() == null ? null : ISO.format(c.createdAt())
            ));
        }
        return out;
    }

    // ============================================================
    // 分支实现
    // ============================================================

    /**
     * action=append_followup 处理。
     * q/a 取自 LLM 响应的 {@code followup_question} / {@code followup_answer}（已在 prompt 强约束面试题口吻 + 精炼答），
     * 而非原始 user_input + reply（避免落库"这个是对的吗"之类的闲聊问 + 完整对话流水）。
     * 引用为空或字段缺失 → 退化为 none。
     */
    private ChatReplyView doAppend(String reply, Map<String, Object> resp, KnowledgeSubtopic quotedSt) {
        if (quotedSt == null) {
            log.warn("[LearnChat] LLM 想 append 但用户未引用任何子话题，退化为 none");
            return ChatReplyView.none(reply);
        }
        String q = strOrBlank(resp.get("followup_question")).strip();
        String a = strOrBlank(resp.get("followup_answer")).strip();
        if (q.isBlank() || a.isBlank()) {
            log.warn("[LearnChat] append 缺 followup_question/answer，退化为 none");
            return ChatReplyView.none(reply);
        }
        subtopicMapper.appendFollowup(quotedSt.id(), q, a);
        return ChatReplyView.appendFollowup(reply, quotedSt.id(), new ChatReplyView.Followup(q, a));
    }

    /** action=new_subtopic 处理；缺字段 → 退化为 none。 */
    private ChatReplyView doNew(String reply, Map<String, Object> resp, long kpId, int currentCount) {
        Object stObj = resp.get("new_subtopic");
        if (!(stObj instanceof Map<?, ?> raw)) {
            log.warn("[LearnChat] new_subtopic 字段缺失或非对象，退化为 none");
            return ChatReplyView.none(reply);
        }
        String title = strOrBlank(raw.get("title"));
        if (title.isBlank()) {
            log.warn("[LearnChat] new_subtopic.title 为空，退化为 none");
            return ChatReplyView.none(reply);
        }
        String body = strOrBlank(raw.get("body_md"));
        int importance = clampImportance(raw.get("importance"));
        int sort = subtopicMapper.maxSortOrder(kpId) + 1;
        long newId = subtopicMapper.insert(kpId, title, body, importance, sort, "chat");
        KnowledgeSubtopic fresh = subtopicMapper.findById(newId)
                .orElseThrow(() -> new BizException(50000, "新子话题落库后查不到"));
        return ChatReplyView.newSubtopic(reply, LearnContentServiceImpl.toView(fresh));
    }

    // ============================================================
    // prompt 上下文渲染 + 工具
    // ============================================================

    /**
     * 子话题总览 — 每行格式 {@code [id=123] (★4) title}，body 不带出避免 prompt 爆长；
     * 空列表显示"（暂无）"。
     */
    private String renderOverview(List<KnowledgeSubtopic> rows) {
        if (rows.isEmpty()) {
            return "（暂无子话题，建议优先返回 new_subtopic）";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeSubtopic s : rows) {
            int imp = s.importance() == null ? 3 : s.importance().intValue();
            sb.append("- [id=").append(s.id()).append("] (★").append(imp).append(") ")
                    .append(s.title()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 用户引用的子话题详情；为空时打"（用户未引用任何子话题）"。 */
    private String renderQuoted(KnowledgeSubtopic s) {
        if (s == null) {
            return "（用户未引用任何子话题）";
        }
        String body = s.bodyMd() == null ? "" : s.bodyMd();
        if (body.length() > 800) {
            body = body.substring(0, 800);
        }
        return "id=" + s.id() + " | title=" + s.title() + "\nbody_md:\n" + body;
    }

    /** 历史对话渲染：空显示"（暂无）"。 */
    private String renderHistory(List<LearnChat> rows) {
        if (rows.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder sb = new StringBuilder();
        for (LearnChat c : rows) {
            sb.append("user".equals(c.role()) ? "用户" : "AI")
                    .append(": ");
            String content = c.content() == null ? "" : c.content();
            if (content.length() > SUBTOPIC_SUMMARY_LIMIT * 4) {
                content = content.substring(0, SUBTOPIC_SUMMARY_LIMIT * 4);
            }
            sb.append(content).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String strOrBlank(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    /** 与 LearnContentServiceImpl.clampImportance 同语义；保持独立避免跨类暴露 helper。 */
    private static int clampImportance(Object raw) {
        int v = 3;
        if (raw instanceof Number n) {
            v = n.intValue();
        } else if (raw != null) {
            try {
                v = Integer.parseInt(raw.toString().strip());
            } catch (NumberFormatException ignored) {
                // 默认 3
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
}
