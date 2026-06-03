-- 添加 extension_qa 字段：finish 阶段 LLM 生成的 3 个延伸深挖 Q&A
-- 格式：[{"q": "...", "a": "..."}, ...]
ALTER TABLE question_attempt ADD COLUMN IF NOT EXISTS extension_qa JSONB;
