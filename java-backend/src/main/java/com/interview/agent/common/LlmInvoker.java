package com.interview.agent.common;

import com.interview.agent.prompts.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

/**
 * 通用 LLM 调用工具：拼 prompt + 重试 + 解析，统一收口"调一次 LLM"这件事。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 讲解：3 次重试，失败抛业务异常
 * Spec spec = new Spec("learn/content-gen", vars, 0.3, 4096, 3);
 * String md = llmInvoker.invoke(spec, raw -> {
 *     String c = raw.strip();
 *     if (缺少必选模块(c)) throw new IllegalStateException("缺少 xxx");
 *     return c;
 * }).orElseThrow(() -> new BizException(50000, "讲解生成失败"));
 *
 * // 题目：单次调用，失败吞掉返空
 * Spec spec = new Spec("learn/question-gen", vars, 0.4, 4096, 1);
 * List<QuestionItem> items = llmInvoker.invoke(spec, raw -> parseJson(raw)).orElse(List.of());
 * }</pre>
 *
 * <h3>语义约定</h3>
 * <ul>
 *   <li>parser 抛任意异常 → 视为"此次结果无效"，进入下一次重试</li>
 *   <li>LLM 返空 / 异常 → 进入下一次重试</li>
 *   <li>全部 maxRetry 用完都失败 → 返回 {@link Optional#empty()}，<b>不抛</b>业务异常</li>
 *   <li>是否阻断由 caller 决定（{@code orElseThrow} vs {@code orElse}）</li>
 * </ul>
 */
@Component
public class LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(LlmInvoker.class);

    private final ChatClient chatClient;
    private final PromptService promptService;

    public LlmInvoker(ChatClient chatClient, PromptService promptService) {
        this.chatClient = chatClient;
        this.promptService = promptService;
    }

    /**
     * 调 LLM 并解析。重试 {@code spec.maxRetry()} 次；全部失败返 {@link Optional#empty()}。
     * <ol>
     *   <li>Step 1: 渲染 prompt（{@link PromptService#render}）+ 装 options</li>
     *   <li>Step 2: 循环调 ChatClient；空内容 / 异常 → log.warn 进入下一轮</li>
     *   <li>Step 3: raw 喂 parser；parser 抛异常 → 视为无效进入下一轮</li>
     * </ol>
     *
     * @param spec   prompt key + 变量 + 模型参数 + 重试次数
     * @param parser raw String → T 的解析器，抛异常即触发重试
     * @param <T>    结果类型
     * @return 解析成功的结果；全部失败返空
     */
    public <T> Optional<T> invoke(Spec spec, ResponseParser<T> parser) {
        // Step 1: 渲染 prompt + 装 options
        String prompt = promptService.render(spec.promptKey(), spec.vars());
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .temperature(spec.temperature())
                .maxTokens(spec.maxTokens())
                .build();

        // Step 2 & 3: 循环调用 + 解析
        for (int attempt = 1; attempt <= spec.maxRetry(); attempt++) {
            try {
                String raw = chatClient.prompt().options(opts).user(prompt).call().content();
                if (raw == null || raw.isBlank()) {
                    log.warn("[LLM:{}] 返空 第{}/{}次", spec.promptKey(), attempt, spec.maxRetry());
                    continue;
                }
                return Optional.of(parser.parse(raw));
            } catch (Exception e) {
                log.warn("[LLM:{}] 第{}/{}次失败: {}", spec.promptKey(), attempt, spec.maxRetry(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * 流式调 LLM，逐段返回文本增量（token）。
     *
     * <p>与 {@link #invoke} 的差异：<b>不重试、不解析、不校验</b>——流式天然无法中途重来。
     * caller 负责累积全文、结束后自行落库/校验（如太短视为失败）。
     *
     * @param spec prompt key + 变量 + 模型参数（maxRetry 在流式下被忽略）
     * @return 文本增量流；订阅方 append 拼全文
     */
    public Flux<String> stream(Spec spec) {
        String prompt = promptService.render(spec.promptKey(), spec.vars());
        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .temperature(spec.temperature())
                .maxTokens(spec.maxTokens())
                .build();
        return chatClient.prompt().options(opts).user(prompt).stream().content();
    }

    /**
     * LLM 调用规格。
     * @param promptKey   {@code prompt_template} 表的 key
     * @param vars        prompt 占位符变量（snake_case key）
     * @param temperature 采样温度
     * @param maxTokens   最大输出 token
     * @param maxRetry    最大尝试次数（&gt;=1；1 表示不重试）
     */
    public record Spec(String promptKey, Map<String, Object> vars,
                       double temperature, int maxTokens, int maxRetry) {
        public Spec {
            if (maxRetry < 1) {
                throw new IllegalArgumentException("maxRetry 必须 >= 1");
            }
        }
    }

    /** 把 LLM 返回的 raw String 解析成 T；抛任意异常视为"此次结果无效"，触发下一轮重试。 */
    @FunctionalInterface
    public interface ResponseParser<T> {
        T parse(String raw) throws Exception;
    }
}
