-- 背景：多用户 IDOR 硬化（Spec 2026-07-15 用户隔离完成）后，tree_node 的读查询几乎都带
--       user_id 过滤（知识树 WHERE tree_kind='knowledge' AND user_id=?；项目树同理）。
-- 现象：tree_node 现有索引仅 (parent_id)、(tree_kind, node_type)，无覆盖 user_id 的索引；
--       多用户下同表混装多人数据，按 (tree_kind, user_id) 组树/列表会退化为过滤扫描。
-- 根因：V26 曾给 knowledge_node 建过 user 复合索引，但 V43 统一进 tree_node 时未随迁。
-- 修复：补一条 (user_id, tree_kind) 复合索引，覆盖「按用户 + 树种」的高频组树/列表/去重查询。
CREATE INDEX IF NOT EXISTS idx_tree_node_user_kind
    ON tree_node (user_id, tree_kind);
