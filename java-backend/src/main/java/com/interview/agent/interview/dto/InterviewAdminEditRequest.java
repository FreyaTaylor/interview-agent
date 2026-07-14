package com.interview.agent.interview.dto;

/**
 * 管理页「面试真题」编辑请求（PATCH /admin/question）。
 * 二选一：传 {@code text}（改文本，需 idx）或 {@code topic}（改主题）。
 */
public record InterviewAdminEditRequest(
        String refType,
        Long refId,
        Integer idx,
        String text,
        String topic
) {
}
