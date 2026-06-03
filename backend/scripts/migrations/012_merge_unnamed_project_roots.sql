-- 012: 合并历史 "未命名项目-N" 根节点
--
-- 背景：早期 _get_or_create_unnamed_root 给每次未匹配都创建独立根
-- (未命名项目-1, 未命名项目-2, ...)，与「未命名知识点」单根策略不一致。
-- 现已改为全局唯一「未命名项目」根。本迁移把存量 -N 根的子节点全部
-- 挂到单根下，并删除空的 -N 根。
--
-- 运行：
--   psql "$DATABASE_URL" -f backend/scripts/migrations/012_merge_unnamed_project_roots.sql

BEGIN;

-- 1) 确保单根「未命名项目」存在
INSERT INTO project_node (parent_id, name, level, node_type, sort_order, user_id)
SELECT NULL, '未命名项目', 1, 'category', 9999, 1
WHERE NOT EXISTS (
    SELECT 1 FROM project_node
    WHERE level = 1 AND name = '未命名项目' AND user_id = 1
);

-- 2) 把所有「未命名项目-N」(level=1) 的直接子节点改挂到单根下
WITH single_root AS (
    SELECT id FROM project_node
    WHERE level = 1 AND name = '未命名项目' AND user_id = 1
    LIMIT 1
),
old_roots AS (
    SELECT id FROM project_node
    WHERE level = 1 AND name ~ '^未命名项目-[0-9]+$' AND user_id = 1
)
UPDATE project_node
SET parent_id = (SELECT id FROM single_root)
WHERE parent_id IN (SELECT id FROM old_roots);

-- 3) 删除已经空的「未命名项目-N」根
DELETE FROM project_node
WHERE level = 1
  AND name ~ '^未命名项目-[0-9]+$'
  AND user_id = 1
  AND NOT EXISTS (
      SELECT 1 FROM project_node c WHERE c.parent_id = project_node.id
  );

COMMIT;
