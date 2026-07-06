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
    /** 学习模式下「子话题审校去重+补全」（两步生成的第二步）。V37 seed。 */
    public static final String LEARN_SUBTOPICS_REFINE = "learn/subtopics-refine";
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
    /** 截图（思维导图/大纲）视觉解析为知识树（qwen-vl-max）。V28 seed。 */
    public static final String TREE_PARSE_IMAGE = "tree/parse-image";

    // ===== project 域（项目树 admin）=====
    /** 把项目描述解析为「项目→话题→问题」三层树。V15 seed。 */
    public static final String PROJECT_PARSE_TEXT = "project/parse-text";
    /** 项目名同名/语义去重判断。V15 seed。 */
    public static final String PROJECT_DUP_CHECK = "project/dup-check";

    // ===== project grilling 域（S7 项目拷打）=====
    /** 项目拷打 — 异步画像抽取：仅 facts_patch。V16/V19 seed。 */
    public static final String PROJECT_EXTRACT_PROFILE = "project/extract-profile";
    /** 项目拷打 v1 — 单轮评分与追问决策。V16 seed。 */
    public static final String PROJECT_PER_TURN = "project/per-turn";
    /** 项目拷打 v1 — 最终综合评分。V16 seed。 */
    public static final String PROJECT_FINAL_SCORE = "project/final-score";
    /** 项目拷打 v2「面试官自由追问」— 单轮：interviewer_note + gaps_found + signals + next_question/wrap_up_reason。V17 seed。 */
    public static final String PROJECT_PER_TURN_V2 = "project/per-turn-v2";
    /** 项目拷打 v2 — 综合评分：dimensions (4维) + overall_summary + design_issues + extension_qa。V17 seed。 */
    public static final String PROJECT_FINAL_SCORE_V2 = "project/final-score-v2";

    // ===== interview 域（S8 面试复盘，完全复刻 Python）=====
    /** 面试复盘：原始文本解析为 turns + groups。V20/V22/V23 seed（V23 复刻 Python 全文）。 */
    public static final String INTERVIEW_PARSE = "interview/parse";
    /** 面试复盘：ASR 纠错 + 删噪声 turn（复刻 ASR_CORRECTION_PROMPT，占位符 {dialogue}）。V23 seed。 */
    public static final String INTERVIEW_ASR_CORRECT = "interview/asr-correct";
    /** 面试复盘：knowledge 类评分（复刻 INTERVIEW_SCORE_PROMPT）。V23 seed。 */
    public static final String INTERVIEW_SCORE_KNOWLEDGE = "interview/score-knowledge";
    /** 面试复盘：project 类评分（复刻 INTERVIEW_PROJECT_SCORE_PROMPT）。V23 seed。 */
    public static final String INTERVIEW_SCORE_PROJECT = "interview/score-project";
    /** 面试复盘：algorithm 类评分（复刻 INTERVIEW_ALGORITHM_SCORE_PROMPT）。V23 seed。 */
    public static final String INTERVIEW_SCORE_ALGORITHM = "interview/score-algorithm";
    /** 面试复盘：hr 类评分（复刻 INTERVIEW_HR_SCORE_PROMPT）。V23 seed。 */
    public static final String INTERVIEW_SCORE_HR = "interview/score-hr";
    /** 面试复盘：单个 group 固定 DTO 评分（旧版，保留兼容）。V20 seed。 */
    public static final String INTERVIEW_SCORE_GROUP = "interview/score-group";
    /** 面试复盘：整场总体分析（复刻 INTERVIEW_OVERALL_ANALYSIS_PROMPT）。V20/V23 seed。 */
    public static final String INTERVIEW_OVERALL_ANALYSIS = "interview/overall-analysis";
    /** 面试复盘：同项目话题合并（复刻 parser 内联 prompt，占位符 {proj_name}{topic_list}）。V23 seed。 */
    public static final String INTERVIEW_MERGE_PROJECT_TOPICS = "interview/merge-project-topics";
    /** 面试复盘：单段遗漏问题二次检查（复刻 parser 内联 prompt）。V23 seed。 */
    public static final String INTERVIEW_MISSED_CHECK = "interview/missed-check";
    /** 面试复盘：ASR 说话人角色归一化（说话人X -> 面试官/我）。V21 seed。 */
    public static final String INTERVIEW_ASR_ROLE_NORMALIZE = "interview/asr-role-normalize";
    /** 面试复盘：知识点 embedding 召回后 LLM rerank（复刻 RERANK_PROMPT，占位符 {text}{candidates}）。V25 seed。 */
    public static final String INTERVIEW_MATCH_KNOWLEDGE_RERANK = "interview/match-knowledge-rerank";
    /** 面试复盘：项目根 LLM 语义匹配（复刻 match_or_create_project_root 内联，占位符 {catalog}{name}）。V25 seed。 */
    public static final String INTERVIEW_MATCH_PROJECT_ROOT = "interview/match-project-root";
    /** 面试复盘：项目话题 LLM 语义匹配（复刻 match_or_create_topic 内联，占位符 {catalog}{topic}）。V25 seed。 */
    public static final String INTERVIEW_MATCH_PROJECT_TOPIC = "interview/match-project-topic";

    // ===== text 域（通用工具）=====
    /** ASR / 输入文本纠错。V14 seed。 */
    public static final String TEXT_CORRECT = "text/correct";
}
