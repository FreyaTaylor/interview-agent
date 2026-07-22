-- =============================================================================
-- V69: 优化 interview/group-refine 的 questions 指令
--
-- 背景：V68 的 questions 说明让模型太容易返回空数组（实测总返 []），导致只精炼了 tag、
--       questions 没被书面化。加强指令：通常至少 1 条、给改写示例。
-- 说明：不改已应用的 V68（Flyway 校验），新迁移 upsert 覆盖 content。
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview/group-refine', $PROMPT$你是面试对话整理助手。下面是一道面试题（及其追问）的完整对话片段（可能有错别字、语音转写噪声、表述不完整，请按语义理解）：

【对话】
{dialogue}

这道题的类型是：{type}
（knowledge=技术知识点 / project=项目深挖 / algorithm=算法手撕 / hr=HR / other=其他）

请提炼两样：
1. tag：一个简短准确的主题标签（≤15字）。不要带"（拆分）"之类的编辑残留、不要带序号或标点噪声。
   - knowledge：技术点名（如"MySQL 事务隔离级别"）
   - project：项目话题（如"订单超时取消设计"）
   - algorithm：算法题名（如"反转链表"）
   - hr / other：一句话主题
2. questions：把这段对话里【面试官】提出的问题逐条改写成**书面化**问题（去掉"呃/那个/啥/来着"等口语词，补全主谓宾，一条一个问题）。
   - **通常至少有 1 条**（面试官总归问了点什么）；只有整段确实没有任何面试官提问时才给空数组 []。
   - 例：口语"呃你说一下Redis持久化RDB和AOF啥区别" → "Redis 的持久化机制 RDB 和 AOF 有什么区别？"

只输出 JSON，不要 markdown、不要解释：
{"tag": "…", "questions": ["…", "…"]}$PROMPT$,
'面试复盘：对被编辑的组用 LLM 以最终对话为准重提干净的 tag + 书面化 questions（V69 强化 questions）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
