-- V59: 删除已弃用的 learn/subtopics-gen-interview prompt（三模块解耦 P6 清旧）
-- 背景：P4 回退了「学习生成把面试真题并入子话题」逻辑（#2），该 prompt 不再被调用；
--        PromptKeys.LEARN_SUBTOPICS_GEN_INTERVIEW 常量已同步移除，删 DB 行以保持 1:1。

DELETE FROM prompt_template WHERE key = 'learn/subtopics-gen-interview';
