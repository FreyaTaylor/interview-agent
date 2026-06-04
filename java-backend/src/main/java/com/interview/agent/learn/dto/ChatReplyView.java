package com.interview.agent.learn.dto;

/**
 * chat 响应。
 *
 * <p>{@code action} 取值：
 * <ul>
 *   <li>{@code append_followup} — 服务端把 (question, answer) append 到
 *       {@code appendedTo} 对应的 subtopic.followups；前端只需热刷该卡片</li>
 *   <li>{@code new_subtopic} — 服务端新建 subtopic（source='chat'），前端追加到列表末尾</li>
 *   <li>{@code none} — 无副作用，仅会话气泡</li>
 * </ul>
 *
 * <p>无关字段保持 null（jackson 默认序列化），前端按 action 取对应字段。
 */
public record ChatReplyView(
        String reply,
        String action,
        Long appendedTo,
        Followup followup,
        SubtopicView newSubtopic
) {
    public static ChatReplyView none(String reply) {
        return new ChatReplyView(reply, "none", null, null, null);
    }

    public static ChatReplyView appendFollowup(String reply, long subtopicId, Followup f) {
        return new ChatReplyView(reply, "append_followup", subtopicId, f, null);
    }

    public static ChatReplyView newSubtopic(String reply, SubtopicView sv) {
        return new ChatReplyView(reply, "new_subtopic", null, null, sv);
    }

    public record Followup(String q, String a) {}
}
