# 技术选型决策记录（ADR）

> 记录 Java 后端关键技术选型的讨论过程与最终决定。规范条目见 [../CONVENTIONS.md](../CONVENTIONS.md)。

---

## ADR-001：DB 访问层 — 选 JdbcClient，不引入 MyBatis（已被 ADR-004 取代）

> **状态：Superseded by ADR-004（2026-06）**。下方为历史记录，保留供回溯。

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
├── V2__add_xxx_col.sql
└── V3__rename_yyy.sql
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

---

## ADR-003：SQL 字面量放置 — 抽到 `XxxSql.java` 常量类（已被 ADR-004 取代）

> **状态：Superseded by ADR-004（2026-06）**。下方为历史记录，保留供回溯。

**决策**：所有 Repository 用到的 SQL 字面量统一放在**同包**下 `XxxSql.java` 常量类（包私有 final class，只有 `static final String`），Repository 不出现任何 SQL 字符串。

### 候选对比

| 方案 | 写法 | 取舍 |
|---|---|---|
| 内嵌文本块（旧） | `jdbc.sql("""SELECT ...""")` 写在方法里 | Repository 方法又长又乱，SQL 与 Java 逻辑搅在一起 |
| **A2 常量类（选）** | `jdbc.sql(XxxSql.FIND_ALL)` | 0 新依赖；SQL 集中、可一眼审完；Repository 方法 ≤ 5 行 |
| A1 外置 `.sql` 文件 | `@Value("classpath:sql/xxx/find_all.sql")` | IDE 高亮更好，但要写 SqlLoader 工具类，多 N 个小文件 |
| MyBatis / jOOQ | — | 已被 ADR-001 拒绝 |

### 规范

- 文件命名：`XxxSql.java`（与 `XxxRepository.java` 同包同前缀）
- 类修饰：`final class`，包私有，私有构造器
- 常量名：Repository 方法名转 `SCREAMING_SNAKE_CASE`（`findAllOrdered` → `FIND_ALL_ORDERED`）
- 当 INSERT/SELECT 有多条变体（如带/不带 embedding），后缀 `_WITH_X` / `_WITHOUT_X`
- 共享片段（如 SELECT 列清单）放本类顶部，用 `String.formatted()` 拼装
- 例外：一次性 DDL 或纯 1 行 SQL 可直接内联，不必单独建常量
- 参考实现：`com.interview.agent.knowledge.KnowledgeNodeSql`

---

## ADR-004：切换到 MyBatis @注解 Mapper（取代 ADR-001 + ADR-003）

**决策**：DB 访问层改用 `mybatis-spring-boot-starter` 的 **@注解 Mapper**，不写 XML。每个领域：`xxx/entity/XxxEntity.java`（Record）+ `xxx/mapper/XxxMapper.java`（`@Mapper` 接口）。

### 触发原因

S1 落地 A2 方案（JdbcClient + `XxxSql.java`）后实际感受：

- 一个领域要 3 个文件（Entity + Repository + Sql），CRUD 简单实体仍要 ~80 行 Repository 模板代码（参数绑定 + RowMapper lambda + 调用 SQL 常量）
- IN 子句要手拼 `?, ?, ?` placeholders + 显式 `for` 循环绑参，比 MyBatis `<foreach>` 啰嗦
- INSERT...RETURNING id 在 JdbcClient 下需要 `KeyHolder`，啰嗦
- 用户反馈："3 文件/实体很蠢"

### 候选对比（复评）

| 维度 | A2 JdbcClient + Sql 常量 | MyBatis @注解 ✅ |
|---|---|---|
| 文件数/实体 | 3（Entity/Repo/Sql） | 2（Entity/Mapper） |
| 代码行数（11 SQL CRUD 实测） | ~140 行 Repo + ~80 行 Sql | ~140 行 Mapper（含 SQL） |
| IN 子句 | 手拼 placeholders | `<script><foreach>` |
| INSERT RETURNING id | KeyHolder 啰嗦 | `@Select` 承载，1 行 |
| Record 映射 | RowMapper lambda | 自动驼峰（开 `-parameters` + `map-underscore-to-camel-case`）|
| pgvector | `?::vector` 字面量 | `#{x}::vector`，等价 |
| JSONB | `PGobject` | String + `::jsonb` cast，等价 |
| 学习成本 | 0 | 低（仅注解 5 个，无 XML） |

### 关键约束

- **必须**开 Maven `-parameters`，否则 Record 构造器参数名丢失 → MyBatis 无法按名绑定
- **必须**配 `mybatis.configuration.map-underscore-to-camel-case: true`
- **不**用 XML mapper，**不**用 MyBatis-Plus（增加心智负担且自动 CRUD 对复杂查询无收益）
- 动态 SQL 一律 `<script>` + `<foreach>`；空集合在 Service 端 `if (!list.isEmpty())` 兜底（`<foreach>` 空集会拼出非法 SQL）
- `INSERT ... RETURNING id` 用 `@Select` 注解（Record 无 setter，不能用 `@Options(useGeneratedKeys=true)`）

### 包结构

```
com.interview.agent.<domain>/
├── entity/
│   └── XxxEntity.java     # Record
└── mapper/
    └── XxxMapper.java     # @Mapper interface
```

### 影响

- 删除 `xxx/XxxRepository.java` + `xxx/XxxSql.java`，新增 `xxx/mapper/XxxMapper.java`
- ADR-001 「不引入 MyBatis」**撤销**
- ADR-003 「SQL 抽到 XxxSql.java」**撤销**
- [CONVENTIONS.md §3.4](../CONVENTIONS.md) 已同步更新

### 参考实现

- [knowledge/mapper/KnowledgeNodeMapper.java](../src/main/java/com/interview/agent/knowledge/mapper/KnowledgeNodeMapper.java)

