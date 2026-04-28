# 面试备考 Agent 系统 — 开发规范

> 本文件是 AI 辅助编码（vibe coding）的核心指引。
> 所有 AI 助手在生成代码前**必须**阅读本文件和 docs/ 目录下的设计文档。

---

## 项目概述

面试备考 Agent 系统：用 LangGraph 实现个性化面试知识学习，核心是"以考代学"的对话流程。
详细设计见 `docs/DESIGN_v2.md` 和 `docs/TECH_DESIGN.md`。

---

## 技术栈

- **语言**: Python 3.11+
- **后端**: FastAPI (async)
- **Agent**: LangGraph（状态图 + ReAct 模式）
- **数据库**: PostgreSQL 16 + pgvector
- **ORM**: SQLAlchemy 2.0 (async, 使用 asyncpg 驱动)
- **LLM**: DeepSeek Chat API (OpenAI 兼容格式)
- **Embedding**: DashScope text-embedding-v3
- **前端 Phase 0**: Streamlit
- **部署**: Docker Compose

---

## 项目结构

```
interview-agent/
├── docs/                        # 设计文档（只读参考，不要修改）
│   ├── DESIGN.md                # v1 产品设计
│   ├── DESIGN_v2.md             # v2 产品设计（当前版本）
│   └── TECH_DESIGN.md           # 技术设计（数据模型+页面+技术选型）
├── backend/
│   ├── main.py                  # FastAPI 启动入口
│   ├── config.py                # 配置管理（环境变量）
│   ├── database.py              # 数据库连接 + session
│   ├── agents/                  # LangGraph Agent 定义
│   │   ├── study_agent.py       # 学习对话 Agent（ReAct 核心）
│   │   └── tree_agent.py        # 知识树生成 Agent（Planning）
│   ├── models/                  # SQLAlchemy ORM 模型
│   ├── schemas/                 # Pydantic 请求/响应模型
│   ├── api/                     # FastAPI 路由
│   │   ├── knowledge.py         # 知识树 CRUD
│   │   ├── study.py             # 学习对话
│   │   ├── review.py            # 面试复盘
│   │   └── admin.py             # 管理（初始化、画像）
│   ├── services/                # 业务逻辑
│   │   ├── mastery.py           # 掌握度 + 遗忘曲线
│   │   ├── recommendation.py    # 推荐算法
│   │   └── rubric.py            # Rubric 评分逻辑
│   └── prompts/                 # Prompt 模板（.txt 或 .py）
├── frontend/                    # Streamlit 前端
├── tests/                       # 测试
├── docker-compose.yml
├── requirements.txt
├── .env.example                 # 环境变量模板
├── .gitignore
└── CONVENTIONS.md               # 本文件
```

---

## 编码规范

### Python 风格

- 使用 Python 3.11+ 语法
- 遵循 PEP 8，行宽 120 字符
- 类型注解：所有函数签名必须有参数和返回值类型注解
- 字符串用双引号 `"`
- import 顺序：stdlib → 第三方 → 本项目，之间空行分隔
- 不用 `print` 调试，用 `logging`

### 异步规范

- FastAPI 路由函数统一用 `async def`
- 数据库操作用 `async session`
- LLM 调用用 `await`，不要用同步阻塞
- Agent 内部通过 LangGraph 的异步接口调用

### 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 文件名 | snake_case | `study_agent.py` |
| 类名 | PascalCase | `KnowledgeNode` |
| 函数/方法 | snake_case | `get_mastery_record()` |
| 常量 | UPPER_SNAKE | `MAX_EXPLORE_ROUNDS = 5` |
| Pydantic 模型 | PascalCase + 后缀 | `KnowledgeNodeCreate`, `KnowledgeNodeResponse` |
| SQLAlchemy 模型 | PascalCase | `KnowledgeNode`（映射表 `knowledge_node`） |
| API 路由前缀 | 复数名词 | `/api/knowledge-nodes`, `/api/conversations` |

### API 设计

- RESTful 风格，路径用 kebab-case
- 统一响应格式：
  ```python
  {"code": 0, "data": {...}, "message": "success"}
  ```
- 错误响应：
  ```python
  {"code": 40001, "data": null, "message": "知识点不存在"}
  ```
- 分页参数：`?page=1&size=20`
- API 路由分组注册，每个文件一个 `APIRouter`

### 数据库规范

- 表名 snake_case 单数：`knowledge_node`（不是 `knowledge_nodes`）
- 主键统一 `id BIGSERIAL`
- 时间字段统一 `TIMESTAMP DEFAULT NOW()`
- 外键字段命名：`{关联表}_id`，如 `knowledge_point_id`
- 预留 `user_id` 字段，一期默认值 1，不做校验
- 不手写 SQL 建表，通过 SQLAlchemy 模型 + Alembic 迁移管理
- JSONB 字段用于半结构化数据（如 `rubric_result`）

### LangGraph Agent 规范

- 每个 Agent 一个文件，文件内定义 `StateGraph`
- State 用 `TypedDict` 定义，字段有类型注解
- 节点函数命名：动词开头，如 `generate_question()`, `score_answer()`
- 条件边函数命名：`should_xxx()`，如 `should_explore()`
- Prompt 模板放 `prompts/` 目录，不要硬编码在 Agent 代码里
- Tool 定义用 `@tool` 装饰器，docstring 作为 tool description

### Prompt 规范

- 所有 prompt 用中文（系统面向中文用户）
- prompt 中包含 `"用户输入可能含错别字，请按语义理解"`
- Rubric 打分 prompt 要求 LLM 输出结构化 JSON
- 用户画像（profile_text）在需要时注入 prompt，作为上下文

### 错误处理

- API 层用 FastAPI 的 `HTTPException`
- Service 层抛自定义业务异常
- Agent 内部错误要 catch 并返回友好提示，不能让对话中断
- 数据库操作用 try/except，失败时 rollback

### Git 规范

- **不直接 push 到 main**，先创建 feature 分支
- **只 commit 到本地分支**，不自动 push，由用户自己 push
- **每次 commit 前检查当前分支**，如果在 main 上必须先 `checkout -b`
- commit message 格式：`type: description`
  - `feat: 添加知识树初始化 Agent`
  - `fix: 修复 Rubric 打分 JSON 解析`
  - `refactor: 重构推荐算法`
  - `docs: 更新设计文档`

---

## 关键业务规则

### 1. 知识树

- 三层结构：一级分类 → 二级分类 → 三级叶子（知识点）
- 叶子节点 = 知识点 = 最小学习单元
- 权重 ★1-5，仅叶子节点有意义
- 用户可编辑（增删改节点、调整权重）
- 初始化由 Agent 生成整棵树结构，不生成 question/rubric

### 2. 问题与 Rubric（懒生成）

- 不在初始化时批量生成
- 用户首次学习某知识点时，Agent 实时生成 3-5 个问题 + Rubric
- 生成后存入 `question` + `rubric_item` 表，下次复用
- 掌握度提升后，Agent 可追加进阶问题

### 3. 评分

- 基于 Rubric 逐关键点判定，不是 LLM 自由打分
- 每个问题 3-5 个关键点，总分 100
- LLM 输出结构化 JSON（`rubric_result`）
- 项目经验题用质量维度 Rubric（清晰度、对比、踩坑、扩展性）

### 4. 自由探索边界

- 最多 5 轮探索（`MAX_EXPLORE_ROUNDS = 5`）
- Agent 检查相关性，不相关的追问温和拒绝
- 探索内容不影响掌握度评分
- 追问涉及其他知识点时，建议跳转

### 5. 遗忘曲线

- `retention = e^(-t / S)`
- 首次学好 S=3，首次学差 S=1
- 复习好 S×2，复习差 S×0.5（最小 1）
- `retention < 0.5` 时加入推荐队列

### 6. 推荐算法

- `priority = interview_weight × (1.0 - mastery_level) × decay(days)`
- 高权重 + 不会 → 优先推荐
- 今日已学 → 不再推荐（灰色）

---

## 一期范围约定

### 做的

- 知识树生成（Agent Planning）+ 用户编辑
- 学习对话（ReAct：出题→打分→探索）+ Rubric 评分
- 掌握度 + 遗忘曲线推荐
- 面试文本上传复盘
- 管理页（用户画像 + 初始化）
- 4 个页面：知识树、学习、面试复盘、管理

### 不做的

- 用户注册/登录（预留 user_id 字段）
- 角色权限校验（预留 role 字段）
- 面经采集 Pipeline（二期）
- 数据看板（二期）
- 背诵小卡导出（二期）
- 语音上传/ASR（二期，一期只支持文本）

---

## 环境变量

```env
# .env
DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/interview_agent
DEEPSEEK_API_KEY=sk-xxx
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DASHSCOPE_API_KEY=sk-xxx
```

---

## 开发流程

1. 阅读 `docs/TECH_DESIGN.md` 了解数据模型和页面交互
2. 阅读本文件了解编码规范
3. 按 Phase 顺序开发，Phase 0 先跑通对话
4. 每个功能先写 service 层，再写 API 层，最后接前端
5. Agent 代码先用硬编码数据测试，再接数据库
