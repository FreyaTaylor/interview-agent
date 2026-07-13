package com.interview.agent.interview.mapper;

import com.interview.agent.interview.entity.InterviewQuestionKpLink;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 面试真题 ↔ 知识点 关联表 Mapper（interview_question_kp_link）。
 *
 * <p>三模块解耦 P1：面试模块拥有真题，知识点/项目只读引用。upsert 幂等（同一 真题↔知识点 只留一条）。
 * snake_case 列 ↔ camelCase 字段由全局 map-underscore-to-camel-case 处理；Record 结果映射依赖 -parameters。
 */
@Mapper
public interface InterviewQuestionKpLinkMapper {

    String COLS = """
            id, user_id, interview_knowledge_question_id, knowledge_point_id,
            knowledge_point_name, source, similarity, created_at
            """;

    /**
     * 写入/更新一条关联（幂等）：同一 (真题, 知识点) 已存在则更新名/来源/相似度，不新增。
     * 依赖唯一约束 uq_iqkl_pair (interview_knowledge_question_id, knowledge_point_id)。
     */
    @Insert("""
            INSERT INTO interview_question_kp_link
                (user_id, interview_knowledge_question_id, knowledge_point_id, knowledge_point_name, source, similarity)
            VALUES (#{userId}, #{ikqId}, #{kpId,jdbcType=BIGINT}, #{kpName}, #{source}, #{similarity,jdbcType=REAL})
            ON CONFLICT (interview_knowledge_question_id, knowledge_point_id)
            DO UPDATE SET knowledge_point_name = EXCLUDED.knowledge_point_name,
                          source               = EXCLUDED.source,
                          similarity           = EXCLUDED.similarity
            """)
    int upsert(@Param("userId") long userId,
               @Param("ikqId") long ikqId,
               @Param("kpId") Long kpId,
               @Param("kpName") String kpName,
               @Param("source") String source,
               @Param("similarity") Float similarity);

    /** 「知识点查相关真题」：按相似度降序（NULL 垫底）。 */
    @Select("SELECT " + COLS + " FROM interview_question_kp_link WHERE knowledge_point_id = #{kpId} ORDER BY similarity DESC NULLS LAST, id")
    List<InterviewQuestionKpLink> findByKnowledgePoint(@Param("kpId") long kpId);

    /** 「真题查关联知识点」：按相似度降序（NULL 垫底）。 */
    @Select("SELECT " + COLS + " FROM interview_question_kp_link WHERE interview_knowledge_question_id = #{ikqId} ORDER BY similarity DESC NULLS LAST, id")
    List<InterviewQuestionKpLink> findByInterviewQuestion(@Param("ikqId") long ikqId);

    /** 命中过真题的知识点 id 集合（distinct），供知识树打「关联真题」徽标。 */
    @Select("SELECT DISTINCT knowledge_point_id FROM interview_question_kp_link WHERE user_id = #{userId} AND knowledge_point_id IS NOT NULL")
    List<Long> findLinkedKnowledgePointIds(@Param("userId") long userId);

    /** 「知识点 → 相关面试真题」只读查询：JOIN 面试真题 + 面试记录，按相似度降序。questions 转 text 供 service 解析。 */
    @Select("""
            SELECT ikq.id                  AS id,
                   ikq.questions::text      AS questions,
                   ikq.tag                  AS tag,
                   l.similarity             AS similarity,
                   ikq.interview_record_id  AS interview_record_id,
                   r.company                AS company,
                   r.position               AS position,
                   ikq.created_at           AS created_at
            FROM interview_question_kp_link l
            JOIN interview_knowledge_question ikq ON ikq.id = l.interview_knowledge_question_id
            JOIN interview_record r ON r.id = ikq.interview_record_id
            WHERE l.knowledge_point_id = #{kpId} AND l.user_id = #{userId}
            ORDER BY l.similarity DESC NULLS LAST, ikq.id
            """)
    List<com.interview.agent.interview.dto.RelatedInterviewQuestionRow> findRelatedByKp(
            @Param("userId") long userId, @Param("kpId") long kpId);
}
