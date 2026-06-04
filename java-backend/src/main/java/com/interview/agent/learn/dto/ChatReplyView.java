package com.interview.agent.learn.dto;

/**
 * chat 响应。一期不做"实时融合到讲解"，updated_subtopic / updated_content 恒为 null，
 * merge_status 恒为 "skipped"（保持字段契约与 Python 端一致，前端无需改）。
 */
public record ChatReplyView(
        String reply,
        String updatedSubtopic,
        String updatedContent,
        String mergeStatus
) {
    public static ChatReplyView basic(String reply) {
        return new ChatReplyView(reply, null, null, "skipped");
    }
}
