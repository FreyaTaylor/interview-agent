package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/learn/chat 请求体。
 *
 * <p>{@code quotedSubtopicId} / {@code quotedText} 均为可选：用户在某子话题卡片内说话时由前端传入，
 * prompt 据此引导 LLM 偏向 {@code append_followup}；为空时偏向 {@code new_subtopic} 或 {@code none}。
 */
public record ChatRequest(
        @NotNull(message = "knowledge_point_id 必填") Long knowledgePointId,
        @NotBlank(message = "message 不可为空") String message,
        Long quotedSubtopicId,
        String quotedText
) {
}
