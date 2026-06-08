package com.interview.agent.project.dto;

import java.util.List;
import java.util.Map;

/**
 * /attempt-turn 响应（v2「面试官自由追问」）。
 *
 * <p>v1 的 covered / mastery / followUpType / recommendedAnswer 字段已删除；改为 v2 字段。
 * {@code signals} 故意不放进 DTO（落 dialog 供调试，前端不渲染；见 S7 doc §8.5）。
 *
 * @param interviewerNote   本轮面试官点评（自然语言一段话）
 * @param gapsFound         本轮新发现的漏洞，[{category, point}, ...]
 * @param followUpQuestion  下一轮追问；为 null 表示 LLM 决定收尾
 * @param wrapUpReason      收尾原因（与 followUpQuestion 互斥），为 null 表示还要继续
 * @param canFinish         前端是否可以触发 finish（== followUpQuestion == null）
 */
public record AttemptTurnResponse(
        Long attemptId,
        List<Object> dialog,
        String interviewerNote,
        List<Map<String, Object>> gapsFound,
        String followUpQuestion,
        String wrapUpReason,
        boolean canFinish,
        int currentStep,
        int maxSteps
) {
}
