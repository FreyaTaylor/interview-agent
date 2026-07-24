package com.interview.agent.interview.exp.study.dto;

/**
 * 「看看面经」侧栏树节点视图 —— 域(domain)/问题(question) 平铺，前端组装成树。
 *
 * @param selfMastery   自评掌握度（0-100；question 未自评为 0）
 * @param frequency     该问题在多少篇面经出现（域恒 0）
 * @param contentStatus 内容懒生成状态（question：pending|ready；域为 null）
 */
public record ExpStudyTreeNode(
        long id,
        Long parentId,
        String name,
        int level,
        String nodeType,
        int sortOrder,
        int selfMastery,
        int frequency,
        String contentStatus
) {
}
