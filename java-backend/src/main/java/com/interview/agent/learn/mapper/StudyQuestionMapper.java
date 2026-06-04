package com.interview.agent.learn.mapper;

import com.interview.agent.infra.db.JsonbTypeHandler;
import com.interview.agent.learn.entity.StudyQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * study_question 表 Mapper。
 *
 * <p>JSONB 字段 (rubric_template / recommended_answer) 通过 #{xxx,typeHandler=JsonbTypeHandler}
 * 显式声明 handler；读取端 record 字段类型为 Object，全局 typeHandlers-package 已注册自动匹配。
 *
 * <p>S4 Learn 写入（一次生成 5 道）；S3 Study 只读。
 */
@Mapper
public interface StudyQuestionMapper {

    String COLS = """
            id, user_id, knowledge_point_id, content,
            rubric_template, recommended_answer, sort_order, created_at
            """;

    @Select("SELECT " + COLS + " FROM study_question WHERE knowledge_point_id = #{kpId} ORDER BY sort_order, id")
    List<StudyQuestion> findByKpId(@Param("kpId") long kpId);

    @Select("SELECT " + COLS + " FROM study_question WHERE id = #{id}")
    java.util.Optional<StudyQuestion> findById(@Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 FROM study_question WHERE knowledge_point_id = #{kpId})")
    boolean existsByKpId(@Param("kpId") long kpId);

    /** 返回新行 id。rubric_template / recommended_answer 走 JsonbTypeHandler。
     *  注意：用 @Select 包 INSERT...RETURNING，需 flushCache=true 强制清一级缓存，
     *  否则同事务内后续 findByKpId 会命中插入前的空缓存。 */
    @Select("""
            INSERT INTO study_question
              (knowledge_point_id, content, rubric_template, recommended_answer, sort_order)
            VALUES (
              #{kpId}, #{content},
              #{rubricTemplate,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              #{recommendedAnswer,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              #{sortOrder}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("kpId") long kpId,
                @Param("content") String content,
                @Param("rubricTemplate") Object rubricTemplate,
                @Param("recommendedAnswer") Object recommendedAnswer,
                @Param("sortOrder") int sortOrder);

    /** 删除某 KP 下所有"无作答历史"的题目，返回删除条数。用于 regenerate-questions。 */
    @Delete("""
            DELETE FROM study_question q
            WHERE q.knowledge_point_id = #{kpId}
              AND NOT EXISTS (
                SELECT 1 FROM question_attempt a
                WHERE a.question_type = 'study' AND a.question_id = q.id
              )
            """)
    int deleteUnattemptedByKpId(@Param("kpId") long kpId);

    @Select("""
            SELECT COALESCE(MAX(sort_order), -1) FROM study_question
            WHERE knowledge_point_id = #{kpId}
            """)
    int maxSortOrder(@Param("kpId") long kpId);
}
