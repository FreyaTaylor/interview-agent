package com.interview.agent.interview.service.impl;

import com.interview.agent.common.BizException;
import com.interview.agent.interview.dto.CheckDuplicateResponse;
import com.interview.agent.interview.dto.DeleteResponse;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.InterviewHistoryDetailResponse;
import com.interview.agent.interview.dto.InterviewHistoryItem;
import com.interview.agent.interview.dto.SaveDraftResponse;
import com.interview.agent.interview.dto.UpdateMetaResponse;
import com.interview.agent.interview.entity.InterviewRecord;
import com.interview.agent.interview.mapper.InterviewRecordMapper;
import com.interview.agent.interview.service.InterviewBasicService;
import com.interview.agent.interview.service.InterviewParseService;
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
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.hasParsedGroups;
import static com.interview.agent.interview.service.impl.InterviewServiceSupport.sha256Hex;

/**
 * 面试复盘基础能力实现 —— 历史 / 查重 / 覆盖 / 草稿 / 继续校准 / 元数据 / 删除。
 *
 * <p>继续校准复用解析编排：先删旧记录，再调 {@link InterviewParseService#finalizeInterview} 走完整后解析流水。
 */
@Service
public class InterviewBasicServiceImpl implements InterviewBasicService {

    private static final int HISTORY_LIMIT = 200;

    private final InterviewRecordMapper recordMapper;
    /** 继续校准复用解析编排的后解析（finalize）流水。 */
    private final InterviewParseService parseService;

    public InterviewBasicServiceImpl(InterviewRecordMapper recordMapper,
                                     InterviewParseService parseService) {
        this.recordMapper = recordMapper;
        this.parseService = parseService;
    }

    @Override
    public List<InterviewHistoryItem> historyList() {
        List<InterviewRecord> rows = recordMapper.findRecent(HISTORY_LIMIT);
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
    public CheckDuplicateResponse checkDuplicate(String textHash) {
        if (textHash == null || textHash.isBlank()) {
            throw new BizException(40001, "text_hash 不能为空");
        }
        return recordMapper.findByTextHash(textHash)
                .map(r -> new CheckDuplicateResponse(
                        true,
                        r.id(),
                        r.company(),
                        r.position(),
                        r.createdAt() == null ? null : r.createdAt().toString(),
                        r.avgScore()
                ))
                .orElseGet(CheckDuplicateResponse::notDuplicate);
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
