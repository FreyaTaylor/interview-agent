package com.interview.agent.study.service;

import com.interview.agent.study.dto.AttemptDetailResponse;
import com.interview.agent.study.dto.AttemptFinishResponse;
import com.interview.agent.study.dto.AttemptStartResponse;
import com.interview.agent.study.dto.AttemptTurnResponse;
import com.interview.agent.study.dto.AttemptsHistoryResponse;

/**
 * Study attempt 状态机编排：start → turn* → finish；以及 detail / history 查询。
 *
 * <p>状态机约束：
 * <ul>
 *   <li>同用户 + 同题最多 1 条 in_progress；start 时若已有则抛 40901</li>
 *   <li>turn / finish 要求 status=in_progress；finish 后 attempt 不可再 turn</li>
 *   <li>follow_up_count &lt; {@value #MAX_FOLLOW_UPS} 才允许追问；否则强制 follow_up_question=null</li>
 * </ul>
 */
public interface StudyAttemptService {

    /** 主问 + N 次追问。current_step ∈ [1, 1+MAX_FOLLOW_UPS]。 */
    int MAX_FOLLOW_UPS = 4;
    int MAX_STEPS = 1 + MAX_FOLLOW_UPS;

    AttemptStartResponse start(long questionId);

    AttemptTurnResponse turn(long attemptId, String userAnswer);

    AttemptFinishResponse finish(long attemptId);

    AttemptDetailResponse detail(long attemptId);

    AttemptsHistoryResponse history(long questionId, int limit);
}
