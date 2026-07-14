package com.interview.agent.interview.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** interview_knowledge_question 表写入。 */
@Mapper
public interface InterviewKnowledgeQuestionMapper {

    @Select("""
            INSERT INTO interview_knowledge_question
              (interview_record_id, tag, questions, user_answer, original_dialogue, score_result)
            VALUES
              (#{recordId}, #{tag},
               #{questions,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
               #{userAnswer}, #{originalDialogue},
               #{scoreResult,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("recordId") long recordId,
                @Param("tag") String tag,
                @Param("questions") Object questions,
                @Param("userAnswer") String userAnswer,
                @Param("originalDialogue") String originalDialogue,
                @Param("scoreResult") Object scoreResult);
}
