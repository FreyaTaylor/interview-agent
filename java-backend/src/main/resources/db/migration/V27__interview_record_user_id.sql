-- =============================================================================
-- V27 多用户隔离 —— interview_record 增加 user_id
--
-- 背景：延续 V26（knowledge_node 隔离），面试复盘记录也要按用户隔离 ——
--       历史列表 / 查重去重 / 落库都只在当前登录用户范围内。
--
-- 约定：与其余业务表一致 —— user_id BIGINT NOT NULL DEFAULT 1。
--       存量记录归属 user_id=1（旧数据保留）。
--       子表（interview_knowledge_question 等）经 record_id 外键级联隔离，无需各自加列。
-- =============================================================================

ALTER TABLE interview_record
    ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1;

-- 历史列表按 user_id + created_at 倒序；查重按 user_id + text_hash
CREATE INDEX idx_interview_record_user      ON interview_record(user_id, created_at DESC);
CREATE INDEX idx_interview_record_user_hash ON interview_record(user_id, text_hash);
