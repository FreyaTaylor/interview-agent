# Phase 1 开发设计文档

## 一、系统概述

面试备考 Agent 系统，核心理念"以考代学"——通过 LLM 动态出题、Rubric 结构化评分、智能追问来帮助用户掌握面试知识点。

### 技术栈
| 层 | 技术 |
|---|------|
| 后端框架 | FastAPI (async) + Uvicorn |
| Agent 框架 | LangGraph (StateGraph) |
| 数据库 | PostgreSQL 16 + pgvector |
| ORM | SQLAlchemy 2.0 (async + asyncpg) |
| LLM | DeepSeek Chat (via langchain-openai) |
| 前端 | Streamlit |

### 启动方式
```bash
# 终端 1：后端
source .venv/bin/activate
python -m uvicorn backend.main:app --reload --host 0.0.0.0 --port 8000

# 终端 2：前端
source .venv/bin/activate
streamlit run frontend/app.py
```

---

## 二、核心流程

```
用户选择知识点
    │
    ▼
LLM 动态出题（面试官口吻）+ 生成 Rubric（总分=100）
    │
    ▼
用户回答
    │
    ▼
LLM 基于 Rubric 逐项评分
    ├── 得分 < 80 → LLM 自动追问遗漏点 → 用户再答 → 再评分（循环）
    └── 得分 >= 80 → 用户可点"下一题"
                          │
                          ▼
                   LLM 根据历史出题记录 + 遗漏点 → 出新角度的题（循环）
```

**关键设计决策：**
- 题目和 Rubric 不再预存数据库，全部由 LLM 动态生成
- 追问由 LLM 自动决定（非用户主导的自由探索），追问也有 Rubric 评分
- 每题评分后自动追加学习小结到 `conversation.learning_summaries`（JSONB）

---

## 三、数据模型

### 3.1 表结构（保留的）

| 表 | 用途 |
|---|------|
| `user` | 用户（Phase 1 固定 user_id=1） |
| `knowledge_node` | 知识树（三层：一级分类→二级分类→叶子知识点） |
| `study_session` | 学习会话 |
| `conversation` | 对话（一个知识点一次学习） |
| `conversation_message` | 消息明细 |
| `mastery_record` | 掌握度记录 |
| `mastery_history` | 掌握度变化历史 |

### 3.2 已删除的表
- `question` — 题目改为 LLM 动态生成，存在 `conversation.current_question`
- `rubric_item` — Rubric 改为 LLM 动态生成，存在 `conversation.current_rubric`

### 3.3 Conversation 核心字段

```python
class Conversation:
    current_question: str           # 当前题目（LLM 动态生成）
    current_rubric: list[dict]      # 当前 Rubric（JSONB）
    question_round: int             # 第几题
    learning_summaries: list[dict]  # 每题评分后追加的小结（JSONB）
    status: str                     # 'questioning' | 'answered' | 'finished'
```

### 3.4 掌握度计算

使用**指数移动平均（EMA）**：
```
新掌握度 = 0.4 × 本次得分 + 0.6 × 旧掌握度
```
近期表现权重更高，不会被很久前的低分拖累。

---

## 四、模块设计

### 4.1 目录结构

```
backend/
├── main.py                 # FastAPI 入口 + 生命周期
├── config.py               # 配置（数据库URL、LLM Key、业务参数）
├── database.py             # SQLAlchemy async engine + session
├── agents/
│   └── study_agent.py      # LangGraph 状态图定义
├── api/
│   └── study.py            # RESTful API（4个接口）
├── models/
│   ├── knowledge.py        # KnowledgeNode
│   └── study.py            # StudySession, Conversation, ConversationMessage, MasteryRecord
├── prompts/
│   └── study_prompts.py    # Prompt 模板（出题 + 评分）
├── schemas/
│   └── study.py            # Pydantic 请求/响应模型
├── services/
│   └── rubric.py           # LLM 调用（出题 + 评分）
└── scripts/
    └── seed_data.py        # 种子数据（只有知识树）
frontend/
└── app.py                  # Streamlit 界面
```

### 4.2 LangGraph Agent（study_agent.py）

```
                    ┌─────────────┐
                    │  __start__  │
                    └──────┬──────┘
                           │ route_action()
                    ┌──────┴──────┐
           action=  │             │  action=
         start/next │             │  answer
                    ▼             ▼
          ┌─────────────┐  ┌────────────┐
          │ generate_    │  │ score_     │
          │ question     │  │ answer     │
          └──────┬──────┘  └─────┬──────┘
                 │               │
                 ▼               ▼
               END             END
```

**State 定义：**
- `action`: "start" | "answer" | "next"
- `knowledge_point_name`: 知识点名
- `user_input`: 用户回答
- `question_history`: 已考题目+得分+遗漏点
- `question_content` / `rubric_items`: 当前题目和 Rubric
- `score` / `rubric_result` / `feedback`: 评分结果
- `follow_up` / `follow_up_rubric`: LLM 决定的追问
- `recommended_answer`: 推荐回答要点

### 4.3 API 接口

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/study/knowledge-points` | 知识点列表（含掌握度） |
| POST | `/api/study/start` | 开始学习 → LLM 出第一题 |
| POST | `/api/study/answer` | 提交回答 → 评分 + 追问决策 |
| POST | `/api/study/next` | 请求下一题 → LLM 根据历史出新题 |
| GET | `/api/study/summaries/{id}` | 获取学习小结 |

### 4.4 Prompt 设计

**出题 Prompt：**
- 面试官口吻，简洁直接（"说一下…"、"讲一下…"）
- 基于历史出题记录避免重复，根据遗漏点出新角度
- Rubric 3-5 项，总分强制 = 100（后端归一化兜底）

**评分 Prompt：**
- 逐项判定 hit/miss + 匹配原文
- 关键点描述 8 字以内
- 自动决定是否追问（<80分追问，>=80不追问）
- 追问也带 Rubric（总分=100）
- 生成推荐回答要点（3-5 条）

### 4.5 分数保障机制

| 环节 | 保障措施 |
|------|---------|
| 出题 | Prompt 要求总分=100 + 后端 `generate_question()` 归一化校正 |
| 评分 | Prompt 要求 total=Σ命中分 + 后端 `score_answer_with_rubric()` 重算 total |

---

## 五、前端设计

### 5.1 分色消息卡片

| 消息类型 | 背景色 | 左边框 |
|---------|--------|--------|
| 题目/追问 | #e8f4fd 浅蓝 | #2196F3 蓝 |
| 用户回答 | #fff8e1 浅黄 | #FF9800 橙 |
| 评分结果 | #e8f5e9 浅绿 | #4CAF50 绿 |

每一轮（题目+回答+评分）用圆角边框分组。

### 5.2 评分展示

每个关键点一行：
```
✅ SETNX+EX原子设置（25分）
  「使用SETNX和EX命令原子设置」

❌ 看门狗续期（25分）
  未提及
```

评分下方显示推荐回答（分点列表）。

---

## 六、配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `DATABASE_URL` | `postgresql+asyncpg://...` | 数据库连接 |
| `DEEPSEEK_API_KEY` | - | LLM API Key |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com/v1` | LLM 端点 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名 |
| `MAX_FOLLOW_UP_ROUNDS` | 3 | 单题最大追问轮数 |

---

## 七、Phase 2 待做

1. **用户认证** — 多用户支持，去掉 `user_id=1` 硬编码
2. **知识点懒生成** — 用户添加知识点时 LLM 自动构建知识树
3. **遗忘曲线** — 基于 `stability_s` 参数推荐复习时间
4. **语音输入** — 集成 ASR，支持语音回答
5. **面试复盘** — 上传面试记录，提取知识点薄弱项
6. **Alembic 迁移** — 替代 `create_all` 的表管理方式
