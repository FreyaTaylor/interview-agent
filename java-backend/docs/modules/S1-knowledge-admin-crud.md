# S1 — 知识树 Admin CRUD 模块

> **范围**：Admin 端对 `knowledge_node` 表的 5 个端点：列出 / 新增 / 修改 / 批量排序 / 递归删除。**不含** LLM 树生成（推迟到 S5）、不含面向终端用户的查询接口（S2）。
> **对应模块**：原 [JAVA_REWRITE_PLAN.md §2.1](../../../docs/JAVA_REWRITE_PLAN.md)。
> **Python 对照**：[backend/api/admin/tree_nodes.py](../../../backend/api/admin/tree_nodes.py) + [backend/api/admin/_tree_router_factory.py](../../../backend/api/admin/_tree_router_factory.py) + [backend/services/knowledge_node.py](../../../backend/services/knowledge_node.py)
> **状态**：✅ 完成

---

## 1. 使用的表

| 表 | 关系 | 本模块的操作 |
|---|---|---|
| `knowledge_node` | **主表（拥有）** | SELECT / INSERT / UPDATE / DELETE |
| `learn_chat` | 反向 FK：`knowledge_point_id`（**无 CASCADE**） | 删节点前 DELETE 该 KP 的全部对话 |
| `interview_knowledge_question` | 反向 FK：`knowledge_node_id`（nullable，**无 CASCADE**） | 删节点前 UPDATE 置 NULL |
| `knowledge_content` | 反向 FK：`knowledge_point_id UNIQUE`（schema 有 **ON DELETE CASCADE**） | 数据库自动级联 |
| `study_question` | 反向 FK：`knowledge_point_id`（schema 有 **ON DELETE CASCADE**） | 数据库自动级联（其下 `question_attempt` 同链路 CASCADE） |

> Schema 定义见 [V1__init_schema.sql](../../src/main/resources/db/migration/V1__init_schema.sql) 中 `knowledge_node`、`learn_chat`、`knowledge_content`、`study_question`、`interview_knowledge_question`。

---

## 2. 与其他模块的交互

### 2.1 本模块对外提供
- `KnowledgeNodeMapper`（包路径 `com.interview.agent.knowledge.mapper`）：**所有下游模块共用**的 `knowledge_node` MyBatis Mapper，包含读 / 写 / FK 清理工具。
- `KnowledgeNode` Record：跨模块共享的 entity。

### 2.2 本模块依赖（上游 / S0）
| 依赖 | 用途 |
|---|---|
| `com.interview.agent.common.ApiResponse` | 统一响应 |
| `com.interview.agent.common.BizException` | 业务异常（参数 / not found） |
| `com.interview.agent.infra.llm.EmbeddingService` | 新建节点时给 `embedding` 字段生成 1024d 向量（失败降级为 NULL） |
| MyBatis （`mybatis-spring-boot-starter`）| SQL 执行（@注解 Mapper，不写 XML）|

### 2.3 下游消费（未来）
- **S2 知识树查询**（`knowledge/KnowledgeController`）：调 `KnowledgeNodeMapper.findAllOrdered()` 组装整树。
- **S3 Study 闭环**：基于 `knowledge_node.interview_weight` + mastery 计算"优先度"做 KP 推荐；新增 KP 时通过 mastery 派生写回（不在本模块）。
- **S4 Learn 讲解**：首次访问 KP 触发 `ensureKpStudied`，读节点信息生成讲解 + 题目。
- **S5 树生成**（同 admin 包内的扩展）：from-text / from-mm / from-image / optimize / merge 六类生成器**复用**本模块的 `KnowledgeAdminService.create()`（含 level 计算 + embedding）。
- **S7 Interview Matcher**：解析后做 embedding 匹配；缺失节点时**自动调** `KnowledgeAdminService.create()` 创建新叶子；命中节点时 `interview_weight += 1`（上限 5）。

---

## 3. API 契约

路径前缀：`/api/admin/tree-nodes`

| 方法 | 路径 | 入参 | 出参 (`data` 字段) |
|---|---|---|---|
| GET | `/` | — | `[{id, parentId, name, level, nodeType, interviewWeight, sortOrder}, ...]` |
| POST | `/` | `{parentId?, name, interviewWeight?}` | `{id, name, level}` |
| PUT | `/batch-sort` | `{updates: [{id, sortOrder}, ...]}` | `{updated: N}` |
| PUT | `/{id}` | `{name?, interviewWeight?, parentId?, sortOrder?, movingParent?}` | `{id, name}` |
| DELETE | `/{id}` | — | `{deleted: id}` |

外层始终包 `ApiResponse`：`{code, data, message}`，HTTP 状态码始终 200。

### 3.1 与 Python 端的差异（重要）
| 项 | Python | Java | 原因 |
|---|---|---|---|
| 移父识别 | `parent_id in model_fields_set`（pydantic 反射） | 显式 `movingParent=true` 标记 | Jackson 反序列化 record 无法区分 "不传" vs "传 null" |
| level 重算范围 | 仅自身（不递归子孙） | 同（保持一致） | 跨树移动稀有，递归改写风险大；留给 S5 树生成统一规整 |
| `nodeType` 判定 | `level >= 3 → leaf`（硬规则） | 按 "有无子节点" 判定 | 知识树可深 3 / 4 层甚至更深（见 `services/tree_gen.py` 注释），Python 这条是老代码遗留 bug |
| embedding 失败处理 | 抛错中断 | try/catch + WARN 日志 + NULL | 允许 dev 环境用 dummy DASHSCOPE key 启动 |

---

## 4. 业务规则

1. **新建**
   - 无 `parentId` → `level=1, sortOrder=0`
   - 有 `parentId` → `level = parent.level + 1`
   - **`nodeType` 默认 `leaf`**（与 level 解耦：知识树可深 3 / 4 层）
   - 父节点原本是 `leaf`（被新建子节点的瞬间）→ 自动升为 `category`
2. **修改**
   - `name / interviewWeight / sortOrder` 用 `COALESCE` 合并（null 表示不改）
   - 仅当 `movingParent=true` 时才会改 `parent_id` 并按新父重算 `level`；`nodeType` 按当前节点 "有无子节点" 重评（不再依赖 level）
   - 禁止挂到自己（`newParentId == id` 直接抛 40001）
3. **批量排序**：逐条 UPDATE `sort_order`，返回成功条数
4. **递归删除**
   1. BFS 收集自身 + 所有子孙 id
   2. DELETE `learn_chat` 中该 KP 的全部记录
   3. UPDATE `interview_knowledge_question` 把 `knowledge_node_id` 置 NULL（保留面试历史）
   4. 批量 DELETE `knowledge_node`（`knowledge_content / study_question / question_attempt` 通过 ON DELETE CASCADE 自动清掉）
   5. 若原父节点已无任何子节点 → 把父节点 `nodeType` 改回 `leaf`

---

## 5. 交付物清单

### 5.1 Java 源码
- [common/HealthController.java](../../src/main/java/com/interview/agent/common/HealthController.java) — 从 `api/` 包迁过来（CONVENTIONS 锁定按模块分包，无全局 `controller/`）
- [knowledge/entity/KnowledgeNode.java](../../src/main/java/com/interview/agent/knowledge/entity/KnowledgeNode.java) — Record，与表一一对应
- [knowledge/mapper/KnowledgeNodeMapper.java](../../src/main/java/com/interview/agent/knowledge/mapper/KnowledgeNodeMapper.java) — MyBatis @注解 Mapper（SQL 在 `@Select`/`@Insert`/`@Update`/`@Delete` 里，动态 SQL 用 `<script><foreach>`，见 ADR-004）
- [admin/dto/CreateKnowledgeNodeReq.java](../../src/main/java/com/interview/agent/admin/dto/CreateKnowledgeNodeReq.java)
- [admin/dto/UpdateKnowledgeNodeReq.java](../../src/main/java/com/interview/agent/admin/dto/UpdateKnowledgeNodeReq.java)
- [admin/dto/BatchSortReq.java](../../src/main/java/com/interview/agent/admin/dto/BatchSortReq.java)
- [admin/dto/KnowledgeNodeView.java](../../src/main/java/com/interview/agent/admin/dto/KnowledgeNodeView.java)
- [admin/service/impl/KnowledgeAdminServiceImpl.java](../../src/main/java/com/interview/agent/admin/service/impl/KnowledgeAdminServiceImpl.java) — `@Transactional` 编排（实现接口 [admin/service/KnowledgeAdminService.java](../../src/main/java/com/interview/agent/admin/service/KnowledgeAdminService.java)）
- [admin/controller/KnowledgeAdminController.java](../../src/main/java/com/interview/agent/admin/controller/KnowledgeAdminController.java) — 5 端点

### 5.2 包结构图
```
com.interview.agent
├── common/
│   ├── HealthController.java        ← 从 api/ 迁入
│   └── (S0 既有横切类)
├── knowledge/                       ← 本模块新建（所有模块共用）
│   ├── entity/KnowledgeNode.java
│   └── mapper/KnowledgeNodeMapper.java   ← MyBatis @注解 Mapper（ADR-004）
└── admin/                           ← 本模块新建（后续 S5 项目树/S5 树生成同包）
    ├── controller/
    │   └── KnowledgeAdminController.java
    ├── service/
    │   ├── KnowledgeAdminService.java       ← 接口
    │   └── impl/
    │       └── KnowledgeAdminServiceImpl.java   ← @Service 实现
    └── dto/
        ├── CreateKnowledgeNodeReq.java
        ├── UpdateKnowledgeNodeReq.java
        ├── BatchSortReq.java
        └── KnowledgeNodeView.java
```

---

## 6. 验收

`mvn -DskipTests package` 通过；启动后：

```bash
# 1. 列表
curl -s http://localhost:8080/api/admin/tree-nodes | jq

# 2. 建根
curl -s -X POST http://localhost:8080/api/admin/tree-nodes \
  -H 'Content-Type: application/json' \
  -d '{"name":"后端"}'

# 3. 建二级
curl -s -X POST http://localhost:8080/api/admin/tree-nodes \
  -H 'Content-Type: application/json' \
  -d '{"parentId":1,"name":"Java"}'

# 4. 建叶子
curl -s -X POST http://localhost:8080/api/admin/tree-nodes \
  -H 'Content-Type: application/json' \
  -d '{"parentId":2,"name":"JVM 内存模型","interviewWeight":4}'

# 5. 改名 + 权重
curl -s -X PUT http://localhost:8080/api/admin/tree-nodes/3 \
  -H 'Content-Type: application/json' \
  -d '{"name":"JMM","interviewWeight":5}'

# 6. 批量排序
curl -s -X PUT http://localhost:8080/api/admin/tree-nodes/batch-sort \
  -H 'Content-Type: application/json' \
  -d '{"updates":[{"id":1,"sortOrder":10},{"id":2,"sortOrder":20}]}'

# 7. 递归删
curl -s -X DELETE http://localhost:8080/api/admin/tree-nodes/1
```

每个响应 `code=0`、`data` 字段符合 §3 约定。

---

## 7. 已知遗留

- `embedding` 字段在 dev 环境（dummy DASHSCOPE key）下会落 NULL，等 S5 的 backfill 或下次 update 时补；不影响 CRUD 流程。
- level 重算未递归到子孙；与 Python 端一致，但移高 level 节点时子孙 level 会"不连续"，留待 S5 树生成模块统一规整。
- 未实现"批量移动 / 跨树合并"，归到 S5。
