package com.interview.agent.learn.dto;

import java.util.List;

/**
 * POST /api/learn/questions 响应。
 * 非叶子节点 {@code questions} 为空列表，{@code generated=false}。
 */
public record QuestionsView(
        Long knowledgePointId,
        String knowledgePointName,
        String nodeType,
        List<QuestionItemView> questions,
        boolean generated
) {
}
