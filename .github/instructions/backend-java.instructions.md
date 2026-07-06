---
applyTo: "java-backend/src/main/java/**/*.java"
---

# 后端 Java 编码规则（Spring Boot）

> 细则以 [java-backend/CONVENTIONS.md](../../java-backend/CONVENTIONS.md) 为准；本文件是高频强约束速览。

## 技术栈事实（别用错）
- **Java 21**（虚拟线程）· Spring Boot 3.x + Spring MVC · **MyBatis**（@注解 Mapper，不写 XML）· **Flyway** 迁移 · PostgreSQL 16 + pgvector。
- LLM 用 **Spring AI `ChatClient`**（→ DeepSeek）；Embedding/Vision 用 **LangChain4j + DashScope**。
- **禁止引入**：Lombok、MapStruct、JPA/Hibernate、MyBatis-Plus、WebFlux、Reactor。

## 分层与命名
- 模块内分层：`controller / service（接口）/ service/impl（实现）/ mapper / entity / dto`。
- Controller **依赖 service 接口**，注入 `@Service` 实现。
- DTO / Entity 一律用 **Record**；`XxxReq / XxxResp / XxxView`；Entity = 表名大驼峰单数。
- 方法**动词起头**（`generateQuestion`）；常量 `UPPER_SNAKE_CASE`；配置类 `XxxProperties`。
- 默认 `package-private`，对外才 `public`；**禁止通配符 import**。
- **不要**造 `*Generator` / `*Resolver` 薄包装：LLM 调用直接 inline 到 ServiceImpl + `LlmInvoker`。

## API
- 全 **POST + JSON body**（含读端点、list 用 `/list` + `{}`）；**id 走 body，禁 `@PathVariable`**。
- 统一响应 `{"code":0,"data":...,"message":"success"}`；错误 `{"code":40001,...}`。
- HTTP 字段 snake_case（全局 Jackson 已配）。

## Prompt / LLM
- **禁止在代码里硬编码 prompt**；prompt 存 `prompt_template` 表，用 `PromptService.render(key, vars)`。
- prompt key 必须引用 `PromptKeys` 常量，**禁止字面量**。
- 新增 prompt 顺序：先写 Flyway `V?__seed_*.sql` → 再登记 `PromptKeys` 常量 → 业务引用。

## 注释
- 只解释"**为什么**"，不解释"是什么"；业务规则注释用**中文**。
