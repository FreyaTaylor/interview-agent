package com.interview.agent.learn.dto;

public record ChatHistoryItem(
        String role,
        String content,
        String quotedText,
        String createdAt
) {
}
