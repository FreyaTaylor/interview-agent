package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 学习探索对话单条 — 与 learn_chat 表一一对应。
 * role: 'user' | 'assistant'。quoted_text / quoted_subtopic_id 由前端在用户引用某子话题时传入（可空）。
 */
public record LearnChat(
        Long id,
        Long knowledgePointId,
        Long userId,
        String role,
        String content,
        String quotedText,
        Long quotedSubtopicId,
        Instant createdAt
) {
}
