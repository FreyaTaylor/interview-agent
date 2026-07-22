-- =============================================================================
-- V68: 面试复盘「组精炼」提示词种子（interview/group-refine）
--
-- 背景：用户在校对页拆分/合并/改归属后，组的 tag 带"（拆分）"残留、questions 退化为
--       原始面试官口语。需在后解析(finalize)对【被编辑】的组用 LLM 以最终对话为准重提
--       干净的 tag + 书面化 questions。
-- 约定：prompt 全中文；输出结构化 JSON；输入可能含语音转写错别字/口语，按语义理解。
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
2. questions：这段对话里【面试官】问的问题列表，逐条书面化、去口语噪声，每条只放一个问题；若确实没有明确问题则给空数组。

只输出 JSON，不要 markdown、不要解释：
{"tag": "…", "questions": ["…", "…"]}$PROMPT$,
'面试复盘：对被编辑的组用 LLM 以最终对话为准重提干净的 tag + 书面化 questions')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
