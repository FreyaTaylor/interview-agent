package com.interview.agent.interview.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.interview.service.InterviewScorerService;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面试分组评分 —— 完全复刻 Python backend/services/interview_scorer.py。
 *
 * 按 type 走不同 prompt（未作答仍走打分，给低分 + 推荐答案）：
 *   knowledge → INTERVIEW_SCORE_KNOWLEDGE：total_score / feedback / items / recommended_answer
 *   project   → INTERVIEW_SCORE_PROJECT：rating / rating_label / impression / highlights / improvements / follow_up_risks / suggested_answer
 *   algorithm → INTERVIEW_SCORE_ALGORITHM：feedback / description / example / suggested_approach
 *   hr        → INTERVIEW_SCORE_HR：feedback / suggestion
 *
 * 聚合：avg = round(knowledge total_score 之和 / knowledge 已评分数)；
 * pass ≥70 较高 / ≥50 一般 / else 较低（与 Python interview_crud 一致）。
 * 整体分析另调 INTERVIEW_OVERALL_ANALYSIS。
 *
 * 对齐依据：java-backend/docs/modules/interview-parser-python-spec.md（第 8 / 11 节）。
 */
@Service
public class InterviewScorerServiceImpl implements InterviewScorerService {

    private static final Logger log = LoggerFactory.getLogger(InterviewScorerServiceImpl.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {
    };

    /** 可评分 type 集合（其余直接置 score_result=null）。 */
    private static final Set<String> SCORABLE = Set.of("knowledge", "project", "algorithm", "hr");
    /** DeepSeek 并发上限（对齐 Python asyncio.Semaphore(5)）。 */
    private static final int SCORE_PARALLEL = 5;

    private final LlmInvoker llmInvoker;

    public InterviewScorerServiceImpl(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    @Override
    public ScoreBundle scoreAll(List<Map<String, Object>> groups, String company, String position) {
        // Phase A: 并发对可评分组评分（Semaphore=5），其余置 null
        List<Map<String, Object>> scored = new ArrayList<>(groups.size());
        for (Map<String, Object> g : groups) {
            scored.add(new LinkedHashMap<>(g));
        }
        List<Map<String, Object>> results = scoreParallel(scored);

        // Phase B: 回填 score_result + 聚合 knowledge 统计
        int totalScoreSum = 0;
        int scoredCount = 0;
        for (int i = 0; i < scored.size(); i++) {
            Map<String, Object> sr = results.get(i);
            scored.get(i).put("score_result", sr);   // 可能为 null（忠实于 Python）
            if (sr != null && "knowledge".equals(sr.get("type"))) {
                totalScoreSum += intVal(sr.get("total_score"));
                scoredCount += 1;
            }
        }

        int avg = scoredCount > 0 ? Math.round((float) totalScoreSum / scoredCount) : 0;
        String pass = avg >= 70 ? "较高" : (avg >= 50 ? "一般" : "较低");

        // 整体分析（失败回退空 map，调用方读 comment 不至 NPE）
        Map<String, Object> overall = generateOverallAnalysis(scored, company, position);
        return new ScoreBundle(scored, avg, pass, overall);
    }

    // ============================================================
    // 并发评分
    // ============================================================

    private List<Map<String, Object>> scoreParallel(List<Map<String, Object>> groups) {
        int parallelism = Math.min(SCORE_PARALLEL, Math.max(1, groups.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>(groups.size());
            for (Map<String, Object> g : groups) {
                if (SCORABLE.contains(str(g.get("type")))) {
                    futures.add(CompletableFuture.supplyAsync(() -> scoreOne(g), pool));
                } else {
                    futures.add(CompletableFuture.completedFuture(null));
                }
            }
            List<Map<String, Object>> out = new ArrayList<>(groups.size());
            for (CompletableFuture<Map<String, Object>> f : futures) {
                out.add(f.join());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    // ============================================================
    // 单组评分（按 type 分派）
    // ============================================================

    /** 复刻 score_interview_group：失败返回 null（不中断整单）。 */
    private Map<String, Object> scoreOne(Map<String, Object> group) {
        String type = str(group.get("type"));
        String questionsText = renderQuestions(group);
        String userAnswer = str(group.get("user_answer")).strip();
        // 未作答占位：仍走打分（给 0 分 + 标准答案要点）
        String answerForPrompt = userAnswer.isEmpty()
                ? "（候选人未作答此问题，请按未作答处理：给 0 分，并在反馈中说明未作答，同时给出标准答案要点）"
                : userAnswer;

        try {
            return switch (type) {
                case "knowledge" -> scoreKnowledge(group, questionsText, answerForPrompt);
                case "project" -> scoreProject(group, questionsText, answerForPrompt);
                case "algorithm" -> scoreAlgorithm(group, userAnswer);
                case "hr" -> scoreHr(questionsText, answerForPrompt);
                default -> null;
            };
        } catch (Exception e) {
            log.error("面试评分失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> scoreKnowledge(Map<String, Object> g, String questions, String answer) {
        Map<String, Object> vars = Map.of(
                "knowledge_point", str(g.getOrDefault("knowledge_point", "")),
                "questions", questions,
                "user_answer", answer,
                "original_dialogue", emptyToWord(str(g.get("original_dialogue")), "无"));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_SCORE_KNOWLEDGE, vars, 0.1, 2048, 1);
        Map<String, Object> r = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "knowledge");
        out.put("total_score", r.getOrDefault("total_score", 0));
        out.put("feedback", r.getOrDefault("feedback", ""));
        out.put("rubric_result", r.getOrDefault("items", new ArrayList<>()));
        out.put("recommended_answer", r.getOrDefault("recommended_answer", new ArrayList<>()));
        return out;
    }

    private Map<String, Object> scoreProject(Map<String, Object> g, String questions, String answer) {
        Map<String, Object> vars = Map.of(
                "project_name", str(g.getOrDefault("project_name", "项目")),
                "topic", str(g.getOrDefault("topic", "拷打")),
                "questions", questions,
                "user_answer", answer);
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_SCORE_PROJECT, vars, 0.1, 2048, 1);
        Map<String, Object> r = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "project");
        out.put("rating", r.getOrDefault("rating", 3));
        out.put("rating_label", r.getOrDefault("rating_label", ""));
        out.put("impression", r.getOrDefault("impression", ""));
        out.put("highlights", r.getOrDefault("highlights", new ArrayList<>()));
        out.put("improvements", r.getOrDefault("improvements", new ArrayList<>()));
        out.put("follow_up_risks", r.getOrDefault("follow_up_risks", new ArrayList<>()));
        out.put("suggested_answer", r.getOrDefault("suggested_answer", new ArrayList<>()));
        return out;
    }

    private Map<String, Object> scoreAlgorithm(Map<String, Object> g, String userAnswer) {
        Map<String, Object> vars = Map.of(
                "title", str(g.getOrDefault("title", "未知算法题")),
                "user_answer", userAnswer.isEmpty() ? "未提供解题过程" : userAnswer,
                "original_dialogue", emptyToWord(str(g.get("original_dialogue")), "无"));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_SCORE_ALGORITHM, vars, 0.1, 2048, 1);
        Map<String, Object> r = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (r == null) {
            return null;
        }
        // leetcode_url/title 由 parser 的 LeetCode skill 提供，这里只取解题点评
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "algorithm");
        out.put("feedback", r.getOrDefault("feedback", ""));
        out.put("description", r.getOrDefault("description", ""));
        out.put("example", r.getOrDefault("example", ""));
        out.put("suggested_approach", r.getOrDefault("suggested_approach", ""));
        return out;
    }

    private Map<String, Object> scoreHr(String questions, String answer) {
        Map<String, Object> vars = Map.of(
                "questions", questions,
                "user_answer", answer);
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_SCORE_HR, vars, 0.1, 1024, 1);
        Map<String, Object> r = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "hr");
        out.put("feedback", r.getOrDefault("feedback", ""));
        out.put("suggestion", r.getOrDefault("suggestion", ""));
        return out;
    }

    // ============================================================
    // 整体分析
    // ============================================================

    /** 复刻 generate_overall_analysis：拼 summary → LLM；失败返回空 map。 */
    private Map<String, Object> generateOverallAnalysis(List<Map<String, Object>> scoredGroups,
                                                        String company, String position) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> g : scoredGroups) {
            String t = str(g.getOrDefault("type", "other"));
            Object srObj = g.get("score_result");
            Map<String, Object> sr = srObj instanceof Map ? castMap(srObj) : null;
            switch (t) {
                case "knowledge" -> {
                    int ts = sr == null ? 0 : intVal(sr.get("total_score"));
                    String scoreInfo = (sr != null && ts != 0) ? "（" + ts + "分）" : "（未评分）";
                    lines.add("📖 知识点：" + str(g.getOrDefault("knowledge_point", "?")) + " " + scoreInfo);
                }
                case "project" -> {
                    int rating = sr == null ? 0 : intVal(sr.get("rating"));
                    String ratingStr = (sr != null && rating != 0) ? "（" + "⭐".repeat(rating) + "）" : "（未评分）";
                    lines.add("🔨 项目拷打：" + str(g.getOrDefault("project_name", "?"))
                            + " · " + str(g.getOrDefault("topic", "?")) + " " + ratingStr);
                }
                case "algorithm" -> lines.add("💻 算法题：" + str(g.getOrDefault("title", "?")));
                case "hr" -> {
                    List<Object> qs = asList(g.get("questions"));
                    List<String> first2 = new ArrayList<>();
                    for (int i = 0; i < Math.min(2, qs.size()); i++) {
                        first2.add(str(qs.get(i)));
                    }
                    lines.add("💬 HR题：" + String.join(", ", first2));
                }
                default -> {
                }
            }
        }

        Map<String, Object> vars = Map.of(
                "company", company == null || company.isEmpty() ? "未知" : company,
                "position", position == null || position.isEmpty() ? "未知" : position,
                "scored_summary", lines.isEmpty() ? "无有效数据" : String.join("\n", lines));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_OVERALL_ANALYSIS, vars, 0.3, 2048, 1);
        Map<String, Object> r = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (r == null) {
            log.error("整体分析生成失败");
            return new LinkedHashMap<>();
        }
        return r;
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static String renderQuestions(Map<String, Object> group) {
        List<Object> qs = asList(group.get("questions"));
        List<String> lines = new ArrayList<>(qs.size());
        for (Object q : qs) {
            lines.add("- " + str(q));
        }
        return String.join("\n", lines);
    }

    private static String emptyToWord(String s, String word) {
        return (s == null || s.isEmpty()) ? word : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        return new ArrayList<>();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
