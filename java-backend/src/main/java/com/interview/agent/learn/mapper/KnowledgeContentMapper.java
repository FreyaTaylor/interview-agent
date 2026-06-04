package com.interview.agent.learn.mapper;

import com.interview.agent.learn.entity.KnowledgeContent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface KnowledgeContentMapper {

    String COLS = "id, knowledge_point_id, user_id, content, user_additions, created_at, updated_at";

    @Select("SELECT " + COLS + " FROM knowledge_content WHERE knowledge_point_id = #{kpId}")
    Optional<KnowledgeContent> findByKpId(@Param("kpId") long kpId);

    @Select("SELECT EXISTS(SELECT 1 FROM knowledge_content WHERE knowledge_point_id = #{kpId})")
    boolean existsByKpId(@Param("kpId") long kpId);

    /** 返回新行 id。content 字段直接用文本绑定即可（TEXT 列）。
     *  @Select 包 INSERT 需 flushCache=true，避免一级缓存影响后续 SELECT。 */
    @Select("""
            INSERT INTO knowledge_content (knowledge_point_id, content)
            VALUES (#{kpId}, #{content})
            ON CONFLICT (knowledge_point_id) DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Optional<Long> insertIfAbsent(@Param("kpId") long kpId,
                                  @Param("content") String content);

    @Delete("DELETE FROM knowledge_content WHERE knowledge_point_id = #{kpId}")
    int deleteByKpId(@Param("kpId") long kpId);
}
