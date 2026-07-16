package com.interview.agent.interview.support;

import com.interview.agent.infra.llm.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义预分块（对照支，默认不启用）—— 只在<b>面试官提问 turn</b> 处考虑切段，
 * 用「相邻两个提问的 embedding 余弦」判话题切换，落在话题边界上，让跨段裂题更少。
 *
 * <p>切段规则（在面试官 turn T 且当前段非空时判）：
 * <ol>
 *   <li><b>硬上限</b>：当前段 + T 超 {@link #maxChars} → 切（防超 LLM 上下文）；</li>
 *   <li><b>语义边界</b>：当前段已达 {@link #minChars} 且 {@code cos(embed(T), embed(上一个面试官 turn)) < }{@link #threshold} → 话题切换 → 切。</li>
 * </ol>
 * 非面试官 turn（我/空）只累加、绝不触发切段——保证每段以面试官提问开头。
 *
 * <p><b>成本</b>：只对面试官 turn 调 {@link EmbeddingService#embed}（每个至多一次），非全 turn。
 * embedding 失败安全降级为「无语义信号」（只剩硬上限生效），不中断。
 *
 * <p>不变量与 {@link FixedSizeTurnChunker} 一致：turn 原子、各段不相交且并起来 == 全部 turns、顺序保持。
 */
@Component
public class SemanticTurnChunker implements TurnChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticTurnChunker.class);
    private static final String INTERVIEWER = "面试官";
    /** 提问签名截断长度（与边界合并 groupSignature 对齐）。 */
    private static final int SIG_MAX = 120;
    /** 单 turn 前缀开销（与 chunkTurns 对齐）。 */
    private static final int TURN_OVERHEAD = 8;

    private final EmbeddingService embeddingService;
    private final int maxChars;
    private final int minChars;
    private final double threshold;

    @Autowired
    public SemanticTurnChunker(
            EmbeddingService embeddingService,
            @Value("${iagent.interview.semantic.max-chars:1600}") int maxChars,
            @Value("${iagent.interview.semantic.min-chars:400}") int minChars,
            @Value("${iagent.interview.semantic.threshold:0.6}") double threshold) {
        this.embeddingService = embeddingService;
        this.maxChars = maxChars;
        this.minChars = minChars;
        this.threshold = threshold;
    }

    @Override
    public String strategy() {
        return "semantic";
    }

    @Override
    public List<List<Map<String, Object>>> chunk(List<Map<String, Object>> turns) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        if (turns == null || turns.isEmpty()) {
            return chunks;
        }

        // 一次性批量 embed 所有「面试官」turn 的提问签名（只嵌候选边界，省调用）。
        // key = turn 在 turns 里的下标；embedding 失败时该 map 为空 → 只剩硬上限生效（安全降级）。
        Map<Integer, float[]> interviewerEmb = precomputeInterviewerEmbeddings(turns);

        List<Map<String, Object>> current = new ArrayList<>();
        int currentLen = 0;
        float[] prevInterviewerEmb = null;   // 当前段内「最后一个面试官提问」的向量（末尾窗口签名）

        for (int i = 0; i < turns.size(); i++) {
            Map<String, Object> t = turns.get(i);
            int tLen = contentLen(t) + TURN_OVERHEAD;
            boolean interviewer = INTERVIEWER.equals(str(t.get("speaker")));
            float[] curEmb = interviewer ? interviewerEmb.get(i) : null;

            if (current.isEmpty()) {
                current.add(t);
                currentLen = tLen;
                prevInterviewerEmb = curEmb;
                continue;
            }

            if (interviewer) {
                boolean cut = false;
                if (currentLen + tLen > maxChars) {
                    cut = true;   // 硬上限
                } else if (currentLen >= minChars && prevInterviewerEmb != null && curEmb != null
                        && cosine(curEmb, prevInterviewerEmb) < threshold) {
                    cut = true;   // 语义边界（话题切换）
                }
                if (cut) {
                    chunks.add(current);
                    current = new ArrayList<>();
                }
                current.add(t);
                if (cut) {
                    currentLen = tLen;
                } else {
                    currentLen += tLen;
                }
                prevInterviewerEmb = curEmb;   // 末尾窗口更新为最新提问（切段后即新话题锚）
            } else {
                // 我 / 空 turn：只累加，绝不切（保证段以面试官提问开头）
                current.add(t);
                currentLen += tLen;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        log.debug("语义切分：{} turns → {} 段（max={}, min={}, thr={}）",
                turns.size(), chunks.size(), maxChars, minChars, threshold);
        return chunks;
    }

    /** 批量 embed 所有面试官 turn 的提问签名；返回 {turns下标 → 向量}。整体失败返回空 map（降级为无语义信号）。 */
    private Map<Integer, float[]> precomputeInterviewerEmbeddings(List<Map<String, Object>> turns) {
        List<Integer> positions = new ArrayList<>();
        List<String> sigs = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            if (INTERVIEWER.equals(str(turns.get(i).get("speaker")))) {
                String content = str(turns.get(i).get("content")).strip();
                if (content.isEmpty()) {
                    continue;
                }
                positions.add(i);
                sigs.add(content.substring(0, Math.min(SIG_MAX, content.length())));
            }
        }
        Map<Integer, float[]> out = new HashMap<>();
        if (sigs.isEmpty()) {
            return out;
        }
        try {
            List<float[]> vecs = embeddingService.embedAll(sigs);
            for (int k = 0; k < positions.size() && k < vecs.size(); k++) {
                out.put(positions.get(k), vecs.get(k));
            }
        } catch (Exception e) {
            log.warn("语义切分批量 embedding 失败（降级为无语义信号，只剩硬上限）: {}", e.getMessage());
        }
        return out;
    }

    private static int contentLen(Map<String, Object> t) {
        return str(t.get("content")).length();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
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
}
