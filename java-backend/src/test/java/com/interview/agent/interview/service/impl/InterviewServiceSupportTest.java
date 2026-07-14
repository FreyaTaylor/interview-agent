package com.interview.agent.interview.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：手动校准的组（questions:[] 空列表）落库时，题干不能被塞成整段对话（大长串 bug）。
 *
 * <p>覆盖 {@link InterviewServiceSupport#normalizeGroups}（空列表也按 turn 重建 questions）
 * 与 {@link InterviewServiceSupport#firstQuestion}（无 questions 只退化为对话首行、不整段塞）。
 */
class InterviewServiceSupportTest {

    private static Map<String, Object> turn(int id, String speaker, String content) {
        return Map.of("id", id, "speaker", speaker, "content", content);
    }

    @Test
    void 手动组_空questions列表_按turn重建为面试官问题_不塞整段对话() {
        List<Map<String, Object>> turns = List.of(
                turn(1, "面试官", "你这段时间做了些什么？"),
                turn(2, "我", "我在学 AI 编程，做了个小项目……（很长的回答）"),
                turn(3, "面试官", "你之前的职级和总包是多少？"));
        // 手动校准新建组：questions 是空列表
        Map<String, Object> manualGroup = Map.of(
                "type", "other", "tag", "gap期间干什么",
                "turn_ids", List.of(1, 2, 3),
                "questions", List.of());

        List<Map<String, Object>> out = InterviewServiceSupport.normalizeGroups(turns, List.of(manualGroup));
        Object questions = out.get(0).get("questions");

        // 空列表被重建为面试官侧问题（非空、不含候选人回答）
        assertTrue(questions instanceof List<?> l && !l.isEmpty(), "questions 应被按 turn 重建");
        @SuppressWarnings("unchecked")
        List<String> qs = (List<String>) questions;
        assertEquals(List.of("你这段时间做了些什么？", "你之前的职级和总包是多少？"), qs);

        // firstQuestion 返回第一个面试官问题，而不是整段对话
        String fq = InterviewServiceSupport.firstQuestion(out.get(0));
        assertEquals("你这段时间做了些什么？", fq);
        assertFalse(fq.contains("我在学 AI 编程"), "题干不应包含候选人回答");
    }

    @Test
    void 自动组_已有干净questions_不被覆盖() {
        List<Map<String, Object>> turns = List.of(
                turn(1, "面试官", "面试官原始啰嗦提问……"),
                turn(2, "我", "回答"));
        Map<String, Object> autoGroup = Map.of(
                "type", "other", "tag", "hr",
                "turn_ids", List.of(1, 2),
                "questions", List.of("请做一下自我介绍"));   // LLM 已清洗

        List<Map<String, Object>> out = InterviewServiceSupport.normalizeGroups(turns, List.of(autoGroup));
        assertEquals(List.of("请做一下自我介绍"), out.get(0).get("questions"), "非空 questions 不应被覆盖");
    }

    @Test
    void firstQuestion_无questions时只取对话首行去掉说话人前缀() {
        Map<String, Object> g = Map.of(
                "questions", List.of(),
                "original_dialogue", "面试官：你叫什么名字？\n我：我叫小明，然后……（很长）");
        String fq = InterviewServiceSupport.firstQuestion(g);
        assertEquals("你叫什么名字？", fq);
    }
}
