package com.interview.agent.interview.leetcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT1：{@link LeetCodeClient} 真实命中 leetcode.com 搜索。
 *
 * <p>依赖外网，默认不跑；需要时设 {@code RUN_NET_TESTS=true} 手动执行：
 * {@code RUN_NET_TESTS=true mvn -Dtest=LeetCodeClientIT test}。
 */
@EnabledIfEnvironmentVariable(named = "RUN_NET_TESTS", matches = "true")
class LeetCodeClientIT {

    @Test
    void search_LRU_shouldReturn146() {
        List<LeetCodeClient.LeetCodeQuestion> res = new LeetCodeClient().search("LRU", 3);

        assertFalse(res.isEmpty(), "应至少返回一条候选");
        boolean hit = res.stream().anyMatch(q ->
                "146".equals(q.id())
                        && "lru-cache".equals(q.slug())
                        && q.url().equals("https://leetcode.cn/problems/lru-cache/description/"));
        assertTrue(hit, "应命中 146 / lru-cache，实际=" + res);
    }

    @Test
    void search_blank_returnsEmpty() {
        assertTrue(new LeetCodeClient().search("  ", 3).isEmpty());
    }
}
