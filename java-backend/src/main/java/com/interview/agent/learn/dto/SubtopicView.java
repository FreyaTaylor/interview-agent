package com.interview.agent.learn.dto;

import java.util.List;
import java.util.Map;

/** 单条子话题视图（tree_node(subtopic)+subtopic_detail 组合）。 */
public record SubtopicView(
        Long id,
        String title,
        String bodyMd,
        int sortOrder,
        String contentStatus,
        Integer masteryLevel,
        boolean isHot,
        List<TargetQuestion> targetQuestions
) {
    /** 子话题目标面试题（= 考核题）的精简视图。tier: core(高频) | ext(扩展)。
     *  rubric：评分采分点 [{key_point, score}]；recommendedAnswer：分点参考答案。
     *  二者均在讲解生成时"答案先"产出，未生成时为空。 */
    public record TargetQuestion(Long id, String content, String tier,
                                 List<String> recommendedAnswer,
                                 List<Map<String, Object>> rubric) {}
}
