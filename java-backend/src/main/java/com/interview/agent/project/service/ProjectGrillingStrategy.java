package com.interview.agent.project.service;

import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.prompts.PromptKeys;
import com.interview.agent.project.entity.Project;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.entity.ProjectUserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目拷打 v2「面试官自由追问」LLM 策略 —— per-turn + final-score。
 *
 * <p>设计要点（详见 S7-project-grilling.md §8）：
 * <ul>
 *   <li>per-turn 输出 interviewer_note + gaps_found + signals + next_question/wrap_up_reason（互斥）</li>
 *   <li>后端唯一硬兜底：follow_up_count &gt;= 6 时强制 next_question=null，其他全交 LLM 自决</li>
 *   <li>final-score 输出 dimensions(4 维) + design_issues（基于整段对话重新提炼）；不输出 rubric_result</li>
 *   <li>final_score 由后端按权重计算：fact_clarity*0.3 + design_quality*0.3 + depth*0.25 + communication*0.15</li>
 * </ul>
 *
 * <p>温度 / token：per-turn 0.2 / 2048；final 0.1 / 3072。
 */
@Component
public class ProjectGrillingStrategy {

    private static final double TEMP_PER_TURN = 0.2;
    private static final double TEMP_FINAL = 0.1;
    private static final int MAX_TOKENS_PER_TURN = 2048;
    private static final int MAX_TOKENS_FINAL = 3072;
    private static final int MAX_RETRY = 2;

    /** dimensions 权重（与 S7-project-grilling.md §8.6 一致）。 */
    private static final double W_FACT = 0.30;
    private static final double W_DESIGN = 0.30;
    private static final double W_DEPTH = 0.25;
    private static final double W_COMM = 0.15;

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};

    private final LlmInvoker llmInvoker;

    public ProjectGrillingStrategy(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    // ============================================================
    // per-turn
    // ============================================================

    /**
     * 单轮面试官追问决策。
     *
     * @param isLastRound 是否最后一轮（current_step + 1 == max_steps，用于 prompt 末尾追加合并问完提示）
     */
    public PerTurnV2 perTurn(Project project, ProjectUserProfile profile, ProjectNode leaf,
                             String topicName, List<Object> dialog,
                             int currentStep, int maxSteps, boolean isLastRound) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("project_block", renderProject(project));
        vars.put("profile_block", renderProfileForPrompt(profile, topicName));
        vars.put("topic_name", topicName == null || topicName.isBlank() ? "—" : topicName);
        vars.put("question_content", leaf.name());
        vars.put("dialog_render", renderDialogV2(dialog));
        vars.put("current_step", currentStep);
        vars.put("max_steps", maxSteps);
        vars.put("is_last_round_hint", isLastRound ? "true（这是最后一轮，把还想问的合并问完）" : "false");

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_PER_TURN_V2, vars,
                TEMP_PER_TURN, MAX_TOKENS_PER_TURN, MAX_RETRY);
        return llmInvoker.invoke(spec, ProjectGrillingStrategy::parsePerTurn).orElseGet(PerTurnV2::empty);
    }

    // ============================================================
    // final-score
    // ============================================================

    /** 综合评分。失败返 {@link FinalScoreV2#zero()}。 */
    public FinalScoreV2 finalScore(Project project, ProjectNode leaf, String topicName, List<Object> dialog) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("project_block", renderProject(project));
        vars.put("topic_name", topicName == null || topicName.isBlank() ? "—" : topicName);
        vars.put("question_content", leaf.name());
        vars.put("dialog_render", renderDialogV2(dialog));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_FINAL_SCORE_V2, vars,
                TEMP_FINAL, MAX_TOKENS_FINAL, MAX_RETRY);
        return llmInvoker.invoke(spec, ProjectGrillingStrategy::parseFinalScore).orElseGet(FinalScoreV2::zero);
    }

    // ============================================================
    // 解析
    // ============================================================

    private static PerTurnV2 parsePerTurn(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        String note = asString(data.get("interviewer_note"));
        List<Map<String, Object>> gaps = asMapList(data.get("gaps_found"));
        Map<String, Object> signals = normalizeSignals(data.get("signals"));
        String nextQ = nullIfBlank(asString(data.get("next_question")));
        String wrap = nullIfBlank(asString(data.get("wrap_up_reason")));
        // next_question 与 wrap_up_reason 互斥；都给优先 next_question
        if (nextQ != null && wrap != null) {
            wrap = null;
        }
        return new PerTurnV2(note, gaps, signals, nextQ, wrap);
    }

    private static FinalScoreV2 parseFinalScore(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        Map<String, Integer> dims = normalizeDimensions(data.get("dimensions"));
        String summary = asString(data.get("overall_summary"));
        List<String> designIssues = asStringList(data.get("design_issues"));
        Object extensionQa = data.get("extension_qa");
        if (!(extensionQa instanceof List<?>)) {
            extensionQa = List.of();
        }
        int finalScore = computeWeightedScore(dims);
        return new FinalScoreV2(finalScore, dims, summary, designIssues, extensionQa);
    }

    /** 按权重计算 final_score：四维 0-10 → 加权 → ×10 round 得 0-100。 */
    static int computeWeightedScore(Map<String, Integer> dims) {
        double raw = dims.getOrDefault("fact_clarity", 0) * W_FACT
                + dims.getOrDefault("design_quality", 0) * W_DESIGN
                + dims.getOrDefault("depth", 0) * W_DEPTH
                + dims.getOrDefault("communication", 0) * W_COMM;
        int v = (int) Math.round(raw * 10.0);
        return Math.max(0, Math.min(100, v));
    }

    private static Map<String, Integer> normalizeDimensions(Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            out.put("fact_clarity", clamp(asInt(m.get("fact_clarity")), 0, 10));
            out.put("design_quality", clamp(asInt(m.get("design_quality")), 0, 10));
            out.put("depth", clamp(asInt(m.get("depth")), 0, 10));
            out.put("communication", clamp(asInt(m.get("communication")), 0, 10));
        } else {
            out.put("fact_clarity", 0);
            out.put("design_quality", 0);
            out.put("depth", 0);
            out.put("communication", 0);
        }
        return out;
    }

    private static Map<String, Object> normalizeSignals(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            out.put("clarity", normalizeClarity(asString(m.get("clarity"))));
            out.put("credibility", normalizeCredibility(asString(m.get("credibility"))));
        } else {
            out.put("clarity", "vague");
            out.put("credibility", "doubtful");
        }
        return out;
    }

    private static String normalizeClarity(String s) {
        if (s == null) return "vague";
        String v = s.trim().toLowerCase();
        return (v.equals("clear") || v.equals("vague") || v.equals("unclear")) ? v : "vague";
    }

    private static String normalizeCredibility(String s) {
        if (s == null) return "doubtful";
        String v = s.trim().toLowerCase();
        return (v.equals("solid") || v.equals("doubtful") || v.equals("fishy")) ? v : "doubtful";
    }

    // ============================================================
    // 渲染 helpers（项目元数据 + 用户画像）
    // ============================================================

    /** 渲染项目元数据 block，对齐 Python services/project_qa_strategy.py: _render_project。 */
    private static String renderProject(Project p) {
        if (p == null) {
            return "（项目信息缺失）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("项目名：").append(safe(p.name())).append("\n");
        sb.append("角色：").append(blankToDash(p.role())).append("\n");
        sb.append("技术栈：").append(renderTechStack(p.techStack()));
        if (p.description() != null && !p.description().isBlank()) {
            sb.append("\n描述：").append(p.description());
        }
        if (p.highlights() != null && !p.highlights().isBlank()) {
            sb.append("\n亮点：").append(p.highlights());
        }
        return sb.toString();
    }

    /** tech_stack 在 DB 是 JSONB 文本；可能是 list / string / null。 */
    private static String renderTechStack(String techStackRaw) {
        if (techStackRaw == null || techStackRaw.isBlank() || "null".equalsIgnoreCase(techStackRaw)) {
            return "—";
        }
        String trimmed = techStackRaw.trim();
        if (trimmed.startsWith("[")) {
            try {
                Object parsed = JsonUtil.fromJson(trimmed, JSON_OBJ);
                if (parsed instanceof List<?> list && !list.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(list.get(i));
                    }
                    return sb.toString();
                }
            } catch (Exception ignore) {
                // 解析失败兜底用原文
            }
        }
        return trimmed;
    }

    /** 渲染用户画像 block；仅包含 project_facts。 */
    private static String renderProfileForPrompt(ProjectUserProfile profile, String currentDimension) {
        if (profile == null) {
            return "（暂无画像）";
        }
        List<String> facts = asStringList(profile.projectFacts());
        if (facts.isEmpty()) {
            return "（暂无画像）";
        }

        StringBuilder sb = new StringBuilder("【用户在本项目的答题画像（仅供出题/追问参考）】");
        sb.append("\n### 项目事实");
        for (String f : facts) {
            if (f != null && !f.isBlank()) sb.append("\n  · ").append(f);
        }
        return sb.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String blankToDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    // ============================================================
    // 渲染 helpers（v2 dialog）
    // ============================================================

    /**
     * v2 dialog 渲染 —— feedback 项读 note + 简述 gaps_found；其他与 v1 一致。
     * 兼容历史 v1 数据：feedback 若无 note 字段，回落到 content（旧版 recommended_answer）。
     */
    @SuppressWarnings("unchecked")
    static String renderDialogV2(List<Object> dialog) {
        if (dialog == null || dialog.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : dialog) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String role = asString(m.get("role"));
            String type = asString(m.get("type"));
            sb.append("[").append(role).append("/").append(type).append("] ");

            if ("feedback".equals(type)) {
                Object note = m.get("note");
                if (note != null && !note.toString().isBlank()) {
                    sb.append(note);
                } else {
                    // v1 兼容：content 可能是 List<String> 或 String
                    Object content = m.get("content");
                    if (content instanceof List<?> list) {
                        for (int i = 0; i < list.size(); i++) {
                            if (i > 0) sb.append("; ");
                            sb.append(list.get(i));
                        }
                    } else if (content != null) {
                        sb.append(content);
                    }
                }
                Object gaps = m.get("gaps_found");
                if (gaps instanceof List<?> gl && !gl.isEmpty()) {
                    sb.append("\n    本轮发现的漏洞：");
                    for (Object g : gl) {
                        if (g instanceof Map<?, ?> gm) {
                            sb.append("\n      · [").append(asString(gm.get("category"))).append("] ")
                                    .append(asString(gm.get("point")));
                        }
                    }
                }
            } else {
                Object content = m.get("content");
                sb.append(content == null ? "" : content.toString());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String asString(Object o) { return o == null ? "" : o.toString(); }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return new ArrayList<>();
        List<String> out = new ArrayList<>(list.size());
        for (Object x : list) {
            if (x == null) continue;
            String s = x.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        if (!(o instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object x : list) {
            if (x instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
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
     * v2 单轮输出。
     *
     * @param interviewerNote 面试官点评，自然语言一段话
     * @param gapsFound       本轮新发现的漏洞 [{category, point}, ...]
     * @param signals         {clarity, credibility} 内部信号，落 dialog 但 DTO 不返
     * @param nextQuestion    下一个追问，与 wrapUpReason 互斥
     * @param wrapUpReason    收尾原因，与 nextQuestion 互斥
     */
    public record PerTurnV2(String interviewerNote,
                            List<Map<String, Object>> gapsFound,
                            Map<String, Object> signals,
                            String nextQuestion,
                            String wrapUpReason) {
        public static PerTurnV2 empty() {
            Map<String, Object> sig = new LinkedHashMap<>();
            sig.put("clarity", "vague");
            sig.put("credibility", "doubtful");
            return new PerTurnV2("（评估失败，建议补充作答或直接结束本题）",
                    new ArrayList<>(), sig, null, "评估失败，自动收尾");
        }
    }

    /**
     * v2 综合评分输出。
     *
     * @param finalScore     0-100，由 dimensions 加权计算
     * @param dimensions     {fact_clarity, design_quality, depth, communication} 各 0-10
     * @param overallSummary 面试官口吻 1-2 句话
     * @param designIssues   设计缺陷列表（基于整段 dialog 重新提炼，去重 + 归类）
     * @param extensionQa    3 个延伸 Q&amp;A
     */
    public record FinalScoreV2(int finalScore,
                               Map<String, Integer> dimensions,
                               String overallSummary,
                               List<String> designIssues,
                               Object extensionQa) {
        public static FinalScoreV2 zero() {
            Map<String, Integer> dims = new LinkedHashMap<>();
            dims.put("fact_clarity", 0);
            dims.put("design_quality", 0);
            dims.put("depth", 0);
            dims.put("communication", 0);
            return new FinalScoreV2(0, dims, "（综合评分失败，建议重试本题）",
                    new ArrayList<>(), List.of());
        }
    }
}
