package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatRequest(
        @NotNull(message = "knowledge_point_id 必填") Long knowledgePointId,
        @NotBlank(message = "message 不可为空") String message,
        String quotedText
) {
}
