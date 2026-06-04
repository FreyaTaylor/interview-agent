package com.interview.agent.study.service;

import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Study 模块 LLM 调用策略 —— per-turn 评估 + final-score 综合评分。
 *
 * <p>职责：装 vars、调 {@link LlmInvoker}、解析 JSON；不碰 DB、不碰 attempt 状态机。
 *
 * <p>dialog 渲染规则（dialog_render）：每轮一行
 * {@code [agent/问题] ... → [user/回答] ... → [agent/反馈] ... → [agent/追问] ...}
 */
@Component
public class StudyQaStrategy {

    private static final String PROMPT_PER_TURN = "study/per-turn";
    private static final String PROMPT_FINAL = "study/final-score";
    private static final double TEMP_PER_TURN = 0.3;
    private static final double TEMP_FINAL = 0.2;
    private static final int MAX_TOKENS = 3072;
    private static final int MAX_RETRY = 2;

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};

    private final LlmInvoker llmInvoker;

    public StudyQaStrategy(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * 单轮评估（含追问决策状态机所需上下文）。
     *
     * @param priorFollowUpTypes   已经出现过的追问类型（按顺序）；用于让 LLM 不重复同类追问
     * @param allowedFollowUpTypes 本轮允许的追问类型；后端会按 5 条硬规则二次校正，prompt 只是 hint
     */
    public PerTurn perTurn(String question, Object rubricTemplate, List<Object> dialog,
                           int currentStep, int maxSteps,
                           List<String> priorFollowUpTypes, List<String> allowedFollowUpTypes) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("question", question);
        vars.put("rubric_template_json", JsonUtil.toJson(rubricTemplate));
        vars.put("dialog_render", renderDialog(dialog));
        vars.put("current_step", currentStep);
        vars.put("max_steps", maxSteps);
        vars.put("prior_follow_up_types", priorFollowUpTypes.isEmpty() ? "（无）" : String.join(", ", priorFollowUpTypes));
        vars.put("allowed_follow_up_types", allowedFollowUpTypes.isEmpty() ? "（无，必须结束）" : String.join(", ", allowedFollowUpTypes));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PROMPT_PER_TURN, vars, TEMP_PER_TURN, MAX_TOKENS, MAX_RETRY);
        return llmInvoker.invoke(spec, StudyQaStrategy::parsePerTurn)
                .orElseGet(() -> PerTurn.empty(currentStep));
    }

    /** 综合评分。失败返 {@link FinalScore#zero()}（final_score=0、空 rubric_result、兜底总结）。 */
    public FinalScore finalScore(String question, Object rubricTemplate, List<Object> dialog) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("question", question);
        vars.put("rubric_template_json", JsonUtil.toJson(rubricTemplate));
        vars.put("dialog_render", renderDialog(dialog));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PROMPT_FINAL, vars, TEMP_FINAL, MAX_TOKENS, MAX_RETRY);
        return llmInvoker.invoke(spec, StudyQaStrategy::parseFinalScore).orElseGet(FinalScore::zero);
    }

    // ============================================================
    // 解析
    // ============================================================

    private static PerTurn parsePerTurn(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        String feedback = asString(data.get("feedback"));
        List<Object> hits = asList(data.get("hits"));
        boolean covered = Boolean.TRUE.equals(data.get("covered"));
        String mastery = normalizeMastery(asString(data.get("mastery")));
        String followUpType = normalizeFollowUpType(asString(data.get("follow_up_type")));
        String followUp = nullIfBlank(asString(data.get("follow_up_question")));
        boolean canFinish = Boolean.TRUE.equals(data.get("can_finish"));
        return new PerTurn(feedback, hits, covered, mastery, followUpType, followUp, canFinish);
    }

    /** mastery 归一为 high/mid/low；解析失败兜底 mid。 */
    private static String normalizeMastery(String s) {
        if (s == null) return "mid";
        String v = s.trim().toLowerCase();
        return (v.equals("high") || v.equals("mid") || v.equals("low")) ? v : "mid";
    }

    /** follow_up_type 归一为 horizontal/deep_dive/null。 */
    private static String normalizeFollowUpType(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return (v.equals("horizontal") || v.equals("deep_dive")) ? v : null;
    }

    private static FinalScore parseFinalScore(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        int score = clamp(asInt(data.get("final_score")), 0, 100);
        Object rubric = data.get("rubric_result");
        if (!(rubric instanceof Map<?, ?>)) {
            rubric = Map.of("hits", List.of(), "missed_key_points", List.of());
        }
        String summary = asString(data.get("overall_summary"));
        return new FinalScore(score, rubric, summary);
    }

    // ============================================================
    // dialog 渲染
    // ============================================================

    @SuppressWarnings("unchecked")
    static String renderDialog(List<Object> dialog) {
        if (dialog == null || dialog.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : dialog) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String role = asString(m.get("role"));
            String type = asString(m.get("type"));
            String content = asString(m.get("content"));
            sb.append("[").append(role).append("/").append(type).append("] ")
              .append(content).append("\n");
        }
        return sb.toString().trim();
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ============================================================
    // VO
    // ============================================================

    /**
     * 单轮评估输出。
     *
     * @param covered        rubric 关键点是否已基本全覆盖
     * @param mastery        最后一次回答展现的掌握度 high / mid / low
     * @param followUpType   LLM 建议的追问类型 horizontal / deep_dive / null（后端二次校正后才作数）
     * @param followUpQuestion 追问内容（与 followUpType 同生同灭）
     */
    public record PerTurn(String feedback, List<Object> hits,
                          boolean covered, String mastery,
                          String followUpType, String followUpQuestion,
                          boolean canFinish) {
        public static PerTurn empty(int currentStep) {
            return new PerTurn("（评估失败，请补充作答或直接结束）", List.of(),
                    true, "mid", null, null, true);
        }
    }

    public record FinalScore(int finalScore, Object rubricResult, String overallSummary) {
        public static FinalScore zero() {
            return new FinalScore(0,
                    Map.of("hits", List.of(), "missed_key_points", List.of()),
                    "（综合评分失败，建议重试本题）");
        }
    }
}
