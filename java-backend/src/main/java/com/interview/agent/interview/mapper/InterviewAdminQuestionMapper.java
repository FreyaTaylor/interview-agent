package com.interview.agent.interview.mapper;

import com.interview.agent.interview.dto.InterviewAdminQuestionRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 管理页「面试真题」三层视图的读查询 —— 三张异构问题表各查一次，统一成扁平 {@link InterviewAdminQuestionRow}。
 *
 * <p>写操作（改文本/改主题/删除）见后续 slice；此 mapper 先只放读。
 * knowledge 主题遵循 D1「不存冗余」：{@code LEFT JOIN LATERAL} 经 kp_link 取**活**知识点名（tree_node.name），
 * 不读 kp_link.knowledge_point_name 快照；有 link → 主题只读（topic_editable=false）。
 */
@Mapper
public interface InterviewAdminQuestionMapper {

    /** 八股知识题：主题=活知识点名（相似度最高的一条 link；无则 tag），已关联时主题只读。 */
    @Select("""
            SELECT 'knowledge' AS ref_type, ikq.id AS ref_id, r.id AS record_id,
                   r.company, r.position, ikq.created_at,
                   COALESCE(kp.name, NULLIF(ikq.tag, ''), '未分类') AS topic,
                   ikq.questions::text AS questions_json,
                   NULL AS content,
                   (kp.name IS NULL) AS topic_editable
            FROM interview_knowledge_question ikq
            JOIN interview_record r ON r.id = ikq.interview_record_id
            LEFT JOIN LATERAL (
                SELECT tn.name
                FROM interview_question_kp_link l
                JOIN tree_node tn ON tn.id = l.knowledge_point_id
                WHERE l.interview_knowledge_question_id = ikq.id AND l.knowledge_point_id IS NOT NULL
                ORDER BY l.similarity DESC NULLS LAST, l.id
                LIMIT 1
            ) kp ON true
            WHERE r.user_id = #{userId}
            """)
    List<InterviewAdminQuestionRow> findKnowledge(@Param("userId") long userId);

    /** 项目题：主题=project_name。 */
    @Select("""
            SELECT 'project' AS ref_type, ipq.id AS ref_id, r.id AS record_id,
                   r.company, r.position, ipq.created_at,
                   COALESCE(NULLIF(ipq.project_name, ''), '未分类') AS topic,
                   ipq.questions::text AS questions_json,
                   NULL AS content,
                   TRUE AS topic_editable
            FROM interview_project_question ipq
            JOIN interview_record r ON r.id = ipq.interview_record_id
            WHERE r.user_id = #{userId}
            """)
    List<InterviewAdminQuestionRow> findProject(@Param("userId") long userId);

    /** 其他题（算法/HR/…）：主题=tag，题干=content（单串）。 */
    @Select("""
            SELECT 'other' AS ref_type, ioq.id AS ref_id, r.id AS record_id,
                   r.company, r.position, ioq.created_at,
                   COALESCE(NULLIF(ioq.tag, ''), '未分类') AS topic,
                   NULL AS questions_json,
                   ioq.content AS content,
                   TRUE AS topic_editable
            FROM interview_other_question ioq
            JOIN interview_record r ON r.id = ioq.interview_record_id
            WHERE r.user_id = #{userId}
            """)
    List<InterviewAdminQuestionRow> findOther(@Param("userId") long userId);

    // ============================================================
    // 写：改文本 / 改主题 / 删除（都按 user_id 经 interview_record 作用域校验，防越权）
    // 返回受影响行数，0 → 越权/不存在/被拒。
    // ============================================================

    String OWN = " interview_record_id IN (SELECT id FROM interview_record WHERE user_id = #{userId}) ";

    /** 改 knowledge/project 的第 idx 个问题文本（jsonb_set；idx 越界则 0 行）。 */
    @Update("UPDATE interview_knowledge_question"
            + " SET questions = jsonb_set(questions, ARRAY[#{idx}::text], to_jsonb(#{text}::text))"
            + " WHERE id = #{refId} AND jsonb_array_length(questions) > #{idx} AND" + OWN)
    int updateKnowledgeText(@Param("userId") long userId, @Param("refId") long refId,
                            @Param("idx") int idx, @Param("text") String text);

    @Update("UPDATE interview_project_question"
            + " SET questions = jsonb_set(questions, ARRAY[#{idx}::text], to_jsonb(#{text}::text))"
            + " WHERE id = #{refId} AND jsonb_array_length(questions) > #{idx} AND" + OWN)
    int updateProjectText(@Param("userId") long userId, @Param("refId") long refId,
                          @Param("idx") int idx, @Param("text") String text);

    @Update("UPDATE interview_other_question SET content = #{text} WHERE id = #{refId} AND" + OWN)
    int updateOtherContent(@Param("userId") long userId, @Param("refId") long refId, @Param("text") String text);

    /** 改主题：project=project_name，other=tag，knowledge=tag（仅当无关联知识点，否则 0 行 → 拒绝）。 */
    @Update("UPDATE interview_project_question SET project_name = #{topic} WHERE id = #{refId} AND" + OWN)
    int updateProjectTopic(@Param("userId") long userId, @Param("refId") long refId, @Param("topic") String topic);

    @Update("UPDATE interview_other_question SET tag = #{topic} WHERE id = #{refId} AND" + OWN)
    int updateOtherTopic(@Param("userId") long userId, @Param("refId") long refId, @Param("topic") String topic);

    @Update("UPDATE interview_knowledge_question SET tag = #{topic}"
            + " WHERE id = #{refId} AND" + OWN
            + " AND NOT EXISTS (SELECT 1 FROM interview_question_kp_link l"
            + "   WHERE l.interview_knowledge_question_id = #{refId} AND l.knowledge_point_id IS NOT NULL)")
    int updateKnowledgeTopicIfUnlinked(@Param("userId") long userId, @Param("refId") long refId,
                                       @Param("topic") String topic);

    /** 删除第 idx 个元素（jsonb `- idx`）；删空由 deleteXxxIfEmpty 收尾。 */
    @Update("UPDATE interview_knowledge_question SET questions = questions - #{idx} WHERE id = #{refId} AND" + OWN)
    int removeKnowledgeElement(@Param("userId") long userId, @Param("refId") long refId, @Param("idx") int idx);

    @Update("UPDATE interview_project_question SET questions = questions - #{idx} WHERE id = #{refId} AND" + OWN)
    int removeProjectElement(@Param("userId") long userId, @Param("refId") long refId, @Param("idx") int idx);

    @Delete("DELETE FROM interview_knowledge_question WHERE id = #{refId} AND jsonb_array_length(questions) = 0 AND" + OWN)
    int deleteKnowledgeIfEmpty(@Param("userId") long userId, @Param("refId") long refId);

    @Delete("DELETE FROM interview_project_question WHERE id = #{refId} AND jsonb_array_length(questions) = 0 AND" + OWN)
    int deleteProjectIfEmpty(@Param("userId") long userId, @Param("refId") long refId);

    @Delete("DELETE FROM interview_other_question WHERE id = #{refId} AND" + OWN)
    int deleteOther(@Param("userId") long userId, @Param("refId") long refId);
}
