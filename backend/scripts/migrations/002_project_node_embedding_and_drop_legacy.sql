-- Migration 002: 统一项目题库到 project_node
--
-- 背景：
--   - 旧表 project_question 与新表 interview_project_question 数据不同步
--   - 决定以 project_node 为题库唯一真源，interview_project_question 作为事实表严格关联到叶子
--
-- 改动：
--   1) project_node 加 embedding 列（level=3 叶子用 pgvector 去重匹配）
--   2) 清空 interview_project_question 旧数据（旧数据 project_node_id 多为空，无法迁移）
--   3) 删除旧表 project_question
--
-- 执行：
--   psql "$DATABASE_URL" -f backend/scripts/migrations/002_project_node_embedding_and_drop_legacy.sql
--
-- 幂等：可重复执行。

-- pgvector 扩展（如已存在则跳过）
CREATE EXTENSION IF NOT EXISTS vector;

-- 1) project_node 加 embedding 列
ALTER TABLE project_node
    ADD COLUMN IF NOT EXISTS embedding vector(1024);

-- 2) 清空旧的项目类面试事实数据（重建链路）
TRUNCATE TABLE interview_project_question RESTART IDENTITY;

-- 3) 删除旧聚合表 project_question
DROP TABLE IF EXISTS project_question;
