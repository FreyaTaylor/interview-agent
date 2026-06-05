package com.interview.agent.text.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.prompts.PromptKeys;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 文本纠错服务 —— 主要修复浏览器原生 ASR 的同音/形近错别字。
 *
 * <p>纯无状态、不碰 DB；LLM 任意失败一律返回原文（保证发送链路不阻塞）。
 */
@Service
public class TextCorrectService {

    private static final double TEMPERATURE = 0.0;   // 纠错要稳，绝不创造
    private static final int MAX_TOKENS = 2048;
    private static final int MAX_RETRY = 2;
    // 太长一段不进 LLM（成本 + 错误风险），直接退回原文
    private static final int MAX_INPUT_CHARS = 1500;

    private static final TypeReference<Map<String, Object>> JSON_OBJ = new TypeReference<>() {};

    private final LlmInvoker llmInvoker;

    public TextCorrectService(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /** 纠错。LLM 失败 / 输入过长 / 输出异常 → 一律返原文。 */
    public String correct(String text, String context) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INPUT_CHARS) {
            return trimmed;
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("text", trimmed);
        vars.put("context", context == null || context.isBlank() ? "（无）" : context.trim());

        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.TEXT_CORRECT, vars, TEMPERATURE, MAX_TOKENS, MAX_RETRY);
        return llmInvoker.invoke(spec, TextCorrectService::parse).orElse(trimmed);
    }

    private static String parse(String raw) {
        Map<String, Object> data = JsonUtil.extractJson(raw, JSON_OBJ);
        Object v = data.get("corrected");
        if (v == null) throw new IllegalArgumentException("缺 corrected 字段");
        String s = v.toString().trim();
        if (s.isEmpty()) throw new IllegalArgumentException("corrected 为空");
        return s;
    }
}
