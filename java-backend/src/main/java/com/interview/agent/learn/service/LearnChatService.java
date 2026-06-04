package com.interview.agent.learn.service;

import com.interview.agent.learn.dto.ChatHistoryItem;
import com.interview.agent.learn.dto.ChatReplyView;

import java.util.List;

/**
 * Learn 模块 · 探索对话（learn_chat）业务接口。
 *
 * <p>职责：基于讲解上下文 + 历史对话调 LLM 回复；只读讲解，不动题目。
 * <p>用户：一期写死 user_id=1（CONVENTIONS §1）。
 */
public interface LearnChatService {

    /** 单轮对话：拼最近 N 轮历史 + 讲解全文调 LLM；user/assistant 两行落库；返回 AI 回复。 */
    ChatReplyView chat(long kpId, String message, String quotedText);

    /** 取全部历史对话（按时间正序）。 */
    List<ChatHistoryItem> getChatHistory(long kpId);
}
