package com.interview.agent.learn.service;

import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.dto.QuestionsView;

/**
 * Learn 模块 · 题目（study_question）业务接口。
 *
 * <p>职责：叶子节点的题目取/生成/重生；与讲解、对话完全无关。
 * <p>用户：一期写死 user_id=1（CONVENTIONS §1）。
 */
public interface LearnQuestionService {

    /** 总入口：根据 {@code action} 分发 fetch / regenerate。 */
    QuestionsView resolveQuestions(LearnAssetRequest req);

    /** 幂等保证：若叶子节点无题则生成；非叶子节点直接跳过。 */
    void ensureQuestions(long kpId);

    /** 删除单道题（需校验属于本 KP），连带清理其作答记录。 */
    void deleteQuestion(long kpId, long questionId);

    /** 切换单题 tier（core/ext），需校验属于本 KP。 */
    void setQuestionTier(long kpId, long questionId, String tier);
}
