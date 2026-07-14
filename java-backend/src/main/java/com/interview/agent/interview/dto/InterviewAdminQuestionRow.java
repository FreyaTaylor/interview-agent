package com.interview.agent.interview.dto;

import java.time.Instant;

/**
 * 管理页「面试真题」三层视图的**扁平投影行**——三张问题表（knowledge/project/other）各查一次后统一成此行，
 * 由 service 端展开 questions 数组 + 按 record→topic 归并。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code refType}：'knowledge' | 'project' | 'other'（SQL 里写死常量）</li>
 *   <li>{@code refId}：对应表的行 id</li>
 *   <li>{@code topic}：主题——knowledge 活取知识点名（无则 tag），project=project_name，other=tag；空回退「未分类」</li>
 *   <li>{@code questionsJson}：knowledge/project 的 questions jsonb::text（String[]）；other 为 null</li>
 *   <li>{@code content}：other 的单串题干；knowledge/project 为 null</li>
 *   <li>{@code topicEditable}：主题是否可编辑（已关联知识点的 knowledge 题为 false，只读）</li>
 * </ul>
 */
public record InterviewAdminQuestionRow(
        String refType,
        long refId,
        long recordId,
        String company,
        String position,
        Instant createdAt,
        String topic,
        String questionsJson,
        String content,
        boolean topicEditable
) {
}
