package com.interview.agent.prompts;

import com.interview.agent.common.LlmInvoker;

/**
 * 所有 prompt key 的<b>唯一登记处</b>。
 *
 * <p><b>风格约束（不要破坏）</b>：
 * <ol>
 *   <li>调用 {@link LlmInvoker} / {@link PromptService} 时，prompt key 必须引用本类常量；
 *       <b>禁止字面量</b>（防写错 key、防同一 key 在多处用不同字符串）。</li>
 *   <li>常量名按 {@code domain/sub-action} → {@code DOMAIN_SUB_ACTION} 转写
 *       （e.g. {@code project/parse-text} → {@code PROJECT_PARSE_TEXT}）。</li>
 *   <li>新增 prompt 时：先写 Flyway V?__seed_*.sql；再来本类登记常量；最后业务代码引用。</li>
 *   <li>本类常量必须与 {@code prompt_template} 表 {@code key} 列 1:1 对齐
 *       （keysInDb == constantsInClass）。</li>
 * </ol>
 *
 * <p>当前 DB 共 11 个 key（V2/V3/V5-V8/V10/V11/V13/V14/V15 seed）。
 */
public final class PromptKeys {

    private PromptKeys() {}

    // ===== learn 域 =====
    /** 学习模式下「子话题生成」（按知识点拆 5±2 个子话题）。V10/V11 seed。 */
    public static final String LEARN_SUBTOPICS_GEN = "learn/subtopics-gen";
    /** 学习模式下「题目生成」（按子话题出题）。V10/V11 seed。 */
    public static final String LEARN_QUESTION_GEN = "learn/question-gen";
    /** 学习模式下「Chat 对话」（讲解 + 引导）。V6/V7/V8/V10 seed（多版本迭代）。 */
    public static final String LEARN_CHAT = "learn/chat";

    // ===== study 域（考核 / 答题评分）=====
    /** 答题每轮评分（状态机驱动 per-turn 评分）。V13 seed。 */
    public static final String STUDY_PER_TURN = "study/per-turn";
    /** 答题最终汇总评分。V11 seed。 */
    public static final String STUDY_FINAL_SCORE = "study/final-score";

    // ===== tree 域（知识树 admin）=====
    /** 把粘贴文本解析为知识树（Markdown / 缩进文本 → 树 JSON）。V3 seed。 */
    public static final String TREE_PARSE_TEXT = "tree/parse-text";
    /** 按 root name + 需求一键生成知识树。V3 seed。 */
    public static final String TREE_GENERATE = "tree/generate";
    /** 知识树根节点同名/语义去重判断。V3 seed。 */
    public static final String TREE_DUPLICATE_CHECK = "tree/duplicate-check";

    // ===== project 域（项目树 admin）=====
    /** 把项目描述解析为「项目→话题→问题」三层树。V15 seed。 */
    public static final String PROJECT_PARSE_TEXT = "project/parse-text";
    /** 项目名同名/语义去重判断。V15 seed。 */
    public static final String PROJECT_DUP_CHECK = "project/dup-check";

    // ===== text 域（通用工具）=====
    /** ASR / 输入文本纠错。V14 seed。 */
    public static final String TEXT_CORRECT = "text/correct";
}
