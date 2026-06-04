package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 学习探索对话单条 — 与 learn_chat 表一一对应。
 * role: 'user' | 'assistant'。quoted_text 为前端选中的讲解原文片段（可空）。
 */
public record LearnChat(
        Long id,
        Long knowledgePointId,
        Long userId,
        String role,
        String content,
        String quotedText,
        Instant createdAt
) {
}
