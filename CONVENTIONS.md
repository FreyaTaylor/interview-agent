# 面试备考 Agent 系统 — 开发规范

> 本文件是项目的核心开发指引，涵盖技术栈、目录结构、通用编码约定与业务规则。
> Java 后端的详细分层 / 命名 / Mapper / Prompt 等规范见 [java-backend/CONVENTIONS.md](java-backend/CONVENTIONS.md)。

---

## 项目概述

面试备考 Agent 系统，核心是"以考代学"的对话闭环：持续出题 → 按 Rubric 评分 → 追踪掌握度 → 反向定位薄弱知识点。

---

## 技术栈

- **后端**: Java 21（虚拟线程）· Spring Boot 3.3 · Spring MVC
- **持久化**: MyBatis（@注解 Mapper，不写 XML）· HikariCP · Flyway 迁移
- **数据库**: PostgreSQL 16 + pgvector（DB `interview_agent_java` / user `iagent_java`）
- **LLM**: Spring AI `ChatClient`（OpenAI 兼容 → DeepSeek Chat）
- **Embedding / 视觉 / ASR**: LangChain4j + DashScope（`text-embedding-v3` / `qwen-vl-max` / Paraformer）
- **前端**: React 19 · Vite · react-router-dom 7
- **本地依赖**: Docker Compose（PostgreSQL）

---

## 项目结构

```
interview-agent/
├── java-backend/                # Spring Boot 后端
│   ├── pom.xml
│   ├── CONVENTIONS.md           # 后端详细规范（分层 / 命名 / Mapper / Prompt）
│   ├── scripts/                 # dev-run.sh（前台）/ dev-run-bg.sh（后台）/ init-db.sql
│   └── src/main/
│       ├── java/com/interview/agent/   # 按业务模块分包（knowledge/study/learn/project/interview/...）
│       └── resources/
│           ├── application.yml
│           ├── prompts/         # *.txt，中文，禁止硬编码
│           └── db/migration/    # Flyway V*.sql
├── frontend-react/              # React + Vite 前端
│   └── src/{pages,components,contexts,hooks}
├── docker-compose.yml           # 本地 PostgreSQL (pgvector/pg16)
├── dev-run.sh / dev-run-bg.sh   # 根目录快捷入口（转发到 java-backend/scripts/）
├── .env.example                 # 环境变量模板
└── CONVENTIONS.md               # 本文件
```

---

## 编码规范

> 后端具体的分层、命名、MyBatis Mapper、异常处理、Prompt 等细则以 [java-backend/CONVENTIONS.md](java-backend/CONVENTIONS.md) 为准。
> 以下为跨语言通用、且前后端共同遵守的约定。

### API 设计

- RESTful 风格，路径用 kebab-case，资源名用复数：`/api/knowledge-nodes`
- 统一成功响应：`{"code": 0, "data": {...}, "message": "success"}`
- 统一错误响应：`{"code": 40001, "data": null, "message": "知识点不存在"}`
- HTTP 字段统一 snake_case（与前端约定一致）

### 数据库规范

- 表名 snake_case 单数：`knowledge_node`（不是 `knowledge_nodes`）
- 主键统一 `id BIGSERIAL`，时间字段统一 `created_at TIMESTAMP DEFAULT NOW()`
- 外键字段命名：`{关联表}_id`
- 关键表预留 `user_id BIGINT DEFAULT 1`，一期默认单用户、不做校验
- Schema 变更走 Flyway 迁移（`db/migration/V*.sql`），不手写散落 SQL
- JSONB 字段用于半结构化数据（如 `rubric_result`）

### Prompt 规范

- 所有 prompt 用中文（系统面向中文用户）
- prompt 中说明"用户输入可能含错别字，请按语义理解"
- Rubric 打分要求 LLM 输出结构化 JSON
- prompt 放 `resources/prompts/`，禁止硬编码在代码里

### Git 规范

- 不直接在 `main` 上开发，先创建 feature 分支
- commit message 格式：`type: description`
  - `feat: 添加知识树初始化`
  - `fix: 修复 Rubric 打分 JSON 解析`
  - `refactor: 重构推荐算法`
  - `docs: 更新文档`

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

完整模板见 [.env.example](.env.example)。本地自用至少需要：

```env
DEEPSEEK_API_KEY=sk-your-deepseek-api-key-here
DASHSCOPE_API_KEY=sk-your-dashscope-api-key-here
```

数据库默认连接 `interview_agent_java` / `iagent_java`（见 `java-backend/scripts/init-db.sql`），本地无需额外配置。

---

## 开发流程

1. 阅读本文件与 [java-backend/CONVENTIONS.md](java-backend/CONVENTIONS.md) 了解编码规范
2. `docker compose up -d` 起库，执行 `java-backend/scripts/init-db.sql` 初始化
3. 后端 `./dev-run.sh`（前台）或 `./dev-run-bg.sh`（后台），前端 `cd frontend-react && npm run dev`
4. 每个功能先写 service 层，再写 controller 层，最后接前端
