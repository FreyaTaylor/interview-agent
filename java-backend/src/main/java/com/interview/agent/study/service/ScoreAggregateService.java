package com.interview.agent.study.service;

/**
 * 题目分 / KP 掌握度聚合服务。
 *
 * <p>题目分 = 最近 {@value Impl#RECENT_N} 次 finished 的 final_score 平均；无记录返 {@code null}。
 * <p>KP 掌握度 = 该 KP 下所有"有 finished 记录"题目的题目分平均；全部为 null 时不写。
 *
 * <p>下游：S3 {@code questions-fetch} 列每题分；{@code attempt-finish} 收尾写回 knowledge_node。
 */
public interface ScoreAggregateService {

    /** 题目分；无 finished 记录返 {@code null}。 */
    Integer questionScore(long questionId);

    /** KP 掌握度；该 KP 下无任何 finished 时返 {@code null}。 */
    Integer kpMastery(long kpId);

    /** finish 钩子：算出 kpMastery 并写回 {@code knowledge_node.mastery_level} + {@code study_count + 1}。
     *  返回最新 mastery（可能为 null，表示该 KP 仍无 finished 记录——但 finish 后理应不为 null）。 */
    Integer refreshKpMastery(long kpId);

    /** 内部常量（IDE 折叠用）。 */
    interface Impl {
        int RECENT_N = 3;
    }
}
