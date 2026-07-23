package com.interview.agent.interview.exp.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 问题 ↔ 来源 关联表 {@code question_source_link} Mapper。
 *
 * <p>出现频率 = 某问题的 link 行数；插入用 {@code ON CONFLICT DO NOTHING}
 * 保证「同一来源对同一问题最多一条」（配合唯一约束幂等）。
 */
@Mapper
public interface QuestionSourceLinkMapper {

    /** 幂等插入一条问题↔来源关联；已存在则不动（同一来源不重复计频）。 */
    @Insert("""
            INSERT INTO question_source_link (question_node_id, source_id)
            VALUES (#{questionNodeId}, #{sourceId})
            ON CONFLICT (question_node_id, source_id) DO NOTHING
            """)
    int insertIgnore(@Param("questionNodeId") long questionNodeId, @Param("sourceId") long sourceId);
}
