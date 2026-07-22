package com.interview.agent.interview.leetcode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.prompts.PromptKeys;
import com.interview.agent.prompts.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * LeetCode 富化 agent —— 把面试里口语化的算法题描述，识别为真实 LeetCode 题目。
 *
 * <p>agent 流程（Spring AI function-calling）：给 {@link ChatClient} 挂 {@link LeetCodeTool}，
 * 让模型自主用英文关键词调 {@code searchLeetCode} 查题库、核对候选是否与描述一致，
 * 再产出规范题名 + 题号 + slug；不确定则 {@code matched=false}（宁缺勿错）。
 *
 * <p><b>失败即降级</b>：LLM/工具/解析任一环出错 → 返回 {@link Optional#empty()}，不阻断解析主流程。
 */
@Service
public class LeetCodeEnrichService {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeEnrichService.class);

    private final ChatClient chatClient;
    private final PromptService promptService;
    private final LeetCodeTool leetCodeTool;

    public LeetCodeEnrichService(ChatClient chatClient,
                                 PromptService promptService,
                                 LeetCodeTool leetCodeTool) {
        this.chatClient = chatClient;
        this.promptService = promptService;
        this.leetCodeTool = leetCodeTool;
    }

    /**
     * 富化一道算法题。
     *
     * @param description 面试中该算法题的口语化描述（问题/对话片段）
     * @return 高置信匹配到的题目；无法确定或出错时返回空
     */
    public Optional<Enriched> enrich(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        try {
            String prompt = promptService.render(PromptKeys.INTERVIEW_LEETCODE_ENRICH,
                    Map.of("description", description));
            String raw = chatClient.prompt()
                    .tools(leetCodeTool)
                    .user(prompt)
                    .call()
                    .content();
            Result r = JsonUtil.extractJson(raw, Result.class);
            if (r == null || !r.matched() || r.titleSlug() == null || r.titleSlug().isBlank()) {
                return Optional.empty();
            }
            String url = "https://leetcode.cn/problems/" + r.titleSlug() + "/description/";
            log.info("[LeetCodeEnrich] 匹配成功 id={} title={} desc='{}'", r.leetcodeId(), r.title(),
                    description.length() > 40 ? description.substring(0, 40) + "…" : description);
            return Optional.of(new Enriched(r.leetcodeId(), r.title(), url));
        } catch (Exception e) {
            log.warn("[LeetCodeEnrich] 富化失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** LLM 输出 JSON。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(
            boolean matched,
            @JsonProperty("leetcode_id") String leetcodeId,
            String title,
            @JsonProperty("title_slug") String titleSlug
    ) {
    }

    /** 富化结果：题号 / 规范题名 / 题目链接。 */
    public record Enriched(String leetcodeId, String title, String url) {
    }
}
