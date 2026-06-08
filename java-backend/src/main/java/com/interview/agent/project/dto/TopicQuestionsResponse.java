package com.interview.agent.project.dto;

import java.util.List;

/**
 * /topic-questions 响应：某话题下全部 L3 题目。
 *
 * <p>{@link QuestionItem} 内嵌：
 * <ul>
 *   <li>{@code content}：题面，对应 project_node.name</li>
 *   <li>{@code score}：题目分（最近 N=3 次平均），未答 null</li>
 *   <li>{@code attemptCount}：已 finished 次数</li>
 * </ul>
 */
public record TopicQuestionsResponse(
        Long topicId,
        String topicName,
        List<QuestionItem> questions
) {
    public record QuestionItem(
            Long id,
            String content,
            Integer score,
            int attemptCount
    ) {
    }
}
