package com.interview.agent.project.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
