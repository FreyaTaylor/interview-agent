package com.interview.agent.prompts;

import com.interview.agent.common.BizException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板加载器 —— 从 classpath:prompts/ 读取 .txt 模板并缓存。
 *
 * <p>设计动机（CONVENTIONS §3.3）：
 * Prompt 内容是中文长文本，硬编码到 Java 文件里既污染源码也无法被设计同学独立审阅；
 * 拆到 resources/prompts/{module}/*.txt 后，文件本身就是"中文文档"，可读 / 可改 / 可 diff。
 *
 * <p>模板变量约定：用 <code>{var_name}</code> 形式（snake_case，与 Python 端对齐），
 * 调用方传 {@code Map.of("var_name", "value")} 做替换；找不到 key 时保留原占位，便于调试。
 *
 * <p>缓存策略：首次加载后存入 ConcurrentHashMap，后续 O(1) 命中；
 * 内容不可变（修改模板需重启），换来零 IO 开销。
 *
 * <p>使用：
 * <pre>
 *   String prompt = promptLoader.render("tree/parse-text.txt",
 *       Map.of("text", userInput));
 * </pre>
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载原始模板（不做变量替换）。
     *
     * @param path 相对于 classpath:prompts/ 的路径，如 "tree/parse-text.txt"
     * @throws BizException 50000 资源不存在或读失败
     */
    public String load(String path) {
        return cache.computeIfAbsent(path, this::loadFromClasspath);
    }

    /**
     * 加载模板并按 vars 做 {key} → value 替换，返回最终 prompt。
     *
     * @param path 模板路径
     * @param vars 变量映射；key 不带花括号；value 自动 toString()
     */
    public String render(String path, Map<String, ?> vars) {
        String template = load(path);
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        // 朴素 replace：用 StringBuilder 一次过，避免每次 replace 都拷贝全串
        // 模板里出现的 {var} 个数有限，O(n*m) 没有问题
        String result = template;
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            String value = e.getValue() == null ? "" : e.getValue().toString();
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private String loadFromClasspath(String path) {
        // Step 1: 拼路径到 classpath:prompts/{path}
        String fullPath = "prompts/" + path;
        ClassPathResource res = new ClassPathResource(fullPath);
        if (!res.exists()) {
            throw new BizException(50000, "prompt 模板不存在: " + fullPath);
        }
        // Step 2: UTF-8 读全文（prompt 都是中文）
        try (InputStream in = res.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(50000, "prompt 模板读取失败: " + fullPath, e);
        }
    }
}
