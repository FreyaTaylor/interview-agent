package com.interview.agent.project.service;

/**
 * 项目侧分数聚合服务（与 study 模块的 {@link com.interview.agent.study.service.ScoreAggregateService} 平行）。
 *
 * <p>三级聚合：
 * <ul>
 *   <li>题目分（{@link #questionScore}）= 该 L3 叶子最近 {@value Impl#RECENT_N} 次 finished 平均</li>
 *   <li>话题分（{@link #topicScore}）= 该 L2 话题下所有 L3 叶子的题目分平均；
 *       <b>仅对已答题取平均，未答题不计入分母</b>（避免拉低话题分）</li>
 *   <li>项目准备度（{@link #projectReadiness}）= 该项目下所有 L2 话题的话题分平均（仅对有话题分的话题取平均）</li>
 * </ul>
 *
 * <p>任一层级"无数据"时返 {@code null}，前端按 null 渲染"暂未练习"。
 */
public interface ProjectScoreAggregateService {

    /** 单题分：最近 N 次 finished 平均；无 finished 记录返 null。 */
    Integer questionScore(long questionId);

    /** 单题已 finished 作答次数。 */
    int questionAttemptCount(long questionId);

    /**
     * 话题分（L2）：仅对已答题取平均，未答题不计入分母。
     * 话题下无 L3 叶子返 null；有叶子但全部未答也返 null（语义：暂未练习）。
     * 同时返回该话题下所有 L3 的 finished 总次数。
     */
    TopicScore topicScore(long topicId);

    /** 项目准备度：仅对有话题分的话题取平均；无 L2 话题或全部未答返 null。 */
    Integer projectReadiness(long projectId);

    record TopicScore(Integer avgScore, int attemptCount) {}

    interface Impl {
        int RECENT_N = 3;
    }
}
