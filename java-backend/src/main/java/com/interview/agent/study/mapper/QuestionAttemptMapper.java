package com.interview.agent.study.mapper;

import com.interview.agent.study.entity.QuestionAttempt;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * question_attempt 表 Mapper（study 侧入口）。
 *
 * <p>JSONB 字段（dialog / rubric_result / design_issues / extension_qa）写入需显式声明
 * {@code typeHandler=JsonbTypeHandler}；读取由全局自动匹配，落到 record 的 {@code Object}。
 *
 * <p>去多态后 {@code question_id} 直指 {@code tree_node.id}（question 节点全局唯一），不再有
 * {@code question_type} 判别列；按 question_id 即可定位，掌握度聚合按题节点在知识树中的归属统计。
 */
@Mapper
public interface QuestionAttemptMapper {

    String COLS = """
            id, user_id, question_id, status,
            final_score, rubric_result, overall_summary,
            design_issues, extension_qa, dialog, follow_up_count,
            finished_at, created_at
            """;

    @Select("SELECT " + COLS + " FROM question_attempt WHERE id = #{id} AND user_id = #{userId}")
    Optional<QuestionAttempt> findById(@Param("id") long id, @Param("userId") long userId);

    /** 查找该用户该题的进行中作答（最多 1 条，业务保证）。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE user_id = #{userId}"
            + " AND question_id = #{questionId} AND status = 'in_progress'"
            + " ORDER BY id DESC LIMIT 1")
    Optional<QuestionAttempt> findInProgress(@Param("userId") long userId,
                                             @Param("questionId") long questionId);

    /** 最近 N 次作答（含 in_progress + finished），按 created_at 倒序；仅当前用户。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE user_id = #{userId} AND question_id = #{questionId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<QuestionAttempt> findRecent(@Param("userId") long userId,
                                     @Param("questionId") long questionId,
                                     @Param("limit") int limit);

    /**
     * 该 KP 掌握度：该 KP 下所有问题节点的"最近 N 次 finished 平均分"再求平均；
     * 未答题的 per_q 计 0（不是从分母里剔除）—— 反映"覆盖+正确率"双重维度。
     * 问题节点归属：父是该 KP 下子话题，或父直接是该 KP。KP 下没有题时返 null。
     */
    @Select("""
            SELECT AVG(COALESCE(q_score, 0)) FROM (
              SELECT (
                SELECT AVG(final_score)
                FROM (
                  SELECT final_score FROM question_attempt
                  WHERE question_id = q.id
                    AND user_id = #{userId}
                    AND status = 'finished'
                  ORDER BY finished_at DESC NULLS LAST, id DESC
                  LIMIT #{recentN}
                ) recent_a
              ) AS q_score
              FROM tree_node q
              JOIN tree_node p ON p.id = q.parent_id
              WHERE q.node_type = 'question'
                AND (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
                     OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
            ) per_q
            """)
    Double avgKpMastery(@Param("userId") long userId, @Param("kpId") long kpId, @Param("recentN") int recentN);

    /**
     * 子话题掌握度：口径与 {@link #avgKpMastery} 一致，聚合边界为该子话题的直接子问题节点。
     * 子话题下无题时返 null。
     */
    @Select("""
            SELECT AVG(COALESCE(q_score, 0)) FROM (
              SELECT (
                SELECT AVG(final_score)
                FROM (
                  SELECT final_score FROM question_attempt
                  WHERE question_id = q.id
                    AND user_id = #{userId}
                    AND status = 'finished'
                  ORDER BY finished_at DESC NULLS LAST, id DESC
                  LIMIT #{recentN}
                ) recent_a
              ) AS q_score
              FROM tree_node q
              WHERE q.node_type = 'question' AND q.parent_id = #{subtopicId}
            ) per_q
            """)
    Double avgSubtopicMastery(@Param("userId") long userId, @Param("subtopicId") long subtopicId, @Param("recentN") int recentN);

    /** 该题该用户“最近 N 次 finished 平均分”；无记录时 SQL 返 null。 */
    @Select("""
            SELECT AVG(a.final_score) FROM (
              SELECT final_score FROM question_attempt
              WHERE question_id = #{questionId}
                AND user_id = #{userId}
                AND status = 'finished'
              ORDER BY finished_at DESC NULLS LAST, id DESC
              LIMIT #{recentN}
            ) a
            """)
    Double avgQuestionScore(@Param("userId") long userId,
                            @Param("questionId") long questionId,
                            @Param("recentN") int recentN);

    /** 创建 in_progress；主问题已先写入 dialog。 */
    @Select("""
            INSERT INTO question_attempt
              (user_id, question_id, status, dialog, follow_up_count)
            VALUES (
              #{userId}, #{questionId}, 'in_progress',
              #{dialog,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              0
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertStudyInProgress(@Param("userId") long userId,
                               @Param("questionId") long questionId,
                               @Param("dialog") Object dialog);

    /** 一轮过后：覆盖 dialog + 累加 follow_up_count；仅限当前用户。 */
    @Update("""
            UPDATE question_attempt
            SET dialog = #{dialog,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                follow_up_count = #{followUpCount}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateTurn(@Param("id") long id,
                   @Param("userId") long userId,
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
            WHERE id = #{id} AND status = 'in_progress' AND user_id = #{userId}
            """)
    int finish(@Param("id") long id,
               @Param("userId") long userId,
               @Param("finalScore") int finalScore,
               @Param("rubricResult") Object rubricResult,
               @Param("overallSummary") String overallSummary);

    /** 删除某题的全部作答记录。返回删除条数。
     *  （删题节点时 FK ON DELETE CASCADE 已自动清；此方法保留供显式清理场景。） */
    @Delete("DELETE FROM question_attempt WHERE question_id = #{questionId} AND user_id = #{userId}")
    int deleteByStudyQuestion(@Param("questionId") long questionId, @Param("userId") long userId);
}
