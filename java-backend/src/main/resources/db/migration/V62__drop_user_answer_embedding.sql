-- V62：删除只写不读的 user_answer_embedding 表
--
-- 背景：面试 finalize 里 storeAnswerEmbeddings 每次对每个 knowledge/project 分组
--       调 embedding 写入 user_answer_embedding（复刻 Python 的「长期记忆」预留）。
-- 现象：全库无任何 SELECT/FROM user_answer_embedding —— 只写从不读，
--       消费端（长期记忆召回）始终未实现。
-- 根因：Python 迁移期按表建的预留能力，一期不做，白算 embedding + 白占存储。
-- 修复：删表；同步已删 storeAnswerEmbeddings / UserAnswerEmbeddingMapper / 调用点。
DROP TABLE IF EXISTS user_answer_embedding;
