package com.interview.agent.learn.mapper;

import com.interview.agent.learn.entity.LearnChat;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LearnChatMapper {

    String COLS = "id, knowledge_point_id, user_id, role, content, quoted_text, created_at";

    @Select("SELECT " + COLS + " FROM learn_chat WHERE knowledge_point_id = #{kpId} ORDER BY id")
    List<LearnChat> findByKpId(@Param("kpId") long kpId);

    /** 取最近 N 条（按 id 倒序），用于嗂 chat prompt 的"对话历史"上下文 */
    @Select("SELECT " + COLS + " FROM ("
            + " SELECT * FROM learn_chat WHERE knowledge_point_id = #{kpId}"
            + " ORDER BY id DESC LIMIT #{limit}"
            + ") sub ORDER BY id")
    List<LearnChat> findRecent(@Param("kpId") long kpId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO learn_chat (knowledge_point_id, role, content, quoted_text)
            VALUES (#{kpId}, #{role}, #{content}, #{quotedText})
            """)
    int insert(@Param("kpId") long kpId,
               @Param("role") String role,
               @Param("content") String content,
               @Param("quotedText") String quotedText);

    @Delete("DELETE FROM learn_chat WHERE knowledge_point_id = #{kpId}")
    int deleteByKpId(@Param("kpId") long kpId);
}
