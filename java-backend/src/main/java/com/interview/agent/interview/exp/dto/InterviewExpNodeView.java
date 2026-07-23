package com.interview.agent.interview.exp.dto;

/**
 * 面经节点列表视图 —— 编辑用平铺结构（前端 OutlinerEditor 自己组装树）。
 *
 * <p>{@code frequency}：该问题在多少篇「不同来源」面经中出现（域节点恒为 0）。
 * 由 {@code COUNT(question_source_link)} 现算，无冗余列。
 */
public record InterviewExpNodeView(
        long id,
        Long parentId,
        String name,
        int level,
        String nodeType,
        int sortOrder,
        int frequency
) {
}
