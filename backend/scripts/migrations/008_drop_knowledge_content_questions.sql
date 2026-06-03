-- 题目已统一迁移到 study_question 表（与答题页同源）
-- knowledge_content.questions 字段废弃，彻底删除避免误导
ALTER TABLE knowledge_content DROP COLUMN IF EXISTS questions;
