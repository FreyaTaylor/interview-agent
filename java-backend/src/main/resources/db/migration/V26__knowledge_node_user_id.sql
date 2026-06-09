-- =============================================================================
-- V26 多用户隔离 —— knowledge_node 增加 user_id
--
-- 背景：一期写死 user_id=1，知识大纲全局共享。引入 GitHub 多用户后，
--       每个用户拥有独立的知识树（B 方案：全部数据隔离）。
--
-- 约定：与其余业务表一致 —— user_id BIGINT NOT NULL DEFAULT 1。
--       存量节点归属 user_id=1（旧数据保留，不迁移到新用户）。
-- =============================================================================

ALTER TABLE knowledge_node
    ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1;

-- 树查询 / 树生成去重 / 面试召回都会按 user_id 过滤，建复合索引覆盖常用访问路径
CREATE INDEX idx_knowledge_node_user        ON knowledge_node(user_id);
CREATE INDEX idx_knowledge_node_user_parent ON knowledge_node(user_id, parent_id);
CREATE INDEX idx_knowledge_node_user_level  ON knowledge_node(user_id, level);
