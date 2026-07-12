package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 知识子话题 — tree_node(node_type='subtopic') + subtopic_detail 侧表的组合视图。
 *
 * <p>骨架字段（id/kpId=parent_id/title=name/sortOrder/userId/createdAt）来自 tree_node；
 * 内容字段（bodyMd/contentStatus/masteryLevel）来自 subtopic_detail。
 *
 * <p>{@code contentStatus}：{@code "pending"} = 仅有标题+目标题、正文待点击生成；{@code "ready"} = 正文已就绪。
 * {@code masteryLevel}：子话题级掌握度（该子话题所有问题最近 N 次 finished 均分再平均），未答为 null。
 */
public record KnowledgeSubtopic(
        Long id,
        Long kpId,
        String title,
        String bodyMd,
        Integer sortOrder,
        String contentStatus,
        Short masteryLevel,
        Long userId,
        Instant createdAt
) {
}
