# S2 — 知识树查询模块

> **范围**：单端点 `GET /api/knowledge/tree`，给 ExamPage 左侧树、Learn 选点、Study 推荐入口提供完整嵌套知识树。
> **对应模块**：[JAVA_REWRITE_PLAN.md §1](../../../docs/JAVA_REWRITE_PLAN.md)。
> **Python 对照**：[backend/api/knowledge.py](../../../backend/api/knowledge.py) + [backend/services/knowledge_node.py](../../../backend/services/knowledge_node.py) 中的 `build_knowledge_tree`。
> **状态**：✅ 完成

---

## 1. 使用的表

| 表 | 关系 | 本模块的操作 |
|---|---|---|
| `knowledge_node` | 只读 | `SELECT ... ORDER BY level, sort_order, id` |

只读单表，无 FK 操作。

---

## 2. 与其他模块的交互

### 2.1 本模块对外提供
- `KnowledgeController`：单 GET 端点。
- `KnowledgeService.buildTree()`：内部 helper，**未对外暴露给其他模块**（其他模块要遍历树请直接用 `KnowledgeNodeMapper.findAllOrdered()` 自行组装）。

### 2.2 本模块依赖（上游）
| 依赖 | 用途 | 来源 |
|---|---|---|
| `KnowledgeNodeMapper.findAllOrdered()` | 拉平表 | S1 |
| `KnowledgeNode` (Record) | Entity | S1 |
| `ApiResponse` | 统一响应 | S0 |

### 2.3 下游消费
- 前端 `ExamPage` 左侧树（暂走 admin 接口 `/api/admin/tree-nodes`，预留 `/api/knowledge/tree` 给非 admin 端用）
- S3 Study 推荐入口可复用 `buildTree()`（或直接用 `findAllOrdered()` 自己算优先度）

---

## 3. API 契约

| 方法 | 路径 | 入参 | 出参 (`data` 字段) |
|---|---|---|---|
| GET | `/api/knowledge/tree` | — | `KnowledgeTreeNodeView[]`（根列表，每个节点含 `children`） |

`KnowledgeTreeNodeView` 字段（Jackson 全局 SNAKE_CASE → JSON 字段为 snake_case）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | long | |
| `parent_id` | Long? | 根节点为 null，被 Jackson NON_NULL 过滤掉 |
| `name` | string | |
| `level` | short | 1=根，向下递增 |
| `node_type` | string | `category` \| `leaf` |
| `interview_weight` | short | |
| `sort_order` | int | |
| `mastery_level` | int | 从 `knowledge_node.mastery_level` 直读（S3 study/finish 钩子写入）；从未学过 → 0 |
| `study_count` | int | 同上 |
| `children` | array | 嵌套子节点（叶子为空数组） |

---

## 4. 算法

`KnowledgeService.buildTree()` —— **O(n) 单次遍历**：

1. 用 `findAllOrdered()` 拉所有节点（SQL 已按 `level → sort_order → id` 排序）
2. 为每个节点预建一个 `ArrayList<KnowledgeTreeNodeView> children` bucket（HashMap 缓存）
3. 遍历一遍：构造 view 并按 `parent_id` 挂到父节点 bucket；无父或父不在表里 → 进 `roots`
4. 由于 SQL 排好序，父节点一定先入 bucket，遍历顺序天然正确

不递归、不二次查询；时间复杂度 O(n)，空间 O(n)。

---

## 5. 与 Python 端的差异

| 项 | Python | Java | 备注 |
|---|---|---|---|
| `parent_id=null` 的根节点序列化 | 字典 `"parent_id": None` → JSON `null` | Jackson 全局 NON_NULL inclusion → 字段省略 | 调用方都靠 `children` 嵌套关系，不依赖根的 `parent_id`，影响为 0 |
| `mastery_level` / `study_count` | 通过 `qa_aggregate.get_kp_mastery_map` 批量查 attempt 表 | 直读 `knowledge_node.mastery_level` / `study_count`（S3 study/finish 钩子实时写入） | 与 Python 语义一致；详见 S3 文档 |

---

## 6. 交付物清单

### 6.1 Java 源码
- [knowledge/dto/KnowledgeTreeNodeView.java](../../src/main/java/com/interview/agent/knowledge/dto/KnowledgeTreeNodeView.java)
- [knowledge/service/KnowledgeService.java](../../src/main/java/com/interview/agent/knowledge/service/KnowledgeService.java)
- [knowledge/controller/KnowledgeController.java](../../src/main/java/com/interview/agent/knowledge/controller/KnowledgeController.java)

### 6.2 数据库
无新表 / 无迁移（V1 已建好 `knowledge_node`）。

---

## 7. 验收

```bash
curl -s http://localhost:8080/api/knowledge/tree | jq '.code, (.data|length)'
# 期待：0 \n N（N=根节点数，当前测试库 N=2）
curl -s http://localhost:8080/api/knowledge/tree | jq '.data[0] | {id,name,level,children: (.children|length)}'
# 期待：根节点带 children 数组
```

测试时 84 个节点（删过一些）正确嵌套，根节点 2 个（`java` / 另一根），第一个根下 4 个 L2。

---

## 8. 后续工作

- **S5 树生成完成后**：本端点输出会自动包含新节点（共享 mapper）
- **S5 树生成完成后**：本端点输出会自动包含新节点（共享 mapper）
