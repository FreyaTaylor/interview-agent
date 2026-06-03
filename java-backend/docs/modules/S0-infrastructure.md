# S0 — 基础设施模块

> **范围**：Spring Boot 工程骨架 + 横切组件 + DB / LLM / Embedding 客户端 + Flyway V1 建所有表 + health 接口。
> **对应模块**：原 [JAVA_REWRITE_PLAN.md §0](../../../docs/JAVA_REWRITE_PLAN.md)。
> **状态**：✅ 完成（本地 `mvn package` 通过 + 启动 Flyway V1 → 15 张表 + `GET /api/health` 返回 ApiResponse 成功）

---

## 1. 交付物清单

### 1.1 构建与配置
- [pom.xml](../../pom.xml) — Maven 配置，Spring Boot 3.3.x + Spring AI 1.0.x + LangChain4j 1.0.x
- [src/main/resources/application.yml](../../src/main/resources/application.yml) — 主配置（端口 / Flyway / LLM 占位）
- [src/main/resources/application-dev.yml](../../src/main/resources/application-dev.yml) — 本地开发 profile
- [.env.example](../../.env.example) — 环境变量模板
- [scripts/init-db.sql](../../scripts/init-db.sql) — PG 初始化（CREATE DATABASE / ROLE / GRANT / vector 扩展）

### 1.2 应用入口与横切
- `com.interview.agent.InterviewAgentApplication` — Spring Boot 入口
- `com.interview.agent.common.ApiResponse<T>` — 统一响应 `{code, data, message}`
- `com.interview.agent.common.BizException` — 业务异常
- `com.interview.agent.common.GlobalExceptionHandler` — `@RestControllerAdvice` 异常 → ApiResponse
- `com.interview.agent.common.JsonUtil` — Jackson 封装 + LLM JSON 容错抽取

### 1.3 基础设施
- `com.interview.agent.infra.async.VirtualThreadConfig` — 虚拟线程 `ExecutorService` Bean
- `com.interview.agent.infra.db.PgVector` — `?::vector` 字面量工具
- `com.interview.agent.infra.llm.LlmProperties` — `@ConfigurationProperties` 集中配置
- `com.interview.agent.infra.llm.ChatClientConfig` — Spring AI `ChatClient`（OpenAI 兼容 → DeepSeek）
- `com.interview.agent.infra.llm.EmbeddingService` — LangChain4j DashScope `text-embedding-v3`（1024d）

### 1.4 Schema 与 Health
- [src/main/resources/db/migration/V1__init_schema.sql](../../src/main/resources/db/migration/V1__init_schema.sql) — 全部表 + 索引 + pgvector 扩展
- `com.interview.agent.api.HealthController` — `GET /api/health`

---

## 2. 关键决策

| 项 | 决策 | 原因 |
|---|---|---|
| Spring Boot | 3.3.5 | 稳定 LTS 系列；兼容 JDK 21 虚拟线程自动支持 |
| Spring AI | 1.0.0 | 已 GA；`ChatClient` 结构化输出 `.entity(Class<T>)` 体验好 |
| LangChain4j | 1.0.1 + community-dashscope 1.0.1-beta6 | 1.x 已 GA；dashscope-community 维护活跃 |
| 虚拟线程 | `spring.threads.virtual.enabled=true` + 自定义 `ExecutorService` Bean | Spring MVC 自动 + 业务并发编排两套 |
| Flyway baseline | 不开启 baselineOnMigrate | 空库起步，V1 即初始 schema |
| 表清单 | 一次性建 16 张（含未来 Interview 用的表） | 后续阶段 V2+ 只加列/索引，不改表名 |

---

## 3. V1 建表清单（16 张 + 必要索引）

| # | 表 | 模块归属 | 备注 |
|---|---|---|---|
| 1 | `user` | 8 User Profile | 复用 Python 表结构（含 GitHub OAuth 字段，一期不用） |
| 2 | `knowledge_node` | 1/2 知识树 | 邻接表 + `embedding vector(1024)` |
| 3 | `knowledge_content` | 4 Learn | `content TEXT` + `user_additions JSONB` |
| 4 | `learn_chat` | 4 Learn | 探索对话流水 |
| 5 | `study_question` | 5 Study | `rubric_template JSONB` + `recommended_answer JSONB` |
| 6 | `question_attempt` | 5/6 Study + Project | 多态表：`question_type ∈ ('study','project')` |
| 7 | `project` | 6 Project Grilling | 项目元数据 |
| 8 | `project_node` | 3/6 项目树 | 三层树 + `embedding vector(1024)` |
| 9 | `project_session` | 6 Project Grilling | 一次拷打会话（current_question / pending_questions JSONB） |
| 10 | `project_session_message` | 6 Project Grilling | 拷打消息流水（审计日志） |
| 11 | `project_user_profile` | 6 Project Grilling | `project_facts JSONB[]` + `weak_points JSONB[]` + `version` 乐观锁 |
| 12 | `interview_record` | 7 Interview | `raw_text` + `text_hash` + `parsed_questions JSONB` + `draft_*` |
| 13 | `interview_knowledge_question` | 7 Interview | knowledge 类 |
| 14 | `interview_project_question` | 7 Interview | project 类 |
| 15 | `interview_other_question` | 7 Interview | hr / leetcode / 其他 |

---

## 4. 本地启动步骤

```bash
# 1. 初始化数据库（一次性）
psql -U postgres -f java-backend/scripts/init-db.sql

# 2. 复制环境变量
cp java-backend/.env.example java-backend/.env
# 填入 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY

# 3. 启动应用（Flyway 自动建表）
cd java-backend
./mvnw spring-boot:run

# 4. 验证 health
curl http://localhost:8080/api/health
# {"code":0,"data":{"status":"UP"},"message":"success"}
```

---

## 5. 验收

> 本地实测于 2026-06-03 跑通（Java 25 / PostgreSQL 14.20 / pgvector）。

- [x] `mvn package` 通过 → `target/interview-agent-0.1.0-SNAPSHOT.jar`
- [x] 应用启动后 Flyway 自动跑 V1，`flyway_schema_history` 1 行 `success=t`；`\dt` 显示 16 张业务表 + `flyway_schema_history`
- [x] `GET /api/health` 返回 `{"code":0,"data":{"status":"UP","time":"..."},"message":"success"}`
- [x] 未注册路径走 `GlobalExceptionHandler` 兜底返回 ApiResponse（HTTP 200，code=50000）
- [x] `ChatClient` Bean / `EmbeddingService` Bean 注入成功（启动日志 `[Embedding] 初始化完成 model=text-embedding-v3 dim=1024`）
- [x] 虚拟线程：`spring.threads.virtual.enabled=true` 已生效，`virtualThreadExecutor` Bean 已注册

---

## 6. 后续阶段对接

- **S1 知识树 Admin CRUD**：直接用 `knowledge_node` 表 + MyBatis Mapper + `EmbeddingService.toPgVectorLiteral(...)` 写 embedding
- **S2 知识树查询**：在 `KnowledgeRepository` 加 `findAll()` → 内存组装树
- **S3 Study**：`study_question` + `question_attempt` 已就绪；`ChatClient.prompt(...).call().entity(RubricResult.class)`
