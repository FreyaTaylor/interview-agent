package com.interview.agent.interview.service.impl;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.PreviewParseResponse;
import com.interview.agent.interview.mapper.InterviewKnowledgeQuestionMapper;
import com.interview.agent.interview.mapper.InterviewOtherQuestionMapper;
import com.interview.agent.interview.mapper.InterviewRecordMapper;
import com.interview.agent.interview.matcher.InterviewNodeMatcher;
import com.interview.agent.interview.service.InterviewParseService;
import com.interview.agent.interview.service.InterviewParserService;
import com.interview.agent.interview.service.InterviewScorerService;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.project.mapper.InterviewProjectQuestionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.interview.agent.interview.service.impl.InterviewServiceSupport.asLongOrNull;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.asString;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.blankToNull;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.buildRawText;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.buildStats;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.dedupText;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.defaultTag;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.firstQuestion;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.normalizeGroups;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.sha256Hex;

/**
 * 面试解析编排实现 —— 前 / 后 / 全 三个入口的具体流水。
 *
 * <p>注意命名区分：
 * <ul>
 *   <li>本类 {@code InterviewParseServiceImpl}：<b>编排层</b>，负责校验/归一化/匹配/评分/落库/副作用。</li>
 *   <li>注入的 {@link InterviewParserService}：<b>底层 LLM 解析引擎</b>，只把原始文本拆成 turns/groups。</li>
 * </ul>
 */
@Service
public class InterviewParseServiceImpl implements InterviewParseService {

    private static final Logger log = LoggerFactory.getLogger(InterviewParseServiceImpl.class);

    /** 底层 LLM 解析引擎（原始文本 → turns/groups），非本编排服务自身。 */
    private final InterviewParserService parserService;
    private final InterviewScorerService scorerService;
    private final InterviewNodeMatcher nodeMatcher;
    private final InterviewRecordMapper recordMapper;
    private final InterviewKnowledgeQuestionMapper knowledgeQuestionMapper;
    private final InterviewProjectQuestionMapper projectQuestionMapper;
    private final InterviewOtherQuestionMapper otherQuestionMapper;
    /** 整段面试文本向量化（语义查重）。 */
    private final EmbeddingService embeddingService;

    public InterviewParseServiceImpl(InterviewParserService parserService,
                                     InterviewScorerService scorerService,
                                     InterviewNodeMatcher nodeMatcher,
                                     InterviewRecordMapper recordMapper,
                                     InterviewKnowledgeQuestionMapper knowledgeQuestionMapper,
                                     InterviewProjectQuestionMapper projectQuestionMapper,
                                     InterviewOtherQuestionMapper otherQuestionMapper,
                                     EmbeddingService embeddingService) {
        this.parserService = parserService;
        this.scorerService = scorerService;
        this.nodeMatcher = nodeMatcher;
        this.recordMapper = recordMapper;
        this.knowledgeQuestionMapper = knowledgeQuestionMapper;
        this.projectQuestionMapper = projectQuestionMapper;
        this.otherQuestionMapper = otherQuestionMapper;
        this.embeddingService = embeddingService;
    }

    // ============================================================
    // 前解析（preview）—— 仅 LLM 拆分，不落库
    // ============================================================

    /**
     * 前解析：原始文本 → 结构化 {@code turns + groups}，<b>不写库</b>。
     *
     * <p>这是校对模式 Step 1：把 LLM 拆分结果交给前端校对页，用户可改分组 / 调 turn 归属 / 修分类，
     * 改完再走后解析 finalize。本方法只做空文本校验，真正的并发分段 + embedding 边界合并 + 锚点重写
     * 都在底层引擎 {@link InterviewParserService#parse(String)} 内。
     *
     * @param text 原始面试文本（语音转写或手动输入）
     * @return {@code {turns, groups, summary?}}
     * @throws BizException 文本为空时 40001
     */
    @Override
    public PreviewParseResponse previewParse(String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(40001, "面试文本不能为空");
        }
        return parserService.parse(text);
    }

    // ============================================================
    // 后解析（finalize）—— 落库 + 评分主流水
    // ============================================================

    /**
     * 后解析：用户校对后的 {@code turns + groups} → 匹配 / 评分 / 落库 / 副作用。
     *
     * <p>面试记录真正写库的唯一入口（直解 parse 与继续校准 recalibrate 最终都汇到这里）。流水 5 步：
     * <ol>
     *   <li><b>Step 1 校验 + 归一化</b>：turns/groups 非空校验；
     *       {@link InterviewServiceSupport#normalizeGroups} 依据 turns 回填
     *       {@code questions/user_answer/original_dialogue}，并把统一字段 {@code tag} 映射回
     *       knowledge 的 {@code knowledge_point} / project 的 {@code topic}。</li>
     *   <li><b>Step 2 节点匹配</b>：{@link InterviewNodeMatcher#matchNodes} 给每组补
     *       {@code matched_node_id}（knowledge：embedding 召回 + LLM rerank，未命中建占位叶子）/
     *       {@code matched_project_id}（project：根→话题→问题叶子三级匹配）。</li>
     *   <li><b>Step 3 评分</b>：{@link InterviewScorerService#scoreAll} 逐组 rubric 评分，
     *       产出每组 {@code score_result}、整体 {@code overall_analysis} 与 {@code avg_score / pass_estimate}。</li>
     *   <li><b>Step 4 落库</b>：写 {@code interview_record}（{@code text_hash = SHA-256(raw_text.strip())}，
     *       raw_text 由 turns 拼），再按 {@code group.type} 分流落 knowledge/project/other 三张子表。</li>
     *   <li><b>Step 5 副作用</b>：命中知识点 {@code interview_weight +1}（上限 5）、
     *       knowledge/project 类用户回答向量化入 {@code user_answer_embedding}（Agent 长期记忆）。</li>
     * </ol>
     * 全流程 {@code @Transactional}：任一步抛异常则整体回滚，不会留下半条脏记录。
     *
     * @param turns    校对后的对话轮次（source of truth）
     * @param groups   校对后的分组
     * @param company  公司（空串转 null）
     * @param position 岗位（空串转 null）
     * @return 落库结果（record_id、turns、scoredGroups、stats、avg_score、pass_estimate、overall_analysis）
     * @throws BizException turns/groups 为空时 40001
     */
    @Override
    @Transactional
    public FinalizeResponse finalizeInterview(List<Map<String, Object>> turns,
                                              List<Map<String, Object>> groups,
                                              String company,
                                              String position) {
        // Step 1: 校验 + 归一化
        if (turns == null || turns.isEmpty() || groups == null || groups.isEmpty()) {
            throw new BizException(40001, "turns/groups 不能为空");
        }
        List<Map<String, Object>> normalizedGroups = normalizeGroups(turns, groups);

        // Step 2: 节点匹配（knowledge embedding 召回/占位叶子；project 三级匹配）→ 写回 matched_*
        List<Map<String, Object>> matchedGroups = nodeMatcher.matchNodes(normalizedGroups);

        // Step 3: 评分（matched_* 字段随 group 透传进评分结果）
        InterviewScorerService.ScoreBundle scoreBundle = scorerService.scoreAll(matchedGroups, company, position);
        List<Map<String, Object>> scoredGroups = scoreBundle.scoredGroups();

        // Step 4: 落主记录
        String rawText = buildRawText(turns);
        Map<String, Object> parsedQuestions = new LinkedHashMap<>();
        parsedQuestions.put("groups", scoredGroups);
        parsedQuestions.put("turns", turns);
        parsedQuestions.put("overall_analysis", scoreBundle.overallAnalysis());

        Map<String, Object> clusterResult = new LinkedHashMap<>();
        clusterResult.put("summary", "");

        long recordId = recordMapper.insert(
                CurrentUser.id(),
                rawText,
                blankToNull(company),
                blankToNull(position),
                sha256Hex(rawText.strip()),
                parsedQuestions,
                clusterResult,
                asString(scoreBundle.overallAnalysis().get("comment"))
        );

        // 副作用（在 updateFinalize 之前）：命中知识点 interview_weight +1 + 计算错题本(performance)。
        // P4（三模块解耦）：不再把真题落到知识树（真题留面试模块，经 interview_question_kp_link 关联）。
        // updateKnowledgeWeights 会给每个 knowledge group 附上 performance（错题本），
        // 故须在此之后再 updateFinalize，才能把带 performance 的 parsedQuestions 落库。
        nodeMatcher.updateKnowledgeWeights(scoredGroups);

        recordMapper.updateFinalize(
                recordId,
                scoreBundle.avgScore(),
                scoreBundle.passEstimate(),
                parsedQuestions,
                asString(scoreBundle.overallAnalysis().get("comment"))
        );

        // 语义查重：整段面试文本向量化回写（失败不影响主流程）
        try {
            recordMapper.updateEmbedding(recordId, embeddingService.embedToLiteral(dedupText(rawText)));
        } catch (Exception e) {
            log.warn("面试记录 embedding 写入失败（不影响主流程）record_id={}: {}", recordId, e.getMessage());
        }

        // Step 4: 子表分流
        for (Map<String, Object> g : scoredGroups) {
            String type = asString(g.get("type"));
            if ("knowledge".equals(type)) {
                long ikqId = knowledgeQuestionMapper.insert(
                        recordId,
                        defaultTag(g),
                        g.get("questions"),
                        asString(g.get("user_answer")),
                        asString(g.get("original_dialogue")),
                        g.get("score_result")
                );
                // P2：给该真题写「相关知识点」关联快照（语义召回 top-k，写 interview_question_kp_link）
                nodeMatcher.linkRelatedKnowledge(ikqId, asString(g.get("knowledge_point")));
            } else if ("project".equals(type)) {
                projectQuestionMapper.insert(
                        recordId,
                        asLongOrNull(g.get("matched_project_id")),
                        asString(g.get("project_name")).isBlank() ? defaultTag(g) : asString(g.get("project_name")),
                        g.get("questions"),
                        asString(g.get("user_answer")),
                        asString(g.get("original_dialogue")),
                        g.get("score_result")
                );
            } else {
                otherQuestionMapper.insert(
                        recordId,
                        firstQuestion(g),
                        defaultTag(g),
                        asString(g.get("user_answer")),
                        g.get("score_result")
                );
            }
        }

        return new FinalizeResponse(
                recordId,
                turns,
                scoredGroups,
                buildStats(scoredGroups),
                scoreBundle.avgScore(),
                scoreBundle.passEstimate(),
                scoreBundle.overallAnalysis()
        );
    }

    // ============================================================
    // 全解析（parse）—— 前 + 后串联，一步直解
    // ============================================================

    /**
     * 全解析：前解析 + 后解析串联，跳过人工校对。
     *
     * @param text     原始面试文本
     * @param company  公司（可空）
     * @param position 岗位（可空）
     * @return 同 {@link #finalizeInterview} 的落库结果
     * @throws BizException 文本为空（40001）或识别不到任何 group（40001）
     */
    @Override
    @Transactional
    public FinalizeResponse parseInterview(String text, String company, String position) {
        PreviewParseResponse preview = previewParse(text);
        if (preview.groups().isEmpty()) {
            throw new BizException(40001, preview.summary().isBlank() ? "未识别出面试问题" : preview.summary());
        }
        return finalizeInterview(preview.turns(), preview.groups(), company, position);
    }
}
