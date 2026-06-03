package com.interview.agent.admin.dto;

/**
 * 节点列表视图 —— 编辑用平铺结构（前端自己组装树）。
 * 与 Python 端 get_all_nodes 返回字段一致。
 */
public record KnowledgeNodeView(
        long id,
        Long parentId,
        String name,
        int level,
        String nodeType,
        int interviewWeight,
        int sortOrder
) {
}
