package com.interview.agent.interview.dto;

import java.util.List;

/** 管理页「面试真题」三层视图——中间层「主题」，含该主题下的问题列表。 */
public record InterviewAdminTopicGroup(
        String topic,
        List<InterviewAdminQuestionItem> questions
) {
}
