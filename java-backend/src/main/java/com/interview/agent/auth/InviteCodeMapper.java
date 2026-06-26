package com.interview.agent.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** invite_code 表 Mapper。 */
@Mapper
public interface InviteCodeMapper {

    String COLS = "id, code_hash, note, created_by, used_by_user_id, used_at, expires_at, created_at";

    /** 邀请码是否存在且未被使用、未过期。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM invite_code
                WHERE code_hash = #{codeHash}
                  AND used_at IS NULL
                  AND (expires_at IS NULL OR expires_at > NOW())
            )
            """)
    boolean existsUsable(@Param("codeHash") String codeHash);

    /** 原子消耗邀请码；返回 1 才算成功。 */
    @Update("""
            UPDATE invite_code
            SET used_by_user_id = #{userId}, used_at = NOW()
            WHERE code_hash = #{codeHash}
              AND used_at IS NULL
              AND (expires_at IS NULL OR expires_at > NOW())
            """)
    int consume(@Param("codeHash") String codeHash, @Param("userId") long userId);

    /** 插入一枚邀请码 hash。 */
    @Insert("""
            INSERT INTO invite_code (code_hash, note, created_by, expires_at)
            VALUES (#{codeHash}, #{note}, #{createdBy}, #{expiresAt})
            """)
    int insert(@Param("codeHash") String codeHash,
               @Param("note") String note,
               @Param("createdBy") long createdBy,
               @Param("expiresAt") LocalDateTime expiresAt);

    /** 最近的邀请码列表。 */
    @Select("SELECT " + COLS + " FROM invite_code ORDER BY created_at DESC LIMIT #{limit}")
    List<InviteCode> listRecent(@Param("limit") int limit);
}