package com.interview.agent.interview.exp.dto;

/**
 * 新建面经节点请求（走 body，不用 PathVariable）。
 *
 * <p>{@code name} 可空——OutlinerEditor 按 Enter 先创建占位节点，onBlur 再 update 填名。
 */
public record CreateInterviewExpNodeReq(
        Long parentId,
        String name
) {
}
