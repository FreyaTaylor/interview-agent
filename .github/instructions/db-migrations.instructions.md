---
applyTo: "java-backend/src/main/resources/db/migration/*.sql"
---

# Flyway 迁移 / Prompt 种子规则

> 这里是 Schema 迁移与 prompt 种子的强约束。细则见 [CONVENTIONS.md](../../CONVENTIONS.md) 数据库规范。

## 迁移文件
- 用 **Flyway**（不是 Alembic / 不是散落 SQL）；命名 `V{下一个序号}__snake_case_描述.sql`。
- **当前最新版本以目录里最大的 V 号为准**，新迁移严格 +1，不得复用或跳号。
- 文件头写清**背景 / 现象 / 根因 / 修复**注释（参考现有迁移风格），用中文。

## 表结构约定
- 表名 **snake_case 单数**（`knowledge_node`，不是 `knowledge_nodes`）。
- 主键统一 `id BIGSERIAL PRIMARY KEY`；时间 `created_at TIMESTAMP DEFAULT NOW()`。
- 外键字段 `{关联表}_id`；关键表预留 `user_id BIGINT DEFAULT 1`（一期不校验）。
- 半结构化数据用 **JSONB**；向量列用 pgvector `vector(1024)`。

## Prompt 种子（prompt_template 表）
- prompt **全中文**；要求 LLM 输出结构化 JSON 的，schema 写明。
- 用 `INSERT ... ON CONFLICT (key) DO UPDATE SET content=EXCLUDED.content, description=EXCLUDED.description`，保证可重复迭代。
- 长 prompt 用 `$PROMPT$ ... $PROMPT$` 美元引号包裹，避免转义。
- 新增 key 后必须去 `PromptKeys.java` 登记同名常量（1:1 对齐）。
- prompt 里提示"用户输入可能含错别字，请按语义理解"。
