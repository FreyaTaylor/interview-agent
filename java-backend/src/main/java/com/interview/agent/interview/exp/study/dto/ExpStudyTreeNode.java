package com.interview.agent.interview.exp.study.dto;

/**
 * 「看看面经」侧栏树节点视图 —— 域(domain)/问题(question) 平铺，前端组装成树。
 *
 * @param viewCount     看过次数（木鱼敲击累计；question 未看过为 0）
 * @param skipped       「不用看」标记（🚫 二值；域级由前端派生，本字段对域恒 false）
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
        int viewCount,
        boolean skipped,
        int frequency,
        String contentStatus
) {
}
