package com.interview.agent.project;

import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.ProjectNodeView;
import com.interview.agent.admin.dto.UpdateProjectNodeReq;
import com.interview.agent.admin.service.ProjectAdminService;
import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目树用户隔离集成测试（Spec 2026-07-15 §3.1 / §4）。
 *
 * <p>两个隔离用户 {@link #USER_A}/{@link #USER_B} 各造一棵项目树，验证：
 * <ol>
 *   <li>读隔离：A 的 listAll 只见 A 的节点，不含 B。</li>
 *   <li>写隔离(IDOR)：A 传 B 的节点 id 做 update/delete → 拒绝（404），B 数据不变。</li>
 * </ol>
 * {@code @Transactional} 回滚保护，不调 LLM/Embedding。
 */
@SpringBootTest
@Transactional
class ProjectIsolationIT {

    static final long USER_A = 880001L;
    static final long USER_B = 880002L;

    @Autowired
    private ProjectAdminService service;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.clear();
    }

    /** 造一个项目根节点（level=1）。 */
    private long seedProjectRoot(long userId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, sort_order) "
                        + "VALUES ('project', ?, NULL, ?, 1, 'project', 0) RETURNING id",
                Long.class, userId, name);
    }

    private boolean nodeExists(long id) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM tree_node WHERE id = ?", Integer.class, id);
        return c != null && c > 0;
    }

    private String nameOf(long id) {
        return jdbc.queryForObject("SELECT name FROM tree_node WHERE id = ?", String.class, id);
    }

    // ===== 读隔离 =====

    @Test
    void listAll_onlyReturnsOwnNodes() {
        long aRoot = seedProjectRoot(USER_A, "A的项目");
        long bRoot = seedProjectRoot(USER_B, "B的项目");

        CurrentUserTestSupport.set(USER_A);
        List<Long> ids = service.listAll().stream().map(ProjectNodeView::id).toList();

        assertTrue(ids.contains(aRoot), "A 应看到自己的项目根");
        assertFalse(ids.contains(bRoot), "A 不应看到 B 的项目根");
    }

    // ===== 写隔离（IDOR）=====

    @Test
    void update_otherUsersNode_denied_andUnchanged() {
        long bRoot = seedProjectRoot(USER_B, "B的项目");

        CurrentUserTestSupport.set(USER_A);
        // A 用 B 的节点 id 改名 → 归属校验失败 → 404
        assertThrows(BizException.class, () ->
                service.update(new UpdateProjectNodeReq(bRoot, "被A篡改", null, null, false)));

        // B 的节点名不变
        assertEquals("B的项目", nameOf(bRoot));
    }

    @Test
    void delete_otherUsersNode_denied_andStillExists() {
        long bRoot = seedProjectRoot(USER_B, "B的项目");

        CurrentUserTestSupport.set(USER_A);
        assertThrows(BizException.class, () -> service.delete(new DeleteNodeReq(bRoot)));

        assertTrue(nodeExists(bRoot), "B 的节点不应被 A 删除");
    }

    @Test
    void delete_ownNode_succeeds() {
        long aRoot = seedProjectRoot(USER_A, "A的项目");

        CurrentUserTestSupport.set(USER_A);
        service.delete(new DeleteNodeReq(aRoot));

        assertFalse(nodeExists(aRoot), "A 应能删除自己的节点");
    }
}
