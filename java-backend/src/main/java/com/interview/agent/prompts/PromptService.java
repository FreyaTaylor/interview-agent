package com.interview.agent.prompts;

import com.interview.agent.common.BizException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板服务 —— 从 DB(prompt_template 表) 按 key 读取模板并按 {var} 占位符渲染。
 *
 * <p>纯工具组件（无业务编排、无第二种实现可能），故不拆接口+impl。
 *
 * <p>模板来源：Flyway 迁移 V2 建表 + V3__seed_prompt_template.sql 用 INSERT ... ON CONFLICT DO NOTHING
 * 写入种子；运营在 DB 里改的内容不会被覆盖。新增模板请写新的 V?__ 迁移。
 *
 * <p>占位符约定：{@code {var_name}}（snake_case）；调用方传 {@code Map.of("var_name", "value")}
 * 做替换；模板里写错的占位符会原样保留以便调试。
 *
 * <p>本地缓存：首次读取后放进 {@link ConcurrentHashMap}，避免每次调用都打 DB；运营改完模板
 * 需要立即生效时调 {@link #invalidateCache()}。
 */
@Service
public class PromptService {

    private final PromptTemplateMapper mapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptService(PromptTemplateMapper mapper) {
        this.mapper = mapper;
    }

    /** 取原始模板（不替换）。找不到抛 50000。 */
    public String load(String key) {
        return cache.computeIfAbsent(key, k -> mapper.findByKey(k)
                .map(PromptTemplate::content)
                .orElseThrow(() -> new BizException(50000, "prompt 模板不存在: " + k)));
    }

    /** 按 key 取模板并替换 {var} 占位符。vars 为空时返回原模板。 */
    public String render(String key, Map<String, ?> vars) {
        String template = load(key);
        if (vars == null || vars.isEmpty()) {
            return template;
        }
        // 朴素 replace：模板里 {var} 个数有限，O(n*m) 足够
        String result = template;
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            String value = e.getValue() == null ? "" : e.getValue().toString();
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /** 清空本地缓存，强制下次从 DB 重读（运营改完模板后调用）。 */
    public void invalidateCache() {
        cache.clear();
    }
}
