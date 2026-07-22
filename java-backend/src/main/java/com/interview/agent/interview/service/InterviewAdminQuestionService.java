package com.interview.agent.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.interview.dto.InterviewAdminQuestionItem;
import com.interview.agent.interview.dto.InterviewAdminQuestionRow;
import com.interview.agent.interview.dto.InterviewAdminRecordGroup;
import com.interview.agent.interview.dto.InterviewAdminTopicGroup;
import com.interview.agent.interview.mapper.InterviewAdminQuestionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理页「面试真题」三层视图（面试→主题→问题）读服务。
 *
 * <p>三张异构问题表各查一次 → 展开 questions 数组为单条问题（D2：拆）→ 按 record→topic 归并。
 * knowledge 主题活取知识点名（D1：不存冗余），已关联时主题只读。
 */
@Service
public class InterviewAdminQuestionService {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };

    private final InterviewAdminQuestionMapper mapper;

    public InterviewAdminQuestionService(InterviewAdminQuestionMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前用户全部面试问题，按 面试 → 主题 → 问题 三层嵌套；无数据返空列表。 */
    public List<InterviewAdminRecordGroup> listAllQuestions() {
        long userId = CurrentUser.id();
        List<InterviewAdminQuestionRow> rows = new ArrayList<>();
        rows.addAll(mapper.findKnowledge(userId));
        rows.addAll(mapper.findProject(userId));
        rows.addAll(mapper.findOther(userId));

        // recordId → (record 元信息 + topic → items)
        Map<Long, RecordAgg> byRecord = new LinkedHashMap<>();
        for (InterviewAdminQuestionRow r : rows) {
            RecordAgg agg = byRecord.computeIfAbsent(r.recordId(),
                    k -> new RecordAgg(r.recordId(), r.company(), r.position(),
                            r.createdAt() == null ? null : r.createdAt().toString()));
            List<InterviewAdminQuestionItem> items = agg.topics.computeIfAbsent(
                    r.topic() == null || r.topic().isBlank() ? "未分类" : r.topic(),
                    k -> new ArrayList<>());
            expandRow(r, items);
        }

        // record 按创建时间倒序（null 垫底）；topic 按名；item 按 refId, idx
        List<RecordAgg> aggs = new ArrayList<>(byRecord.values());
        aggs.sort(Comparator.comparing((RecordAgg a) -> a.createdAt == null ? "" : a.createdAt).reversed());

        List<InterviewAdminRecordGroup> out = new ArrayList<>(aggs.size());
        for (RecordAgg a : aggs) {
            List<InterviewAdminTopicGroup> topics = new ArrayList<>(a.topics.size());
            List<String> topicNames = new ArrayList<>(a.topics.keySet());
            topicNames.sort(Comparator.naturalOrder());
            for (String t : topicNames) {
                List<InterviewAdminQuestionItem> items = a.topics.get(t);
                items.sort(Comparator.comparingLong(InterviewAdminQuestionItem::refId)
                        .thenComparingInt(InterviewAdminQuestionItem::idx));
                topics.add(new InterviewAdminTopicGroup(t, items));
            }
            out.add(new InterviewAdminRecordGroup(a.recordId, a.company, a.position, a.createdAt, topics));
        }
        return out;
    }

    /** 把一行（可能是 questions 数组）展开成若干问题节点。 */
    private void expandRow(InterviewAdminQuestionRow r, List<InterviewAdminQuestionItem> sink) {
        if ("other".equals(r.refType())) {
            String text = r.content() == null ? "" : r.content();
            sink.add(new InterviewAdminQuestionItem(r.refType(), r.refId(), 0, text, r.topicEditable(),
                    r.leetcodeUrl(), r.leetcodeTitle()));
            return;
        }
        // knowledge / project：questions jsonb 数组，逐元素展开
        List<String> qs = parseQuestions(r.questionsJson());
        for (int i = 0; i < qs.size(); i++) {
            String text = qs.get(i) == null ? "" : qs.get(i);
            sink.add(new InterviewAdminQuestionItem(r.refType(), r.refId(), i, text, r.topicEditable(),
                    null, null));
        }
    }

    /** questions jsonb::text → List<String>；解析失败返空。 */
    private static List<String> parseQuestions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = JsonUtil.fromJson(json, STR_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ============================================================
    // 写：改文本 / 改主题 / 删除（按 refType 分派；0 行受影响 → 抛业务异常）
    // ============================================================

    /** 改问题文本：knowledge/project 改 questions[idx]，other 改 content。 */
    @Transactional
    public void updateText(String refType, long refId, int idx, String text) {
        long userId = CurrentUser.id();
        int n = switch (nz(refType)) {
            case "knowledge" -> mapper.updateKnowledgeText(userId, refId, idx, nz(text));
            case "project" -> mapper.updateProjectText(userId, refId, idx, nz(text));
            case "other" -> mapper.updateOtherContent(userId, refId, nz(text));
            default -> 0;
        };
        if (n == 0) {
            throw new BizException(40400, "问题不存在或下标越界");
        }
    }

    /** 改主题：project=project_name，other=tag，knowledge=tag（已关联知识点则拒绝）。 */
    @Transactional
    public void updateTopic(String refType, long refId, String topic) {
        long userId = CurrentUser.id();
        String t = nz(topic).isBlank() ? "未分类" : topic;
        int n = switch (nz(refType)) {
            case "project" -> mapper.updateProjectTopic(userId, refId, t);
            case "other" -> mapper.updateOtherTopic(userId, refId, t);
            case "knowledge" -> mapper.updateKnowledgeTopicIfUnlinked(userId, refId, t);
            default -> 0;
        };
        if (n == 0) {
            // knowledge 已关联时改主题会被 SQL 的 NOT EXISTS 挡住 → 0 行
            throw new BizException(40003, "该题主题来自关联知识点或不存在，不可在此修改");
        }
    }

    /** 删问题：knowledge/project 删 questions[idx]（删空则删整行），other 删整行。 */
    @Transactional
    public void deleteQuestion(String refType, long refId, int idx) {
        long userId = CurrentUser.id();
        int n = switch (nz(refType)) {
            case "knowledge" -> {
                int r = mapper.removeKnowledgeElement(userId, refId, idx);
                mapper.deleteKnowledgeIfEmpty(userId, refId);
                yield r;
            }
            case "project" -> {
                int r = mapper.removeProjectElement(userId, refId, idx);
                mapper.deleteProjectIfEmpty(userId, refId);
                yield r;
            }
            case "other" -> mapper.deleteOther(userId, refId);
            default -> 0;
        };
        if (n == 0) {
            throw new BizException(40400, "问题不存在");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 归并中间态：一场面试的元信息 + 主题桶。 */
    private static final class RecordAgg {
        final long recordId;
        final String company;
        final String position;
        final String createdAt;
        final Map<String, List<InterviewAdminQuestionItem>> topics = new LinkedHashMap<>();

        RecordAgg(long recordId, String company, String position, String createdAt) {
            this.recordId = recordId;
            this.company = company;
            this.position = position;
            this.createdAt = createdAt;
        }
    }
}
