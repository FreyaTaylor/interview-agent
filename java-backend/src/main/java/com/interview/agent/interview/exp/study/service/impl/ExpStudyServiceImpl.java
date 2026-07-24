package com.interview.agent.interview.exp.study.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.interview.exp.study.dto.ExpContentRequest;
import com.interview.agent.interview.exp.study.dto.ExpDetailRow;
import com.interview.agent.interview.exp.study.dto.ExpQuestionView;
import com.interview.agent.interview.exp.study.dto.ExpStudyTreeNode;
import com.interview.agent.interview.exp.study.mapper.ExpStudyMapper;
import com.interview.agent.interview.exp.study.service.ExpStudyService;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 「看看面经」学习页实现 —— 面经树 + 问题内容懒生成 + 自评掌握度。
 *
 * <h3>懒生成（复刻知识点子话题「答案先→讲解后」）</h3>
 * <ol>
 *   <li>ready 且 fetch → 直接读库返回，<b>不调 LLM</b>。</li>
 *   <li>否则问题级事务咨询锁 → 拿锁后重查（并发幂等）→ 建 pending 侧表行。</li>
 *   <li>先产 rubric + 推荐答案（{@link PromptKeys#INTERVIEW_EXP_RUBRIC_GEN}，失败降级空）。</li>
 *   <li>再产讲解正文（{@link PromptKeys#INTERVIEW_EXP_QUESTION_CONTENT}，采分点作硬约束）；正文空则抛错、整事务回滚保持 pending。</li>
 *   <li>落库置 ready → 重查返回。</li>
 * </ol>
 *
 * <p>失败语义：rubric 失败 → 空 rubric/答案（不阻断，仍生成讲解）；讲解失败 → BizException 50000（保持 pending）。
 * IDOR：所有读写经 Mapper 带 {@code tree_kind='interview_exp'} + userId 校验。
 */
@Service
public class ExpStudyServiceImpl implements ExpStudyService {

    private static final Logger log = LoggerFactory.getLogger(ExpStudyServiceImpl.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};
    private static final TypeReference<List<Object>> JSON_LIST = new TypeReference<>() {};

    private static final double GEN_TEMPERATURE = 0.3;
    private static final int RUBRIC_MAX_TOKENS = 2048;
    private static final int CONTENT_MAX_TOKENS = 4096;
    private static final int GEN_MAX_RETRY = 2;

    private final ExpStudyMapper mapper;
    private final LlmInvoker llmInvoker;

    public ExpStudyServiceImpl(ExpStudyMapper mapper, LlmInvoker llmInvoker) {
        this.mapper = mapper;
        this.llmInvoker = llmInvoker;
    }

    @Override
    public List<ExpStudyTreeNode> getTree() {
        return mapper.findTree(CurrentUser.id());
    }

    // ============================================================
    // 内容懒生成
    // ============================================================

    @Override
    @Transactional
    public ExpQuestionView resolveContent(ExpContentRequest req) {
        long userId = CurrentUser.id();
        String action = req.resolvedAction();

        // Step 1: 存在性 + 归属校验；ready & fetch 直接读库（不调 LLM）
        ExpDetailRow row = mapper.findDetail(req.questionId(), userId)
                .orElseThrow(() -> new BizException(40400, "面经问题不存在"));
        if ("fetch".equals(action) && isReady(row)) {
            return toView(row, false);
        }

        // Step 2: 问题级咨询锁 → 拿锁后重查（并发已生成则幂等直返）
        mapper.acquireContentLock(req.questionId());
        row = mapper.findDetail(req.questionId(), userId)
                .orElseThrow(() -> new BizException(40400, "面经问题不存在"));
        if ("fetch".equals(action) && isReady(row)) {
            return toView(row, false);
        }

        // Step 3: 建 pending 侧表行（幂等）
        mapper.ensureDetailRow(req.questionId());

        // Step 4: 产 rubric + 推荐答案（失败降级空，不阻断讲解）
        Map<String, Object> rubricRes = genRubric(row.name(), row.domainName());
        Object rubric = rubricRes.get("rubric");
        Object recommendedAnswer = rubricRes.get("recommended_answer");

        // Step 5: 产讲解正文（采分点硬约束）；为空则抛错回滚，保持 pending
        String body = genContent(row.name(), row.domainName(), rubric);
        if (body == null || body.isBlank()) {
            throw new BizException(50000, "讲解生成失败，请重试");
        }

        // Step 6: 落库置 ready → 重查返回
        mapper.updateContent(req.questionId(), body.strip(), rubric, recommendedAnswer);
        ExpDetailRow fresh = mapper.findDetail(req.questionId(), userId)
                .orElseThrow(() -> new BizException(40400, "面经问题不存在"));
        log.info("[ExpStudy] content generated qid={} name='{}'", req.questionId(), row.name());
        return toView(fresh, true);
    }

    /** rubric + 推荐答案；失败降级为空 map（不阻断）。 */
    private Map<String, Object> genRubric(String question, String domain) {
        Map<String, Object> vars = Map.of(
                "question", question == null ? "" : question,
                "domain", domain == null || domain.isBlank() ? "（未分类）" : domain);
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_EXP_RUBRIC_GEN, vars, GEN_TEMPERATURE, RUBRIC_MAX_TOKENS, GEN_MAX_RETRY);
        return llmInvoker.invoke(spec, raw -> {
            Map<String, Object> m = JsonUtil.extractJson(raw, JSON_OBJ);
            if (m == null || !(m.get("rubric") instanceof List<?> l) || l.isEmpty()) {
                throw new IllegalStateException("rubric 生成为空");
            }
            return m;
        }).orElseGet(Map::of);
    }

    /** 讲解正文（Markdown）；采分点拼成 answer_points 作硬约束。 */
    private String genContent(String question, String domain, Object rubric) {
        Map<String, Object> vars = Map.of(
                "question", question == null ? "" : question,
                "domain", domain == null || domain.isBlank() ? "（未分类）" : domain,
                "answer_points", formatAnswerPoints(rubric));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_EXP_QUESTION_CONTENT, vars, GEN_TEMPERATURE, CONTENT_MAX_TOKENS, GEN_MAX_RETRY);
        return llmInvoker.invoke(spec, raw -> {
            String c = raw == null ? "" : raw.strip();
            if (c.isEmpty()) {
                throw new IllegalStateException("讲解为空");
            }
            return c;
        }).orElse(null);
    }

    /** 把 rubric 采分点拼成「- key_point：hit_rule」列表，供讲解 prompt。空则给占位。 */
    @SuppressWarnings("unchecked")
    private static String formatAnswerPoints(Object rubric) {
        if (!(rubric instanceof List<?> list) || list.isEmpty()) {
            return "（无采分点，请直接把这道题讲透）";
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object kp = ((Map<String, Object>) m).get("key_point");
                Object hr = ((Map<String, Object>) m).get("hit_rule");
                if (kp != null) {
                    sb.append("- ").append(kp);
                    if (hr != null) {
                        sb.append("：").append(hr);
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.isEmpty() ? "（无采分点，请直接把这道题讲透）" : sb.toString().strip();
    }

    // ============================================================
    // 自评掌握度
    // ============================================================

    @Override
    @Transactional
    public Integer setSelfMastery(long questionId, Integer selfMastery) {
        Integer val = selfMastery == null ? null : Math.max(0, Math.min(100, selfMastery));
        int updated = mapper.updateSelfMastery(questionId, CurrentUser.id(), val);
        if (updated == 0) {
            throw new BizException(40400, "面经问题不存在");
        }
        return val;
    }

    // ============================================================
    // 内部
    // ============================================================

    private static boolean isReady(ExpDetailRow row) {
        return "ready".equals(row.contentStatus()) && row.bodyMd() != null && !row.bodyMd().isBlank();
    }

    private static ExpQuestionView toView(ExpDetailRow row, boolean generated) {
        return new ExpQuestionView(
                row.questionId(), row.name(), row.domainName(),
                row.bodyMd(), row.contentStatus() == null ? "pending" : row.contentStatus(),
                parseList(row.rubricTemplate()), parseList(row.recommendedAnswer()),
                row.selfMastery(), row.frequency(), generated);
    }

    /** JSON 文本 → List；空/非法返空列表。 */
    private static List<Object> parseList(String json) {
        if (json == null || json.isBlank() || "null".equals(json.strip())) {
            return List.of();
        }
        try {
            List<Object> l = JsonUtil.extractJson(json, JSON_LIST);
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }
}
