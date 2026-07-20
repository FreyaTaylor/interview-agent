package com.interview.agent.interview.service;

import com.interview.agent.interview.dto.CheckDuplicateResponse;
import com.interview.agent.interview.dto.DeleteResponse;
import com.interview.agent.interview.dto.FinalizeResponse;
import com.interview.agent.interview.dto.InterviewHistoryDetailResponse;
import com.interview.agent.interview.dto.InterviewHistoryItem;
import com.interview.agent.interview.dto.SaveDraftResponse;
import com.interview.agent.interview.dto.UpdateMetaResponse;

import java.util.List;
import java.util.Map;

/**
 * 面试复盘基础服务 —— 解析（前/后/全）与 ASR 之外的其余能力。
 *
 * <p>聚合「历史读取 / 重复检测 / 覆盖 / 草稿 / 继续校准 / 元数据修改 / 删除」等围绕已有记录的操作。
 * 其中继续校准 {@link #historyRecalibrate} 复用 {@link InterviewParseService#finalizeInterview} 完成重算。
 */
public interface InterviewBasicService {

    /** 历史列表（按创建时间倒序，上限 200 条）。 */
    List<InterviewHistoryItem> historyList();

    /** 历史详情：某条面试的完整快照（turns / groups / rubric / overall）。 */
    InterviewHistoryDetailResponse historyDetail(long recordId);

    /** 语义重复检测：embed 整段面试文本 → pgvector 余弦最近邻；命中返回旧记录信息供用户选择覆盖。 */
    CheckDuplicateResponse checkDuplicate(String text);

    /** 覆盖记录：删除 record + 子表（用户随后重走 finalize）。 */
    DeleteResponse overwrite(long recordId);

    /** 保存草稿（recordId 为空新建草稿态、非空更新 draft 字段），不跑解析/评分。 */
    SaveDraftResponse saveDraft(Long recordId,
                                List<Map<String, Object>> turns,
                                List<Map<String, Object>> groups,
                                String company,
                                String position);

    /** 继续校准：删旧记录后用新 turns/groups 重算落库（record_id 变化）。 */
    FinalizeResponse historyRecalibrate(long recordId,
                                        List<Map<String, Object>> turns,
                                        List<Map<String, Object>> groups);

    /** 删除历史记录及其子表问题。 */
    DeleteResponse historyDelete(long recordId);

    /** 仅更新 company/position 元数据，不动解析结果。 */
    UpdateMetaResponse updateMeta(long recordId, String company, String reviewStatus);
}
