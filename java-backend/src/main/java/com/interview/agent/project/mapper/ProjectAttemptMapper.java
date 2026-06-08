package com.interview.agent.project.mapper;

import com.interview.agent.study.entity.QuestionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * question_attempt 表 Mapper —— project 侧分支。
 *
 * <p>与 {@link com.interview.agent.study.mapper.QuestionAttemptMapper} 平行存在：
 * 每条 SQL 写死 {@code question_type='project'}，避免误把 study 数据混入 project 查询。
 * 共 ~30 行 SQL 与 study 雷同（仅字面量差异），换来类型安全 + 模块边界清晰
 * （详见 S7-project-grilling.md §3.1 决策）。
 *
 * <p>JSONB 字段（dialog / rubric_result / design_issues / extension_qa）写入需显式声明
 * {@code typeHandler=JsonbTypeHandler}；读取由全局自动反序列化。
 *
 * <p>{@code question_id} 含义：project_node.id（level=3 叶子）；逻辑外键由应用层保证。
 */
@Mapper
public interface ProjectAttemptMapper {

    String COLS = """
            id, user_id, question_type, question_id, status,
            final_score, rubric_result, overall_summary,
            design_issues, extension_qa, dialog, follow_up_count,
            finished_at, created_at
            """;

    @Select("SELECT " + COLS + " FROM question_attempt WHERE id = #{id}")
    Optional<QuestionAttempt> findById(@Param("id") long id);

    /** 查找该用户该 project 题（L3 叶子）的进行中作答。业务保证至多 1 条。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE user_id = #{userId} AND question_type = 'project'"
            + " AND question_id = #{questionId} AND status = 'in_progress'"
            + " ORDER BY id DESC LIMIT 1")
    Optional<QuestionAttempt> findInProgress(@Param("userId") long userId,
                                             @Param("questionId") long questionId);

    /** 最近 N 次作答（含 in_progress + finished），按 created_at 倒序。 */
    @Select("SELECT " + COLS + " FROM question_attempt"
            + " WHERE question_type = 'project' AND question_id = #{questionId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<QuestionAttempt> findRecent(@Param("questionId") long questionId,
                                     @Param("limit") int limit);

    /** 该题"最近 N 次 finished 平均分"；无记录时 SQL 返 null。 */
    @Select("""
            SELECT AVG(a.final_score) FROM (
              SELECT final_score FROM question_attempt
              WHERE question_type = 'project'
                AND question_id = #{questionId}
                AND status = 'finished'
              ORDER BY finished_at DESC NULLS LAST, id DESC
              LIMIT #{recentN}
            ) a
            """)
    Double avgQuestionScore(@Param("questionId") long questionId,
                            @Param("recentN") int recentN);

    /** 该题已 finished 的作答次数（用于"已练习 N 次"展示）。 */
    @Select("""
            SELECT COUNT(*) FROM question_attempt
            WHERE question_type = 'project'
              AND user_id = #{userId}
              AND question_id = #{questionId}
              AND status = 'finished'
            """)
    int countFinishedAttempts(@Param("userId") long userId,
                              @Param("questionId") long questionId);

    /** 创建 in_progress；主问题已先写入 dialog。 */
    @Select("""
            INSERT INTO question_attempt
              (user_id, question_type, question_id, status, dialog, follow_up_count)
            VALUES (
              #{userId}, 'project', #{questionId}, 'in_progress',
              #{dialog,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              0
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertProjectInProgress(@Param("userId") long userId,
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

    /**
     * 收尾：写 final_score / rubric_result / overall_summary / design_issues / extension_qa /
     * finished_at / status。仅在 status='in_progress' 时生效，避免并发 finish 重复写。
     */
    @Update("""
            UPDATE question_attempt
            SET status = 'finished',
                final_score = #{finalScore},
                rubric_result = #{rubricResult,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                overall_summary = #{overallSummary},
                design_issues = #{designIssues,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                extension_qa = #{extensionQa,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                finished_at = NOW()
            WHERE id = #{id} AND status = 'in_progress'
            """)
    int finish(@Param("id") long id,
               @Param("finalScore") int finalScore,
               @Param("rubricResult") Object rubricResult,
               @Param("overallSummary") String overallSummary,
               @Param("designIssues") Object designIssues,
               @Param("extensionQa") Object extensionQa);
}
