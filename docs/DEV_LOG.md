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

---

## 2026-05-01 — 面试复盘模块完整实现 + 前端重构

### 变更概述

完成面试复盘模块从零到完整可用的全链路开发，包含：文本解析、聚类归类、知识树匹配、多类型评分（知识点 Rubric / 项目面试官印象 / 算法题题解 / HR 评价）、整体分析、embedding 长期记忆、持久化去重。React 前端全面重构为幕布风格 + Tab 布局。最后一轮代码重构提取共享模块。

### 功能清单

| 功能 | 说明 |
|------|------|
| 面试文本解析 | 长文本自动分段 → LLM 解析 → 聚类 → 5 种类型分类（knowledge/project/algorithm/hr/other） |
| 知识树匹配 | 解析出的知识点自动匹配叶子节点，未匹配则自动创建 |
| 多类型评分 | knowledge: Rubric 打分; project: 面试官印象评估; algorithm: 题解+LeetCode 匹配; hr: 表现评价 |
| 整体分析 | 面试官视角 3-5 句话系统性评语 + 通过预测 |
| HR 归一化 | LLM 批量归一化 HR 题为标准书面提问 |
| 算法题去重 | 按 leetcode_id 或 title 去重 upsert，评分结果回写 |
| 项目问题持久化 | 跨面试累积，按 project_name + topic 语义合并 |
| 用户回答 embedding | 向量化存储用户回答，用于 Agent 长期记忆 |
| 掌握度更新 | 面试评分自动更新知识点掌握度（EMA） |
| 面试权重提升 | 面试中出现的知识点自动提升面试权重 |
| 二次检查 | LLM 对比原文和已提取问题，补漏 |
| 同项目话题合并 | 同项目下语义相似的 topic 自动合并 |

### 前端变更

| 变更 | 说明 |
|------|------|
| 幕布风格统一 | 全局使用 `tree-card/tree-tabs/tree-node` 组件体系 |
| InterviewPage | 输入页精简（去掉标题/公司/岗位），结果页 Tab 三栏（知识点/项目/其他） |
| ProjectQuestionsPage | 独立持久化页面，从后端 DB 读取，按项目→话题→问题三级树展示 |
| OtherQuestionsPage | 动态子 Tab（算法/HR/其他），从后端 DB 读取，去重+计数 |
| sessionStorage 持久化 | 切换 Tab 不丢失面试复盘结果 |
| localStorage merge | 项目/其他问题跨会话持久化到 localStorage |
| LeetCode 标签样式 | `.lc-tag` CSS 类（圆角标签 + hover 效果 + 可点击跳转） |

### 新增/修改文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `backend/services/llm.py` | 新增 | 共享 LLM 工具：`get_llm()` + `parse_llm_json()` |
| `backend/services/interview.py` | 大幅扩展 | 面试解析+评分+归一化+编排层（7 个新 service 函数） |
| `backend/api/interview.py` | 重构 | API handler 从 ~250 行瘦身到 ~50 行，业务逻辑提取到 service 层 |
| `backend/prompts/interview_prompts.py` | 大幅扩展 | 6 个 Prompt 模板（解析/知识点评分/项目评分/算法评分/HR评分/整体分析/HR归一化） |
| `backend/models/interview.py` | 扩展 | 新增 ProjectQuestion、UserAnswerEmbedding 模型；AlgorithmQuestion 新增评分字段 |
| `backend/services/embedding.py` | 扩展 | DashScope embedding 服务 |
| `backend/services/rubric.py` | 重构 | 改用共享 `llm.py`，去除重复代码 |
| `frontend-react/src/pages/InterviewPage.jsx` | 重写 | 幕布风格 + Tab 三栏布局 |
| `frontend-react/src/pages/ProjectQuestionsPage.jsx` | 重写 | 独立持久化页面 |
| `frontend-react/src/pages/OtherQuestionsPage.jsx` | 重写 | 动态子 Tab + 去重计数 |
| `frontend-react/src/styles.css` | 扩展 | `.lc-tag` 样式 |
| `frontend-react/src/App.jsx` | 修改 | 导航栏重排 |

### API 接口

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | `/api/interview/parse` | 上传面试文本 → 全链路处理（解析+匹配+评分+整体分析） | 新增 |
| GET | `/api/interview/project-questions` | 获取所有累积的项目拷打问题 | 新增 |
| GET | `/api/interview/other-questions` | 获取算法题+HR题（去重+计数） | 新增 |

### 代码重构

| 重构项 | Before | After |
|--------|--------|-------|
| API handler | `parse_interview` 250 行（混合 API/DB/业务逻辑） | 50 行编排代码，业务逻辑在 service 层 |
| LLM 工具 | `_get_llm()` 和 `_parse_json()` 在 interview.py 和 rubric.py 各写一遍 | 提取到 `services/llm.py` 共享 |
| Service 函数 | 全部内联在 API handler | 7 个独立函数：`store_algorithm_questions`, `store_hr_questions`, `score_all_groups`, `update_algo_scores`, `update_knowledge_weights`, `store_answer_embeddings`, `store_project_questions` |

### 技术决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 长文本处理 | 分段解析（2000 字/段）| 防止 LLM 截断，保证解析完整性 |
| LLM 重试 | 3 次重试 | 解析/评分调用不稳定时自动重试 |
| 掌握度更新 | EMA（0.4 新分 + 0.6 旧分） | 平滑更新，避免单次波动 |
| HR 题去重 | LLM 归一化 → 标准问题映射 | 同义问题（"为啥离职" vs "离职原因"）归一 |
| 算法题去重 | 优先 leetcode_id，其次 title.lower() | 同一道题不同措辞归一 |
| 项目问题合并 | project_name + topic 精确匹配 + LLM 语义合并 | 跨面试同项目问题累积 |
| embedding 存储 | 问题+回答拼接后向量化 | 便于后续 RAG 检索用户历史理解 |
