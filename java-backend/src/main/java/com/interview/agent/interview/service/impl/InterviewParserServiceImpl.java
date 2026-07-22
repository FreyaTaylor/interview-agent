package com.interview.agent.interview.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.dto.PreviewParseResponse;
import com.interview.agent.interview.service.InterviewParserService;
import com.interview.agent.interview.support.InterviewAsrCorrector;
import com.interview.agent.interview.support.FixedSizeTurnChunker;
import com.interview.agent.interview.support.SemanticTurnChunker;
import com.interview.agent.interview.support.TurnChunker;
import com.interview.agent.interview.support.InterviewTurns;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interview 文本预解析服务 —— 完全复刻 Python {@code backend/services/interview_parser.py}。
 *
 * <p><b>流水线（顺序即语义，不得调换/省略）</b>：
 * <ol>
 *   <li>0  原文 → turns（{@link InterviewTurns#splitIntoTurns}）+ 修复（{@code repairTurns}）</li>
 *   <li>0.5 ASR 纠错 + 删噪声 turn（{@link InterviewAsrCorrector}）</li>
 *   <li>1  按 turns 分块（chunk_size=1200）→ 并发 LLM 解析（Semaphore=5，temp=0.1/maxTokens=4096/retry=3）</li>
 *   <li>2  跨段 embedding 边界合并（cos≥0.82）</li>
 *   <li>3  同项目话题合并（LLM）</li>
 *   <li>4  other 去重</li>
 *   <li>5  LeetCode 补全（结构占位，见 spec 差异表）</li>
 *   <li>6  legacy 字段归一</li>
 *   <li>6.5 以"我"为锚重排 turn_ids</li>
 *   <li>6.6 吸收纯面试官孤儿组</li>
 *   <li>7  回填 original_dialogue</li>
 *   <li>8  遗漏问题二次检查（全文级，单/多段均执行）</li>
 * </ol>
 *
 * <p>对齐依据：{@code java-backend/docs/modules/interview-parser-python-spec.md}。</p>
 */
@Service
public class InterviewParserServiceImpl implements InterviewParserService {

    private static final Logger log = LoggerFactory.getLogger(InterviewParserServiceImpl.class);
    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {
    };

    private static final double BOUNDARY_SIM_THRESHOLD = 0.82;                 // embedding 余弦阈值
    private static final int PARSE_PARALLEL = 5;                               // Semaphore(5)
    /** 行首「说话人X：」标签（兼容字母 A/B 与数字 0/1，中英文冒号）。 */
    private static final Pattern SPEAKER_LABEL_RE = Pattern.compile("(?m)^(说话人[A-Z0-9]+)[：:]");

    private final LlmInvoker llmInvoker;
    private final EmbeddingService embeddingService;
    private final InterviewAsrCorrector asrCorrector;
    /** 预分块策略：定长（现网默认）与语义切分（对照支）。 */
    private final FixedSizeTurnChunker fixedChunker;
    private final SemanticTurnChunker semanticChunker;
    /** 默认策略（配置 {@code iagent.interview.chunk-strategy}，默认 fixed）。 */
    private final String defaultChunkStrategy;

    public InterviewParserServiceImpl(LlmInvoker llmInvoker,
                                      EmbeddingService embeddingService,
                                      InterviewAsrCorrector asrCorrector,
                                      FixedSizeTurnChunker fixedChunker,
                                      SemanticTurnChunker semanticChunker,
                                      @Value("${iagent.interview.chunk-strategy:fixed}") String defaultChunkStrategy) {
        this.llmInvoker = llmInvoker;
        this.embeddingService = embeddingService;
        this.asrCorrector = asrCorrector;
        this.fixedChunker = fixedChunker;
        this.semanticChunker = semanticChunker;
        this.defaultChunkStrategy = defaultChunkStrategy;
    }

    /** 按名选预分块策略；null/空 → 配置默认；未知名安全回退 fixed。 */
    private TurnChunker selectChunker(String requested) {
        String s = (requested == null || requested.isBlank()) ? defaultChunkStrategy : requested.trim();
        return "semantic".equalsIgnoreCase(s) ? semanticChunker : fixedChunker;
    }

    @Override
    public PreviewParseResponse parse(String text) {
        return parse(text, null);
    }

    @Override
    public PreviewParseResponse parse(String text, String chunkStrategy) {
        String rawText = text == null ? "" : text;

        // 0.0）说话人角色归一化：把「说话人A/B」「说话人0/1」统一替换为「面试官/我」。
        //      —— 这步从 ASR 移到解析入口，统一覆盖音频上传 / 粘贴文本 / 草稿重载所有来源，
        //      因为 splitIntoTurns 只认「面试官/我」，未归一化的说话人文本会塌成单个 turn。
        rawText = normalizeSpeakerRoles(rawText);

        // 0）原文 → 结构化 turns（全局唯一 id）+ 修复破碎 turn
        List<Map<String, Object>> turns = InterviewTurns.repairTurns(InterviewTurns.splitIntoTurns(rawText));
        if (turns.isEmpty()) {
            return new PreviewParseResponse(List.of(), List.of(), "面试文本为空");
        }

        // 0.5）ASR 纠错 + 删短噪声 turn（失败内部回退原 turns）
        turns = asrCorrector.correct(turns);
        if (turns.isEmpty()) {
            return new PreviewParseResponse(List.of(), List.of(), "纠错后内容为空");
        }

        // 1）按策略预分块 → 并发解析（定长 fixed / 语义 semantic）
        TurnChunker chunker = selectChunker(chunkStrategy);
        List<List<Map<String, Object>>> chunks = chunker.chunk(turns);
        int avgChars = chunks.isEmpty() ? 0 : rawText.length() / chunks.size();
        log.info("面试文本 {} 字 → {} 个 turns，[{}] 分 {} 段并发解析", rawText.length(), turns.size(), chunker.strategy(), chunks.size());
        List<List<Map<String, Object>>> chunkResults = parseChunksParallel(chunks);
        int rawGroups = chunkResults.stream().mapToInt(g -> g == null ? 0 : g.size()).sum();

        // 2）跨段边界合并
        List<Map<String, Object>> all = mergeByEmbeddingBoundary(chunkResults);
        int boundaryMerges = rawGroups - all.size();
        if (all.isEmpty()) {
            return new PreviewParseResponse(turns, List.of(), "解析失败，请重试");
        }

        // 3）同项目话题合并
        all = mergeProjectTopics(all);
        // 4）other 去重
        all = dedupOtherGroups(all);
        // 5）legacy 字段（算法题 LeetCode 富化已挑到 finalize，见 InterviewParseServiceImpl）
        all = normalizeToLegacySchema(all);
        // 6.5）以"我"为锚重排 turn_ids
        all = regroupByAnswerAnchors(all, turns);
        // 6.6）吸收纯面试官 + 紧贴下一组的孤儿组
        all = absorbOrphanInterviewerGroups(all, turns);
        // 7）回填 original_dialogue
        backfillOriginalDialogue(all, turns);

        // 8）遗漏问题二次检查（全文级）。
        //    原限「仅单段」——长面试(>1200字)分多段时被完全跳过，是漏题的主因；
        //    missed-check prompt 吃整段 raw_text + 已解析问题清单，与分段数无关，故对多段同样执行。
        appendMissedQuestions(all, rawText);

        // 对比指标：同一文本跑两策略可并排看 chunk 数/平均长度/边界合并次数/最终 group 数
        log.info("[分块对比] strategy={} chunks={} avgChars={} rawGroups={} boundaryMerges={} finalGroups={}",
                chunker.strategy(), chunks.size(), avgChars, rawGroups, boundaryMerges, all.size());

        return new PreviewParseResponse(turns, all, "");
    }

    // ============================================================
    // 0.0）说话人角色归一化（说话人A/B、说话人0/1 → 面试官/我）
    // ============================================================

    /**
     * 角色归一化（复刻 Python {@code _normalize_speaker_roles}）。
     *
     * <p>ASR 分离出的「说话人A/B」无法区分面试官/候选人，用 LLM 看开头 1500 字判断，
     * 再统一替换为「面试官 / 我」；任何异常/无标签/单标签均安全回退原文。</p>
     *
     * <p>放在解析入口而非 ASR：统一覆盖音频上传、粘贴文本、草稿重载所有来源，
     * 避免未归一化的「说话人X」文本因 {@code splitIntoTurns} 只认「面试官/我」而塌成单个 turn。</p>
     */
    private String normalizeSpeakerRoles(String text) {
        if (text == null || text.isEmpty() || !text.contains("说话人")) {
            return text;
        }
        // 收集出现过的说话人标签（去重升序）
        TreeSet<String> labelSet = new TreeSet<>();
        Matcher m = SPEAKER_LABEL_RE.matcher(text);
        while (m.find()) {
            labelSet.add(m.group(1));
        }
        List<String> labels = new ArrayList<>(labelSet);
        if (labels.size() < 2) {
            // 单人录音：直接当成候选人自述
            if (labels.size() == 1) {
                return text.replace(labels.get(0) + "：", "我：").replace(labels.get(0) + ":", "我:");
            }
            return text;
        }

        // 取开头一段送给 LLM 判断哪个标签是面试官
        String snippet = text.substring(0, Math.min(1500, text.length()));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_ASR_ROLE_NORMALIZE, Map.of("snippet", snippet), 0.0, 200, 1);
        Map<String, Object> data = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (data == null) {
            log.warn("LLM 角色识别返回无 JSON，保留原始说话人标签");
            return text;
        }
        String interviewer = data.get("interviewer") == null ? "" : data.get("interviewer").toString();
        String candidate = data.get("candidate") == null ? "" : data.get("candidate").toString();
        if (!labels.contains(interviewer) || !labels.contains(candidate) || interviewer.equals(candidate)) {
            log.warn("LLM 角色识别结果异常: interviewer={}, candidate={}, labels={}", interviewer, candidate, labels);
            return text;
        }

        // 替换：先用占位符避免互相覆盖
        String out = text;
        out = out.replace(interviewer + "：", "__IV__：").replace(interviewer + ":", "__IV__:");
        out = out.replace(candidate + "：", "__ME__：").replace(candidate + ":", "__ME__:");
        out = out.replace("__IV__", "面试官").replace("__ME__", "我");
        log.info("角色归一化完成: {}→面试官, {}→我", interviewer, candidate);
        return out;
    }

    // ============================================================
    // 1）分段并发解析
    // ============================================================

    private List<List<Map<String, Object>>> parseChunksParallel(List<List<Map<String, Object>>> chunks) {
        int parallelism = Math.min(PARSE_PARALLEL, Math.max(1, chunks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                final int idx = i;
                final List<Map<String, Object>> chunk = chunks.get(i);
                futures.add(CompletableFuture.supplyAsync(
                        () -> parseSingleChunk(chunk, idx, chunks.size()), pool));
            }
            List<List<Map<String, Object>>> results = new ArrayList<>(chunks.size());
            for (CompletableFuture<List<Map<String, Object>>> f : futures) {
                results.add(f.join());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /** 单段 LLM 解析；失败返回空 list（不中断整单）。turn_ids 裁剪到本段合法范围并去重升序。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSingleChunk(List<Map<String, Object>> chunk, int idx, int total) {
        String context = "";
        if (total > 1) {
            context = "\n## 当前段位置\n这是面试记录的第 " + (idx + 1) + "/" + total + " 段，请只解析本段内容。"
                    + "本段已按面试官提问作为边界切分，开头是一个完整的提问；"
                    + "如有话题与其他段重叠由系统后处理合并，本段不必猜测上下文。";
        }
        String chunkText = InterviewTurns.renderTurnsForLlm(chunk);
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_PARSE,
                Map.of("context", context, "raw_text", chunkText),
                0.1, 4096, 3);

        List<Map<String, Object>> groups = llmInvoker.invoke(spec, raw -> {
            Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
            Object g = data == null ? null : data.get("groups");
            return g instanceof List ? (List<Map<String, Object>>) g : new ArrayList<Map<String, Object>>();
        }).orElseGet(ArrayList::new);

        // 校验/裁剪 turn_ids 范围，升序去重
        TreeSet<Integer> validIds = new TreeSet<>();
        for (Map<String, Object> t : chunk) {
            validIds.add(intVal(t.get("id")));
        }
        for (Map<String, Object> grp : groups) {
            TreeSet<Integer> cleaned = new TreeSet<>();
            for (Object x : asIntList(grp.get("turn_ids"))) {
                int v = (Integer) x;
                if (validIds.contains(v)) {
                    cleaned.add(v);
                }
            }
            grp.put("turn_ids", new ArrayList<>(cleaned));
        }
        return groups;
    }

    // ============================================================
    // 2）跨段 embedding 边界合并
    // ============================================================

    private List<Map<String, Object>> mergeByEmbeddingBoundary(List<List<Map<String, Object>>> chunkResults) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (int ci = 0; ci < chunkResults.size(); ci++) {
            List<Map<String, Object>> groups = chunkResults.get(ci);
            if (groups == null || groups.isEmpty()) {
                continue;
            }
            if (merged.isEmpty()) {
                merged.addAll(groups);
                continue;
            }
            Map<String, Object> last = merged.get(merged.size() - 1);
            Map<String, Object> first = groups.get(0);
            boolean isDup = false;

            if (isSameGroup(last, first)) {
                isDup = true;
                log.info("分段{}开头与上段「{}」同名直连，合并对话片段", ci + 1, str(last.get("tag")));
            } else if (str(last.get("category")).equals(str(first.get("category")))) {
                try {
                    float[] embA = embeddingService.embed(groupSignature(last));
                    float[] embB = embeddingService.embed(groupSignature(first));
                    double sim = cosine(embA, embB);
                    if (sim >= BOUNDARY_SIM_THRESHOLD) {
                        isDup = true;
                        log.info("分段{}开头与上段「{}」语义相似(cos={})，合并对话片段",
                                ci + 1, str(last.get("tag")), String.format("%.2f", sim));
                    }
                } catch (Exception e) {
                    log.warn("边界 embedding 判定失败（不影响主流程）: {}", e.getMessage());
                }
            }

            if (isDup) {
                mergeContinuation(last, first);
                merged.addAll(groups.subList(1, groups.size()));
            } else {
                merged.addAll(groups);
            }
        }
        return merged;
    }

    /** 快速判同：category + tag 完全相同（project 还要 project_name 相同）。 */
    private boolean isSameGroup(Map<String, Object> a, Map<String, Object> b) {
        if (!str(a.get("category")).equals(str(b.get("category")))) {
            return false;
        }
        if ("project".equals(str(a.get("category")))) {
            return str(a.get("project_name")).strip().equals(str(b.get("project_name")).strip())
                    && str(a.get("tag")).strip().equals(str(b.get("tag")).strip());
        }
        return str(a.get("tag")).strip().equals(str(b.get("tag")).strip());
    }

    /** 构造 embedding 话题签名：tag + 首问截断。 */
    private String groupSignature(Map<String, Object> g) {
        String cat = str(g.get("category"));
        String tag = str(g.get("tag")).strip();
        String proj = str(g.get("project_name")).strip();
        String firstQ = firstQuestion(g);
        firstQ = firstQ.substring(0, Math.min(120, firstQ.length())).strip();
        if ("project".equals(cat)) {
            return "[项目]" + proj + "·" + tag + ": " + firstQ;
        }
        return "[" + cat + "]" + tag + ": " + firstQ;
    }

    /** 把 cont 视为 base 的延续段，拼 questions/user_answer/original_dialogue/turn_ids。 */
    @SuppressWarnings("unchecked")
    private void mergeContinuation(Map<String, Object> base, Map<String, Object> cont) {
        List<Object> baseQ = (List<Object>) base.computeIfAbsent("questions", k -> new ArrayList<>());
        for (Object q : asList(cont.get("questions"))) {
            if (q != null && !baseQ.contains(q)) {
                baseQ.add(q);
            }
        }
        String contAns = str(cont.get("user_answer")).strip();
        if (!contAns.isEmpty()) {
            String prev = str(base.get("user_answer")).strip();
            base.put("user_answer", prev.isEmpty() ? cont.get("user_answer") : prev + "\n" + contAns);
        }
        String contDlg = str(cont.get("original_dialogue")).strip();
        if (!contDlg.isEmpty()) {
            String prev = str(base.get("original_dialogue")).strip();
            base.put("original_dialogue", prev.isEmpty() ? cont.get("original_dialogue") : prev + "\n" + contDlg);
        }
        base.put("turn_ids", unionSorted(base.get("turn_ids"), cont.get("turn_ids")));
    }

    // ============================================================
    // 3）同项目话题合并
    // ============================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mergeProjectTopics(List<Map<String, Object>> groups) {
        List<Map<String, Object>> nonProject = new ArrayList<>();
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if ("project".equals(str(g.get("category")))) {
                projects.add(g);
            } else {
                nonProject.add(g);
            }
        }
        if (projects.size() <= 1) {
            return groups;
        }

        Map<String, List<Map<String, Object>>> byName = new LinkedHashMap<>();
        for (Map<String, Object> g : projects) {
            String name = str(g.get("project_name")).strip();
            if (name.isEmpty()) {
                name = "未命名项目";
            }
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(g);
        }

        List<Map<String, Object>> mergedProjects = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byName.entrySet()) {
            String projName = e.getKey();
            List<Map<String, Object>> topics = e.getValue();
            if (topics.size() <= 1) {
                mergedProjects.addAll(topics);
                continue;
            }

            StringBuilder topicList = new StringBuilder();
            for (int i = 0; i < topics.size(); i++) {
                topicList.append(i + 1).append(". ").append(str(topics.get(i).getOrDefault("tag", "未知"))).append("\n");
            }
            LlmInvoker.Spec spec = new LlmInvoker.Spec(
                    PromptKeys.INTERVIEW_MERGE_PROJECT_TOPICS,
                    Map.of("proj_name", projName, "topic_list", topicList.toString().stripTrailing()),
                    0.1, 1024, 1);

            Map<String, Object> result = llmInvoker.invoke(spec,
                    raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
            if (result == null) {
                log.warn("项目「{}」话题合并失败（保留原始）", projName);
                mergedProjects.addAll(topics);
                continue;
            }

            List<Object> mergeGroups = asList(result.get("merge_groups"));
            for (Object mgObj : mergeGroups) {
                List<Integer> indices = new ArrayList<>();
                for (Object idxObj : asList(mgObj)) {
                    Integer one = tryInt(idxObj);
                    if (one != null && one >= 1 && one <= topics.size()) {
                        indices.add(one - 1);
                    }
                }
                if (indices.isEmpty()) {
                    continue;
                }
                Map<String, Object> base = new LinkedHashMap<>(topics.get(indices.get(0)));
                for (int k = 1; k < indices.size(); k++) {
                    Map<String, Object> t = topics.get(indices.get(k));
                    List<Object> bq = new ArrayList<>(asList(base.get("questions")));
                    bq.addAll(asList(t.get("questions")));
                    base.put("questions", bq);
                    String ta = str(t.get("user_answer")).strip();
                    if (!ta.isEmpty()) {
                        String ex = str(base.get("user_answer")).strip();
                        base.put("user_answer", ex.isEmpty() ? t.get("user_answer") : ex + "\n" + ta);
                    }
                    String td = str(t.get("original_dialogue")).strip();
                    if (!td.isEmpty()) {
                        String ex = str(base.get("original_dialogue")).strip();
                        base.put("original_dialogue", ex.isEmpty() ? t.get("original_dialogue") : ex + "\n---\n" + td);
                    }
                    base.put("turn_ids", unionSorted(base.get("turn_ids"), t.get("turn_ids")));
                }
                // questions 去重保序
                List<Object> uniqueQ = new ArrayList<>();
                for (Object q : asList(base.get("questions"))) {
                    if (!uniqueQ.contains(q)) {
                        uniqueQ.add(q);
                    }
                }
                base.put("questions", uniqueQ);
                mergedProjects.add(base);
            }
            log.info("项目「{}」: {}个话题 → {}组", projName, topics.size(), mergeGroups.size());
        }

        List<Map<String, Object>> out = new ArrayList<>(nonProject);
        out.addAll(mergedProjects);
        return out;
    }

    // ============================================================
    // 4）other 去重
    // ============================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dedupOtherGroups(List<Map<String, Object>> groups) {
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashMap<String, Map<String, Object>> otherSeen = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            if (!"other".equals(str(g.get("category")))) {
                result.add(g);
                continue;
            }
            String tag = str(g.getOrDefault("tag", "misc"));
            String firstQ = firstQuestion(g).strip().toLowerCase();
            String key = tag + "::" + firstQ;
            if (otherSeen.containsKey(key)) {
                Map<String, Object> existing = otherSeen.get(key);
                List<Object> eq = (List<Object>) existing.computeIfAbsent("questions", k -> new ArrayList<>());
                for (Object q : asList(g.get("questions"))) {
                    if (!eq.contains(q)) {
                        eq.add(q);
                    }
                }
                if (!str(g.get("user_answer")).isEmpty() && str(existing.get("user_answer")).isEmpty()) {
                    existing.put("user_answer", g.get("user_answer"));
                }
                if (!str(g.get("original_dialogue")).isEmpty() && str(existing.get("original_dialogue")).isEmpty()) {
                    existing.put("original_dialogue", g.get("original_dialogue"));
                }
                existing.put("turn_ids", unionSorted(existing.get("turn_ids"), g.get("turn_ids")));
            } else {
                otherSeen.put(key, g);
            }
        }
        result.addAll(otherSeen.values());
        return result;
    }

    // ============================================================
    // 6）legacy 字段归一
    // ============================================================

    private List<Map<String, Object>> normalizeToLegacySchema(List<Map<String, Object>> groups) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> src : groups) {
            Map<String, Object> g = new LinkedHashMap<>(src);
            String cat = str(g.get("category"));
            String tag = g.get("tag") == null ? null : str(g.get("tag"));
            switch (cat) {
                case "knowledge" -> {
                    g.put("type", "knowledge");
                    g.put("knowledge_point", (tag == null || tag.isEmpty()) ? "未命名" : tag);
                }
                case "project" -> {
                    g.put("type", "project");
                    g.put("topic", tag == null ? "" : tag);
                }
                case "other" -> {
                    if ("leetcode".equals(tag)) {
                        g.put("type", "algorithm");
                        Object lt = g.get("leetcode_title");
                        g.put("title", lt != null ? lt : firstQuestionOrDefault(g, "未知算法题"));
                    } else if ("hr".equals(tag)) {
                        g.put("type", "hr");
                    } else {
                        g.put("type", "other");
                    }
                }
                default -> g.put("type", "other");
            }
            out.add(g);
        }
        return out;
    }

    // ============================================================
    // 6.5）以"我"为锚重排 turn_ids
    // ============================================================

    private List<Map<String, Object>> regroupByAnswerAnchors(List<Map<String, Object>> groups,
                                                             List<Map<String, Object>> turns) {
        if (groups.isEmpty() || turns.isEmpty()) {
            return groups;
        }
        Map<Integer, String> speakers = new LinkedHashMap<>();
        for (Map<String, Object> t : turns) {
            speakers.put(intVal(t.get("id")), str(t.get("speaker")));
        }
        List<Integer> sortedTids = new ArrayList<>(new TreeSet<>(speakers.keySet()));

        Map<Integer, Integer> meToGroup = new LinkedHashMap<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            for (Object tidObj : asIntList(groups.get(gi).get("turn_ids"))) {
                int tid = (Integer) tidObj;
                if ("我".equals(speakers.get(tid)) && !meToGroup.containsKey(tid)) {
                    meToGroup.put(tid, gi);
                }
            }
        }
        if (meToGroup.isEmpty()) {
            return groups;
        }

        Map<Integer, List<Integer>> newIds = new LinkedHashMap<>();
        boolean[] hasAnchor = new boolean[groups.size()];
        for (int gi = 0; gi < groups.size(); gi++) {
            newIds.put(gi, new ArrayList<>());
        }
        int lastAnchorPos = -1;
        for (int pos = 0; pos < sortedTids.size(); pos++) {
            int tid = sortedTids.get(pos);
            if (!"我".equals(speakers.get(tid))) {
                continue;
            }
            Integer gi = meToGroup.get(tid);
            if (gi == null) {
                continue;
            }
            hasAnchor[gi] = true;
            for (int p = lastAnchorPos + 1; p <= pos; p++) {
                newIds.get(gi).add(sortedTids.get(p));
            }
            lastAnchorPos = pos;
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            if (hasAnchor[gi]) {
                groups.get(gi).put("turn_ids", new ArrayList<>(new TreeSet<>(newIds.get(gi))));
            }
        }
        return groups;
    }

    // ============================================================
    // 6.6）吸收纯面试官 + 位于下一组之前的孤儿组
    // ============================================================

    private List<Map<String, Object>> absorbOrphanInterviewerGroups(List<Map<String, Object>> groups,
                                                                    List<Map<String, Object>> turns) {
        if (groups.isEmpty()) {
            return groups;
        }
        Map<Integer, String> speakers = new LinkedHashMap<>();
        for (Map<String, Object> t : turns) {
            speakers.put(intVal(t.get("id")), str(t.get("speaker")));
        }
        // (minTurnId, 原始 index) 按首 turn 升序
        List<int[]> order = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            List<Object> ids = asIntList(groups.get(gi).get("turn_ids"));
            int minId = ids.isEmpty() ? Integer.MAX_VALUE : (Integer) ids.get(0);
            order.add(new int[]{minId, gi});
        }
        order.sort((x, y) -> Integer.compare(x[0], y[0]));

        java.util.Set<Integer> drop = new java.util.HashSet<>();
        for (int i = 0; i < order.size() - 1; i++) {
            int gi = order.get(i)[1];
            Map<String, Object> g = groups.get(gi);
            List<Object> ids = asIntList(g.get("turn_ids"));
            if (ids.isEmpty() || drop.contains(gi)) {
                continue;
            }
            boolean allInterviewer = true;
            for (Object tidObj : ids) {
                if (!"面试官".equals(speakers.get((Integer) tidObj))) {
                    allInterviewer = false;
                    break;
                }
            }
            if (!allInterviewer) {
                continue;
            }
            Map<String, Object> nextG = groups.get(order.get(i + 1)[1]);
            List<Object> nextIds = asIntList(nextG.get("turn_ids"));
            if (nextIds.isEmpty()) {
                continue;
            }
            int maxId = (Integer) ids.get(ids.size() - 1);
            int nextMin = (Integer) nextIds.get(0);
            // 放宽：只要孤儿组整体位于下一组之前（max < nextMin）即合并，
            // 而非严格紧邻 max+1==nextMin（兼容答案被误划到后组造成的缝隙）
            if (maxId >= nextMin) {
                continue;
            }
            nextG.put("turn_ids", unionSorted(nextG.get("turn_ids"), g.get("turn_ids")));
            drop.add(gi);
        }
        if (drop.isEmpty()) {
            return groups;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            if (!drop.contains(gi)) {
                out.add(groups.get(gi));
            }
        }
        return out;
    }

    // ============================================================
    // 7）回填 original_dialogue
    // ============================================================

    private void backfillOriginalDialogue(List<Map<String, Object>> groups, List<Map<String, Object>> turns) {
        Map<Integer, Map<String, Object>> turnById = new LinkedHashMap<>();
        for (Map<String, Object> t : turns) {
            turnById.put(intVal(t.get("id")), t);
        }
        for (Map<String, Object> g : groups) {
            if (!str(g.get("original_dialogue")).strip().isEmpty()) {
                continue;
            }
            List<Object> ids = asIntList(g.get("turn_ids"));
            if (ids.isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Object tidObj : ids) {
                Map<String, Object> t = turnById.get((Integer) tidObj);
                if (t == null) {
                    continue;
                }
                String speaker = str(t.get("speaker"));
                String prefix = speaker.isEmpty() ? "" : speaker + "：";
                lines.add(prefix + str(t.get("content")));
            }
            if (!lines.isEmpty()) {
                g.put("original_dialogue", String.join("\n", lines));
            }
        }
    }

    // ============================================================
    // 8）遗漏二次检查（全文级，单/多段均执行）
    // ============================================================

    private void appendMissedQuestions(List<Map<String, Object>> groups, String rawText) {
        List<Object> allQuestions = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            allQuestions.addAll(asList(g.get("questions")));
        }
        StringBuilder qList = new StringBuilder();
        for (int i = 0; i < allQuestions.size(); i++) {
            qList.append(i + 1).append(". ").append(str(allQuestions.get(i))).append("\n");
        }
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_MISSED_CHECK,
                Map.of("raw_text", rawText,
                        "question_count", allQuestions.size(),
                        "question_list", qList.toString().stripTrailing()),
                0.1, 2048, 1);
        Map<String, Object> result = llmInvoker.invoke(spec,
                raw -> JsonUtil.extractJson(raw, JSON_OBJ)).orElse(null);
        if (result == null) {
            log.warn("二次检查失败（不影响主流程）");
            return;
        }
        List<Object> missed = asList(result.get("missed"));
        if (missed.isEmpty()) {
            return;
        }
        log.info("二次检查发现 {} 个遗漏问题", missed.size());
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("category", "other");
        g.put("tag", "misc");
        g.put("type", "other");
        g.put("questions", new ArrayList<>(missed));
        g.put("user_answer", "");
        g.put("original_dialogue", "");
        g.put("turn_ids", new ArrayList<>());
        groups.add(g);
    }

    // ============================================================
    // 小工具
    // ============================================================

    private String firstQuestion(Map<String, Object> g) {
        List<Object> qs = asList(g.get("questions"));
        return qs.isEmpty() ? "" : str(qs.get(0));
    }

    private String firstQuestionOrDefault(Map<String, Object> g, String def) {
        List<Object> qs = asList(g.get("questions"));
        return qs.isEmpty() ? def : str(qs.get(0));
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** turn_ids 并集升序（结果为 List<Integer>）。 */
    private static List<Object> unionSorted(Object a, Object b) {
        TreeSet<Integer> set = new TreeSet<>();
        for (Object x : asIntList(a)) {
            set.add((Integer) x);
        }
        for (Object x : asIntList(b)) {
            set.add((Integer) x);
        }
        return new ArrayList<>(set);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        return new ArrayList<>();
    }

    /** 把任意 list 转成 List<Integer>（非数值/不可解析的丢弃）。 */
    private static List<Object> asIntList(Object o) {
        List<Object> out = new ArrayList<>();
        for (Object x : asList(o)) {
            Integer v = tryInt(x);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
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
