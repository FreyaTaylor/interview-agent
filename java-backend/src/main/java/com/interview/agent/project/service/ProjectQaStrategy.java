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
 * 项目拷打 LLM 调用策略 —— per-turn 评估 + final-score 综合评分。
 *
 * <p>职责：装 vars、调 {@link LlmInvoker}、解析 JSON；不碰 DB、不碰 attempt 状态机。
 *
 * <p>与 study 侧 {@code StudyQaStrategy} 的差异：
 * <ul>
 *   <li>per-turn 输出 {@code recommended_answer}（list） 替代 {@code feedback}+{@code hits}</li>
 *   <li>final-score 多输出 {@code design_issues} / {@code extension_qa}</li>
 *   <li>注入 {@code project_block} + {@code profile_block}（项目元数据 + 用户画像）</li>
 *   <li>无 {@code can_finish} 字段；由 {@code ProjectAttemptService} 据状态机推导</li>
 * </ul>
 *
 * <p>温度 / token：对齐 [S7 doc §6](../../../../../../docs/modules/S7-project-grilling.md)
 * （per-turn 0.2 / final 0.1 / extract 0.2；max_tokens per-turn 2048 / final 3072 / extract 2048）。
 */
@Component
public class ProjectQaStrategy {

    private static final double TEMP_PER_TURN = 0.2;
    private static final double TEMP_FINAL = 0.1;
    private static final int MAX_TOKENS_PER_TURN = 2048;
    private static final int MAX_TOKENS_FINAL = 3072;
    private static final int MAX_RETRY = 2;

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};

    private final LlmInvoker llmInvoker;

    public ProjectQaStrategy(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    // ============================================================
    // per-turn
    // ============================================================

    /**
     * 单轮评估（注入项目元数据 + 用户画像）。
     *
     * @param topicName            L2 话题名（用于画像里 current_dimension 过滤）
     * @param priorFollowUpTypes   已经出现过的追问类型（按顺序）
     * @param allowedFollowUpTypes 本轮允许的追问类型
     */
    public PerTurn perTurn(Project project, ProjectUserProfile profile, ProjectNode leaf,
                           String topicName, List<Object> dialog,
                           int currentStep, int maxSteps,
                           List<String> priorFollowUpTypes, List<String> allowedFollowUpTypes) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("project_block", renderProject(project));
        vars.put("profile_block", renderProfileForPrompt(profile, topicName));
        vars.put("topic_name", topicName == null || topicName.isBlank() ? "—" : topicName);
        vars.put("question_content", leaf.name());
        vars.put("dialog_render", renderDialog(dialog));
        vars.put("current_step", currentStep);
        vars.put("max_steps", maxSteps);
        vars.put("prior_follow_up_types", priorFollowUpTypes.isEmpty() ? "（无）" : String.join(", ", priorFollowUpTypes));
        vars.put("allowed_follow_up_types", allowedFollowUpTypes.isEmpty() ? "（无，必须结束）" : String.join(", ", allowedFollowUpTypes));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_PER_TURN, vars,
                TEMP_PER_TURN, MAX_TOKENS_PER_TURN, MAX_RETRY);
        return llmInvoker.invoke(spec, ProjectQaStrategy::parsePerTurn).orElseGet(PerTurn::empty);
    }

    // ============================================================
    // final-score
    // ============================================================

    /** 综合评分。失败返 {@link FinalScore#zero()}。 */
    public FinalScore finalScore(Project project, ProjectNode leaf, String topicName, List<Object> dialog) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("project_block", renderProject(project));
        vars.put("topic_name", topicName == null || topicName.isBlank() ? "—" : topicName);
        vars.put("question_content", leaf.name());
        vars.put("dialog_render", renderDialog(dialog));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_FINAL_SCORE, vars,
                TEMP_FINAL, MAX_TOKENS_FINAL, MAX_RETRY);
        return llmInvoker.invoke(spec, ProjectQaStrategy::parseFinalScore).orElseGet(FinalScore::zero);
    }

    // ============================================================
    // 解析
    // ============================================================

    private static PerTurn parsePerTurn(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        boolean covered = Boolean.TRUE.equals(data.get("covered"));
        String mastery = normalizeMastery(asString(data.get("mastery")));
        List<String> recommended = asStringList(data.get("recommended_answer"));
        String followUpType = normalizeFollowUpType(asString(data.get("follow_up_type")));
        String followUp = nullIfBlank(asString(data.get("follow_up_question")));
        return new PerTurn(covered, mastery, recommended, followUpType, followUp);
    }

    private static FinalScore parseFinalScore(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        int score = clamp(asInt(data.get("final_score")), 0, 100);
        Object rubric = data.get("rubric_result");
        if (!(rubric instanceof List<?>)) {
            rubric = List.of();
        }
        String summary = asString(data.get("overall_summary"));
        Object designIssues = data.get("design_issues");
        if (!(designIssues instanceof List<?>)) {
            designIssues = List.of();
        }
        Object extensionQa = data.get("extension_qa");
        if (!(extensionQa instanceof List<?>)) {
            extensionQa = List.of();
        }
        return new FinalScore(score, rubric, summary, designIssues, extensionQa);
    }

    private static String normalizeMastery(String s) {
        if (s == null) return "mid";
        String v = s.trim().toLowerCase();
        return (v.equals("high") || v.equals("mid") || v.equals("low")) ? v : "mid";
    }

    private static String normalizeFollowUpType(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return (v.equals("horizontal") || v.equals("deep_dive")) ? v : null;
    }

    // ============================================================
    // 渲染 helpers
    // ============================================================

    /** 渲染项目元数据 block，对齐 Python services/project_qa_strategy.py: _render_project。 */
    static String renderProject(Project p) {
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
    @SuppressWarnings("unchecked")
    static String renderProfileForPrompt(ProjectUserProfile profile, String currentDimension) {
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

    /**
     * dialog 渲染 —— 与 study 侧规则一致；feedback 的 content 是 List（recommended_answer）时
     * 拼成多行 bullet 输出，避免 toString 印出 [a, b] 这种 Java 列表字面量。
     */
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
            Object content = m.get("content");
            sb.append("[").append(role).append("/").append(type).append("] ");
            if (content instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(list.get(i));
                }
            } else {
                sb.append(content == null ? "" : content.toString());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String safe(String s) { return s == null ? "" : s; }
    private static String blankToDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }
    private static String asString(Object o) { return o == null ? "" : o.toString(); }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
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
        if (!(o instanceof List<?> list)) return List.of();
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
     * 单轮评估输出。
     * <p>{@code recommendedAnswer} 落入 dialog feedback 项的 content；不重新拼 feedback 文本。
     */
    public record PerTurn(boolean covered, String mastery,
                          List<String> recommendedAnswer,
                          String followUpType, String followUpQuestion) {
        public static PerTurn empty() {
            return new PerTurn(true, "mid", List.of("（评估失败，请补充作答或直接结束）"), null, null);
        }
    }

    /**
     * 综合评分输出。rubricResult / designIssues / extensionQa 都是 List；落 JSONB。
     */
    public record FinalScore(int finalScore, Object rubricResult, String overallSummary,
                             Object designIssues, Object extensionQa) {
        public static FinalScore zero() {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put("key_point", "评分失败");
            placeholder.put("hit", false);
            placeholder.put("matched_text", "");
            placeholder.put("standard_answer", "");
            return new FinalScore(0, List.of(placeholder), "（综合评分失败，建议重试本题）",
                    List.of(), List.of());
        }
    }
}
