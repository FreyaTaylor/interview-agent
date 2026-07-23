package com.interview.agent.interview.exp.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.infra.llm.QwenVisionClient;
import com.interview.agent.interview.exp.dto.ExpQuestionMatch;
import com.interview.agent.interview.exp.dto.InterviewExpParseResult;
import com.interview.agent.interview.exp.entity.InterviewExpNode;
import com.interview.agent.interview.exp.mapper.InterviewExpNodeMapper;
import com.interview.agent.interview.exp.mapper.InterviewExpSourceMapper;
import com.interview.agent.interview.exp.mapper.QuestionSourceLinkMapper;
import com.interview.agent.interview.exp.service.InterviewExpParseService;
import com.interview.agent.prompts.PromptKeys;
import com.interview.agent.prompts.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面经解析实现 —— 文本 → 问题清单（rewrite + 分域 + 语义去重 + 计频）。
 *
 * <p>核心两层去重：
 * <ol>
 *   <li><b>来源级</b>（防同篇灌水）：规范化文本 SHA-256 精确命中 → 整篇拒；否则整篇 embedding 与历史来源
 *       余弦距离 ≤ {@link #SOURCE_DISTANCE_MAX} → 整篇拒（同文改写转发）。</li>
 *   <li><b>问题级</b>（计频）：每题 rewrite 后 embedding，在其知识域内召回最近问题，
 *       距离 ≤ {@link #QUESTION_DISTANCE_MAX} → 判为同一问题（仅写 link 计频、不新建）。</li>
 * </ol>
 *
 * <p>域一致性：解析前把已有域清单喂进 prompt 要求原样复用；后端再按 {@link #normDomain} 归一（含少量别名）兜底。
 * <p>失败降级：单题处理异常 → 跳过 + warn，不阻断整批（对齐 LeetCode enrich 风格）。
 * embedding 不可用（未配 key）→ safeEmbed 返 null，去重降级为「全部新建」，不阻断落库。
 */
@Service
public class InterviewExpParseServiceImpl implements InterviewExpParseService {

    private static final Logger log = LoggerFactory.getLogger(InterviewExpParseServiceImpl.class);

    // 来源模糊去重：cosine 相似度 ≥ 0.90 ⇒ 距离 ≤ 0.10 判为同篇
    private static final double SOURCE_DISTANCE_MAX = 0.10;
    // 问题语义去重：cosine 相似度 ≥ 0.86 ⇒ 距离 ≤ 0.14 判为同题
    private static final double QUESTION_DISTANCE_MAX = 0.14;

    private static final double PARSE_TEMPERATURE = 0.2;
    private static final int PARSE_MAX_TOKENS = 4096;
    private static final int PARSE_MAX_RETRY = 2;

    private static final String DEFAULT_DOMAIN = "其他";

    /** 域名别名归一（小写 key）：把常见同义写法收敛到规范名，防域分裂。 */
    private static final Map<String, String> DOMAIN_ALIAS = Map.of(
            "mysql数据库", "mysql",
            "计算机网络原理", "计算机网络",
            "操作系统原理", "操作系统",
            "java并发", "java",
            "java多线程", "java"
    );

    private final InterviewExpNodeMapper repo;
    private final InterviewExpSourceMapper sourceMapper;
    private final QuestionSourceLinkMapper linkMapper;
    private final EmbeddingService embeddingService;
    private final LlmInvoker llmInvoker;
    private final PromptService promptService;
    private final QwenVisionClient visionClient;

    public InterviewExpParseServiceImpl(InterviewExpNodeMapper repo,
                                        InterviewExpSourceMapper sourceMapper,
                                        QuestionSourceLinkMapper linkMapper,
                                        EmbeddingService embeddingService,
                                        LlmInvoker llmInvoker,
                                        PromptService promptService,
                                        QwenVisionClient visionClient) {
        this.repo = repo;
        this.sourceMapper = sourceMapper;
        this.linkMapper = linkMapper;
        this.embeddingService = embeddingService;
        this.llmInvoker = llmInvoker;
        this.promptService = promptService;
        this.visionClient = visionClient;
    }

    /** LLM 抽题输出的单条。 */
    private record ParsedItem(String domain, String question) {
    }

    @Override
    @Transactional
    public InterviewExpParseResult parseFromImage(String imageBase64, String mediaType) {
        // 先 OCR 转录为文本（视觉模型），再复用文本解析主流水
        String ocrPrompt = promptService.render(PromptKeys.INTERVIEW_EXP_OCR, Map.of());
        String recognized = visionClient.parseImage(imageBase64, mediaType, ocrPrompt, 0.0);
        if (recognized == null || recognized.isBlank()) {
            throw new BizException(50000, "图片未识别出文字，请换一张更清晰的截图或改用粘贴文本");
        }
        return parseFromText(recognized);
    }

    @Override
    @Transactional
    public InterviewExpParseResult parseFromText(String text) {
        // Step 1: 校验 + 规范化 + hash
        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            throw new BizException(40001, "面经文本不能为空");
        }
        long userId = CurrentUser.id();
        String hash = sha256Hex(normalized);

        // Step 2: 来源精确去重（hash）
        if (sourceMapper.findIdByHash(userId, hash).isPresent()) {
            return duplicate("这篇面经已导入过（内容完全相同），已跳过。");
        }

        // Step 3: 来源模糊去重（整篇 embedding）
        String sourceEmb = safeEmbed(normalized);
        if (sourceEmb != null) {
            Optional<Double> nearest = sourceMapper.nearestDistance(userId, sourceEmb);
            if (nearest.isPresent() && nearest.get() <= SOURCE_DISTANCE_MAX) {
                return duplicate("这篇面经与已导入的某篇高度相似（疑似同文转发），已跳过。");
            }
        }

        // Step 4: 落来源
        long sourceId = (sourceEmb == null)
                ? sourceMapper.insertWithoutEmbedding(userId, normalized, hash)
                : sourceMapper.insertWithEmbedding(userId, normalized, hash, sourceEmb);

        // Step 5: LLM 抽题 + rewrite + 判域（已有域清单喂进 prompt 促复用）
        Map<String, Long> domainIdByKey = new LinkedHashMap<>();
        for (InterviewExpNode d : repo.findDomains(userId)) {
            domainIdByKey.put(normDomain(d.name()), d.id());
        }
        List<ParsedItem> items = parseItems(normalized, domainIdByKey.keySet());

        // Step 6: 逐题落库（域匹配/新建 → 问题域内语义去重 → 写 link 计频）
        int newQuestions = 0, matchedQuestions = 0, newDomains = 0;
        for (ParsedItem it : items) {
            try {
                String question = it.question() == null ? "" : it.question().strip();
                if (question.isEmpty()) {
                    continue;
                }
                // 6.1 域：归一后查内存表；无则新建 domain 节点
                String domainName = it.domain() == null || it.domain().isBlank() ? DEFAULT_DOMAIN : it.domain().strip();
                String domainKey = normDomain(domainName);
                Long domainId = domainIdByKey.get(domainKey);
                if (domainId == null) {
                    domainId = createDomain(userId, domainName);
                    domainIdByKey.put(domainKey, domainId);
                    newDomains++;
                }
                // 6.2 问题：域内语义去重
                String qEmb = safeEmbed(question);
                Long matchedQid = null;
                if (qEmb != null) {
                    Optional<ExpQuestionMatch> m = repo.findNearestQuestionInDomain(userId, domainId, qEmb);
                    if (m.isPresent() && m.get().distance() <= QUESTION_DISTANCE_MAX) {
                        matchedQid = m.get().id();
                    }
                }
                long qid;
                if (matchedQid != null) {
                    qid = matchedQid;
                    matchedQuestions++;
                } else {
                    qid = createQuestion(userId, domainId, question, qEmb);
                    newQuestions++;
                }
                // 6.3 写来源关联（幂等）→ 频率
                linkMapper.insertIgnore(qid, sourceId);
            } catch (Exception e) {
                log.warn("[InterviewExpParse] 单题落库失败，跳过: {}", e.getMessage());
            }
        }

        log.info("[InterviewExpParse] source={} parsed={} new={} matched={} newDomains={}",
                sourceId, items.size(), newQuestions, matchedQuestions, newDomains);
        return new InterviewExpParseResult(false, "解析完成", sourceId,
                items.size(), newQuestions, matchedQuestions, newDomains);
    }

    // ============================================================
    // 内部
    // ============================================================

    private List<ParsedItem> parseItems(String text, java.util.Collection<String> existingDomainKeys) {
        String existing = existingDomainKeys.isEmpty() ? "（暂无）" : String.join("、", existingDomainKeys);
        Map<String, Object> vars = Map.of("text", text, "existing_domains", existing);
        LlmInvoker.Spec spec = new LlmInvoker.Spec(
                PromptKeys.INTERVIEW_EXP_PARSE, vars, PARSE_TEMPERATURE, PARSE_MAX_TOKENS, PARSE_MAX_RETRY);
        return llmInvoker.invoke(spec, InterviewExpParseServiceImpl::parse).orElseGet(List::of);
    }

    private static List<ParsedItem> parse(String raw) {
        List<ParsedItem> list = JsonUtil.extractJson(raw, new TypeReference<List<ParsedItem>>() {
        });
        return list == null ? List.of() : list;
    }

    private long createDomain(long userId, String name) {
        String emb = safeEmbed(name);
        return emb == null
                ? repo.insertWithoutEmbedding(userId, null, name, (short) 1, "domain", 0)
                : repo.insertWithEmbedding(userId, null, name, (short) 1, "domain", 0, emb);
    }

    private long createQuestion(long userId, long domainId, String question, String qEmb) {
        return qEmb == null
                ? repo.insertWithoutEmbedding(userId, domainId, question, (short) 2, "question", 0)
                : repo.insertWithEmbedding(userId, domainId, question, (short) 2, "question", 0, qEmb);
    }

    private static InterviewExpParseResult duplicate(String msg) {
        return new InterviewExpParseResult(true, msg, 0, 0, 0, 0, 0);
    }

    /** 域名归一：strip + 小写 + 去空格，再查别名映射（收敛同义域名）。 */
    private static String normDomain(String name) {
        String key = name == null ? "" : name.strip().toLowerCase().replace(" ", "");
        return DOMAIN_ALIAS.getOrDefault(key, key);
    }

    private String safeEmbed(String text) {
        try {
            return embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            log.warn("[InterviewExpParse] embedding failed, fallback null: {}", e.getMessage());
            return null;
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(50000, "计算文本 hash 失败");
        }
    }
}
