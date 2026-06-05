package com.interview.agent.admin.dto;

/**
 * S6 项目从文本解析响应。
 *
 * @param rootId    项目树根节点 id（project_node 表）
 * @param projectId 项目元数据 id（project 表）
 * @param name      项目名（root.name）
 * @param leafCount 三层叶子（具体问题）总数
 */
public record ProjectFromTextResp(long rootId, long projectId, String name, int leafCount) {
}
