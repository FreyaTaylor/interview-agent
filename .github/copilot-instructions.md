# 面试备考 Agent 系统 — Copilot 全局指令

> 本文件全局自动加载，只放**稳定、跨领域**的规则；细则按目录由 `.github/instructions/*.instructions.md`（applyTo 分层）就近提供。

## 项目

**面试备考 Agent 系统**：以考代学的对话闭环 —— 持续出题 → 按 Rubric 评分 → 追踪掌握度 → 反向定位薄弱知识点。

## 技术栈（当前事实）

- **后端**：Java 21（虚拟线程）· Spring Boot 3.x + Spring MVC · **MyBatis**（@注解，不写 XML）· **Flyway** 迁移
- **DB**：PostgreSQL 16 + pgvector（`interview_agent_java` / `iagent_java`）
- **LLM**：Spring AI `ChatClient` → DeepSeek；Embedding/Vision：LangChain4j + DashScope
- **前端**：React 19 + Vite + react-router-dom 7

> 注意：本项目已从早期 Python（FastAPI/LangGraph/SQLAlchemy）迁移到 Java，遇到旧描述以代码现状为准。

## 必读

1. [CONVENTIONS.md](../CONVENTIONS.md) — 通用规范与业务规则
2. [java-backend/CONVENTIONS.md](../java-backend/CONVENTIONS.md) — 后端分层 / 命名 / Mapper / Prompt 细则

## 稳定通用规则

- 业务逻辑注释用**中文**，只解释"为什么"。
- Prompt 全**中文**，存 DB `prompt_template`，**禁止硬编码**；key 引用 `PromptKeys` 常量。
- 用户输入可能含错别字（语音转写）——**按语义匹配，不做精确字符串比对**。
- 题目 + Rubric **懒生成**：用户首次学某知识点时才创建。
- 不直接 push main；先开 feature 分支、本地 commit。
- HTTP 字段统一 snake_case；统一响应 `{"code":0,"data":...,"message":"success"}`。

## 一期不做

用户注册/登录、角色权限校验、数据看板、语音上传 ASR、面经采集 Pipeline（均预留字段/二期）。

## 分层规则（applyTo 自动挂载）

- 后端 Java → `.github/instructions/backend-java.instructions.md`
- Flyway 迁移 / Prompt 种子 → `.github/instructions/db-migrations.instructions.md`
- 前端 React → `.github/instructions/frontend-react.instructions.md`
