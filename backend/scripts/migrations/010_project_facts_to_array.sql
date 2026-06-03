-- 010_project_facts_to_array.sql
-- 目的：project_user_profile.project_facts 从历史上的 dict 默认值统一为 list
-- 背景：服务层 _apply_facts_patch / extract_and_apply 全程当 list[str] 用，
--      但旧表 server_default 是 '{}'::jsonb，导致 ORM 类型与运行时不一致。

BEGIN;

-- 1) 把存量为 '{}' / null / 非数组 的行强制改为空数组
UPDATE project_user_profile
SET project_facts = '[]'::jsonb
WHERE project_facts IS NULL
   OR jsonb_typeof(project_facts) <> 'array';

-- 2) 修改默认值
ALTER TABLE project_user_profile
    ALTER COLUMN project_facts SET DEFAULT '[]'::jsonb;

COMMIT;
