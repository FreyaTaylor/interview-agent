-- V12: knowledge_node 增加 study 派生字段
-- - mastery_level    最近一次 study finish 后实时刷新的掌握度（0-100），可空表示从未学过
-- - study_count      该 KP 上 finished 作答累计次数，每次 finish +1
--
-- 仅 study/finish 钩子写入；admin CRUD / S5 不动这两列。

ALTER TABLE knowledge_node
    ADD COLUMN IF NOT EXISTS mastery_level SMALLINT,
    ADD COLUMN IF NOT EXISTS study_count   INTEGER NOT NULL DEFAULT 0;
