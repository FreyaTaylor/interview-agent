-- 005: project ↔ project_node 关联（消除 name 字符串硬关联）
-- 设计：
--   project.root_node_id 指向 project_node 树根（level=1）
--   ON DELETE SET NULL —— 删除题库树时不波及简历元数据/会话历史
-- 回填策略：按 name 精确匹配现有 project_node 根节点
BEGIN;

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS root_node_id BIGINT
        REFERENCES project_node(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_project_root_node_id
    ON project(root_node_id);

-- 回填：现存 project.name 与 project_node(level=1).name 精确匹配
UPDATE project p
   SET root_node_id = n.id
  FROM project_node n
 WHERE n.level = 1
   AND n.parent_id IS NULL
   AND n.name = p.name
   AND p.root_node_id IS NULL;

COMMIT;
