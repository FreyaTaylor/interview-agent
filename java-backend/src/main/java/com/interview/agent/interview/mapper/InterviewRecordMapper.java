package com.interview.agent.interview.mapper;

import com.interview.agent.interview.entity.InterviewRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/** interview_record 表 Mapper。 */
@Mapper
public interface InterviewRecordMapper {

    String COLS = """
            id, raw_text, company, position, text_hash,
            avg_score, pass_estimate, parsed_questions, cluster_result,
            summary_report, draft_turns, draft_groups, created_at
            """;

    @Select("SELECT " + COLS + " FROM interview_record WHERE id = #{id}")
    Optional<InterviewRecord> findById(@Param("id") long id);

    @Select("SELECT " + COLS + " FROM interview_record ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<InterviewRecord> findRecent(@Param("limit") int limit);

        @Select("SELECT " + COLS + " FROM interview_record WHERE text_hash = #{textHash} ORDER BY id DESC LIMIT 1")
        Optional<InterviewRecord> findByTextHash(@Param("textHash") String textHash);

    @Select("""
            INSERT INTO interview_record
              (raw_text, company, position, text_hash, parsed_questions, cluster_result, summary_report)
            VALUES
              (#{rawText}, #{company}, #{position}, #{textHash},
               #{parsedQuestions,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
               #{clusterResult,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
               #{summaryReport})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("rawText") String rawText,
                @Param("company") String company,
                @Param("position") String position,
                @Param("textHash") String textHash,
                @Param("parsedQuestions") Object parsedQuestions,
                @Param("clusterResult") Object clusterResult,
                @Param("summaryReport") String summaryReport);

    @Update("""
            UPDATE interview_record
            SET avg_score = #{avgScore},
                pass_estimate = #{passEstimate},
                parsed_questions = #{parsedQuestions,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                summary_report = #{summaryReport},
                draft_turns = NULL,
                draft_groups = NULL
            WHERE id = #{id}
            """)
    int updateFinalize(@Param("id") long id,
                       @Param("avgScore") int avgScore,
                       @Param("passEstimate") String passEstimate,
                       @Param("parsedQuestions") Object parsedQuestions,
                       @Param("summaryReport") String summaryReport);

    @Select("""
            INSERT INTO interview_record
              (raw_text, company, position, text_hash, parsed_questions, cluster_result, summary_report, draft_turns, draft_groups)
            VALUES
              (#{rawText}, #{company}, #{position}, #{textHash},
               NULL, NULL, NULL,
               #{draftTurns,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
               #{draftGroups,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertDraft(@Param("rawText") String rawText,
                     @Param("company") String company,
                     @Param("position") String position,
                     @Param("textHash") String textHash,
                     @Param("draftTurns") Object draftTurns,
                     @Param("draftGroups") Object draftGroups);

    @Update("""
            UPDATE interview_record
            SET draft_turns = #{draftTurns,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                draft_groups = #{draftGroups,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                company = COALESCE(#{company}, company),
                position = COALESCE(#{position}, position)
            WHERE id = #{id}
            """)
    int updateDraft(@Param("id") long id,
                    @Param("draftTurns") Object draftTurns,
                    @Param("draftGroups") Object draftGroups,
                    @Param("company") String company,
                    @Param("position") String position);

    @Update("""
            UPDATE interview_record
            SET company = #{company},
                position = #{position}
            WHERE id = #{id}
            """)
    int updateMeta(@Param("id") long id,
                   @Param("company") String company,
                   @Param("position") String position);

    @Delete("DELETE FROM interview_record WHERE id = #{id}")
    int deleteById(@Param("id") long id);
}
