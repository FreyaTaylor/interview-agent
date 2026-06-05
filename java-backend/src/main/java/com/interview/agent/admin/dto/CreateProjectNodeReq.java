package com.interview.agent.admin.dto;

/**
 * 新增项目节点请求（S6）。
 *
 * @param parentId 父节点 id；null = 新建根（项目级，level=1）
 * @param name 节点名称（不能空白）
 */
public record CreateProjectNodeReq(
        Long parentId,
        String name
) {
}
