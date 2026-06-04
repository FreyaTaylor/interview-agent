package com.interview.agent.learn.service.impl;

import com.interview.agent.common.BizException;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.ChatHistoryItem;
import com.interview.agent.learn.dto.ChatReplyView;
import com.interview.agent.learn.entity.KnowledgeContent;
import com.interview.agent.learn.entity.LearnChat;
import com.interview.agent.learn.mapper.KnowledgeContentMapper;
import com.interview.agent.learn.mapper.LearnChatMapper;
import com.interview.agent.learn.service.LearnChatService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 探索对话服务实现：基于讲解 + 历史对话调 LLM。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>取讲解上下文（无讲解给占位文本，对话仍可进行）</li>
 *   <li>拼最近 {@value #HISTORY_PROMPT_LIMIT} 轮历史（空显式标记"暂无"）</li>
 *   <li>quotedText 前置到用户输入</li>
 *   <li>调 {@link LlmInvoker}（prompt key={@value #CHAT_PROMPT_KEY}）；空抛 50000</li>
 *   <li>user / assistant 两行落 {@code learn_chat}（quotedText 只挂 user 那行）</li>
 * </ol>
 *
 * <h3>关键资源</h3>
 * <ul>
 *   <li>Prompt：DB 模板 key={@value #CHAT_PROMPT_KEY}</li>
 *   <li>LLM：单次调用（无重试），T={@value #CHAT_TEMPERATURE} maxTokens={@value #CHAT_MAX_TOKENS}</li>
 * </ul>
 */
@Service
public class LearnChatServiceImpl implements LearnChatService {

    private static final String CHAT_PROMPT_KEY = "learn/chat";
    private static final int HISTORY_PROMPT_LIMIT = 10;
    private static final double CHAT_TEMPERATURE = 0.3;
    private static final int CHAT_MAX_TOKENS = 4096;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeContentMapper contentMapper;
    private final LearnChatMapper chatMapper;
    private final LlmInvoker llmInvoker;

    public LearnChatServiceImpl(KnowledgeNodeMapper nodeMapper,
                                KnowledgeContentMapper contentMapper,
                                LearnChatMapper chatMapper,
                                LlmInvoker llmInvoker) {
        this.nodeMapper = nodeMapper;
        this.contentMapper = contentMapper;
        this.chatMapper = chatMapper;
        this.llmInvoker = llmInvoker;
    }

    /**
     * 单轮对话。
     * <ol>
     *   <li>Step 1: 校验节点 + 取讲解上下文（缺给占位）</li>
     *   <li>Step 2: 拼最近 N 轮历史</li>
     *   <li>Step 3: 引用块前置</li>
     *   <li>Step 4: 调 {@link LlmInvoker}；空 / 异常 → 抛 50000</li>
     *   <li>Step 5: user + assistant 两行落库</li>
     * </ol>
     */
    @Override
    @Transactional
    public ChatReplyView chat(long kpId, String message, String quotedText) {
        // Step 1: 校验节点 + 取讲解上下文
        KnowledgeNode node = nodeMapper.findById(kpId)
                .orElseThrow(() -> new BizException(40400, "知识点不存在"));
        String content = contentMapper.findByKpId(kpId)
                .map(KnowledgeContent::content)
                .orElse("（尚未生成讲解内容）");

        // Step 2: 拼最近 N 轮对话历史
        List<LearnChat> recent = chatMapper.findRecent(kpId, HISTORY_PROMPT_LIMIT);
        StringBuilder history = new StringBuilder();
        for (LearnChat c : recent) {
            history.append("user".equals(c.role()) ? "用户" : "AI")
                    .append(": ").append(c.content()).append('\n');
        }
        if (history.isEmpty()) {
            history.append("（暂无）");
        }

        // Step 3: 引用块前置
        String userInput = message.strip();
        if (quotedText != null && !quotedText.isBlank()) {
            userInput = "【引用】" + quotedText + "\n\n" + userInput;
        }

        // Step 4: 调 LLM；空 / 异常 → 50000
        Map<String, Object> vars = Map.of(
                "knowledge_point", node.name(),
                "content", content,
                "history", history.toString().strip(),
                "user_input", userInput
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(CHAT_PROMPT_KEY, vars, CHAT_TEMPERATURE, CHAT_MAX_TOKENS, 1);
        String reply = llmInvoker.invoke(spec, String::strip)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new BizException(50000, "对话失败，请重试"));

        // Step 5: 用户原文 + AI 回复落库
        chatMapper.insert(kpId, "user", message.strip(), quotedText);
        chatMapper.insert(kpId, "assistant", reply, null);

        return ChatReplyView.basic(reply);
    }

    /** 取全部历史对话（按时间正序），仅做实体→DTO 转换。 */
    @Override
    public List<ChatHistoryItem> getChatHistory(long kpId) {
        List<LearnChat> rows = chatMapper.findByKpId(kpId);
        List<ChatHistoryItem> out = new ArrayList<>(rows.size());
        for (LearnChat c : rows) {
            out.add(new ChatHistoryItem(
                    c.role(),
                    c.content(),
                    c.quotedText(),
                    c.createdAt() == null ? null : ISO.format(c.createdAt())
            ));
        }
        return out;
    }
}
