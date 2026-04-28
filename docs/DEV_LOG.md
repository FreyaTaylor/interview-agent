# 开发日志

> 每次开发记录：日期、变更内容、新增/修改接口、技术决策。

---

## 2026-04-28 — Phase 0 骨架搭建

### 变更概述

初始化 Phase 0 项目骨架，搭建完整的后端 + 前端 + 数据库基础设施。

### 新增文件

| 文件 | 说明 |
|------|------|
| `requirements.txt` | Python 依赖清单 |
| `docker-compose.yml` | PostgreSQL + pgvector 容器 |
| `backend/config.py` | 配置管理（pydantic-settings） |
| `backend/database.py` | 异步数据库连接（SQLAlchemy async + asyncpg） |
| `backend/main.py` | FastAPI 应用入口 |
| `backend/models/base.py` | SQLAlchemy 基类 + TimestampMixin |
| `backend/models/user.py` | 用户模型（预留，含 profile_text） |
| `backend/models/knowledge.py` | 知识树模型：KnowledgeNode, Question, RubricItem |
| `backend/models/study.py` | 学习模型：StudySession, Conversation, ConversationMessage, MasteryRecord, MasteryHistory |
| `backend/models/interview.py` | 面试复盘模型（Phase 2 用）：InterviewRecord, AlgorithmQuestion, HrQuestion |
| `backend/schemas/common.py` | 统一 API 响应模型 `ApiResponse` |
| `backend/schemas/study.py` | 学习相关请求/响应 Pydantic 模型 |
| `backend/prompts/study_prompts.py` | Rubric 打分 + 自由探索 Prompt 模板 |
| `backend/services/rubric.py` | Rubric 评分服务（调用 DeepSeek LLM） |
| `backend/agents/study_agent.py` | 学习对话 Agent（LangGraph StateGraph） |
| `backend/api/study.py` | 学习 API 路由 |
| `backend/scripts/seed_data.py` | Phase 0 种子数据脚本 |
| `frontend/app.py` | Streamlit 学习对话界面 |

### API 接口清单

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| GET | `/api/study/knowledge-points` | 获取所有叶子知识点列表（含掌握度） |
| POST | `/api/study/start` | 开始学习一个知识点，返回题目 |
| POST | `/api/study/answer` | 提交回答，LLM 基于 Rubric 打分 |
| POST | `/api/study/explore` | 自由探索追问（≤5轮） |

### 数据模型

Phase 0 定义了全部 13 张表的 SQLAlchemy 模型（含 Phase 2 预留表），启动时 `create_all` 建表。

种子数据包含 3 个知识点：
- Redis 分布式锁 ★5（5 个 Rubric 关键点）
- HashMap 原理 ★4（4 个 Rubric 关键点）
- MySQL 索引优化 ★5（5 个 Rubric 关键点）

### LangGraph Agent 设计

学习对话 Agent 使用 `StateGraph`，3 个节点：
- `generate_question` — 出题（Phase 0 从种子数据选题）
- `score_answer` — Rubric 打分（调用 DeepSeek）
- `handle_explore` — 自由探索回答

Phase 0 采用 API 驱动的分步调用（每次请求执行一个节点），不使用 interrupt 机制。

### 技术决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 建表方式 | `create_all()` | Phase 0 简化，Phase 1 切换到 Alembic |
| Agent 调用方式 | API 逐步驱动 | Web 场景下比 interrupt 更直观 |
| LLM 调用 | langchain-openai ChatOpenAI | DeepSeek 兼容 OpenAI 格式 |
| 打分温度 | temperature=0.1 | 评分需要稳定可重复 |
| 探索温度 | temperature=0.3 | 回答可以稍有变化 |

### 启动方式

```bash
# 1. 启动数据库
docker compose up -d

# 2. 安装依赖
pip install -r requirements.txt

# 3. 配置 .env（复制 .env.example 并填入 API Key）
cp .env.example .env

# 4. 写入种子数据
python -m backend.scripts.seed_data

# 5. 启动后端
uvicorn backend.main:app --reload

# 6. 启动前端（新终端）
cd frontend && streamlit run app.py
```

---

*后续开发记录追加在此文件末尾*
