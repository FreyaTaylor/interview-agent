package com.interview.agent.admin.dto;

/**
 * 项目节点列表视图（S6）—— 平铺给前端 OutlinerEditor，自己组装树。
 *
 * <p>字段名 camelCase，与 OutlinerEditor 期待一致：parentId / nodeType / sortOrder。
 */
public record ProjectNodeView(
        long id,
        Long parentId,
        String name,
        int level,
        String nodeType,
        int sortOrder
) {
}
