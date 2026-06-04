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

    /**
     * 单轮对话：拼最近 N 轮历史 + 子话题总览调 LLM；user/assistant 两行落库；按 action 落子话题副作用。
     *
     * @param kpId              当前知识点
     * @param message           用户输入
     * @param quotedSubtopicId  可选：用户引用的子话题 id；非空时引导 LLM 偏向 append_followup
     * @param quotedText        可选：引用的具体文本片段
     */
    ChatReplyView chat(long kpId, String message, Long quotedSubtopicId, String quotedText);

    /** 取全部历史对话（按时间正序）。 */
    List<ChatHistoryItem> getChatHistory(long kpId);
}
