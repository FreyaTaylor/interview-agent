package com.interview.agent.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 返回 / 内部递归用的"树节点"JSON 形状。
 *
 * <p>对应 prompt 输出格式：
 * <pre>
 * {"name": "Redis", "children": [
 *    {"name": "数据结构", "children": [
 *       {"name": "String", "interview_weight": 4}
 *    ]}
 * ]}
 * </pre>
 *
 * <p>字段命名经 Jackson 自动 snake_case ↔ camelCase 转换（已开启 PROPERTY_NAMING_STRATEGY？
 * 否则 LLM 返回的 interview_weight 需要 @JsonProperty）。我们直接配置 Mapper 处理。
 *
 * <p>Record 不可变：递归落库时只读不改。
 *
 * @param name            节点名称（必填）
 * @param children        子节点，可空（null/空 → 叶子）
 * @param interviewWeight 面试权重 1-5，叶子节点用；可为 null（按 3 兜底）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreeNodeJson(
        String name,
        List<TreeNodeJson> children,
        @JsonProperty("interview_weight") Short interviewWeight
) {
    /** 没娃 = 叶子。 */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }
}
