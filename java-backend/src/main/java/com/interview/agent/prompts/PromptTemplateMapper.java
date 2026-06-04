package com.interview.agent.prompts;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PromptTemplateMapper {

    String COLS = "id, key, content, description, created_at, updated_at";

    @Select("SELECT " + COLS + " FROM prompt_template WHERE key = #{key}")
    Optional<PromptTemplate> findByKey(@Param("key") String key);

    @Select("SELECT " + COLS + " FROM prompt_template ORDER BY key")
    List<PromptTemplate> findAll();

    /** key 已存在则什么都不做，避免覆盖运营改过的内容。 */
    @Insert("""
            INSERT INTO prompt_template (key, content, description)
            VALUES (#{key}, #{content}, #{description})
            ON CONFLICT (key) DO NOTHING
            """)
    int insertIfAbsent(@Param("key") String key,
                       @Param("content") String content,
                       @Param("description") String description);
}
