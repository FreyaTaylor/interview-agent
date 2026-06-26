package com.interview.agent.user.mapper;

import com.interview.agent.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * user 表的 Mapper。
 *
 * <p>承载两类用途：
 * <ul>
 *   <li>S5 树生成读 {@code profile_text}（{@link #findProfileText}）</li>
 *   <li>GitHub OAuth 登录：按 github_id 查找 / 新建 / 更新资料、按 id 取当前用户</li>
 * </ul>
 *
 * <p>SQL 注意 <code>"user"</code> 必须加双引号 —— user 是 PG 保留字，
 * 不带引号会被解析为当前会话用户。
 */
@Mapper
public interface UserMapper {

    String COLS = """
            id, username, role, profile_text, github_id, github_login, avatar_url, created_at
            """;

    /**
     * 取指定 user 的画像文本（profile_text 列）。
     *
     * @param id user.id
     * @return Optional<画像文本>；user 不存在或 profile_text 为 NULL → Optional.empty()
     */
    @Select("SELECT profile_text FROM \"user\" WHERE id = #{id}")
    Optional<String> findProfileText(@Param("id") long id);

    /** 按主键取用户（/me 接口用）。 */
    @Select("SELECT " + COLS + " FROM \"user\" WHERE id = #{id}")
    Optional<User> findById(@Param("id") long id);

    /** self-hosted 模式下确保固定本地用户存在。 */
    @Select("""
            INSERT INTO "user" (id, username, role)
            VALUES (1, 'local', 'admin')
            ON CONFLICT (id) DO NOTHING
            RETURNING id
            """)
    Long ensureLocalUser();

    /** 插入 id=1 后同步序列，避免后续自增 id 冲突。 */
    @Select("SELECT setval(pg_get_serial_sequence('\"user\"', 'id'), GREATEST((SELECT MAX(id) FROM \"user\"), 1))")
    Long syncUserIdSequence();

    /** 按 GitHub 数字 id 取用户（OAuth 回调判断查找或创建）。 */
    @Select("SELECT " + COLS + " FROM \"user\" WHERE github_id = #{githubId}")
    Optional<User> findByGithubId(@Param("githubId") long githubId);

    /**
     * 新建 GitHub 用户，返回自增 id。
     *
     * <p>username 直接用 github_login；password 走列默认空串；role 默认 'user'。
     */
    @Select("""
            INSERT INTO "user" (username, github_id, github_login, avatar_url)
            VALUES (#{githubLogin}, #{githubId}, #{githubLogin}, #{avatarUrl})
            RETURNING id
            """)
    long insertGithubUser(@Param("githubId") long githubId,
                          @Param("githubLogin") String githubLogin,
                          @Param("avatarUrl") String avatarUrl);

    /** 用户已存在时刷新 login / 头像（GitHub 资料可能变更）。 */
    @Update("""
            UPDATE "user"
            SET github_login = #{githubLogin}, avatar_url = #{avatarUrl}
            WHERE id = #{id}
            """)
    int updateGithubProfile(@Param("id") long id,
                            @Param("githubLogin") String githubLogin,
                            @Param("avatarUrl") String avatarUrl);

    /**
     * 整段覆写用户画像文本（ProfilePage "保存" 按钮）。
     *
     * @param id          user.id
     * @param profileText 新画像文本（已 trim）
     * @return 受影响行数；user 不存在时为 0
     */
    @Update("UPDATE \"user\" SET profile_text = #{profileText} WHERE id = #{id}")
    int updateProfileText(@Param("id") long id, @Param("profileText") String profileText);
}
