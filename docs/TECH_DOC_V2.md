# 面试备考 Agent — 技术文档 v2

> 最后更新：2026-05-11

## 项目概述

面试备考 Agent 系统，核心理念"以考代学"。LangGraph + FastAPI + React 全栈，DeepSeek Chat API 驱动。

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Python 3.11, FastAPI (async), SQLAlchemy 2.0 (async + asyncpg) |
| Agent | LangGraph, DeepSeek Chat API |
| 数据库 | PostgreSQL 16 + pgvector |
| 前端 | React 19, Vite 8, react-markdown |
| 向量化 | DashScope text-embedding-v3 (1536维，当前未启用) |
| 视觉 | DashScope qwen-vl-max (截图解析知识树) |

---

## 目录结构

```
backend/
├── main.py                 # FastAPI 入口，路由注册
├── config.py               # 环境变量配置
├── database.py             # SQLAlchemy async engine + session
├── agents/
│   └── study_agent.py      # LangGraph 学习 Agent（出题+评分）
├── api/
│   ├── admin.py             # 管理 API（知识树CRUD + 多树创建 + 优化 + 合并）
│   ├── interview.py         # 面试复盘 API
│   ├── knowledge.py         # 知识树查看 API
│   ├── learn.py             # 学习页 API（讲解生成 + 探索对话 + 合并）
│   └── study.py             # 答题 API（出题 + 评分 + 掌握度 + 停止追问）
├── models/
│   ├── base.py              # Base + TimestampMixin
│   ├── knowledge.py         # KnowledgeNode（邻接表）
│   ├── learn.py             # KnowledgeContent + LearnChat
│   ├── study.py             # StudySession + Conversation + MasteryRecord + ...
│   ├── interview.py         # InterviewRecord + UserAnswerEmbedding
│   └── user.py              # User
├── prompts/
│   ├── tree_prompts.py      # 知识树生成/解析/优化/合并/去重检测 Prompt
│   ├── learn_prompts.py     # 面试题生成 + 探索对话 + 对话合并 Prompt
│   ├── study_prompts.py     # 出题 + Rubric评分 Prompt
│   └── interview_prompts.py # 面试文本解析 Prompt
├── services/
│   ├── llm.py               # get_llm() + parse_llm_json()
│   ├── tree.py              # 知识树创建/合并/优化/去重检测服务
│   ├── embedding.py         # DashScope embedding（当前未使用）
│   ├── rubric.py            # Rubric 评分服务
│   └── interview.py         # 面试文本解析服务
├── skills/
│   ├── __init__.py          # 导出
│   └── learn_content_skill.py  # 知识讲解生成 Skill（模块注册表）
├── schemas/
│   ├── common.py            # ApiResponse 统一响应
│   └── study.py             # 请求/响应 Schema
└── scripts/
    └── seed_data.py         # 种子数据

frontend-react/src/
├── App.jsx                  # 路由 + 导航
├── main.jsx                 # 入口
├── styles.css               # 全局样式
└── pages/
    ├── KnowledgeTreePage.jsx  # 知识树（学习/答题按钮）
    ├── LearnPage.jsx          # 学习页（左目录 + 中讲解 + 右对话）
    ├── ExamPage.jsx           # 答题页（左目录 + 右答题）
    ├── StudyPage.jsx          # 每日一学（旧，保留兼容）
    ├── OutlinerPage.jsx       # 管理页（幕布风格大纲编辑 + 新建知识树）
    ├── InterviewPage.jsx      # 面试复盘
    ├── AdminPage.jsx          # 旧管理页（保留）
    ├── ProjectQuestionsPage.jsx
    └── OtherQuestionsPage.jsx
```

---

## 数据模型（13+ 表）

### 知识树
- `knowledge_node` — 邻接表，level 1-N，node_type: category/leaf
- `knowledge_content` — 知识讲解（Markdown + 面试题 + rubric），一对一关联 knowledge_node

### 学习
- `study_session` — 一次学习/答题会话
- `conversation` — 一个知识点的完整对话（多轮出题+评分+追问）
- `conversation_message` — 消息明细
- `mastery_record` — 掌握度（每用户每知识点一条）
- `mastery_history` — 掌握度变化历史
- `learn_chat` — 学习页探索对话记录

### 面试
- `interview_record` — 面试记录
- `user_answer_embedding` — 用户回答向量（pgvector，当前未启用）

### 用户
- `user` — 用户表（Phase 1 硬编码 user_id=1）

---

## 功能模块

### 1. 知识树管理（/admin）

**大纲编辑器（幕布风格）**
- Enter 新增同级 / Tab 缩进 / Shift+Tab 反缩进 / 拖拽排序
- 面试权重下拉（1-5星）
- ✨ LLM 优化按钮（去重合并 + 结构调整 + 查漏补缺 + 语言精简）

**新建知识树（4种方式）**
1. 🤖 LLM 生成 — 输入名称+描述
2. 📄 文本导入 — 粘贴 Markdown/文本
3. 📷 截图解析 — DashScope qwen-vl 多模态
4. 📁 文件导入 — FreeMind .mm 文件

**语义去重** — LLM 判断新树名是否与已有树语义重复，冲突时用户选择：
- 删除旧树 / 取消新增 / LLM 语义合并

**API**
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/admin/tree-nodes | 获取完整知识树（编辑用） |
| POST | /api/admin/tree-nodes | 新增节点 |
| PUT | /api/admin/tree-nodes/batch-sort | 批量更新排序 |
| PUT | /api/admin/tree-nodes/{id} | 修改节点（含层级自动计算） |
| DELETE | /api/admin/tree-nodes/{id} | 删除节点（自动更新父节点类型） |
| POST | /api/admin/trees/from-text | 文本导入 |
| POST | /api/admin/trees/from-generate | LLM 生成 |
| POST | /api/admin/trees/from-image | 截图解析 |
| POST | /api/admin/trees/from-mm | .mm 文件导入 |
| POST | /api/admin/trees/{id}/optimize | LLM 全面优化 |
| GET | /api/admin/trees/{id}/check-duplicate | 语义去重检测 |
| POST | /api/admin/trees/merge | LLM 语义合并 |

### 2. 知识树查看（/）

- Tab 式分类展示，叶子节点显示：权重星星 + 掌握度进度条
- 每个叶子节点两个按钮：📖 学习 / ✏️ 答题

**API**: `GET /api/knowledge/tree`

### 3. 学习页面（/learn/:kpId）

**布局**：左侧知识树目录 | 中间知识讲解 | 右侧探索对话

**知识讲解（Skill 驱动）**
- 首次打开时 LLM 生成并落库，再次访问直接读库
- Skill 定义 4 个必选模块 + 3 个可选模块
- 必选：📌 一句话概述 / 🔑 必须掌握 / 💡 核心原理 / ⚠️ 常见误区
- 可选：💻 代码示例 / 🔍 关键细节 / 📊 对比表格
- `validate_sections()` 验证必选模块是否缺失，缺失则重试

**高频面试题**
- 随讲解一起生成，含 rubric 评分标准（key_point + score，总分100）
- 答题页直接复用这些题目

**探索对话**
- 上下文：当前知识点名称 + 已生成的讲解内容
- 支持引用文本（选中左侧内容自动引用）
- 对话可合并到讲解文章（用户确认后 LLM 智能插入）

**API**
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/learn/content/{kpId} | 获取/生成讲解内容 |
| POST | /api/learn/chat | 探索对话 |
| GET | /api/learn/chat-history/{kpId} | 对话历史 |
| POST | /api/learn/merge-chat | 合并对话到讲解 |

### 4. 答题页面（/exam/:kpId）

**布局**：左侧知识树目录 | 右侧答题区

**出题逻辑**
- 直接使用 knowledge_content.questions 中的已生成题目（不调 LLM）
- 每题附带 rubric 评分标准
- 没有题目时自动触发 Skill 生成

**评分逻辑**
- LLM 对照 rubric 逐项评分（hit/miss + matched_text）
- 追问决策：核心考点追问，冷门知识不追问
- 停止追问按钮（⏹）+ 本轮总结生成

**掌握度算法**
```
每道题得分 = 原题得分 × 60% + 追问平均分 × 40%
掌握度 = 所有已答题平均分 × (完成题数 / 总题数)
```
- 每次评分实时更新（含追问中）
- 答完所有题显示 🎉 全部题目已完成

**追问覆盖检测**
- next 端点自动检查 pending 题目的 rubric 是否被追问命中 ≥ 70%
- 覆盖的题目自动跳过并赋分

**API**
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/study/exam-start | 答题模式开始（使用已有题目） |
| POST | /api/study/answer | 提交回答并评分 |
| POST | /api/study/next | 下一题（exam 模式 pending 用完返回 finished） |
| POST | /api/study/stop-followup | 停止追问，生成总结 |
| GET | /api/study/exam-progress/{kpId} | 答题进度（掌握度 + 已掌握/待加强） |

### 5. 面试复盘（/interview）

- 粘贴面试文本 → LLM 解析为结构化问答
- 按类型分 Tab：知识类 / 项目类 / 其他
- 整体分析：通过概率、薄弱点、建议
- 每道题可跳转到学习/答题页面

**API**: `POST /api/interview/parse`, `GET /api/interview/project-questions`

### 6. 每日一学（/study，旧版保留）

- 知识点横向选择栏（Top 10 推荐 + 今日置顶）
- 题目栏（可切换已答/未答题目）
- 多轮对话式答题 + 评分 + 追问 + 总结

---

## Skill 架构

```
backend/skills/learn_content_skill.py
├── SectionType (Enum)        — 7 种内容模块类型
├── SectionSpec (dataclass)   — 模块规格（emoji/标题/是否必选/格式规则）
├── SECTION_REGISTRY (list)   — 模块注册表
├── build_skill_prompt()      — 从注册表动态构建 Prompt
├── execute_content_skill()   — 执行 Skill（调 LLM + 验证）
└── validate_sections()       — 验证必选模块
```

扩展方式：在 `SECTION_REGISTRY` 中增删模块定义，Prompt 自动更新。

---

## 关键业务规则

1. **JSONB 脏检测**：SQLAlchemy JSONB 字段就地修改需 `flag_modified()` 显式标记
2. **删除节点自动更新类型**：删除子节点后父节点无子则自动变为 leaf
3. **答题模式不生成新题**：exam 的 next 端点 pending 用完返回 finished
4. **知识树去重**：先精确匹配，再 LLM 语义匹配
5. **内容生成防重**：落库前二次查询防并发插入
6. **前端防并发**：startingRef + lastStartedRef 防重复启动

---

## 路由表

| 路径 | 页面 | 说明 |
|------|------|------|
| / | KnowledgeTreePage | 知识树首页 |
| /learn/:kpId | LearnPage | 学习页 |
| /exam/:kpId | ExamPage | 答题页 |
| /study | StudyPage | 每日一学（旧） |
| /interview | InterviewPage | 面试复盘 |
| /projects | ProjectQuestionsPage | 项目拷打 |
| /others | OtherQuestionsPage | 其他问题 |
| /admin | OutlinerPage | 管理（大纲编辑） |

---

## 环境变量（.env）

```
DATABASE_URL=postgresql+asyncpg://...
DEEPSEEK_API_KEY=sk-xxx
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_MODEL=deepseek-chat
DASHSCOPE_API_KEY=sk-xxx
```

## 启动

```bash
# 后端
source .venv/bin/activate
python3 -m uvicorn backend.main:app --reload --port 8000

# 前端
cd frontend-react && npm run dev
```
