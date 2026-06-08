package com.interview.agent.project.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.project.entity.Project;
import com.interview.agent.project.entity.ProjectUserProfile;
import com.interview.agent.project.mapper.ProjectMapper;
import com.interview.agent.project.mapper.ProjectUserProfileMapper;
import com.interview.agent.project.service.ProjectProfileService;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProjectProfileService} 实现 —— @Async 异步抽取 + 乐观锁更新画像。
 *
 * <p>线程隔离：默认 SimpleAsyncTaskExecutor 每次新线程；不复用主请求线程的 DB/事务/上下文。
 * 主请求事务提交后调用本方法，{@code WHERE version=?} 看到的是已提交版本，不会脏读。
 *
 * <p>幂等：单次 {@code extractAndApply} 最多重试 3 次，每次冲突重读 profile；
 * 全部失败仅 WARN 日志，下次拷打 finish 会重新抽取。
 *
 * <p>提示词来自 {@link PromptKeys#PROJECT_EXTRACT_PROFILE}（V16 migration seed）。
 *
 * <p>{@code applyFactsPatch} 对照 Python 的纯函数实现。
 */
@Service
public class ProjectProfileServiceImpl implements ProjectProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProjectProfileServiceImpl.class);
    private static final double TEMP_EXTRACT = 0.2;
    private static final int MAX_TOKENS_EXTRACT = 2048;
    private static final int LLM_MAX_RETRY = 2;
    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};

    private final ProjectUserProfileMapper profileMapper;
    private final ProjectMapper projectMapper;
    private final LlmInvoker llmInvoker;

    public ProjectProfileServiceImpl(ProjectUserProfileMapper profileMapper,
                                     ProjectMapper projectMapper,
                                     LlmInvoker llmInvoker) {
        this.profileMapper = profileMapper;
        this.projectMapper = projectMapper;
        this.llmInvoker = llmInvoker;
    }

    @Async
    @Override
    public void extractAndApply(long projectId, String topic, String question, String answer,
                                String scoringSummary, List<String> missedKeyPoints, long userId) {
        Project project = projectMapper.findById(projectId).orElse(null);
        if (project == null) {
            log.warn("extractAndApply: project {} 不存在", projectId);
            return;
        }

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            // 每轮都重读最新版本（应对并发冲突）
            profileMapper.ensureRowExists(projectId, userId);
            ProjectUserProfile profile = profileMapper.findByProjectUser(projectId, userId).orElse(null);
            if (profile == null) {
                log.warn("extractAndApply: profile 读取失败 project={} user={}", projectId, userId);
                return;
            }

            Map<String, Object> patch = callExtractLlm(project, profile, topic, question, answer,
                    scoringSummary, missedKeyPoints).orElse(null);
            if (patch == null) {
                // LLM 失败不重试（重试很难有不同结果）；下次 finish 再抽取
                return;
            }

            List<String> currentFacts = asStringList(profile.projectFacts());

            Object factsPatchRaw = patch.get("facts_patch");
            Map<String, Object> factsPatch = factsPatchRaw instanceof Map<?, ?> m
                    ? castStringObjMap(m) : Map.of();
            List<String> newFacts = applyFactsPatch(currentFacts, factsPatch);

            int affected = profileMapper.updateFactsWithLock(profile.id(), profile.version(), newFacts);
            if (affected == 1) {
                log.info("ProjectUserProfile {} 更新成功 v{} → v{}", profile.id(),
                        profile.version(), profile.version() + 1);
                return;
            }
            log.info("ProjectUserProfile {} 版本冲突，重试 {}/{}", profile.id(), attempt, MAX_RETRY);
        }
        log.warn("extractAndApply 在 {} 次重试后仍失败 (project={})", MAX_RETRY, projectId);
    }

    // ============================================================
    // LLM 调用
    // ============================================================

    private Optional<Map<String, Object>> callExtractLlm(Project project, ProjectUserProfile profile,
                                                        String topic, String question, String answer,
                                                        String scoringSummary, List<String> missedKeyPoints) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("project_name", safe(project.name()));
        vars.put("project_description", safe(project.description()));
        vars.put("current_facts", formatFactsForPrompt(asStringList(profile.projectFacts())));
        vars.put("topic", topic == null || topic.isBlank() ? "未分类" : topic);
        vars.put("question", safe(question));
        vars.put("answer", safe(answer));
        vars.put("scoring_summary", safe(scoringSummary));
        vars.put("missed_key_points", (missedKeyPoints == null || missedKeyPoints.isEmpty())
                ? "（无）" : String.join("、", missedKeyPoints));

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_EXTRACT_PROFILE, vars,
                TEMP_EXTRACT, MAX_TOKENS_EXTRACT, LLM_MAX_RETRY);
        return llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ));
    }

    // ============================================================
    // patch 纯函数（对照 Python 同名函数 1:1 实现）
    // ============================================================

    /**
     * facts: List&lt;String&gt;。patch: {add, update:[{old,new}], remove}。
     * 顺序：update（按 old 原文匹配并就地替换；未匹配则当 add 兜底）→ remove（按原文）→ add（去重）→ 截断保留最新 MAX_FACTS 条。
     */
    static List<String> applyFactsPatch(List<String> facts, Map<String, Object> patch) {
        List<String> out = new ArrayList<>();
        if (facts != null) {
            for (String f : facts) {
                if (f != null && !f.isEmpty()) out.add(f);
            }
        }
        Map<String, Object> p = patch == null ? Map.of() : patch;

        // 1) update
        for (Object op : asListOfMaps(p.get("update"))) {
            if (!(op instanceof Map<?, ?> m)) continue;
            String oldStr = trimOrEmpty(m.get("old"));
            String newStr = trimOrEmpty(m.get("new"));
            if (oldStr.isEmpty() || newStr.isEmpty()) continue;
            boolean matched = false;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).trim().equals(oldStr)) {
                    out.set(i, newStr);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.add(newStr); // 兜底防止信息丢失
            }
        }

        // 2) remove
        Set<String> rm = new LinkedHashSet<>();
        for (Object x : asListOfObjects(p.get("remove"))) {
            String s = trimOrEmpty(x);
            if (!s.isEmpty()) rm.add(s);
        }
        if (!rm.isEmpty()) {
            out.removeIf(item -> rm.contains(item.trim()));
        }

        // 3) add（去重）
        Set<String> existing = new LinkedHashSet<>();
        for (String item : out) existing.add(item.trim());
        for (Object x : asListOfObjects(p.get("add"))) {
            String s = trimOrEmpty(x);
            if (!s.isEmpty() && !existing.contains(s)) {
                out.add(s);
                existing.add(s);
            }
        }

        // 4) 截断：保留最新 MAX_FACTS
        if (out.size() > MAX_FACTS) {
            return new ArrayList<>(out.subList(out.size() - MAX_FACTS, out.size()));
        }
        return out;
    }

    // ============================================================
    // prompt 渲染 helpers
    // ============================================================

    static String formatFactsForPrompt(List<String> facts) {
        if (facts == null || facts.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (String f : facts) {
            if (f == null || f.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(idx++).append(". ").append(f);
        }
        return sb.length() == 0 ? "（暂无）" : sb.toString();
    }

    // ============================================================
    // 类型转换 helpers
    // ============================================================

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static List<Object> asListOfObjects(Object raw) {
        List<Object> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            out.addAll(list);
        }
        return out;
    }

    private static List<Object> asListOfMaps(Object raw) {
        return asListOfObjects(raw);
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String trimOrEmpty(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

}
