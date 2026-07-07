-- V38: 讲解按面试题驱动重构 —— 数据层（纯 additive，不改变现有行为）
-- 背景：讲解从"按知识结构拆的浅列表"改为"目标导向"——每个子话题挂明示的面试题(=考核题)，
--   点击才懒生成深度正文。详见 local/java-backend-docs/design/2026-07-06-learn-question-driven-subtopics.md
-- 本迁移只加列/放松约束，历史数据默认值保证老逻辑不受影响。

-- 1. study_question 归属到子话题（目标题 = 该子话题的 study_question）
--    可空以兼容历史题（subtopic_id=null）；新流程写入时必填。
--    子话题删除级联删其题；历史 question_attempt 是逻辑外键、不受影响。
ALTER TABLE study_question
    ADD COLUMN subtopic_id BIGINT REFERENCES knowledge_subtopic(id) ON DELETE CASCADE;
CREATE INDEX idx_study_question_subtopic ON study_question(subtopic_id);

-- 2. knowledge_subtopic 支持"懒正文" + 子话题级掌握度
--    body_md 放开 NOT NULL：Step A 只出标题+目标题，正文点击(Step B)后补。
ALTER TABLE knowledge_subtopic ALTER COLUMN body_md DROP NOT NULL;
--    content_status：pending(仅列表) | ready(正文已生成)。历史行默认 ready（已有正文）。
ALTER TABLE knowledge_subtopic ADD COLUMN content_status VARCHAR(16) NOT NULL DEFAULT 'ready';
--    mastery_level：子话题级掌握度（该子话题所有 study_question 最近 N 次 finished 均分再平均；未答计 0）。
ALTER TABLE knowledge_subtopic ADD COLUMN mastery_level SMALLINT;
