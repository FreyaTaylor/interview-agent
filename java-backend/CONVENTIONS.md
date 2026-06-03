# Java Backend — 开发规范

> 本文件是 Java 端 AI 辅助编码的核心指引。所有 AI 助手在生成 Java 代码前**必须**先阅读：
> 1. 本文件
> 2. [docs/TECH_DESIGN.md](../docs/TECH_DESIGN.md) — 产品 / 数据模型 / API 契约（与 Python 端共享）
> 3. [docs/JAVA_REWRITE_PLAN.md](../docs/JAVA_REWRITE_PLAN.md) — 模块清单与映射
> 4. [java-backend/docs/ADR.md](docs/ADR.md) — 技术选型决策记录（为什么选 JdbcClient / Flyway 等）
> 5. Python 原版实现（同语义参考）：[backend/](../backend/)
>
> 根目录的 [CONVENTIONS.md](../CONVENTIONS.md) 是 **Python 端**规范，仅业务规则部分对 Java 通用，技术栈部分**不要照搬**。

---

## 1. 技术栈（已敲定）

| 项 | 选型 |
|---|---|
| JDK | **21 LTS**（启用虚拟线程） |
| 构建 | **Maven**（单 module 起步） |
| Web | **Spring Boot 3.x + Spring MVC**（`spring.threads.virtual.enabled=true`） |
| DB | **Spring `JdbcClient` + HikariCP**（手写 SQL，不引入 JPA / MyBatis） |
| Schema 迁移 | **Flyway**（`src/main/resources/db/migration/V*.sql`） |
| DB 引擎 | **PostgreSQL 16 + pgvector**（独立 DB `interview_agent_java` / user `iagent_java`） |
| LLM（Chat / 结构化输出） | **Spring AI 1.x** `ChatClient`（OpenAI 兼容 → DeepSeek） |
| Embedding / Vision | **LangChain4j**（DashScope `text-embedding-v3` 1024d、`qwen-vl-max`） |
| JSON | Jackson |
| 测试 | JUnit 5 + Testcontainers（`pgvector/pgvector:pg16`） |
| 端口 | 8080 |
| API 文档 | `springdoc-openapi`（可选） |

**不引入**：Lombok、MapStruct、JPA/Hibernate、WebFlux、R2DBC、Reactor。

---

## 2. 项目结构

```
java-backend/
├── pom.xml
├── CONVENTIONS.md                     # 本文件
├── src/main/java/com/interview/agent/
│   ├── InterviewAgentApplication.java
│   ├── common/                        # 横切：ApiResponse / BizException / JsonUtil / GlobalExceptionHandler
│   ├── infra/
│   │   ├── db/                        # JdbcConfig / PgVector 工具
│   │   ├── llm/                       # ChatClientConfig / EmbeddingService
│   │   └── async/                     # 虚拟线程 / StructuredTaskScope 工具
│   ├── knowledge/                     # 模块 1：知识树查询
│   ├── admin/                         # 模块 2/3：Admin CRUD + 树生成
│   ├── learn/                         # 模块 4
│   ├── study/                         # 模块 5（核心闭环）
│   ├── project/                       # 模块 6：Project Grilling
│   ├── interview/                     # 模块 7：面试复盘
│   ├── user/                          # 模块 8
│   └── auth/                          # 模块 9（P3）
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-test.yml
│   ├── prompts/                       # *.txt，中文，禁止硬编码
│   └── db/migration/                  # Flyway V1__*.sql, V2__*.sql ...
└── src/test/java/                     # JUnit 5 + Testcontainers
```

**每个业务模块内分层**（参考 Spring 经典三层）：
```
xxx/
├── XxxController.java       # @RestController，路由
├── XxxService.java          # 业务编排
├── XxxRepository.java       # JdbcClient 封装
├── dto/                     # Record：XxxReq / XxxResp / XxxView
└── entity/                  # Record 或普通类：与表一对一
```

---

## 3. 编码规范

### 3.1 Java 风格

- **JDK 21 特性**：优先 Record、Sealed、Pattern Matching、Switch Expression、Text Blocks
- **行宽**：120
- **格式化**：Google Java Format（IDE 装插件 + `mvn fmt:format` 可选）
- **不可变优先**：DTO 一律 Record；集合返回 `List.copyOf(...)` 或 `Collections.unmodifiableList`
- **null 处理**：内部 API 用 `Optional` 表达可空；DTO 字段允许 null（JSON 反序列化场景）
- **字符串**：拼接用文本块 `"""..."""` 或 `String.format`，禁止 `+` 多行拼接
- **import**：禁止通配符 `import xx.*`
- **可见性**：默认 `package-private`，对外才 `public`
- **不要写注释解释"是什么"**，只写"为什么"；业务规则注释用**中文**

### 3.2 命名

| 类型 | 规则 | 示例 |
|---|---|---|
| 包 | 全小写，模块名 | `com.interview.agent.study` |
| 类 | 大驼峰，后缀表达类型 | `StudyController` / `StudyService` / `StudyRepository` |
| Record DTO | `XxxReq` / `XxxResp` / `XxxView` | `StartAttemptReq` / `AttemptResp` |
| Entity Record | 表名大驼峰单数 | `KnowledgeNode` / `QuestionAttempt` |
| 方法 | 小驼峰，动词起头 | `generateQuestion`、`scoreAnswer` |
| 常量 | `UPPER_SNAKE_CASE` | `MAX_FOLLOW_UPS` |
| 配置属性类 | `XxxProperties` | `LlmProperties` |
| 异常 | `XxxException` | `BizException` |

### 3.3 API 规范（与 Python 端对齐）

- RESTful，kebab-case 路径，复数名词：`/api/study/attempts`、`/api/admin/tree-nodes`
- 统一响应：`ApiResponse<T>{code: int, data: T, message: String}`
  - 成功：`{code: 0, data: ..., message: "success"}`
  - 失败：`{code: 40001, data: null, message: "..."}`（错误码沿用 Python 端，见 `backend/schemas/common.py`）
- Controller 方法直接返回 `ApiResponse<T>`；异常由 `GlobalExceptionHandler` 统一包装
- 一个 `@RestController` 对应一个业务子域；过大的 Controller 拆 `XxxAdminController` / `XxxQueryController`
- 请求体 / 响应体 **必须**用 Record DTO，**不**直接暴露 Entity

### 3.4 DB / Repository

- 表名：snake_case **单数**（`knowledge_node`，不是 `knowledge_nodes`），与 Python 端一致
- 所有表必有：`id BIGSERIAL PRIMARY KEY`、`created_at TIMESTAMP DEFAULT NOW()`
- 关键业务表带 `user_id BIGINT DEFAULT 1`（一期写死，不强制鉴权）
- **手写 SQL**，用 `JdbcClient.sql(...).param(...).query(RowMapper).list()`
- 复杂查询 SQL 用 Java 文本块 `"""..."""`，多行对齐易读
- **JSONB**：写入 `new PGobject() { type="jsonb", value=jsonStr }`；读出 `rs.getString("col")` + Jackson 反序列化
- **vector**：参数化绑定 `?::vector`，值用 `EmbeddingService.toPgVectorLiteral(float[])` → `'[v1,v2,...]'`
- **乐观锁**：用 `version` 字段，`UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?`；失败重试上限 3 次
- **事务**：跨表写用 `@Transactional`；只读查询不加注解
- **不使用** JPA / MyBatis / EntityManager

### 3.5 Flyway

- 文件名：`V{递增编号}__{说明}.sql`，如 `V1__init_schema.sql`、`V2__add_user_answer_embedding.sql`
- **永不修改**已发布的 V 文件；新增列 / 改约束写新 V 文件
- 初始 schema 按 [docs/TECH_DESIGN.md](../docs/TECH_DESIGN.md) 重新建一遍（不复用 Python Alembic 输出）
- `CREATE EXTENSION IF NOT EXISTS vector;` 必须在 V1 里

### 3.6 异步 / 并发

- Controller 与 Service 用**同步代码**，Spring MVC 自动跑在虚拟线程
- **多路并发**（如 parser MAX_CONCURRENT=5、scorer MAX_CONCURRENT=5）：用 `StructuredTaskScope.ShutdownOnFailure`
- **Fire-and-forget**（如 project profile extract）：注入虚拟线程 `ExecutorService` Bean 提交任务；日志记录失败，**不阻塞主流程**
- 全局禁止：`new Thread(...)`、`CompletableFuture.supplyAsync(...)`（不指定 Executor 用 ForkJoinPool common）

### 3.7 LLM / Prompt

- **Prompt 模板**放 `src/main/resources/prompts/{module}/*.txt`，禁止硬编码到 Java 文件
- 加载工具：`PromptLoader.load("study/per_turn.txt")` → 缓存到 ConcurrentHashMap
- 占位符用 `${var}` 风格；渲染走 Spring AI `PromptTemplate` 或自写 `StringSubstitutor`
- **LLM 客户端**：
  - Chat / 结构化输出 → `ChatClient`（Spring AI 1.x）
    - 结构化输出用 `.entity(Class<T>)`；不稳定时回退到 `JsonUtil.extractJson(...)` 容错抽取
  - Embedding → `EmbeddingService`（封装 LangChain4j DashScope）
  - Vision → `VisionService`（LangChain4j `qwen-vl-max`，P1 模块用）
- **温度**：评分类 0.0~0.2，生成类 0.5~0.7，在 Service 里显式指定
- **token 上限**：在 `application.yml` 集中配置，不要散落

### 3.8 日志

- SLF4J；禁止 `System.out.println` / `e.printStackTrace()`
- 格式：`[ModuleName] key1=val1 key2=val2 msg`
  - 示例：`log.info("[Study] attemptId={} kpId={} score={}", id, kp, score);`
- 异常：`log.error("[Module] op failed, id={}", id, ex);` —— 异常对象作为最后一个参数（不要 `+ ex.getMessage()`）
- 不打印敏感信息（API key、用户隐私）

### 3.9 异常处理

- 业务异常：抛 `BizException(code, message)`，由 `GlobalExceptionHandler` → `ApiResponse`
- 参数校验：Jakarta Validation (`@Valid` + `@NotNull` 等)，错误码 `40001`
- 不允许吞异常（`catch (Exception e) {}` 空块）；至少 `log.warn` 并 rethrow 或转 `BizException`
- 受检异常（如 `IOException`）：在 Service 边界转 `BizException`

---

## 4. 业务通用规则（与 Python 端一致）

- 一期 `user_id = 1`，多用户预留但**不**做权限检查
- Questions + Rubric **懒生成**：用户首次访问 kp 才创建（`ensureKpStudied` 幂等）
- 用户输入可能有错别字（语音转写）：**按语义匹配**，禁止精确字符串比对
- 题目分 = 最近 3 次 finished `question_attempt.score` 平均
- KP 掌握度 = 该 KP 下所有题目分平均
- Study 推荐优先度：未学 `weight * 1.0`；已学 `weight * (1 - mastery/100) * 0.8`
- Project Grilling：finish 后 fire-and-forget 调用 profile extract（rubric `hit=false` → `missed_key_points`）
- Interview：text_hash = `SHA-256(raw_text.strip())` 去重；matched node `interview_weight += 1`（上限 5）；avg_score → pass_estimate：`≥70 较高 / ≥50 一般 / else 较低`
- 评分 Rubric 输出**结构化 JSON**，**禁止**自然语言评分

---

## 5. 测试

- 单元测试：Service 层为主，Mock LLM 客户端
- 集成测试：`@SpringBootTest` + Testcontainers (`pgvector/pgvector:pg16`)，Flyway 自动建表
- 命名：`XxxServiceTest`、`XxxControllerIT`
- 评分类用例与 Python 端共用 `tests/golden/*.json`，允许 ±10% 波动

---

## 6. Git / 协作

- 分支：`feature/{module}-{shortdesc}`，如 `feature/study-loop-mvp`
- 不直接推 `main`；本地 commit 即可
- Commit 信息中文 OK，**前缀加模块**：`[Study] 实现首次学习自动出题`
- PR 描述需列：变动模块、API 变更、DB 变更（Flyway V 文件）、测试覆盖

---

## 7. 与 Python 端对照速查

| Python | Java |
|---|---|
| `async def` route | 同步方法（虚拟线程承载） |
| `AsyncSession` (SQLAlchemy) | `JdbcClient` |
| `await llm_client.chat(...)` | `chatClient.prompt(...).call().content()` |
| `Pydantic BaseModel` | Java Record |
| `JSONB` (SQLAlchemy) | `PGobject("jsonb", json)` + Jackson |
| `pgvector` (SQLAlchemy) | `?::vector` + `toPgVectorLiteral` |
| `asyncio.gather(*tasks, return_exceptions=True)` | `StructuredTaskScope.ShutdownOnFailure` |
| `logging.getLogger(__name__)` | `LoggerFactory.getLogger(XxxService.class)` |
| Alembic | Flyway |
