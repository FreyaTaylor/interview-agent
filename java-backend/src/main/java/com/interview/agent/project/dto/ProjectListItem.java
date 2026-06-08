package com.interview.agent.project.dto;

/**
 * /projects-list 单项响应。
 *
 * @param realQuestionCount  项目下 L3 叶子题目数（从 project_node 数）
 * @param readinessScore     项目准备度（0-100，未答题返 null）
 */
public record ProjectListItem(
        Long id,
        String name,
        String description,
        String techStack,
        String role,
        String highlights,
        int realQuestionCount,
        Integer readinessScore
) {
}
