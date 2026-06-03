# 面试备考 Agent 系统 — 技术设计与实现

> 本文档基于当前代码仓库实际实现整理，覆盖产品理念、整体架构、数据模型、API、Agent、关键业务流程与外部依赖。
> 作为后续开发与新人 onboarding 的唯一技术参考。`docs/modules/` 下为单模块深入说明。

---

## 1. 产品概览

### 1.1 一句话定位
**以考代学**：通过 Agent 持续给用户出题、按 Rubric 评分、追踪掌握度，并基于面试录音/文本反向定位薄弱知识点，形成个性化的面试备考闭环。

### 1.2 核心抽象

所有输入归一化为 **`知识点 → 对话 → 评分 → 掌握度`**：

| 抽象 | 含义 | 落地表 |
|---|---|---|
| 知识点（KnowledgePoint） | 知识树的叶子节点，最小学习单元 | `knowledge_node` |
| 项目节点（ProjectNode） | 项目拷打的"项目→话题→问题"三层树 | `project_node` |
| 对话（Conversation/Session） | 一次围绕某知识点/项目的多轮问答 | `conversation` / `project_session` |
| Rubric | 评分关键点列表（JSON），总分恒为 100 | JSONB 字段 |
| 掌握度（Mastery） | 知识点级别 0-100，EMA 更新 | `mastery_record` / `mastery_history` |

### 1.3 一期范围（已落地）

- ✅ 知识树（手写 / LLM 生成 / 文本解析 / 图片解析 / .mm 导入 / 优化 / 合并 / 编辑）
- ✅ 学习与答题（动态出题 + Rubric 评分 + 追问 + 掌握度 EMA 更新）
- ✅ 知识点讲解内容（LLM 生成 + 缓存 + 探索对话 + 对话合并回讲解）
- ✅ 项目拷打（Tool-Calling Agent + 项目档案累积 + 真题库选题）
- ✅ 面试复盘（文本两阶段解析：preview → finalize；知识点/项目/算法/HR 分类评分；自动更新掌握度 + 写入长期记忆）
- ✅ GitHub OAuth 登录 + 用户画像

### 1.4 一期不做（预留）

| 功能 | 预留 |
|---|---|
| 多用户隔离 | 所有表 `user_id BIGINT DEFAULT 1`，二期接 JWT |
| 模拟面试模块 | 已移除，专注核心闭环 |
| 面经爬虫 pipeline | 二期接入小红书等数据源更新权重 |
| 录音上传前端入口 | 后端 ASR (DashScope Paraformer) 已就绪，仅未暴露 UI |
| Alembic 迁移 | 一期用 `create_all()` + 手写 SQL 迁移脚本 |
| 监控/看板 | 二期补 |

---

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 后端语言 | Python 3.11+ |
| Web 框架 | FastAPI（全异步） |
| ORM | SQLAlchemy 2.0 async + asyncpg |
| Agent 框架 | LangGraph（StateGraph + create_react_agent） |
| LLM | DeepSeek Chat (`deepseek-chat`) |
| Embedding | DashScope `text-embedding-v3`（1024 维） |
| 视觉 | DashScope `qwen-vl-max`（截图解析知识树） |
| ASR | DashScope Paraformer（异步任务） |
| 数据库 | PostgreSQL 16 + pgvector |
| 前端 | React 19 + Vite + react-router-dom 7 |
| Markdown | react-markdown + rehype-highlight + remark-gfm |

---

## 3. 仓库结构

```
backend/
├── main.py              # FastAPI 入口 + 路由注册 + lifespan
├── config.py            # Pydantic Settings
├── database.py          # async engine + session 工厂
├── models/              # 12 张 ORM 表
├── api/                 # 路由层（按域分文件，含 admin/、project_grilling/ 子包）
├── services/            # 业务逻辑（含 project_grilling/ 子包）
├── agents/              # LangGraph Agent（study_agent / project_grilling_agent）
├── prompts/             # LLM Prompt 模板（中文）
├── schemas/             # Pydantic IO 模型
├── skills/              # 可复用工具：embedding_match / learn_content / leetcode
└── scripts/
    ├── seed_data.py
    ├── backfill_node_embeddings.py
    └── migrations/      # 手写 SQL 迁移
frontend-react/
├── src/
│   ├── App.jsx          # 路由 + 顶导
│   ├── pages/           # 页面级组件
│   ├── components/      # 复用组件
│   ├── contexts/        # AuthContext / InterviewBusyContext
│   └── hooks/           # useSpeechRecognition 等
docs/
├── TECH_DESIGN.md       # 本文
└── modules/             # 各子模块详细说明
tests/                   # golden case
```

---

## 4. 系统架构

```
┌────────────────────────────────────────────────────────────┐
│  React SPA (Vite + react-router)                           │
│    KnowledgeTree │ Learn │ Exam │ Interview │ Grilling │   │
│                  │       │      │ Outliner  │ Profile  │   │
└──────────────────────────┬─────────────────────────────────┘
                           │ HTTP / JSON
┌──────────────────────────┴─────────────────────────────────┐
│  FastAPI (async)                                           │
│    api/ ─→ services/ ─→ agents/ (LangGraph) ─→ LLM/Embed   │
│                    └─→ models/ (SQLAlchemy async)          │
└──────────────────────────┬─────────────────────────────────┘
                           │ asyncpg
                  ┌────────┴────────┐
                  │ PostgreSQL 16   │
                  │ + pgvector      │
                  └─────────────────┘
                           ▲
                           │
              ┌────────────┴──────────────┐
        DeepSeek Chat              DashScope
        （出题/评分/解析/讲解）    （Embedding / ASR / Vision）
```

---

## 5. 数据模型（12 张表）

所有表均含 `id BIGSERIAL PK`、`created_at TIMESTAMP DEFAULT NOW()`（通过 [TimestampMixin](backend/models/base.py) 注入）。表名 `snake_case` 单数。

### 5.1 知识树

| 表 | 关键字段 | 说明 |
|---|---|---|
| `knowledge_node` | `parent_id`, `name`, `level`, `node_type`(`category`/`leaf`), `interview_weight`(1-5), `embedding`(Vector 1024) | 邻接表多层结构，叶子节点即"知识点" |
| `knowledge_content` | `knowledge_point_id UNIQUE`, `content`(MD), `questions`(JSONB), `user_additions`(JSONB) | 知识点讲解缓存，按需 LLM 生成 |
| `learn_chat` | `knowledge_point_id`, `role`, `content`, `quoted_text` | 知识点探索对话历史 |

### 5.2 学习与掌握度

| 表 | 关键字段 |
|---|---|
| `study_session` | `user_id`, `source_type`(`text_upload`/`manual_select`), `title` |
| `conversation` | `study_session_id`, `knowledge_point_id`, `current_question`, `current_rubric`(JSONB), `pending_questions`(JSONB), `learning_summaries`(JSONB), `follow_up_count`, `status`(`questioning`/`answered`/`finished`) |
| `conversation_message` | `role`(`user`/`agent`), `content`, `message_type`(`question`/`answer`/`scoring`/`follow_up`/`summary`) |
| `mastery_record` | `user_id`, `knowledge_point_id`, `mastery_level`(0-100), `stability_s`, `study_count`, `last_studied_at` |
| `mastery_history` | `knowledge_point_id`, `conversation_id`, `score`, `previous_mastery`, `new_mastery` |

### 5.3 面试复盘

| 表 | 关键字段 |
|---|---|
| `interview_record` | `raw_text`, `company`, `position`, `text_hash`(去重), `avg_score`, `pass_estimate`, `parsed_questions`(JSONB), `summary_report`, `draft_turns`(JSONB), `draft_groups`(JSONB) |
| `interview_knowledge_question` | `interview_record_id`, `knowledge_node_id`, `tag`, `questions`(JSONB), `user_answer`, `original_dialogue`, `score_result`(JSONB) |
| `interview_project_question` | `interview_record_id`, `project_node_id`, `project_name`, `questions`, `user_answer`, `score_result` |
| `interview_other_question` | `category`(`algorithm`/`hr`/`other`), `tag`, `questions`, `user_answer`, `score_result` |

> **草稿字段**：`draft_turns` / `draft_groups` 是“面试解析校对”弹框保存但未提交的中间状态，
> 由 `POST /api/interview/draft` 写入，`finalize_interview()` 成功后清零。历史列表返回 `has_draft` / `has_parsed` 两个布尔供
> 前端判断“草稿” / “未解析” 状态。

### 5.4 项目拷打

| 表 | 关键字段 |
|---|---|
| `project` | `name`, `description`, `tech_stack`(JSONB), `role`, `highlights`, `root_node_id` |
| `project_node` | 三层邻接表：`level 1=项目` → `level 2=话题` → `level 3=问题`；`embedding` 用于去重与话题合并 |
| `project_session` | `project_id`, `current_topic`, `current_question`, `current_rubric`, `learning_summaries`, `readiness_score`, `follow_up_count` |
| `project_session_message` | `message_type`, `content`, `extra`(JSONB 评分详情) |
| `project_user_profile` | 项目档案：从拷打回答中持续累积的 `facts` / `weak_points` |

> **未命名项目单根策略**：面试解析时若 `project_name` 与现有项目都不匹配，一律挂到
> 唯一的「未命名项目」根节点下（与「未命名知识点」一致），避免多棵孤立根节点。存量数据由
> `migrations/012_merge_unnamed_project_roots.sql` 合并。

### 5.5 用户

| 表 | 关键字段 |
|---|---|
| `"user"` | `username UNIQUE`, `password`, `role`, `profile_text`, `github_id`, `avatar_url` |

---

## 6. 后端模块

### 6.1 入口与配置

- [backend/main.py](backend/main.py)：注册 9 个 router（study / knowledge / interview / admin / learn / project-grilling / auth / profile / **modular admin 子路由**），`lifespan` 中 `create_all()` 初始化表
- [backend/config.py](backend/config.py)：`Settings(BaseSettings)`，关键字段 `DATABASE_URL` / `DEEPSEEK_API_KEY` / `DEEPSEEK_MODEL` / `DASHSCOPE_API_KEY` / `MAX_FOLLOW_UP_ROUNDS=3`
- [backend/database.py](backend/database.py)：`create_async_engine` + `async_sessionmaker`，`get_db()` 依赖注入

### 6.2 路由总览

所有响应统一使用 [`ApiResponse`](backend/schemas/common.py) 包装：`{ code, data, message }`。`code=0` 成功，`40xxx` 客户端错误，`50xxx` 服务端错误。

| Router | Prefix | 主要 endpoint |
|---|---|---|
| study | `/api/study` | `GET /knowledge-points`、`POST /start`、`POST /answer`、`POST /next`、`POST /exam-start`、`POST /start-with-answer`、`GET /exam-progress/{kp_id}` |
| knowledge | `/api/knowledge` | `GET /tree` |
| learn | `/api/learn` | `GET/DELETE /content/{kp_id}`、`POST /chat`、`GET /chat-history/{kp_id}`、`POST /merge-chat` |
| interview | `/api/interview` | `POST /preview-parse`、`POST /finalize`、`POST /draft`、`POST /history/{id}/recalibrate`、`GET /history`、`GET /history/{id}`、`POST /check-duplicate`、`POST /overwrite` |
| admin (tree_gen) | `/api/admin` | `POST /trees/from-text`、`/from-generate`、`/from-image`、`/from-mm`、`/trees/{id}/optimize`、`/trees/merge` |
| admin (tree_nodes) | `/api/admin` | `GET/POST /tree-nodes`、`PUT/DELETE /tree-nodes/{id}`、`PUT /tree-nodes/batch-sort` |
| admin (project_nodes) | `/api/admin` | `POST /project-nodes/from-text`、`GET/POST /project-nodes`、`PUT/DELETE /project-nodes/{id}`、`PUT /project-nodes/batch-sort` |
| project_grilling | `/api/project-grilling` | `POST /start`、`POST /answer`、`POST /next`、`POST /pick-question`、`POST /end`、`GET /projects`、`GET /projects/{id}/dimensions` |
| auth | `/api/auth` | `GET /github-authorize-url`、`POST /github-callback`、`GET /me` |
| profile | `/api/profile` | 用户画像 CRUD |

### 6.3 Service 层

按职责分文件，每个 service 一句话职责：

| 文件 | 职责 |
|---|---|
| `study.py` | 学习会话生命周期（start / answer / next / exam-start / start-with-answer） |
| `rubric.py` | 调 LLM 出题（一次 3-5 题）+ Rubric 评分 + 追问决策 |
| `mastery.py` | 掌握度 EMA 更新（`0.4*new + 0.6*old`）+ 历史记录 |
| `knowledge_node.py` | 知识节点增删改查 + 推荐排序 |
| `tree_gen.py` | 知识树多源构造（text / generate / image / mm）+ optimize / merge |
| `learn.py` | 知识点讲解内容生成、缓存、对话、合并 |
| `interview_parser.py` | 面试文本解析流水线（详见 §8.2） |
| `interview_matcher.py` | groups → knowledge_node / project_node 匹配（embedding + LLM rerank） |
| `interview_scorer.py` | 各类问题评分（knowledge/project/algorithm/hr） |
| `interview_storage.py` | 评分结果落库 + 更新权重 + 写长期记忆 |
| `interview_turns.py` | turns ↔ groups 重建辅助 |
| `interview_crud.py` | preview-parse / finalize / history / dedup 编排 |
| `embedding.py` | 调 DashScope text-embedding-v3 |
| `asr.py` | DashScope Paraformer ASR（ffmpeg→OSS→submit→poll） |
| `llm.py` | `get_llm()` 工厂 + `parse_llm_json()` + 调试回调 |
| `project_node.py` / `project_node_matcher.py` | 项目节点 CRUD + 三层匹配 |
| `project_question.py` | 项目真题库按维度选题 |
| `project_profile.py` | 项目档案累积与渲染 |
| `project_grilling/` | 项目拷打子流程（start/answer/next/end 编排） |
| `user.py` / `profile.py` | 用户与画像 |
| `_db_utils.py` | SQLAlchemy 通用小工具（`get_or_create`），主要服务于各种「未命名 *」根的懒创建 |

> **Admin 树形 CRUD 路由工厂**：[backend/api/admin/_tree_router_factory.py](backend/api/admin/_tree_router_factory.py) 封装了
> list / create / update / delete / batch-sort 5 个通用端点，tree_nodes 和 project_nodes 各自只需定义两个 Pydantic 请求体
> 并注入 service 函数，不再手写重复路由。跨父移动一致使用 Pydantic v2 的 `model_fields_set` 判断是否显式传了 `parent_id`
> （包括 `null`），避免拖到根层被静默忽略。

### 6.4 Agent

**LangGraph 风格**：State = TypedDict，节点函数动词开头，条件边 `should_xxx()` 命名。Prompt 全部在 `backend/prompts/` 中，不硬编码。

#### 6.4.1 [study_agent.py](backend/agents/study_agent.py) — 学习对话

- State `StudyState`：`action` / `knowledge_point_name` / `user_input` / `question_history` / `question_content` / `rubric_items` / `score` / `follow_up` / `feedback`
- 节点：
  - `generate_question_node`：一次生成 3-5 题 + 各自 Rubric，首题作 current，其余进 `pending_questions`
  - `score_answer_node`：按 Rubric 逐项判断命中，归一化总分=100；`score < 80` 时返回追问，否则 `follow_up=None`
- 由 HTTP 请求逐步驱动（无 `interrupt`），单次请求触发单节点

#### 6.4.2 [project_grilling_agent.py](backend/agents/project_grilling_agent.py) — 项目拷打

- 使用 `create_react_agent`（Tool-Calling 模式）
- 工具集（[backend/agents/tools.py](backend/agents/tools.py)）：`query_real_questions` / `score_answer` / `generate_follow_up` / `generate_summary`
- 由 LLM 自主决策何时查真题库、出题、评分、追问、结束

### 6.5 Prompts

| 文件 | 用途 |
|---|---|
| `study_prompts.py` | `GENERATE_QUESTION_PROMPT`、`RUBRIC_SCORING_PROMPT` |
| `interview_prompts.py` | 面试解析分组分类、知识点/项目/HR 评分、整体面试官评语 |
| `tree_prompts.py` | 知识树 generate / parse-text / parse-image / optimize / merge / dedup |
| `learn_prompts.py` | 讲解内容生成、补充内容生成、探索对话 |
| `project_grilling_prompts.py` | 项目拷打系统 Prompt（人设 + 工具使用指南） |
| `project_prompts.py` | 项目档案抽取、项目节点解析 |
| `extract_profile_prompt.py` | 从用户输入抽取画像信息 |

### 6.6 Skills（可复用工具）

[backend/skills/](backend/skills/)：
- `embedding_match_skill.py`：通用 embedding + LLM rerank 匹配
- `learn_content_skill.py`：讲解内容操作的薄封装
- `leetcode_skill.py`：识别算法题 → 匹配 LeetCode 题号/难度

---

## 7. 前端架构

### 7.1 路由（[App.jsx](frontend-react/src/App.jsx)）

| 路径 | 页面 |
|---|---|
| `/` | KnowledgeTreePage |
| `/learn/:kpId` | LearnPage |
| `/exam/:kpId` | ExamPage |
| `/study` `/study/:kpId` | StudyPage |
| `/interview` `/interview/:recordId` `/interview/new/review` | InterviewPage / InterviewReviewPage |
| `/grilling` `/grilling/:projectId` | ProjectGrillingPage |
| `/admin/:tab` | OutlinerPage（知识树/项目编辑器） |
| `/profile` | ProfilePage |

未登录显示 `LoginPage`（GitHub OAuth）。导航栏在 `InterviewBusyContext.busy=true` 时把其他链接改为"新标签打开"，避免中断解析流程。

### 7.2 状态与 Hook

- `AuthContext`：JWT 持久化、`user` / `loading` / `logout`
- `InterviewBusyContext`：面试解析进行中标志位
- `useSpeechRecognition`：Web Speech API 封装（浏览器原生 ASR）

### 7.3 关键页面要点

- **KnowledgeTreePage**：树形展示 + 掌握度进度条 + 权重星标
- **LearnPage**：左侧讲解（Markdown），右侧探索对话；支持引用片段
- **ExamPage**：左侧目录，右侧题目 + Rubric 表 + 输入 + 评分回显 + 下一题
- **InterviewPage**：上传 → preview-parse 预览（turns + groups 可编辑）→ finalize → 评分结果三 Tab
- **ProjectGrillingPage**：选项目 → 消息流（题/答/评/反馈）→ 下一题
- **OutlinerPage**：幕布风格编辑器，支持多源创建、增删改排序、优化/合并

---

## 8. 关键业务流程

### 8.1 学习一道题（端到端）

```
[前端] 选知识点 → POST /api/study/start
[后端 study_agent.generate_question_node]
  └ LLM 生成 3-5 题 + Rubric → 首题 current，其余 pending
  └ INSERT conversation
返回首题 + Rubric
       ↓
[前端] 用户回答 → POST /api/study/answer
[后端 study_agent.score_answer_node]
  └ LLM 按 Rubric 评分 + 追问决策
  └ UPDATE conversation / INSERT conversation_message
  └ services/mastery.update_mastery_ema()   # 0.4*new + 0.6*old
  └ INSERT mastery_history
返回 { score, items, feedback, follow_up? }
       ↓
[前端] 若 follow_up 非空 → 继续 answer；否则 POST /api/study/next
[后端] 从 pending_questions 取下一题或重新生成
```

### 8.2 面试文本解析与入库

两阶段，落库前用户可校对：

```
[Step 1] POST /api/interview/preview-parse   ── 不落库
  services/interview_parser.parse_interview_text(text)
    1. split_into_turns           按"面试官："切 turn
    2. chunk_turns(1200)          Q&A 边界分段
    3. _parse_single_chunk        并发 LLM 解析各段（INTERVIEW_PARSE_PROMPT）
    4. _merge_by_embedding_boundary   跨段边界 embedding 合并
    5. _merge_project_topics      同项目话题语义合并
    6. _dedup_other_groups        other 类去重
    7. _enrich_leetcode_groups    LeetCode 补题号/难度
    8. _normalize_to_legacy_schema
  → { turns, groups, summary }
       ↓
[Step 2] 用户在前端校对 turns + groups（拖拽/编辑/删除）
       ↓
[Step 2.5 可选] POST /api/interview/draft
  services/interview_crud.save_draft()
    将当前校对中的 turns/groups 存到 interview_record.draft_turns/draft_groups
    再次打开校对弹框时优先回填 draft，避免丢失修改
       ↓
[Step 3] POST /api/interview/finalize  或  POST /api/interview/history/{id}/recalibrate
  services/interview_crud.finalize_interview()
    a. 拼回 raw_text，INSERT interview_record
    b. interview_matcher.match_nodes()     knowledge ⇒ knowledge_node；project ⇒ project_node 三层挂载
       - knowledge 未匹配 → 「未命名知识点」根下新建占位叶子
       - project   未匹配 → 「未命名项目」根（全局唯一）下挂入对应话题/问题
    c. interview_scorer.score_all_groups() knowledge=Rubric，project=印象评估，algorithm=题解对比，hr=表现评价
    d. interview_storage.store_new_interview_tables()
    e. update_knowledge_weights()          被考到的知识点 interview_weight ↑
    f. services/mastery.update_mastery_ema() 自动更新掌握度
    g. LLM 生成 3-5 句面试官评语 + 通过概率
    h. 清空 draft_turns/draft_groups
  → 评分结果 + 整体分析

> **详情读取时的名字同步**：`get_history_detail()` 会按 `matched_node_id` /
> `matched_project_id` 从 `knowledge_node` / `project_node` 实时刷新
> `matched_node_name` / `matched_project_name`，让管理页改过的节点名立刻在
> 历史面试详情中生效。
```

### 8.3 项目拷打

```
[前端] 选项目 → POST /api/project-grilling/start
[后端] services/project_grilling/...
  └ 创建 project_session
  └ 构建系统 Prompt（项目名/描述/技术栈/亮点 + 项目档案）
  └ project_grilling_agent (Tool-Calling)
      ├ query_real_questions(dim)   查项目真题库
      └ 返回首题 + Rubric
       ↓
[前端] 用户回答 → POST /api/project-grilling/answer
[后端]
  └ project_profile.extract_and_apply()  从回答抽取事实/弱点 → 累积档案
  └ Agent 调 score_answer / generate_follow_up
  └ INSERT project_session_message
       ↓
[前端] 结束拷打 → POST /api/project-grilling/end
[后端] Agent 调 generate_summary → 准备度评分 + 强弱项 + 改进建议
```

---

## 9. 外部依赖

### 9.1 DeepSeek Chat

[backend/services/llm.py](backend/services/llm.py) `get_llm(temperature)` 工厂返回 `ChatOpenAI`，base_url 指向 DeepSeek。温度建议：
- 评分/解析：`0.1`（可重复）
- 出题/讲解：`0.3`
- 对话：`0.5`

`parse_llm_json()` 处理 markdown 代码块包裹、截断补齐。`LLM_DEBUG=1` 输出完整 request/response。

### 9.2 DashScope

| 能力 | 用途 | 入口 |
|---|---|---|
| `text-embedding-v3`（1024 维） | 知识/项目节点 embedding、用户回答长期记忆、跨段合并 | [services/embedding.py](backend/services/embedding.py) |
| Paraformer ASR | 异步音频转写（ffmpeg→OSS→submit→poll，max 600s，max 300MB） | [services/asr.py](backend/services/asr.py) |
| `qwen-vl-max` | 截图解析知识树 → 嵌套 JSON | [services/tree_gen.py](backend/services/tree_gen.py) `create_tree_from_image` |

### 9.3 pgvector

- `knowledge_node.embedding`、`project_node.embedding` 均使用 `Vector(1024)`
- 用于：面试问题 → 知识节点匹配、项目话题去重合并

---

## 10. 约定与规范

详见 [CONVENTIONS.md](../CONVENTIONS.md)，要点：

- 中文注释解释业务逻辑；类型注解必填
- 异步全链路：路由 / DB / LLM
- 表名 snake_case 单数；JSONB 存半结构化数据
- 路径 kebab-case 复数；响应统一 `ApiResponse`
- Prompt 在 `backend/prompts/`，禁止硬编码
- 一题最多 `MAX_FOLLOW_UP_ROUNDS=3` 轮追问
- Rubric 总分强制归一化为 100
- 掌握度 EMA：`new_level = 0.4 * score + 0.6 * old_level`
- 用户输入可能含 STT 错别字 → 一律语义匹配，禁止精确匹配

---

## 11. 启动与开发

```bash
# 1. PostgreSQL（pgvector 镜像）
docker run -d --name interview-pg \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=interview_agent \
  -p 5432:5432 pgvector/pgvector:pg16

# 2. Python
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # 填 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY

# 3. 后端（项目根目录运行，注意是 backend.main:app）
uvicorn backend.main:app --reload --host 0.0.0.0 --port 8000

# 4. 前端
cd frontend-react && npm install && npm run dev   # http://localhost:5173
```

数据库迁移：一期通过 `Base.metadata.create_all()` 自动建表；新增字段通过 [backend/scripts/migrations/](backend/scripts/migrations/) 中手写 SQL 演进，二期切 Alembic。

---

## 12. 后续演进方向

| 项 | 计划 |
|---|---|
| 多用户隔离 | 接入 JWT 中间件，依赖 `current_user`，去掉 `user_id DEFAULT 1` |
| Alembic | 替换 `create_all` + 手写 SQL |
| 遗忘曲线 | 启用 `mastery_record.stability_s`，按 SM-2 简化算法推荐复习 |
| 面经 pipeline | 小红书爬虫 → 题目入库 → 更新 `interview_weight` |
| 录音端到端 | 前端上传 → ASR → 复用面试解析流程 |
| 监控/看板 | LLM 调用次数/耗时、掌握度热力图、学习曲线 |
