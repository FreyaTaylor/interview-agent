package com.interview.agent.interview.service.impl;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.interview.dto.CheckDuplicateResponse;
import com.interview.agent.interview.dto.DeleteResponse;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.InterviewHistoryDetailResponse;
import com.interview.agent.interview.dto.InterviewHistoryItem;
import com.interview.agent.interview.dto.SaveDraftResponse;
import com.interview.agent.interview.dto.UpdateMetaResponse;
import com.interview.agent.interview.entity.InterviewRecord;
import com.interview.agent.interview.mapper.InterviewEmbeddingBackfillRow;
import com.interview.agent.interview.mapper.InterviewRecordMapper;
import com.interview.agent.interview.service.InterviewBasicService;
import com.interview.agent.interview.service.InterviewParseService;
import com.interview.agent.infra.llm.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.interview.agent.interview.service.impl.InterviewServiceSupport.asMap;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.asMapList;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.asString;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.blankToNull;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.buildRawText;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.buildStats;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.dedupText;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.hasParsedGroups;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.sha256Hex;

/**
 * 面试复盘基础能力实现 —— 历史 / 查重 / 覆盖 / 草稿 / 继续校准 / 元数据 / 删除。
 *
 * <p>继续校准复用解析编排：先删旧记录，再调 {@link InterviewParseService#finalizeInterview} 走完整后解析流水。
 */
@Service
public class InterviewBasicServiceImpl implements InterviewBasicService {

    private static final Logger log = LoggerFactory.getLogger(InterviewBasicServiceImpl.class);

    private static final int HISTORY_LIMIT = 200;
    /** 语义查重余弦相似度阈值：≥ 此值判为同一场面试（pgvector 距离 ≤ 1 - 阈值）。 */
    private static final double DEDUP_SIM_THRESHOLD = 0.90;
    /** 单次查重最多懒回填多少条历史 embedding（控制首次调用的额外耗时）。 */
    private static final int BACKFILL_MAX_PER_CALL = 50;

    private final InterviewRecordMapper recordMapper;
    /** 继续校准复用解析编排的后解析（finalize）流水。 */
    private final InterviewParseService parseService;
    /** 整段面试文本向量化（语义查重）。 */
    private final EmbeddingService embeddingService;

    public InterviewBasicServiceImpl(InterviewRecordMapper recordMapper,
                                     InterviewParseService parseService,
                                     EmbeddingService embeddingService) {
        this.recordMapper = recordMapper;
        this.parseService = parseService;
        this.embeddingService = embeddingService;
    }

    @Override
    public List<InterviewHistoryItem> historyList() {
        List<InterviewRecord> rows = recordMapper.findRecent(CurrentUser.id(), HISTORY_LIMIT);
        List<InterviewHistoryItem> out = new ArrayList<>(rows.size());
        for (InterviewRecord r : rows) {
            Map<String, Object> parsed = asMap(r.parsedQuestions());
            List<Map<String, Object>> groups = asMapList(parsed.get("groups"));
            out.add(new InterviewHistoryItem(
                    r.id(),
                    r.company(),
                    r.position(),
                    r.avgScore(),
                    r.passEstimate(),
                    r.createdAt() == null ? null : r.createdAt().toString(),
                    !groups.isEmpty(),
                    r.draftTurns() != null && r.draftGroups() != null
            ));
        }
        return out;
    }

    @Override
    public InterviewHistoryDetailResponse historyDetail(long recordId) {
        InterviewRecord r = recordMapper.findById(recordId)
                .orElseThrow(() -> new BizException(40004, "记录不存在"));

        Map<String, Object> parsed = asMap(r.parsedQuestions());
        List<Map<String, Object>> groups = asMapList(parsed.get("groups"));
        List<Map<String, Object>> turns = asMapList(parsed.get("turns"));
        Map<String, Object> overall = asMap(parsed.get("overall_analysis"));
        if (overall.isEmpty()) {
            overall = Map.of("comment", r.summaryReport() == null ? "" : r.summaryReport());
        }
        String summary = asString(asMap(r.clusterResult()).get("summary"));

        return new InterviewHistoryDetailResponse(
                r.id(),
                r.company(),
                r.position(),
                r.rawText(),
                groups,
                turns,
                summary,
                buildStats(groups),
                r.avgScore() == null ? 0 : r.avgScore(),
                r.passEstimate() == null ? "" : r.passEstimate(),
                overall,
                groups.isEmpty(),
                r.createdAt() == null ? null : r.createdAt().toString(),
                r.draftTurns() != null && r.draftGroups() != null,
                !groups.isEmpty(),
                r.draftTurns(),
                r.draftGroups()
        );
    }

    @Override
    public CheckDuplicateResponse checkDuplicate(String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(40001, "面试文本不能为空");
        }
        long userId = CurrentUser.id();
        // 懒回填：feature 上线前落库的老记录无 embedding，先按需补算，否则查重对历史数据失效
        backfillMissingEmbeddings(userId);
        String vec;
        try {
            vec = embeddingService.embedToLiteral(dedupText(text));
        } catch (Exception e) {
            // embedding 不可用时不阻塞提交，视为不重复
            log.warn("语义查重 embedding 失败，跳过查重: {}", e.getMessage());
            return CheckDuplicateResponse.notDuplicate();
        }
        return recordMapper.findNearestByEmbedding(userId, vec)
                .filter(m -> (1.0 - m.distance()) >= DEDUP_SIM_THRESHOLD)
                .map(m -> new CheckDuplicateResponse(
                        true,
                        m.id(),
                        m.company(),
                        m.position(),
                        m.createdAt() == null ? null : m.createdAt().toString(),
                        m.avgScore()
                ))
                .orElseGet(CheckDuplicateResponse::notDuplicate);
    }

    /**
     * 按需为当前用户尚未生成 embedding 的历史记录补算（每次最多 {@link #BACKFILL_MAX_PER_CALL} 条）。
     * 补算用 raw_text（与 finalize 写入侧一致地走 dedupText 归一化），单条失败不影响整体。
     */
    private void backfillMissingEmbeddings(long userId) {
        List<InterviewEmbeddingBackfillRow> rows;
        try {
            rows = recordMapper.findMissingEmbedding(userId, BACKFILL_MAX_PER_CALL);
        } catch (Exception e) {
            log.warn("查询待回填 embedding 记录失败，跳过回填: {}", e.getMessage());
            return;
        }
        for (InterviewEmbeddingBackfillRow row : rows) {
            try {
                String literal = embeddingService.embedToLiteral(dedupText(row.rawText()));
                recordMapper.updateEmbedding(row.id(), literal);
            } catch (Exception e) {
                log.warn("回填面试记录 embedding 失败（不影响查重）record_id={}: {}", row.id(), e.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public DeleteResponse overwrite(long recordId) {
        deleteRecordOrThrow(recordId);
        return new DeleteResponse(true);
    }

    @Override
    @Transactional
    public SaveDraftResponse saveDraft(Long recordId,
                                       List<Map<String, Object>> turns,
                                       List<Map<String, Object>> groups,
                                       String company,
                                       String position) {
        if (turns == null || turns.isEmpty() || groups == null || groups.isEmpty()) {
            throw new BizException(40001, "turns/groups 不能为空");
        }
        if (recordId != null) {
            InterviewRecord record = recordMapper.findById(recordId)
                    .orElseThrow(() -> new BizException(40004, "记录不存在"));
            int affected = recordMapper.updateDraft(
                    record.id(),
                    turns,
                    groups,
                    blankToNull(company),
                    blankToNull(position)
            );
            if (affected == 0) {
                throw new BizException(50000, "草稿保存失败");
            }
            boolean hasParsed = hasParsedGroups(record.parsedQuestions());
            return new SaveDraftResponse(record.id(), !hasParsed, hasParsed);
        }

        String rawText = buildRawText(turns);
        long newId = recordMapper.insertDraft(
                CurrentUser.id(),
                rawText,
                blankToNull(company),
                blankToNull(position),
                sha256Hex(rawText.strip()),
                turns,
                groups
        );
        return new SaveDraftResponse(newId, true, false);
    }

    @Override
    @Transactional
    public FinalizeResponse historyRecalibrate(long recordId,
                                               List<Map<String, Object>> turns,
                                               List<Map<String, Object>> groups) {
        InterviewRecord record = recordMapper.findById(recordId)
                .orElseThrow(() -> new BizException(40004, "记录不存在"));
        deleteRecordOrThrow(recordId);
        return parseService.finalizeInterview(turns, groups, record.company(), record.position());
    }

    @Override
    @Transactional
    public DeleteResponse historyDelete(long recordId) {
        deleteRecordOrThrow(recordId);
        return new DeleteResponse(true);
    }

    @Override
    @Transactional
    public UpdateMetaResponse updateMeta(long recordId, String company, String position) {
        InterviewRecord record = recordMapper.findById(recordId)
                .orElseThrow(() -> new BizException(40004, "记录不存在"));
        int affected = recordMapper.updateMeta(
                record.id(),
                blankToNull(company),
                blankToNull(position)
        );
        if (affected == 0) {
            throw new BizException(50000, "更新失败");
        }
        InterviewRecord latest = recordMapper.findById(recordId)
                .orElseThrow(() -> new BizException(40004, "记录不存在"));
        return new UpdateMetaResponse(latest.id(), latest.company(), latest.position());
    }

    /** 删除记录前置校验：不存在抛 40004，删除影响行数为 0 抛 50000。 */
    private void deleteRecordOrThrow(long recordId) {
        if (recordMapper.findById(recordId).isEmpty()) {
            throw new BizException(40004, "记录不存在");
        }
        int affected = recordMapper.deleteById(recordId);
        if (affected == 0) {
            throw new BizException(50000, "删除失败");
        }
    }
}
