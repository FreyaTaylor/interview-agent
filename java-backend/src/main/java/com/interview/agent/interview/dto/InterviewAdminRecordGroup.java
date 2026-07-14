package com.interview.agent.interview.dto;

import java.util.List;

/**
 * 管理页「面试真题」三层视图——最外层一场面试（interview_record）。
 * 结构：面试 → 主题(topics) → 问题。
 */
public record InterviewAdminRecordGroup(
        long recordId,
        String company,
        String position,
        String createdAt,
        List<InterviewAdminTopicGroup> topics
) {
}
