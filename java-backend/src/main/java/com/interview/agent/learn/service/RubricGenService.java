package com.interview.agent.learn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.prompts.PromptKeys;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rubric 生成器（学考同源单一入口）。
 *
 * <p>给定一道目标题，若其 {@code rubric_template} 为空 → 调 {@code study/rubric-gen}
 * 生成【采分点 + 分点范例答案】并落 {@code question_detail}。
 *
 * <p>被两处复用，保证"学的采分点 == 评分采分点"：
 * <ul>
 *   <li>learn Step B（{@code LearnContentServiceImpl}）：点开讲解时"答案先"生成；</li>
 *   <li>study 答题（{@code StudyAttemptServiceImpl}）：首次答题的幂等兜底（已填则跳过）。</li>
 * </ul>
 */
@Service
public class RubricGenService {

    private static final TypeReference<Map<String, Object>> RUBRIC_RESP = new TypeReference<>() {};

    private final LlmInvoker llmInvoker;
    private final StudyQuestionMapper questionMapper;
    private final LearnHelper learnHelper;

    public RubricGenService(LlmInvoker llmInvoker,
                            StudyQuestionMapper questionMapper,
                            LearnHelper learnHelper) {
        this.llmInvoker = llmInvoker;
        this.questionMapper = questionMapper;
        this.learnHelper = learnHelper;
    }

    /**
     * 若题的 rubric 为空 → 生成 rubric + 范例答案并落库。
     *
     * @return true 表示本次新生成并落库；false 表示已有 rubric（跳过）或生成失败（降级保持空）。
     */
    public boolean ensureRubric(StudyQuestion q) {
        if (!rubricEmpty(q.rubricTemplate())) {
            return false;
        }
        String categoryPath = q.knowledgePointId() == null ? "" : learnHelper.categoryPath(q.knowledgePointId());
        Map<String, Object> vars = Map.of(
                "question", q.content(),
                "category_path", categoryPath
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.STUDY_RUBRIC_GEN, vars, 0.3, 2048, 3);
        Optional<Map<String, Object>> res = llmInvoker.invoke(spec, raw -> {
            Map<String, Object> m = JsonUtil.extractJson(raw, RUBRIC_RESP);
            if (m == null || !(m.get("rubric") instanceof List<?> l) || l.isEmpty()) {
                throw new IllegalStateException("rubric 生成为空");
            }
            return m;
        });
        if (res.isEmpty()) {
            return false; // 降级：保持空 rubric，不阻断
        }
        questionMapper.updateRubric(q.id(), res.get().get("rubric"), res.get().get("recommended_answer"));
        return true;
    }

    /** rubric_template 是否为空（null / 空 JSON 数组 / 空串）。 */
    public static boolean rubricEmpty(Object r) {
        if (r == null) {
            return true;
        }
        if (r instanceof List<?> l) {
            return l.isEmpty();
        }
        if (r instanceof String s) {
            String t = s.strip();
            return t.isEmpty() || "[]".equals(t) || "null".equals(t);
        }
        return false;
    }
}
