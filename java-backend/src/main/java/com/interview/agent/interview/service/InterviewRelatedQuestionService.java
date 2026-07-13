package com.interview.agent.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.interview.dto.RelatedInterviewQuestion;
import com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper;
import com.interview.agent.project.dto.RelatedProjectQuestion;
import com.interview.agent.project.mapper.InterviewProjectQuestionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「知识点/项目 → 相关面试真题」只读查询服务（三模块解耦 P3/P5）。
 *
 * <p>真题属面试模块，知识点/项目只读引用：查关联（知识点走 interview_question_kp_link；
 * 项目走 interview_project_question.project_node_id 落在项目子树内），解析真题题干后返回。
 */
@Service
public class InterviewRelatedQuestionService {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };

    private final InterviewQuestionKpLinkMapper linkMapper;
    private final InterviewProjectQuestionMapper projectQuestionMapper;

    public InterviewRelatedQuestionService(InterviewQuestionKpLinkMapper linkMapper,
                                           InterviewProjectQuestionMapper projectQuestionMapper) {
        this.linkMapper = linkMapper;
        this.projectQuestionMapper = projectQuestionMapper;
    }

    /** 查某知识点关联的所有面试真题（按相似度降序）。 */
    public List<RelatedInterviewQuestion> byKnowledgePoint(long kpId) {
        return linkMapper.findRelatedByKp(CurrentUser.id(), kpId).stream()
                .map(r -> new RelatedInterviewQuestion(
                        r.id(), parseQuestions(r.questions()), r.tag(), r.similarity(),
                        r.interviewRecordId(), r.company(), r.position(), r.createdAt()))
                .toList();
    }

    /** 查某项目（子树内）关联的所有面试真题。 */
    public List<RelatedProjectQuestion> byProject(long projectId) {
        return projectQuestionMapper.findRelatedByProject(CurrentUser.id(), projectId).stream()
                .map(r -> new RelatedProjectQuestion(
                        r.id(), parseQuestions(r.questions()), r.projectName(),
                        r.interviewRecordId(), r.company(), r.position(), r.createdAt()))
                .toList();
    }

    /** jsonb::text 的真题题干数组 → List&lt;String&gt;；解析失败返空。 */
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
}
