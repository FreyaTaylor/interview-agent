package com.interview.agent.interview.service;

import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.PreviewParseResponse;

import java.util.List;
import java.util.Map;

/**
 * 面试解析编排服务 —— 模块核心，只暴露「前 / 后 / 全」三个解析入口。
 *
 * <p>三者关系（前端两种使用姿势）：
 * <pre>
 *   ① 校对模式：  前解析 previewParse ──▶ 用户在校对页改 turns/groups ──▶ 后解析 finalizeInterview
 *   ② 直解模式：  全解析 parseInterview（内部 = 前解析 + 后解析，跳过人工校对）
 * </pre>
 *
 * <p>与底层引擎的区别：本服务是<b>编排层</b>（校验 / 归一化 / 节点匹配 / 评分 / 落库 / 副作用），
 * 真正把「原始文本 → 结构化 turns/groups」的 LLM 解析在底层 {@link InterviewParserService} 引擎里完成。
 */
public interface InterviewParseService {

    /**
     * 前解析（preview）—— 把原始面试文本交给 LLM 引擎拆成结构化 {@code turns + groups}，<b>不落库</b>。
     *
     * <p>用途：校对模式 Step 1，结果回给前端校对页让用户增删改分组 / 调整 turn 归属 / 修正分类。
     * 仅做空文本校验后委托 {@link InterviewParserService#parse(String)}，不触碰数据库与评分。
     *
     * @param text 原始面试文本（语音转写或手动输入）
     * @return {@code {turns, groups, summary?}}；未识别出 group 时由引擎返回空 groups + summary
     */
    PreviewParseResponse previewParse(String text);

    /**
     * 后解析（finalize）—— 接收用户<b>校对后</b>的 {@code turns + groups}，完成匹配 / 评分 / 落库 / 副作用。
     *
     * <p>用途：校对模式 Step 2，是面试记录真正写库的唯一入口（直解与继续校准最终都汇到这里）。完整流水：
     * <ol>
     *   <li>校验 + 归一化（补 questions/user_answer/original_dialogue，tag → knowledge_point/topic 映射）</li>
     *   <li>节点匹配：knowledge embedding 召回 / 占位叶子；project 三级匹配（根→话题→问题叶子）</li>
     *   <li>rubric 逐组评分，产出 overall_analysis 与 avg_score / pass_estimate</li>
     *   <li>落 {@code interview_record}（text_hash = SHA-256(raw_text)）+ 按 group.type 分流落 3 张子表</li>
     *   <li>副作用：命中知识点 {@code interview_weight +1}</li>
     * </ol>
     *
     * @param turns    校对后的对话轮次（source of truth，raw_text 由其拼出）
     * @param groups   校对后的分组
     * @param company  公司（可空）
     * @param position 岗位（可空）
     * @return 落库结果（record_id、评分、整体分析、分类统计等）
     */
    FinalizeResponse finalizeInterview(List<Map<String, Object>> turns,
                                       List<Map<String, Object>> groups,
                                       String company,
                                       String position);

    /**
     * 全解析（parse）—— 一步直解：前解析 + 后解析串联，跳过人工校对。
     *
     * <p>用途：直解模式，先 {@link #previewParse(String)} 拿 turns/groups，有 group 就直接
     * {@link #finalizeInterview} 落库。识别不到任何 group 则抛业务错。
     *
     * @param text     原始面试文本
     * @param company  公司（可空）
     * @param position 岗位（可空）
     * @return 同 {@link #finalizeInterview} 的落库结果
     */
    FinalizeResponse parseInterview(String text, String company, String position);
}
