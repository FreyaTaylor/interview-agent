package com.interview.agent.project.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * interview_project_question 表 Mapper（仅 FK 兜底用）。
 *
 * <p>S6 删除项目节点时，需要把面试题表里 project_node_id 引用置 NULL（保留事实数据）。
 * 完整 CRUD 留给后续 Interview 模块（S7/S8）按需补。
 */
@Mapper
public interface InterviewProjectQuestionMapper {

        /** S8 finalize：写入项目类面试问题。 */
        @Select("""
                        INSERT INTO interview_project_question
                            (interview_record_id, project_node_id, project_name, questions, user_answer, original_dialogue, score_result)
                        VALUES
                            (#{recordId}, #{projectNodeId}, #{projectName},
                             #{questions,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                             #{userAnswer}, #{originalDialogue},
                             #{scoreResult,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER})
                        RETURNING id
                        """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE)
        long insert(@Param("recordId") long recordId,
                                @Param("projectNodeId") Long projectNodeId,
                                @Param("projectName") String projectName,
                                @Param("questions") Object questions,
                                @Param("userAnswer") String userAnswer,
                                @Param("originalDialogue") String originalDialogue,
                                @Param("scoreResult") Object scoreResult);

    /** 把指定 node_id 集合的引用置 NULL；空列表由 Service 层护守。 */
    @Update("""
            <script>
            UPDATE interview_project_question SET project_node_id = NULL
            WHERE project_node_id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int nullOutByNodeIds(@Param("ids") List<Long> ids);
}
