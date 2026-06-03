package com.interview.agent.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jackson 封装 + LLM JSON 容错抽取。
 *
 * 三类用法：
 *   1. {@link #toJson(Object)} / {@link #fromJson} —— 业务侧普通 JSON 转换
 *   2. {@link #extractJson} —— 从 LLM 自然语言输出里"挖"出第一个完整 JSON 块再反序列化
 *   3. 直接拿 {@link #mapper()} 做 JsonNode、流式解析等复杂场景
 *
 * 为什么需要 extractJson：
 *   LLM（DeepSeek / Qwen / GPT 都一样）即使被 prompt 严格要求"只返回 JSON"，
 *   仍然经常返回类似：
 *
 *       好的，下面是评分结果：
 *       ```json
 *       { "score": 85, "hits": [...] }
 *       ```
 *       如有疑问请告知。
 *
 *   直接 readValue 会因为"好的"开头就 fail。这里通过两步降级匹配把 JSON 抠出来。
 */
public final class JsonUtil {

    /**
     * 全局共用的 ObjectMapper。Jackson 的 ObjectMapper 是线程安全的（只要不在运行时改配置），
     * 复用单例可避免每次 new 的反射注册成本。
     *
     * 配置项：
     *  - JavaTimeModule：支持 LocalDateTime / Instant 等 JSR-310 类型
     *  - FAIL_ON_UNKNOWN_PROPERTIES=false：JSON 多出字段不抛异常（前后端版本不同步时容错）
     *  - WRITE_DATES_AS_TIMESTAMPS=false：日期序列化成 ISO 字符串而非毫秒数字（前端更易处理）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * Markdown 代码围栏正则：匹配 ```json ... ``` 或 ``` ... ```
     *   - (?:json)?  非捕获分组，json 标签可有可无
     *   - (.*?)      惰性匹配（取最近的结束 ```，避免跨多段串）
     *   - DOTALL     让 . 匹配换行（LLM 输出的 JSON 都是多行的）
     */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*(.*?)```", Pattern.DOTALL);

    private JsonUtil() {} // 工具类，禁实例化

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    // ============================================================
    // 普通 JSON 转换 —— 失败统一包成 BizException(50000)
    // 业务侧不用每处 try-catch，由 GlobalExceptionHandler 兜底
    // ============================================================

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(50000, "JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /** 反序列化到具体 Class（非泛型类型用） */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(50000, "JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反序列化到带泛型的类型（如 {@code List<RubricItem>}、{@code Map<String,Object>}）。
     * 用法：{@code fromJson(s, new TypeReference<List<RubricItem>>(){})}
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            throw new BizException(50000, "JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // LLM 容错抽取 —— extractJson
    // ============================================================

    /** 从 LLM 自然语言输出中容错抽取 JSON 并反序列化成 Class。 */
    public static <T> T extractJson(String llmOutput, Class<T> type) {
        String raw = extractJsonString(llmOutput);
        return fromJson(raw, type);
    }

    /** 带泛型的版本。 */
    public static <T> T extractJson(String llmOutput, TypeReference<T> typeRef) {
        String raw = extractJsonString(llmOutput);
        return fromJson(raw, typeRef);
    }

    /**
     * 抽取算法（两步降级）：
     *
     * STEP 1: 优先匹配 Markdown 代码围栏 ```json ... ```
     *   - LLM 主流输出格式，命中率最高
     *   - 命中即返回围栏内文本（已 strip）
     *
     * STEP 2: 没围栏 → 找首个 { 或 [，按"括号配对"扫描到对应的 } 或 ]
     *   - 字符串内的 { } [ ] 不计入深度（需要识别 " 与 \" 转义）
     *   - 找到 depth==0 的闭合位置即截取
     *
     * 失败：抛 BizException(50000)，并在 message 里放前 200 字预览便于排查
     *
     * 注意：本方法不做 JSON 语法校验，只负责"挖出来"。语法错误会在
     * 上层 fromJson 阶段被 Jackson 抛出（同样被包成 BizException）。
     */
    static String extractJsonString(String llmOutput) {
        // 入参兜底
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new BizException(50000, "LLM 输出为空，无法抽取 JSON");
        }

        // ---- STEP 1: 代码围栏 ----
        Matcher m = CODE_FENCE.matcher(llmOutput);
        if (m.find()) {
            return m.group(1).strip();
        }

        // ---- STEP 2: 括号配对扫描 ----
        // 先判断首个 JSON 块到底是对象还是数组（哪个出现得早用哪个）
        int objStart = llmOutput.indexOf('{');
        int arrStart = llmOutput.indexOf('[');
        int start;
        char open, close;
        if (objStart >= 0 && (arrStart < 0 || objStart < arrStart)) {
            // 对象在前（或没有数组）
            start = objStart;
            open = '{';
            close = '}';
        } else if (arrStart >= 0) {
            // 数组在前
            start = arrStart;
            open = '[';
            close = ']';
        } else {
            // 两种都没有 → 整段输出里压根没 JSON
            throw new BizException(50000, "LLM 输出中未找到 JSON 块: " + preview(llmOutput));
        }

        // 状态机扫描：
        //   depth     当前未闭合的 open 数（=0 时整块结束）
        //   inString  是否处于 "..." 内（字符串里的括号不算）
        //   escape    上一字符是否是 \（处理 \" 等转义，避免误判字符串结束）
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < llmOutput.length(); i++) {
            char c = llmOutput.charAt(i);

            if (escape) {
                // 上一字符是 \，无论本字符是什么都跳过（已被转义）
                escape = false;
                continue;
            }
            if (c == '\\') {
                // 进入转义状态，下个字符跳过
                escape = true;
                continue;
            }
            if (c == '"') {
                // 进/出字符串
                inString = !inString;
                continue;
            }
            if (inString) continue; // 字符串内部的所有字符都不计

            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    // 找到完整闭合，左闭右闭截取
                    return llmOutput.substring(start, i + 1);
                }
            }
        }

        // 走完循环还没归零 → 输出被截断或括号确实不平衡
        throw new BizException(50000, "LLM 输出中 JSON 括号不匹配: " + preview(llmOutput));
    }

    /** 异常 message 防爆：超过 200 字用 ... 截断 */
    private static String preview(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
