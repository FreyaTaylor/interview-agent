package com.interview.agent.interview.dto;

/**
 * 管理页「面试真题」三层视图——最内层「问题」节点（D2：questions 数组已展开到单个元素）。
 *
 * <p>{@code refType + refId + idx} 唯一定位一条可编辑问题：
 * knowledge/project 的 {@code idx} 是 questions 数组下标；other 只有一条，{@code idx=0}。
 * {@code topicEditable} 透传自所属行（已关联知识点的 knowledge 题主题只读）。
 */
public record InterviewAdminQuestionItem(
        String refType,
        long refId,
        int idx,
        String text,
        boolean topicEditable,
        String leetcodeUrl,
        String leetcodeTitle
) {
}
