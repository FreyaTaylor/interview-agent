package com.interview.agent.project.mapper;

import com.interview.agent.project.entity.ProjectUserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * project_user_profile 表 Mapper。
 *
 * <p>读操作（findByProjectUser / ensureRowExists）一期单用户，user_id 由 Service 注入。
 * 写操作（updateFactsWithLock）走乐观锁：{@code WHERE id=? AND version=?} 命中行数 != 1 视为冲突，
 * 由 Service 重读重算最多 {@code MAX_RETRY=3} 次。
 *
 * <p>JSONB 字段（project_facts）写入需声明 typeHandler；读取由全局自动反序列化。
 */
@Mapper
public interface ProjectUserProfileMapper {

    String COLS = """
            id, user_id, project_id, project_facts,
            version, created_at, updated_at
            """;

    @Select("SELECT " + COLS + " FROM project_user_profile"
            + " WHERE project_id = #{projectId} AND user_id = #{userId}")
    Optional<ProjectUserProfile> findByProjectUser(@Param("projectId") long projectId,
                                                   @Param("userId") long userId);

    /**
     * 保证 (project_id, user_id) 存在一行；不存在则插入空画像（version=0）。
     * 使用 ON CONFLICT DO NOTHING + UNIQUE 约束去重，幂等。
     */
    @Update("""
            INSERT INTO project_user_profile (user_id, project_id, project_facts, version)
            VALUES (#{userId}, #{projectId}, '[]'::jsonb, 0)
            ON CONFLICT (project_id, user_id) DO NOTHING
            """)
    int ensureRowExists(@Param("projectId") long projectId, @Param("userId") long userId);

    /**
     * 乐观锁更新：命中 {@code id + version} 才写。返回受影响行数；
     * 调用方按 {@code affectedRows == 1} 判定成功/冲突。
     */
    @Update("""
            UPDATE project_user_profile SET
              project_facts = #{facts,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              version = #{oldVersion} + 1,
              updated_at = NOW()
            WHERE id = #{id} AND version = #{oldVersion}
            """)
        int updateFactsWithLock(@Param("id") long id,
                                                        @Param("oldVersion") int oldVersion,
                                                        @Param("facts") Object facts);
}
