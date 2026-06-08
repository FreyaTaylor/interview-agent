package com.interview.agent.project.service;

import java.util.List;

/**
 * 项目答题画像服务 —— 异步抽取 + 乐观锁更新 project_user_profile。
 *
 * <p>调用契约：{@link #extractAndApply(long, String, String, String, String, List, long)}
 * 必须由**另一个 bean** 调用才走 @Async 代理（同 bean 内调失效）。
 * 接入点见 {@code ProjectAttemptServiceImpl.finish} 末尾的 fire-and-forget。
 *
 * <p>失败语义：LLM 解析失败或 3 次乐观锁冲突均放弃本轮；下一次 finish 会重新抽取，不影响主流程。
 */
public interface ProjectProfileService {

    /** 单次抽取最多重试 3 次（每次冲突会重读最新版本）。 */
    int MAX_RETRY = 3;

    /** project_facts 扁平列表上限（超出保留最新）。 */
    int MAX_FACTS = 50;

    /**
     * 异步抽取并落库；调用方无需 await。
     *
     * @param projectId         项目 id
     * @param topic             本轮 topic 名（L2 话题名）
     * @param question          本轮主问题（leaf.name）
     * @param answer            候选人首轮回答（取 dialog 中第一个 user/answer 内容）
     * @param scoringSummary    final-score 的 overall_summary
     * @param missedKeyPoints   final-score rubric 中 hit=false 的 key_point 列表
     * @param userId            用户 id（一期固定 1）
     */
    void extractAndApply(long projectId, String topic, String question, String answer,
                         String scoringSummary, List<String> missedKeyPoints, long userId);
}
