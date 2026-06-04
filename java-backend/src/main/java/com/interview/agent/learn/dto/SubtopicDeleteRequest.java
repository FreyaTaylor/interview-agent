package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 删除单个子话题请求体（POST /api/learn/subtopic-delete）。
 * <p>带 {@code kpId} 是为了在 SQL WHERE 里再加一道防越权校验。
 */
public record SubtopicDeleteRequest(
        @NotNull Long kpId,
        @NotNull Long subtopicId
) {
}
