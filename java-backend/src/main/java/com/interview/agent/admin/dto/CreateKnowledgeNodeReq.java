package com.interview.agent.admin.dto;

/**
 * 新增知识树节点请求。
 *
 * @param parentId 父节点 id；null = 新建一级根节点
 * @param name 节点名称（不能空白）
 * @param interviewWeight 面试权重，可空时取默认 3
 */
public record CreateKnowledgeNodeReq(
        Long parentId,
        String name,
        Short interviewWeight
) {
}
