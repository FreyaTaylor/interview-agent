# Java Backend — 开发规范

> 本文件是 Java 端 AI 辅助编码的核心指引。所有 AI 助手在生成 Java 代码前**必须**先阅读：
> 1. 本文件
> 2. [docs/TECH_DESIGN.md](../docs/TECH_DESIGN.md) — 产品 / 数据模型 / API 契约（与 Python 端共享）
> 3. [docs/JAVA_REWRITE_PLAN.md](../docs/JAVA_REWRITE_PLAN.md) — 模块清单与映射
> 4. [java-backend/docs/ADR.md](docs/ADR.md) — 技术选型决策记录（为什么选 MyBatis @注解 / Flyway 等）
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
| DB | **MyBatis 3.x + HikariCP**（`mybatis-spring-boot-starter`，@注解 Mapper，不写 XML）|
| Schema 迁移 | **Flyway**（`src/main/resources/db/migration/V*.sql`） |
| DB 引擎 | **PostgreSQL 16 + pgvector**（独立 DB `interview_agent_java` / user `iagent_java`） |
| LLM（Chat / 结构化输出） | **Spring AI 1.x** `ChatClient`（OpenAI 兼容 → DeepSeek） |
| Embedding / Vision | **LangChain4j**（DashScope `text-embedding-v3` 1024d、`qwen-vl-max`） |
| JSON | Jackson |
| 测试 | JUnit 5 + Testcontainers（`pgvector/pgvector:pg16`） |
| 端口 | 8080 |
| API 文档 | `springdoc-openapi`（可选） |

**不引入**：Lombok、MapStruct、JPA/Hibernate、MyBatis-Plus、WebFlux、R2DBC、Reactor。

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

**每个业务模块内分层**（参考 Spring 经典三层 + 接口/实现分离）：
```
xxx/
├── controller/
│   └── XxxController.java       # @RestController，路由
├── service/
│   ├── XxxService.java          # 接口：只声明能力（方法签名 + 一句话 Javadoc）
│   ├── XxxHelper.java           # 可选：模块内跨多个 Service 共用的纯函数（@Service，扁平不入 impl/）
│   └── impl/
│       └── XxxServiceImpl.java  # @Service 实现，业务逻辑 / 完整 Javadoc 都在这里
├── mapper/
│   └── XxxMapper.java           # MyBatis @Mapper 接口（SQL 写在注解里）
├── entity/                  # Record：与表一对一
└── dto/                     # Record：XxxReq / XxxResp / XxxView
```

> 接口 vs 实现：Controller **依赖接口**，Spring 自动注入唯一的 `@Service` Bean。现阶段每个接口仅一个实现，但预留划分以便后续切插代理 / 多实现路由，与主流 Spring 项目习惯一致。
>
> `XxxHelper` 命名约定：**模块内** Service 之间共享的小函数（如 `LearnHelper.categoryPath(kpId)`）收纳于此；只供单个 Service 用 → 直接做 `private` 方法，不抽。**禁止**再创建 `*Generator` / `*Resolver` 这类薄包装（LLM 调用直接 inline 到 ServiceImpl + `LlmInvoker`）。
>
> 横切包（`common/` `infra/`）不必遵从这个骨架：HealthController / GlobalExceptionHandler 这种辅助类直接放包根。

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
- **优先全 POST + body 参数**：所有读/写端点都用 POST，参数走 JSON body；避免 path/query 把字段写进 URL 和 access log
- **id 一律走 body，禁用 `@PathVariable`**：包括 update/delete 这种"看起来很想用 RESTful 路径"的场景。共享 DTO（如 `admin/dto/DeleteNodeReq`），多个 Update DTO 顶部加 `Long id` 字段；Service 方法只接 DTO，不再接独立 `long id` 参数
- **list 端点也用 POST `/list` + body `{}`**，不要 GET 取列表。空 body 比 GET query 更利于前端封装一致 + 后续加过滤参数零成本扩
- **多操作 CRUD 路径动词分开 OK**（admin tree 模式）：`/list /create /update /delete /batch-sort` 5 端点拆开，每个 body shape 清晰；不强行合并成单 action 入口。判断：能否用同一 Request DTO 表达所有操作？能 → 合并为 action 字段；不能 → 路径动词分开
- **用 `action` 字段区分语义**（同一资源多操作 + body shape 一致时；不是用路径后缀）：
  - `POST /api/learn/content`   body `{ kpId, action: "fetch"|"regenerate" }`
  - `POST /api/learn/questions` body `{ kpId, action: "fetch"|"regenerate" }`
  - action 解析下沉到 DTO record（如 `LearnAssetRequest.resolvedAction()`），Service 提供 `resolveXxx(Request)` 总入口；Controller 不写 switch/if
- 仅纯静态资源 / 无任何参数才用 GET
- **Controller 严格薄层**：每个方法体 ≤ 2 行（一行委托 Service + return），禁止在 Controller 里 switch action / 抛 BizException / 拼装多个 Service
- 统一响应：`ApiResponse<T>{code: int, data: T, message: String}`
  - 成功：`{code: 0, data: ..., message: "success"}`
  - 失败：`{code: 40001, data: null, message: "..."}`（错误码沿用 Python 端，见 `backend/schemas/common.py`）
- Controller 方法直接返回 `ApiResponse<T>`；异常由 `GlobalExceptionHandler` 统一包装
- 一个 `@RestController` 对应一个业务子域；过大的 Controller 拆 `XxxAdminController` / `XxxQueryController`
- 请求体 / 响应体 **必须**用 Record DTO，**不**直接暴露 Entity

### 3.4 DB / Mapper（MyBatis @注解）

- 表名：snake_case **单数**（`knowledge_node`，不是 `knowledge_nodes`），与 Python 端一致
- 所有表必有：`id BIGSERIAL PRIMARY KEY`、`created_at TIMESTAMP DEFAULT NOW()`
- 关键业务表带 `user_id BIGINT DEFAULT 1`（一期写死，不强制鉴权）
- **MyBatis @注解** 方式（`mybatis-spring-boot-starter`），**不**用 XML mapper，**不**用 JPA
- 包结构：每个领域 `xxx/entity/XxxEntity.java`（Record）+ `xxx/mapper/XxxMapper.java`（`@Mapper` 接口）
- SQL 写在 `@Select` / `@Insert` / `@Update` / `@Delete` 注解的文本块 `"""..."""` 里；列名复用提取 `static final String COLS = "..."`
- 动态 SQL（IN 子句、可选 WHERE）用 `<script>` + `<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>`；空集合在 Service 端用 `if (!list.isEmpty())` 兜底
- `INSERT ... RETURNING id` 用 `@Select` 承载（Record 无 setter，不能用 `@Options(useGeneratedKeys=true)`）
- 自动驼峰映射：`mybatis.configuration.map-underscore-to-camel-case: true`（snake_case 列直接绑 camelCase Record 参数 / 字段）
- 编译参数必须开 `-parameters`（Maven `<parameters>true</parameters>`），否则 Record 构造器参数名丢失，MyBatis 无法按名绑定
- **JSONB**：参数 String 即可，列声明 `::jsonb` cast；读出 String + Jackson 反序列化
- **vector**：参数 String literal（`EmbeddingService.toPgVectorLiteral(float[])` → `'[v1,v2,...]'`），SQL 里 `#{embeddingLiteral}::vector`
- **乐观锁**：`version` 字段，`UPDATE ... SET version = version + 1 WHERE id = #{id} AND version = #{version}`；失败重试上限 3 次
- **事务**：跨表写用 `@Transactional`；只读查询不加注解

### 3.5 Flyway

- 文件名：`V{递增编号}__{说明}.sql`，如 `V1__init_schema.sql`、`V2__add_xxx_col.sql`
- **永不修改**已发布的 V 文件；新增列 / 改约束写新 V 文件
- 初始 schema 按 [docs/TECH_DESIGN.md](../docs/TECH_DESIGN.md) 重新建一遍（不复用 Python Alembic 输出）
- `CREATE EXTENSION IF NOT EXISTS vector;` 必须在 V1 里

### 3.6 异步 / 并发

- Controller 与 Service 用**同步代码**，Spring MVC 自动跑在虚拟线程
- **多路并发**（如 parser MAX_CONCURRENT=5、scorer MAX_CONCURRENT=5）：用 `StructuredTaskScope.ShutdownOnFailure`
- **Fire-and-forget**（如 project profile extract）：注入虚拟线程 `ExecutorService` Bean 提交任务；日志记录失败，**不阻塞主流程**
- 全局禁止：`new Thread(...)`、`CompletableFuture.supplyAsync(...)`（不指定 Executor 用 ForkJoinPool common）

### 3.7 LLM / Prompt

- **Prompt 模板**走 `prompt_template` 表（DB 存储 + Flyway V?__ INSERT 种子，`ON CONFLICT (key) DO NOTHING`），**禁止**硬编码到 Java 文件或回退到 classpath txt 方案
- 加载工具：`PromptService.render(key, Map.of("var_name", val))`，占位符 `{var_name}`（snake_case）；缓存走 `ConcurrentHashMap`，热更新调 `invalidateCache()` 或重启
- key 命名：`<module>/<purpose>`，如 `learn/content-gen`、`learn/question-gen`、`tree/generate`
- **Prompt key 必须用 `com.interview.agent.prompts.PromptKeys.XXX` 常量引用**（强制）：
  - **禁止**字面量 `new LlmInvoker.Spec("project/parse-text", ...)`
  - **禁止**在 ServiceImpl 里写文件私有 `private static final String XXX_PROMPT_KEY = "..."`
    （历史上曾出现两个 ServiceImpl 都叫 `GEN_PROMPT_KEY` 但指向不同 key 的坑）
  - 新增 prompt 三步缺一不可：① 写 Flyway `V?__seed_*.sql` 插表；② 在 `PromptKeys.java` 登记常量（JavaDoc 标注 V seed 来源）；③ 业务代码引用常量
  - 不变式：`PromptKeys` 常量集合 == `SELECT key FROM prompt_template` 集合
- **LLM 调用必须走 `common/LlmInvoker.invoke(Spec, ResponseParser<T>)` 单一收口**：
  - **禁止**在 Service 里直写 `chatClient.prompt()...call()`
  - parser 用 lambda 或 method reference 直接传入，**不**创建独立 `XxxParser` 类（`@FunctionalInterface` 的本意）：
    - 短逻辑 → inline lambda（如 `LearnContentServiceImpl` 的必选模块校验）
    - 长逻辑 / 想单测 → 同类 `private static` 方法 + `Xxx::parseXxx` 方法引用（如 `LearnQuestionServiceImpl::parseQuestions`）
  - parser 抛任意异常 = "此次结果无效"，自动触发下一轮重试（**不要**在 parser 里 try/catch 吞）
  - 失败语义由 caller 决定：`.orElseThrow(() -> new BizException(50000, ...))` 阻断 / `.orElse(默认值)` 吞掉
- **温度 / maxTokens / maxRetry** 都进 `LlmInvoker.Spec`，由调用方在常量里显式声明（评分类 0.0~0.2，生成类 0.3~0.7）
- Embedding → `EmbeddingService`（封装 LangChain4j DashScope）；Vision → `VisionService`（`qwen-vl-max`，P1 模块用）

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

### 3.10 服务分层（接口 + 实现）

- 每个业务 Service **接口 + 实现** 分离：
  - 接口 `xxx/service/XxxService.java`：只有方法签名 + 一句话 Javadoc 说明能力
  - 实现 `xxx/service/impl/XxxServiceImpl.java`：`@Service` 注解，完整 Javadoc + 业务逻辑
- Controller / 其他 Service 注入**接口类型**，由 Spring 自动绑定唯一实现
- 实现类方法必须加 `@Override`
- 现阶段每个接口仅一个实现；分层为后续切插代理 / 多实现路由 / 单测 mock 预留
- 完整 Javadoc 只写一份在实现类上（避免接口与实现 Javadoc 双重维护漂移）；接口里每个方法一行注释说清"做什么"即可
- **职责单一**：一个 Service 只动一类资源。Learn 模块拆 `LearnContentService`（只动 `knowledge_content` + `learn_chat`）/ `LearnQuestionService`（只动 `study_question`）/ `LearnChatService`（只动 `learn_chat` + 调 LLM），不做"大杂烩 LearnService"
  - 判断：Impl 类头能写「本服务**只**动 X」3 个表名以内 → 拆得对；要写 5 个表名 → 拆早了
- **模块内共用纯函数**：放 `xxx/service/XxxHelper.java`（`@Service`，扁平不入 `impl/`）；只供单个 Service 用 → 直接做 `private` 方法
- **禁止**再创建 `*Generator` / `*Resolver` 这类薄包装层（历史的 `LearnContentGenerator` / `CategoryPathResolver` 已删，LLM 调用 inline 到 ServiceImpl + `LlmInvoker`）
- 例外：`common/` `infra/` 等横切包不必走接口/实现分层；`PromptService`、`LlmInvoker`、`JsonUtil` 这类纯工具单文件 `@Component`/`@Service` 即可

### 3.11 注释规范

每个方法必须包含**两层**注释：

1. **方法头 Javadoc**（写在实现类上）
   - 一句话用途
   - `<ol>` 列表分步骤描述完整业务逻辑
   - 关键约束 / 边界 / 反直觉点 / 陷阱（如 `<foreach>` 空集陷阱、事务边界、对称逻辑等）
   - `@param`（仅当含义不明显时）、`@return`（说明返回结构）、`@throws`（含错误码）
2. **方法体内行内注释**
   - 每个步骤用 `// Step N:` 标号，与 Javadoc 的 `<ol>` 顺序一一对应
   - 核心代码（含 trick、反直觉点、为何这样写）单独一行解释 **why**，不是 **what**
   - 不写"赋值给变量 x"这种重复代码字面的废话注释

类级 Javadoc 写清楚：模块归属（如 S1 / S5）、业务规则汇总、依赖外部服务的失败降级策略。

参考实现：[admin/service/impl/KnowledgeAdminServiceImpl.java](src/main/java/com/interview/agent/admin/service/impl/KnowledgeAdminServiceImpl.java)

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
| `AsyncSession` (SQLAlchemy) | MyBatis `@Mapper` 接口 |
| `await llm_client.chat(...)` | `chatClient.prompt(...).call().content()` |
| `Pydantic BaseModel` | Java Record |
| `JSONB` (SQLAlchemy) | `PGobject("jsonb", json)` + Jackson |
| `pgvector` (SQLAlchemy) | `?::vector` + `toPgVectorLiteral` |
| `asyncio.gather(*tasks, return_exceptions=True)` | `StructuredTaskScope.ShutdownOnFailure` |
| `logging.getLogger(__name__)` | `LoggerFactory.getLogger(XxxService.class)` |
| Alembic | Flyway |
