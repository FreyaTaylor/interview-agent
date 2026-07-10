-- V42: 移除学习页「探索对话」功能
-- 背景：探索对话（learn_chat + /chat + /chat-history + LearnChatService）体验价值低，决定下线。
-- 现象：学习页右侧对话区、划词引用回写讲解等交互整体废弃。
-- 根因：产品收敛——学习聚焦「子话题讲解 + 目标题」，对话链路不再保留。
-- 修复：删除 learn_chat 表及其 learn/chat prompt 种子（不兼容历史，直接删）。
--   相关后端 LearnChatService/Mapper/Entity/DTO、KnowledgeNodeMapper.deleteLearnChat 已在代码层移除。

DROP TABLE IF EXISTS learn_chat;

DELETE FROM prompt_template WHERE key = 'learn/chat';
