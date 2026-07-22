package com.interview.agent.interview.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.prompts.PromptKeys;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组精炼服务 —— 校对编辑（拆分/合并/改归属）后，以该组最终对话为准，
 * 用 LLM 重提干净的 {@code tag} + 书面化 {@code questions}。
 *
 * <p>只在 finalize 对【被编辑】的组调用；失败/空返回 {@link Optional#empty()}，调用方保留原值。
 */
@Service
public class GroupRefineService {

    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 1024;
    private static final int MAX_RETRY = 2;

    private final LlmInvoker llmInvoker;

    public GroupRefineService(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * @param dialogue 该组最终 turns 拼出的对话（{@code 面试官：… 我：…}）
     * @param type     组类型（knowledge/project/algorithm/hr/other）
     * @return 精炼结果；输入空、LLM 失败或解析失败返回空
     */
    public Optional<Refined> refine(String dialogue, String type) {
        if (dialogue == null || dialogue.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> vars = Map.of(
                "dialogue", dialogue,
                "type", type == null || type.isBlank() ? "other" : type
        );
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.INTERVIEW_GROUP_REFINE, vars,
                TEMPERATURE, MAX_TOKENS, MAX_RETRY);
        return llmInvoker.invoke(spec, raw -> {
            Refined r = JsonUtil.extractJson(raw, Refined.class);
            if (r == null || r.tag() == null || r.tag().isBlank()) {
                throw new IllegalStateException("组精炼结果为空");
            }
            return r;
        });
    }

    /** LLM 输出：干净 tag + 书面化 questions。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Refined(String tag, List<String> questions) {
    }
}
