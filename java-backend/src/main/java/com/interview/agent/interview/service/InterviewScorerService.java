package com.interview.agent.interview.service;

import java.util.List;
import java.util.Map;

/** 面试分组评分服务。 */
public interface InterviewScorerService {

    /**
     * 对所有 group 评分并返回带 score_result 的新 groups。
     */
    ScoreBundle scoreAll(List<Map<String, Object>> groups, String company, String position);

    /**
     * 评分结果聚合。
     */
    record ScoreBundle(List<Map<String, Object>> scoredGroups,
                       int avgScore,
                       String passEstimate,
                       Map<String, Object> overallAnalysis) {
    }
}
