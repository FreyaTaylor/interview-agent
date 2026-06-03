# 技术选型决策记录（ADR）

> 记录 Java 后端关键技术选型的讨论过程与最终决定。规范条目见 [../CONVENTIONS.md](../CONVENTIONS.md)。

---

## ADR-001：DB 访问层 — 选 JdbcClient，不引入 MyBatis

**决策**：使用 Spring `JdbcClient` + HikariCP，手写 SQL；不引入 MyBatis / MyBatis-Plus / JPA / JOOQ。

### 候选对比

| 维度 | 纯 JdbcClient ✅ | MyBatis | MyBatis-Plus |
|---|---|---|---|
| SQL 控制力 | 完全手写 | 完全手写 | 半自动（CRUD 自动 + 复杂手写） |
| 学习/配置成本 | 0（Spring Boot 自带） | 中（XML / 注解 / mapper 扫描） | 中高 |
| pgvector 支持 | 直接 `?::vector` 字面量 | 需写 TypeHandler | 同左，还得绕 BaseMapper |
| JSONB 支持 | `PGobject` 直接绑 | 需写 TypeHandler | 同左 |
| Record DTO 映射 | `RowMapper` lambda 一行 | 需 `resultMap` 或注解 | 同左 |
| 动态 SQL | Java 字符串拼接（够用） | `<if>` `<foreach>` 强 | 同 MyBatis |
| 复杂查询调试 | SQL 在 Java 里，IDE 直接看 | XML 跳来跳去 | 同 |

### 选 JdbcClient 的理由

1. **SQL 大多复杂手写**：pgvector 相似度、JSONB 聚合、递归 CTE 算 KP 掌握度 —— MyBatis 的 CRUD 自动化收益小
2. **pgvector / JSONB 是非标类型**：MyBatis 要写 TypeHandler，反而绕；JdbcClient `PGobject` + `?::vector` 更直白
3. **Record DTO** 用 `RowMapper`（lambda 一行）映射很自然，不需要 `resultMap` XML
4. **与 Python 原版形态接近**：SQLAlchemy 写 `select(...)` 也是手写 SQL，翻译到 JdbcClient 一对一

### 何时回头考虑 MyBatis

- 出现 ≥5 个真正需要 `<foreach>` / `<if>` 大量动态拼接的查询
- 局部引入（两者可共存），不全量切换

### 拒绝 JOOQ 的理由

类型安全 + 编译期校验 SQL 很香，但首次配置（codegen + Gradle/Maven 集成）成本高于一期收益。如果以后查询复杂度爆炸再说。

---

## ADR-002：Schema 管理 — 用 Flyway，空库起步也用

**决策**：使用 Flyway 管理 schema；脚本放 `src/main/resources/db/migration/V*.sql`，应用启动自动执行。

### 澄清概念

**Flyway 不是"迁移历史数据"**。名字误导。它做的是 **schema migration**：

> 把 SQL 脚本按版本号有序执行（建表 / 加列 / 改约束），并记录"哪些脚本跑过了"。

跟 data migration（搬数据）完全两码事。Python 端的 Alembic 是同类工具。

### 空库起步为什么仍要用

| 不用 Flyway | 用 Flyway ✅ |
|---|---|
| 手动 `psql -f init.sql` | 应用启动自动建表 |
| 新加一列要去线上手动改 | 写 `V2__add_xxx.sql`，部署即生效 |
| 队友 / CI / Docker 都得手动复述 | 全自动，记录到 `flyway_schema_history` |
| 谁忘跑某条 SQL → 故障 | Flyway 校验版本，缺了启动失败 |

### 目录形态

```
java-backend/src/main/resources/db/migration/
├── V1__init_schema.sql              # 空库第一次跑，建所有表
├── V2__add_user_answer_embedding.sql
└── V3__add_interview_weight_col.sql
```

启动时 Spring Boot 自动调用 Flyway → 查 `flyway_schema_history` → 跑还没跑过的 V 脚本。**空库 = 跑 V1**。

### 规则

- 文件名 `V{递增编号}__{说明}.sql`
- **永不修改**已发布的 V 文件；改 schema 写新 V 文件
- V1 必须包含 `CREATE EXTENSION IF NOT EXISTS vector;`
- 初始 schema 按 [docs/TECH_DESIGN.md](../../docs/TECH_DESIGN.md) 重写一份，**不**复用 Python Alembic 输出
- 不导入 Python DB 的数据，**空库起步**

### 拒绝替代方案

- **手写 init.sql + 自己跑**：迭代多次后必然漏跑、不一致
- **Liquibase**：同类工具，更重，无优势
- **JPA `ddl-auto=create`**：项目不用 JPA，且生产环境不能用此方案

---

## 其他已敲定项（详见 [JAVA_REWRITE_PLAN.md](../../docs/JAVA_REWRITE_PLAN.md)）

| 项 | 选型 | 关键理由 |
|---|---|---|
| Web 框架 | Spring MVC + 虚拟线程 | 同步代码 + 异步吞吐；避开 WebFlux 学习与 R2DBC 痛点 |
| JDK | 21 LTS | 虚拟线程、Record、Pattern Matching；不引入 Lombok |
| 构建 | Maven | 生态稳；单 module 起步 |
| LLM Chat | Spring AI 1.x `ChatClient` | 结构化输出 `.entity(Class<T>)` 体验好 |
| Embedding / Vision | LangChain4j | Spring AI 对 DashScope 一等支持有限，LangChain4j 更稳 |
| DB | 同 PG 实例独立 DB `interview_agent_java` + user `iagent_java` | 与 Python 端完全隔离，重写期间互不污染 |
