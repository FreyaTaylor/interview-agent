package com.interview.agent.study.mapper;

import com.interview.agent.study.entity.QuestionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * question_attempt 表 Mapper。
 *
 * <p>JSONB 字段（dialog / rubric_result / design_issues / extension_qa）写入需显式声明
 * {@code typeHandler=JsonbTypeHandler}；读取由全局自动匹配，落到 record 的 {@code Object}。
 *
 * <p>{@code question_type='study'} 在所有方法中显式写死；项目侧后续在 {@code project} 包另写
 * 自己的查询入口（也可后续合并）。
 */
@Mapper
public interface QuestionAttemptMapper {

    String COLS = """
            id, user_id, question_type, question_id, status,
            final_score, rubric_result, overall_summary,
            design_issues, extension_qa, dialog, follow_up_count,
            finished_at, created_at
            """;

    @Select("SELECT " + COLS + " FROM question_attempt WHERE id = #{id}")
    Optional<QuestionAttempt> findById(@Param("id") long id);

    /** 查找该用户该 study 题的进行中作答（最多 1 条，业务保证）。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE user_id = #{userId} AND question_type = 'study'"
            + " AND question_id = #{questionId} AND status = 'in_progress'"
            + " ORDER BY id DESC LIMIT 1")
    Optional<QuestionAttempt> findInProgress(@Param("userId") long userId,
                                             @Param("questionId") long questionId);

    /** 最近 N 次作答（含 in_progress + finished），按 created_at 倒序。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE question_type = 'study' AND question_id = #{questionId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<QuestionAttempt> findRecent(@Param("questionId") long questionId,
                                     @Param("limit") int limit);

    /**
     * 该 KP 掌握度：所有 study_question 的"最近 N 次 finished 平均分"再求平均；
     * 未答题的 per_q 计 0（不是从分母里剔除）—— 这样总分均摊到 KP 整体题量，
     * 反映"覆盖+正确率"双重维度，而非仅"已答正确率"。
     * KP 下没有题时返 null。
     */
    @Select("""
            SELECT AVG(COALESCE(q_score, 0)) FROM (
              SELECT (
                SELECT AVG(final_score)
                FROM (
                  SELECT final_score FROM question_attempt
                  WHERE question_type = 'study'
                    AND question_id = q.id
                    AND status = 'finished'
                  ORDER BY finished_at DESC NULLS LAST, id DESC
                  LIMIT #{recentN}
                ) recent_a
              ) AS q_score
              FROM study_question q
              WHERE q.knowledge_point_id = #{kpId}
            ) per_q
            """)
    Double avgKpMastery(@Param("kpId") long kpId, @Param("recentN") int recentN);

    /** 该题"最近 N 次 finished 平均分"；无记录时 SQL 返 null。 */
    @Select("""
            SELECT AVG(a.final_score) FROM (
              SELECT final_score FROM question_attempt
              WHERE question_type = 'study'
                AND question_id = #{questionId}
                AND status = 'finished'
              ORDER BY finished_at DESC NULLS LAST, id DESC
              LIMIT #{recentN}
            ) a
            """)
    Double avgQuestionScore(@Param("questionId") long questionId,
                            @Param("recentN") int recentN);

    /** 创建 in_progress；主问题已先写入 dialog。 */
    @Select("""
            INSERT INTO question_attempt
              (user_id, question_type, question_id, status, dialog, follow_up_count)
            VALUES (
              #{userId}, 'study', #{questionId}, 'in_progress',
              #{dialog,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              0
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertStudyInProgress(@Param("userId") long userId,
                               @Param("questionId") long questionId,
                               @Param("dialog") Object dialog);

    /** 一轮过后：覆盖 dialog + 累加 follow_up_count。 */
    @Update("""
            UPDATE question_attempt
            SET dialog = #{dialog,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                follow_up_count = #{followUpCount}
            WHERE id = #{id}
            """)
    int updateTurn(@Param("id") long id,
                   @Param("dialog") Object dialog,
                   @Param("followUpCount") int followUpCount);

    /** 收尾：写 final_score / rubric_result / overall_summary / finished_at / status。 */
    @Update("""
            UPDATE question_attempt
            SET status = 'finished',
                final_score = #{finalScore},
                rubric_result = #{rubricResult,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                overall_summary = #{overallSummary},
                finished_at = NOW()
            WHERE id = #{id} AND status = 'in_progress'
            """)
    int finish(@Param("id") long id,
               @Param("finalScore") int finalScore,
               @Param("rubricResult") Object rubricResult,
               @Param("overallSummary") String overallSummary);
}
