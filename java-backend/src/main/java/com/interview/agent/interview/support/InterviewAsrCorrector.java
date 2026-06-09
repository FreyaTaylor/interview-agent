package com.interview.agent.interview.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASR 转写纠错 —— 完全复刻 Python {@code backend/services/asr_corrector.py}。
 *
 * <p>职责：按发音对齐修正技术术语错别字 + 删除短噪声 turn；保留原 turn id/speaker，仅重写 content。</p>
 *
 * <p>复刻要点：
 * <ul>
 *   <li>分批字符上限 {@code 6000}，单 turn 超长独占一批</li>
 *   <li>LLM 温度 {@code 0.0}，max_tokens {@code 8192}，单次调用（失败该批原样保留）</li>
 *   <li>返回 turn id 是原 id 子集（删除的 turn 直接消失），最终按 id 升序</li>
 * </ul>
 */
@Component
public class InterviewAsrCorrector {

    private static final Logger log = LoggerFactory.getLogger(InterviewAsrCorrector.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {
    };

    /** 单次纠错的 turn 字数上限；超过则分批（保留 turn 边界）。 */
    static final int CORRECT_BATCH_CHAR_LIMIT = 6000;

    private final LlmInvoker llmInvoker;

    public InterviewAsrCorrector(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * 对完整 turns 列表做 ASR 纠错 + 噪声 turn 删除。
     * <ul>
     *   <li>返回的 turn id 是原 id 的子集（删除的 turn 直接消失）</li>
     *   <li>speaker 与原一致</li>
     *   <li>任一 batch LLM 失败 → 该 batch 原样保留，不影响其他 batch</li>
     * </ul>
     */
    public List<Map<String, Object>> correct(List<Map<String, Object>> turns) {
        if (turns == null || turns.isEmpty()) {
            return turns;
        }
        List<List<Map<String, Object>>> batches = chunkTurnsByChar(turns, CORRECT_BATCH_CHAR_LIMIT);

        List<Map<String, Object>> result = new ArrayList<>();
        int deleted = 0;
        for (List<Map<String, Object>> batch : batches) {
            List<Map<String, Object>> corrected = correctOneBatch(batch);
            if (corrected == null) {
                // 失败 → 该 batch 原样保留
                result.addAll(batch);
                continue;
            }
            result.addAll(corrected);
            deleted += batch.size() - corrected.size();
        }
        result.sort((a, b) -> Integer.compare(intVal(a.get("id")), intVal(b.get("id"))));
        log.info("ASR 纠错完成：{} turns → {} turns（删除噪声 {}），分 {} 批",
                turns.size(), result.size(), deleted, batches.size());
        return result;
    }

    /** 按字符数把 turns 分批，单个 turn 即使超长也独占一批。 */
    private List<List<Map<String, Object>>> chunkTurnsByChar(List<Map<String, Object>> turns, int limit) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        if (turns.isEmpty()) {
            return batches;
        }
        List<Map<String, Object>> current = new ArrayList<>();
        int currentLen = 0;
        for (Map<String, Object> t : turns) {
            int clen = str(t.get("content")).length();
            if (!current.isEmpty() && currentLen + clen > limit) {
                batches.add(current);
                current = new ArrayList<>();
                current.add(t);
                currentLen = clen;
            } else {
                current.add(t);
                currentLen += clen;
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /** 单批纠错。返回 null 表示 LLM 失败，调用方应回退原 turns。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> correctOneBatch(List<Map<String, Object>> batch) {
        String dialogue = InterviewTurns.renderTurnsForLlm(batch);
        // 单次调用（maxRetry=1），与 Python 行为一致：失败即回退原 batch
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_ASR_CORRECT, Map.of("dialogue", dialogue), 0.0, 8192, 1);

        Map<String, Object> data = llmInvoker.invoke(spec,
                raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (data == null) {
            log.warn("ASR 纠错 LLM 调用失败");
            return null;
        }
        Object outTurnsObj = data.get("turns");
        if (!(outTurnsObj instanceof List<?> outTurns)) {
            log.warn("ASR 纠错返回结构异常，缺 turns 字段");
            return null;
        }

        // 校验：id 必须在原 batch 范围内，content 非空
        Map<Integer, Map<String, Object>> validIds = new LinkedHashMap<>();
        for (Map<String, Object> t : batch) {
            validIds.put(intVal(t.get("id")), t);
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Object o : outTurns) {
            if (!(o instanceof Map<?, ?> ot)) {
                continue;
            }
            Integer tid = tryInt(((Map<String, Object>) ot).get("id"));
            if (tid == null || !validIds.containsKey(tid)) {
                continue;
            }
            String speaker = str(((Map<String, Object>) ot).get("speaker"));
            if (speaker.isEmpty()) {
                speaker = str(validIds.get(tid).get("speaker"));
            }
            String content = str(((Map<String, Object>) ot).get("content")).strip();
            if (content.isEmpty()) {
                continue;
            }
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", tid);
            turn.put("speaker", speaker);
            turn.put("content", content);
            cleaned.add(turn);
        }
        cleaned.sort((a, b) -> Integer.compare(intVal(a.get("id")), intVal(b.get("id"))));
        return cleaned;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intVal(Object o) {
        Integer v = tryInt(o);
        return v == null ? 0 : v;
    }

    private static Integer tryInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
