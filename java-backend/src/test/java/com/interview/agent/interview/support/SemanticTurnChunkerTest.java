package com.interview.agent.interview.support;

import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.infra.llm.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义预分块单元测试（无 Spring / 无网络）—— 用假 {@link EmbeddingService} 返回按话题关键字确定的向量，
 * 锁 {@link SemanticTurnChunker} 的不变量与切段行为。
 *
 * <p>假 embedding 约定：内容含 "TA" → 向量[1,0]；含 "TB" → [0,1]；其它 → [1,1]。
 * 故同话题 cos=1（≥阈值不切），跨话题 cos=0（<阈值切）。
 */
class SemanticTurnChunkerTest {

    /** 按话题关键字给确定向量的假 embedding（chunker 走批量 embedAll）。 */
    private static EmbeddingService fakeEmbedding() {
        return new EmbeddingService(new EmbeddingProperties(null, "fake", 2)) {
            @Override
            public float[] embed(String text) {
                if (text.contains("TA")) return new float[]{1f, 0f};
                if (text.contains("TB")) return new float[]{0f, 1f};
                return new float[]{1f, 1f};
            }

            @Override
            public java.util.List<float[]> embedAll(java.util.List<String> texts) {
                java.util.List<float[]> out = new java.util.ArrayList<>(texts.size());
                for (String t : texts) {
                    out.add(embed(t));
                }
                return out;
            }
        };
    }

    private static Map<String, Object> turn(int id, String speaker, String content) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("speaker", speaker);
        t.put("content", content);
        return t;
    }

    private static List<Integer> idsOf(List<Map<String, Object>> chunk) {
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> t : chunk) {
            ids.add((Integer) t.get("id"));
        }
        return ids;
    }

    // ===== 不变量：partition（不相交 + 并集 == 全部 + 顺序保持）=====

    @Test
    void chunks_arePartition_disjointAndCoverAll() {
        // min=1 让语义可触发；max 很大避免硬上限
        SemanticTurnChunker chunker = new SemanticTurnChunker(fakeEmbedding(), 10_000, 1, 0.6);
        List<Map<String, Object>> turns = List.of(
                turn(0, "面试官", "TA 问题一"),
                turn(1, "我", "回答一"),
                turn(2, "面试官", "TA 追问"),
                turn(3, "我", "回答二"),
                turn(4, "面试官", "TB 新话题"),
                turn(5, "我", "回答三"));

        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);

        // 覆盖全部且不相交，顺序保持
        List<Integer> flat = new ArrayList<>();
        TreeSet<Integer> seen = new TreeSet<>();
        for (List<Map<String, Object>> c : chunks) {
            for (Integer id : idsOf(c)) {
                assertTrue(seen.add(id), "turn 不应出现在多段（重叠）：" + id);
                flat.add(id);
            }
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5), flat, "并集 == 全部 turns 且顺序保持");
    }

    // ===== 每段以面试官提问开头 =====

    @Test
    void everyChunk_startsWithInterviewer() {
        SemanticTurnChunker chunker = new SemanticTurnChunker(fakeEmbedding(), 10_000, 1, 0.6);
        List<Map<String, Object>> turns = List.of(
                turn(0, "面试官", "TA 问题一"),
                turn(1, "我", "回答一"),
                turn(2, "面试官", "TB 新话题"),
                turn(3, "我", "回答二"));

        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);
        assertFalse(chunks.isEmpty());
        for (List<Map<String, Object>> c : chunks) {
            assertEquals("面试官", c.get(0).get("speaker"), "每段开头应是面试官提问");
        }
    }

    // ===== 语义边界：话题切换在面试官 turn 处切 =====

    @Test
    void topicShift_cutsAtInterviewerBoundary() {
        SemanticTurnChunker chunker = new SemanticTurnChunker(fakeEmbedding(), 10_000, 1, 0.6);
        List<Map<String, Object>> turns = List.of(
                turn(0, "面试官", "TA 问题一"),
                turn(1, "我", "回答一"),
                turn(2, "面试官", "TA 追问"),   // 同话题 → 不切
                turn(3, "我", "回答二"),
                turn(4, "面试官", "TB 新话题"), // 跨话题 → 切
                turn(5, "我", "回答三"));

        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);
        assertEquals(2, chunks.size(), "应在 TA→TB 处切成 2 段");
        assertEquals(List.of(0, 1, 2, 3), idsOf(chunks.get(0)), "TA 提问+追问同段");
        assertEquals(List.of(4, 5), idsOf(chunks.get(1)), "TB 新话题独立成段");
    }

    // ===== 硬上限：即便同话题，超长也在面试官 turn 处切 =====

    @Test
    void hardUpper_cutsEvenSameTopic() {
        // maxChars 很小 → 第二个面试官 turn 必超上限而切（即便同 TA 话题）
        SemanticTurnChunker chunker = new SemanticTurnChunker(fakeEmbedding(), 20, 1, 0.6);
        List<Map<String, Object>> turns = List.of(
                turn(0, "面试官", "TA 这是一个比较长的问题内容"),
                turn(1, "面试官", "TA 又一个比较长的问题内容"));

        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);
        assertEquals(2, chunks.size(), "超硬上限应切段");
        assertEquals(List.of(0), idsOf(chunks.get(0)));
        assertEquals(List.of(1), idsOf(chunks.get(1)));
    }

    // ===== minChars：太短不因语义切，防碎片 =====

    @Test
    void belowMin_doesNotCutOnSemantics() {
        // min 很大 → 语义不触发；max 很大 → 硬上限不触发 → 全并一段
        SemanticTurnChunker chunker = new SemanticTurnChunker(fakeEmbedding(), 10_000, 10_000, 0.6);
        List<Map<String, Object>> turns = List.of(
                turn(0, "面试官", "TA 问题一"),
                turn(1, "面试官", "TB 新话题"));

        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);
        assertEquals(1, chunks.size(), "未达 minChars 时语义边界不切，避免碎片");
        assertEquals(List.of(0, 1), idsOf(chunks.get(0)));
    }
}
