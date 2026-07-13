-- =============================================================================
-- V60: 面试知识点 rerank prompt —— 强化「跨技术域」拒绝
--
-- 背景：面试解析把分组 knowledge_point 名 embedding 后召回 top-k 叶子做 LLM rerank。
-- 现象：跨域错配 —— "Spring 事务失效场景" 被匹配到 "Redis 事务与 lua 脚本"（都含"事务"，
--       向量距离近，但技术域完全不同）。
-- 根因：候选此前只给 LLM 看叶子"名"（无所属分类），LLM 缺"域"信息难以判别；
--       现在候选改为「父路径 / 名」（如 Redis / 事务与lua脚本），prompt 同步要求：
--       所属技术域（父分类）与问题考察域不符时，即便字面/距离很近也必须判 null。
-- 修复：仅更新 content（ON CONFLICT 幂等），占位符 {text}{candidates} 不变。
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview/match-knowledge-rerank', $PMT$你正在为面试问题匹配最合适的知识点。

## 面试问题
{text}

## 候选知识点（已按向量相似度排序，格式：父分类 / 知识点名）
{candidates}

## 规则
- 仔细判断面试问题真正考察的核心知识点
- 用户输入可能有口误/语音转写错误，按语义而非字面匹配
- **注意「域」**：候选的「父分类」代表它所属的技术域。若候选所属的技术域与问题考察的
  技术域不同，即使名字里有相同词、向量距离很近，也必须判定不匹配（输出 null）
  - 例如：问「Spring 事务失效场景」，候选只有「Redis / 事务与lua脚本」——两者都含"事务"
    但一个是 Spring 声明式事务、一个是 Redis 命令原子性，技术域不同 → 输出 null
  - 例如：问「Redis 持久化」，候选只有「Redis / 数据结构」——同域但考察点不同 → 输出 null
- 如果有明显匹配的候选（域一致且考察点相符），输出该候选的 id
- 如果所有候选都和问题考察点不符，输出 null

## 输出格式（JSON）
```json
{"node_id": 123, "reason": "简要理由"}
```
或
```json
{"node_id": null, "reason": "都不匹配"}
```

只返回 JSON。$PMT$, $PMT$S8 面试复盘：知识点 embedding 召回后的 LLM rerank（候选带父路径，强化跨技术域拒绝；占位符 {text}{candidates}）$PMT$)
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
