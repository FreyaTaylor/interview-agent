-- =============================================================================
-- V29: interview_record 增加 embedding 列 —— 面试记录级「语义去重」
--
-- 背景：原查重基于 text_hash = SHA-256(raw_text)，存在两个问题：
--   1) 检测端算 SHA-256(前端原始输入文本)，写入端存 SHA-256(由 turns 重建文本)，
--      两套归一化几乎不可能相等 → 永远查不出重复（findByTextHash 永远 Total: 0）。
--   2) 即便口径统一，同一场面试「语音转写」与「手敲文本」字符不同 → hash 必然不同。
-- 改为语义去重：embed 整段面试文本（截断）入库，查重时按 pgvector 余弦最近邻 + 阈值判定。
-- 与 user_answer_embedding 一致使用 VECTOR(1024)（DashScope text-embedding-v3）。
-- 记录量按用户维度很小（数十条），WHERE user_id 过滤后顺序扫描即可，不建 ANN 索引。
-- =============================================================================

ALTER TABLE interview_record ADD COLUMN embedding VECTOR(1024);
