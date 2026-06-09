package com.interview.agent.interview.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** interview_other_question 表写入。 */
@Mapper
public interface InterviewOtherQuestionMapper {

    @Select("""
            INSERT INTO interview_other_question
              (interview_record_id, content, tag, user_answer, extra)
            VALUES
              (#{recordId}, #{content}, #{tag}, #{userAnswer},
               #{extra,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("recordId") long recordId,
                @Param("content") String content,
                @Param("tag") String tag,
                @Param("userAnswer") String userAnswer,
                @Param("extra") Object extra);
}
