package com.interview.agent.interview.leetcode;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LeetCode 搜索工具 —— 暴露给 Spring AI function-calling（{@code ChatClient.tools(...)}）。
 *
 * <p>让富化 agent 能自主"查题库"：把面试里口语化的算法题描述对应到真实 LeetCode 题目。
 * 底层走 {@link LeetCodeClient}，失败即返回空列表（不抛）。
 */
@Component
public class LeetCodeTool {

    private final LeetCodeClient client;

    public LeetCodeTool(LeetCodeClient client) {
        this.client = client;
    }

    @Tool(description = "按英文关键词搜索 LeetCode 题库，返回候选题目（题号 id、题名 title、slug、难度）。"
            + "用于把面试里口语描述的算法题对应到真实的 LeetCode 题目。")
    public List<LeetCodeClient.LeetCodeQuestion> searchLeetCode(
            @ToolParam(description = "英文搜索关键词，如 'LRU'、'two sum'、'reverse linked list'") String keyword) {
        return client.search(keyword, 5);
    }
}
