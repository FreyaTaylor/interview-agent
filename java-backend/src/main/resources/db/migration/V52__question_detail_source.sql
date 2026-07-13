-- V52: question_detail 加 source + interview_record_id —— 面试真题落库溯源
-- 背景：设计 2026-07-12-interview-questions-landing.md。面试真题与生成目标题同存 tree_node(question)+question_detail，
--        用 source 区分、interview_record_id 溯源；真题在 regenerate 删题时豁免、展示层标"真题"。
-- 现象：此前无法区分题来源，面试真题会被"重新生成学习内容"误删、也无法在管理/复盘页标注。
-- 修复：加两列。source 默认 generated（历史题即生成题）；interview_record_id 可空（真题指向那次面试）。

ALTER TABLE question_detail
    ADD COLUMN source TEXT NOT NULL DEFAULT 'generated',
    ADD COLUMN interview_record_id BIGINT;

-- source 取值约束：generated（LLM 生成目标题）| interview（面试真题）
ALTER TABLE question_detail
    ADD CONSTRAINT question_detail_source_check
    CHECK (source IN ('generated', 'interview'));

-- 按来源过滤/统计（真题徽标、regenerate 豁免）
CREATE INDEX idx_question_detail_source ON question_detail(source);
