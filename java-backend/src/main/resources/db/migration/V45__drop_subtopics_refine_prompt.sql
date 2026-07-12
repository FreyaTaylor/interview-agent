-- V45: 清理退役的 prompt —— learn/subtopics-refine（two-step 审校，已退役）
-- 背景：目标题驱动重构后，去重前移到 Step A，two-step 的第二步 refine 已下线；
--   对应 Java 常量 PromptKeys.LEARN_SUBTOPICS_REFINE 与 two-step 服务代码均已删除，此处清 DB 残留行。

DELETE FROM prompt_template WHERE key = 'learn/subtopics-refine';
