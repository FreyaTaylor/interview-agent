package com.interview.agent.interview.exp.entity;

/**
 * 面经树节点 —— 统一表 {@code tree_node} 中 {@code tree_kind='interview_exp'} 的部分。
 *
 * <p>两层结构：{@code node_type='domain'}（知识域，level=1）/ {@code node_type='question'}（问题，level=2，
 * name = LLM rewrite 后的标准问法，embedding = 其向量，供域内语义去重）。
 */
public record InterviewExpNode(
        long id,
        Long parentId,
        String name,
        short level,
        String nodeType,
        int sortOrder
) {
}
