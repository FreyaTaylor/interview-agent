-- 006: 删除 project_question_backlog（"待深入问题"队列）
-- 背景：流程简化，追问与下一题统一交由 LLM 现场生成，不再维护独立 backlog。
-- 同时回收 prompt 中 backlog_add 字段、相关 service / model / extra 字段。

DROP TABLE IF EXISTS project_question_backlog CASCADE;
