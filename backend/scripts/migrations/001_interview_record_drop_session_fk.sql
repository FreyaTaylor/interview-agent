-- Migration 001: interview_record.study_session_id 解耦
--
-- 背景：早期面试复盘借用了 study_session 容器（source_type='text_upload'），
-- 但面试复盘从不创建 conversation / mastery_record，session 只是一行空壳，
-- 是历史包袱。本次将 FK 改为 nullable，新记录不再写入；老数据保留不动。
--
-- 执行方式（任选其一）：
--   docker compose exec postgres psql -U interview -d interview_agent -f /docker-entrypoint-initdb.d/001.sql
--   psql "$DATABASE_URL" -f backend/scripts/migrations/001_interview_record_drop_session_fk.sql
--
-- 幂等：可重复执行。

ALTER TABLE interview_record
    ALTER COLUMN study_session_id DROP NOT NULL;
