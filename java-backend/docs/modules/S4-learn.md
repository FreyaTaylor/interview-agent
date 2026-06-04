# S4 — Learn 模块（讲解 + 探索对话）

> **范围**：知识点的"子话题化讲解" + 探索对话（基于引用追问 / 新增子话题）。
> **对应模块**：[JAVA_REWRITE_PLAN.md §4](../../../docs/JAVA_REWRITE_PLAN.md)。
> **Python 对照**：[backend/services/learn.py](../../../backend/services/learn.py)、[backend/prompts/learn_prompts.py](../../../backend/prompts/learn_prompts.py)、[backend/api/learn.py](../../../backend/api/learn.py)。
> **状态**：🚧 重构中（旧 `knowledge_content` Markdown 单文档 → 新 `knowledge_subtopic` 子话题结构化）

---

## 0. 关键决策（与之前版本的差异）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **拆 `knowledge_subtopic` 表**，废弃 `knowledge_content` | 子话题成为 UI 一级公民（独立卡片 + 重要度⭐ + 追问块）；LLM 产 JSON 比产 Markdown 鲁棒 |
| 2 | **直接 DROP 旧 `knowledge_content` 表**，不做数据迁移 | 开发期，数据丢了重生成；遵循"删就彻底删"原则 |
| 3 | **chat 不再融合改写**；改为：LLM 三选一动作（`append_followup` / `new_subtopic` / `none`） | 旧"实时融合改写"破坏性强且 prompt 复杂；新流程更可控 |
| 4 | **追问用 `followups JSONB` 累加**，不拆子表 | YAGNI；后续要单独编辑/删除某条追问时再 ALTER 拆 |
| 5 | **不加 subtopic embedding** | 引用匹配前端直传 `subtopic_id`，无须服务端向量召回 |
| 6 | **importance 仅 LLM 写**（生成时自评） | 一期不开放前端 ⭐ 控件，简化交互 |
| 7 | **引用匹配下沉到前端**：前端展示子话题卡片，用户引用某段时直接传 `subtopic_id` + 引用文本到 `/chat` | 避免后端规则匹配的 Markdown 解析痛点 |
| 8 | 删除 Python `_split_subtopics` / `_normalize_for_match` / `_find_subtopic_by_quote` / `_replace_subtopic` 4 个 helper（不移植） | 拆表后用不上 |

---

## 1. 使用的表

| 表 | 操作 | 说明 |
|---|---|---|
| `knowledge_node` | 只读 | 取 KP 名称 / `category_path` 注入 prompt |
| **`knowledge_subtopic`**（新建） | INSERT / SELECT / UPDATE | 子话题主表 |
| `learn_chat` | ALTER + INSERT / SELECT | 对话历史；V4 加 `quoted_subtopic_id BIGINT` 列，便于历史回看高亮 |
| `study_question` | INSERT / SELECT / DELETE | 题目；本模块只在 `ensureQuestions` 时写 |
| `prompt_template` | 只读 | 通过 `LlmInvoker` 间接取 |

### 1.1 `knowledge_subtopic` 表结构（V4 新增）

```sql
CREATE TABLE knowledge_subtopic (
    id            BIGSERIAL PRIMARY KEY,
    kp_id         BIGINT NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,                                  -- 不带 #### 前缀
    body_md       TEXT NOT NULL DEFAULT '',                       -- 正文 Markdown
    importance    SMALLINT NOT NULL DEFAULT 3
                  CHECK (importance BETWEEN 1 AND 5),             -- LLM 自评
    followups     JSONB NOT NULL DEFAULT '[]'::jsonb,             -- [{q,a,created_at}]
    sort_order    INT NOT NULL DEFAULT 0,
    source        TEXT NOT NULL DEFAULT 'initial'
                  CHECK (source IN ('initial','chat')),
    user_id       BIGINT NOT NULL DEFAULT 1,
    created_at    TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_subtopic_kp ON knowledge_subtopic(kp_id, sort_order);
```

`followups` 元素：`{ "q": "面试追问：...", "a": "答：...", "created_at": "2026-..." }`。

### 1.2 V4 迁移脚本

```sql
-- V4__knowledge_subtopic.sql
CREATE TABLE knowledge_subtopic ( ... );  -- 同上
CREATE INDEX idx_subtopic_kp ON knowledge_subtopic(kp_id, sort_order);

-- learn_chat 加引用 subtopic 外键（可选）
ALTER TABLE learn_chat ADD COLUMN quoted_subtopic_id BIGINT
    REFERENCES knowledge_subtopic(id) ON DELETE SET NULL;

-- 彻底废弃旧表
DROP TABLE IF EXISTS knowledge_content;
```

---

## 2. 与其他模块的交互

### 2.1 本模块对外提供
- `LearnContentService.ensureContent(kpId)`：S3 Study 首访某 KP 时调（懒生成讲解）
- `LearnQuestionService.ensureQuestions(kpId)`：S3 Study 首访某 KP 时调（懒生成题目）

### 2.2 本模块依赖
| 依赖 | 用途 | 来源 |
|---|---|---|
| `LlmInvoker` | 统一 LLM 调用 + 重试 | S0/common |
| `PromptService`（间接） | DB 取 prompt 模板 | S0/prompts |
| `KnowledgeNodeMapper.findById` | KP 校验 / 取 name | S1 |
| `LearnHelper.categoryPath(kpId)` | 取前缀路径（"redis → 数据结构 → Set"） | 本模块 |

### 2.3 下游消费
- 前端 `LearnPage`：取 `POST /learn/content`、渲染子话题卡片、引用某段后 `POST /learn/chat`
- S3 Study：`ensureContent` + `ensureQuestions` 在首访 KP 时被调一次

---

## 3. API 契约

全 POST，body 参数（仓库 `java-style.md` "API 形式" 约束）。

### 3.1 `POST /api/learn/content`
取 / 重生讲解（子话题列表）。

**Request**
```jsonc
{ "kp_id": 265, "action": "fetch" }   // action: fetch | regenerate
```

**Response (`code:0`)**
```jsonc
{
  "kp_id": 265,
  "kp_name": "equals与hashCode",
  "subtopics": [
    {
      "id": 1001,
      "title": "为什么重写 equals 必须重写 hashCode",
      "body_md": "...",
      "importance": 5,
      "followups": [
        { "q": "面试追问：哈希冲突...", "a": "答：...", "created_at": "..." }
      ],
      "sort_order": 1,
      "source": "initial"
    },
    ...
  ],
  "generated": false   // 本次是否触发了 LLM 生成
}
```

### 3.2 `POST /api/learn/questions`
取 / 重生题目（不变，复用 S4 旧实现）。

### 3.3 `POST /api/learn/chat`

**Request**
```jsonc
{
  "kp_id": 265,
  "message": "为啥用异或而不是与？",
  "quoted_subtopic_id": 1001,        // 可选：用户在某 subtopic 卡片内提问时由前端传入
  "quoted_text": "(h = key.hashCode()) ^ (h >>> 16)"   // 可选：引用的具体文本
}
```

规则：`quoted_subtopic_id` 有值 → prompt 引导 LLM 倾向 `append_followup`；null → 倾向 `new_subtopic` 或 `none`。最终动作由 LLM 决定（也可不听从引导，例如引用文本与问题无关时回 `none`）。

**Response**
```jsonc
{
  "reply": "因为异或保留更多随机性...",   // 给用户看的回复
  "action": "append_followup",          // append_followup | new_subtopic | none
  // action = append_followup
  "appended_to": 1001,                  // 落到哪个 subtopic
  "followup": { "q": "面试追问：...", "a": "答：..." },
  // action = new_subtopic
  "new_subtopic": {
    "id": 1042, "title": "...", "body_md": "...", "importance": 4, "sort_order": 8
  }
  // action = none：无副作用
}
```

### 3.4 `POST /api/learn/chat-history`
（不变）

---

## 4. 服务层

| 类 | 职责 |
|---|---|
| `LearnContentService` / `Impl` | resolveContent（fetch/regenerate）、ensureContent、内部 `generateSubtopics`（调 LLM 产 JSON list） |
| `LearnQuestionService` / `Impl` | （沿用现实现） |
| `LearnChatService` / `Impl` | chat 三分支：调 LLM → 写 `learn_chat` → 按 action 落 subtopic / followup |
| `LearnHelper` | `categoryPath(kpId)` |
| `KnowledgeSubtopicMapper` | CRUD：findByKp / insert / appendFollowup / deleteByKp |

### 4.1 chat 流程（核心）

```
Step 1: 校验 kp + 取 kp.name + categoryPath
Step 2: 拉该 KP 全部 subtopic（id, title, importance）+ 最近 10 轮对话
Step 3: 拉 quoted_subtopic_id 对应行（若有），传 title+body_md 给 prompt
Step 4: 调 LlmInvoker(prompt=learn/chat) → 解析 JSON 产出
        必须含字段：reply, action ∈ {append_followup, new_subtopic, none}
        action=append_followup → 必须含 target_subtopic_id, followup_question, followup_answer
        action=new_subtopic    → 必须含 new_subtopic{title, body_md, importance}
Step 5: 落 learn_chat：user 行（带 quoted_subtopic_id + quoted_text） + assistant 行（reply）
Step 6: 按 action 落库
        append_followup → UPDATE knowledge_subtopic SET followups = followups || jsonb_build_object(...)
        new_subtopic    → INSERT knowledge_subtopic (sort_order = max+1, source='chat')
        none            → 无副作用
Step 7: 返 ChatReplyView
```

**幂等性**：chat 不重试 LLM（一次失败就报错），避免重复 append。

### 4.2 generateSubtopics（讲解生成）

```
Step 1: 调 LlmInvoker(prompt=learn/subtopics-gen, vars={kp_name, category_path})
        返回 JSON：[ {title, body_md, importance}, ... ]，至少 3 条
        parser 校验：title 非空、importance ∈ [1,5]，否则抛 IllegalStateException → 重试
Step 2: 批量 INSERT 到 knowledge_subtopic（sort_order = 数组下标+1，source='initial'）
Step 3: 返回 ContentView（含 subtopics list）
```

---

## 5. Prompt（DB `prompt_template` 表，V4 seed）

| key | 用途 | 关键字段 |
|---|---|---|
| `learn/subtopics-gen` | 生成讲解（替换旧 `learn/content-gen`） | `{kp_name, category_path}` → JSON list |
| `learn/chat` | 探索对话 + 三选一动作（替换旧 `learn/chat`） | `{kp_name, category_path, content_summary, history, quoted_subtopic, quoted_text, user_input}` → JSON |
| `learn/questions-gen` | 题目生成（沿用） | 不变 |

**旧 key 同名 `learn/content-gen` / `learn/chat` 直接 UPDATE 覆盖**（V4 seed 用 `INSERT ... ON CONFLICT UPDATE`）。

---

## 6. 验收清单

- [x] Flyway V4 启动自动执行（建表 + DROP knowledge_content）
- [x] `POST /learn/content {kp_id, action:'fetch'}` 首次返 `generated:true` + ≥3 条 subtopic，每条 importance ∈ [1,5]
- [x] 二次访问同 kp 返 `generated:false`
- [x] `POST /learn/chat` 带 `quoted_subtopic_id` → action 多为 `append_followup`；不带 → 多为 `new_subtopic` 或 `none`
- [x] `append_followup` 后 SELECT subtopic 看到 `followups` 数组追加一条；`followup_question` / `followup_answer` 由 LLM 自产，面试题风格 + 精炼总结
- [x] `new_subtopic` 后 SELECT 看到新行 + `source='chat'`；与已有 subtopic 角度去重
- [x] 前端 LearnPage 渲染：子话题卡片 + ⭐重要度 + 追问块（折叠/展开）+ 引用某段后 chat 命中正确
- [x] 引用某段触发 append/new 后，目标卡片自动滚到视口中央 + 红色背景闪烁 2.2s
- [x] 每张 subtopic 卡片右上角 × 删除按钮（confirm 后 `POST /learn/subtopic-delete`）
- [x] 句号/问号/叹号后自动分行（两轮预处理，含跨行段升级为硬换行）
- [x] 统一输入组件 Enter = 换行，发送只能点按钮（LearnPage chat + AnswerInput）
- [x] 所有响应 `{code:0,...}` 格式

---

## 7. 改动清单（实施时勾选）

### Java 后端
- [x] `db/migration/V4__knowledge_subtopic.sql`（建表 + DROP `knowledge_content`）
- [x] `db/migration/V5__seed_learn_prompts.sql`（upsert 3 个 prompt）
- [x] `db/migration/V6 ~ V10` 迭代 prompt 修订：
  - V6/V7：subtopics-gen + chat 强化"面试题风格 / 总结而非硬塞"
  - V8：chat 引入 LLM 自产 `followup_question` / `followup_answer`（不再回灌 user_input）
  - V9：subtopics-gen + chat new_subtopic 加去重规则（按角度分类，每角度≤1 条）
  - V10：chat 加"聚焦不漂移"约束 + 反例 + 自检规则（followup 必须与 quoted_subtopic 讨论同一机制的同一面）
- [x] `learn/entity/KnowledgeSubtopic.java`（record；`followups` 字段类型为 `Object`，受 JsonbTypeHandler 约束）
- [x] `learn/mapper/KnowledgeSubtopicMapper.java`（@注解 CRUD + appendFollowup + `deleteById(id, kpId)`）
- [x] **删除** `learn/entity/KnowledgeContent.java`、`learn/mapper/KnowledgeContentMapper.java`
- [x] `learn/dto/SubtopicView.java`（新）
- [x] `learn/dto/ContentView.java`（改：subtopics list）
- [x] `learn/dto/ChatReplyView.java`（重塑：reply + action + appendedTo/followup/newSubtopic）
- [x] `learn/dto/ChatRequest.java`（加 `quotedSubtopicId`）
- [x] `learn/dto/SubtopicDeleteRequest.java`（新；`kpId` + `subtopicId`）
- [x] `learn/service/impl/LearnContentServiceImpl.java`（重写 generateAndPersist + 实现 `deleteSubtopic`，按 `(id, kp_id)` 删除）
- [x] `learn/service/impl/LearnChatServiceImpl.java`（重写 chat：三选一 + 落 subtopic；`doAppend` 读 LLM 自产 followup 字段，缺一即降级为 none）
- [x] `learn/controller/LearnController.java`（新增 `POST /api/learn/subtopic-delete`）
- [x] `common/JsonbTypeHandler`（确认能处理 List）

### 前端
- [x] `LearnPage.jsx` 渲染 subtopic 列表 + ⭐ + 追问折叠 + 引用传 id
- [x] `LearnPage.jsx` 新增能力：
  - `SubtopicCard` 头部 × 删除按钮（confirm + 调 `/subtopic-delete` + 同步 state/cache）
  - `flashAndScroll(subtopicId)`：append/new 后滚动到中央 + 红闪 2.2s（rAF×2 重启动画）
  - `preprocessSentences()` 两轮：行内 `句号  \n`，全文跨行 `句号\n非空白` → 硬换行；保留代码段/引用/标题
  - chat 输入框 Enter = 换行，按钮发送
- [x] `AnswerInput.jsx`：移除 Enter 触发发送（Enter 始终换行）
- [x] `styles.css`：`.learn-sub-card-flash` + keyframes、`.learn-sub-card-delete` 红色 hover、`.learn-sub-card-quoted` 金色边框、列表缩进修复
- [x] **浏览器验证**（per `/memories/frontend-rules.md`）

### Python
- 不动（Python 端逐步弃用，本表 DROP 仅影响 Java DB；Python DB 是另一套）

---

## 8. 未做（明确推迟）

- subtopic embedding 字段（按引用语义匹配）
- 用户手动调 importance（前端 ⭐ 控件）
- 流式 chat
- 单独的 `/merge-chat` 端点
- subtopic 排序拖拽 / 单条 followup 编辑或删除（本期只读 followup + 整卡删除）
