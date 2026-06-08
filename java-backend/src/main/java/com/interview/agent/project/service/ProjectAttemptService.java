package com.interview.agent.project.service;

import com.interview.agent.project.dto.AttemptDetailResponse;
import com.interview.agent.project.dto.AttemptFinishResponse;
import com.interview.agent.project.dto.AttemptStartResponse;
import com.interview.agent.project.dto.AttemptTurnResponse;
import com.interview.agent.project.dto.AttemptsHistoryResponse;

/**
 * 项目拷打 attempt 状态机编排：start → turn* → finish；外加 detail / history 读端点。
 *
 * <p>状态机约束（v2 — 面试官自由追问模式）：
 * <ul>
 *   <li>同用户 + 同题最多 1 条 in_progress；start 时若已有则抛 40901</li>
 *   <li>turn / finish 要求 status=in_progress；finish 后 attempt 不可再 turn</li>
 *   <li>follow_up_count &lt; {@value #MAX_FOLLOW_UPS} 才允许追问；否则强制 next_question=null</li>
 *   <li>其他追问决策（继续 / 收尾 / 换种问法重问）全交 LLM 自决，详见 S7 doc §8</li>
 * </ul>
 *
 * <p>question_id = project_node.id（level=3 叶子）。
 */
public interface ProjectAttemptService {

    /** 主问 + N 次追问。current_step ∈ [1, 1+MAX_FOLLOW_UPS]。v1 是 4；v2 升到 6，容纳"换种问法重问"。 */
    int MAX_FOLLOW_UPS = 6;
    int MAX_STEPS = 1 + MAX_FOLLOW_UPS;

    AttemptStartResponse start(long questionId);

    AttemptTurnResponse turn(long attemptId, String userAnswer);

    AttemptFinishResponse finish(long attemptId);

    AttemptDetailResponse detail(long attemptId);

    AttemptsHistoryResponse history(long questionId, int limit);
}
