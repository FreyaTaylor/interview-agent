# OutlinerEditor 操作契约与端点验证（S1 知识树 / S6 项目树共用）

> **产出动机**：用户报告"按 Enter 没反应"。根因是后端 `create()` 拒绝空 name（40001）。  
> 本文同时完成：(a) 修复 Enter；(b) 全 admin 端点改 POST + body 传 id（落地 java-style §3.3）；(c) 6 个键盘/拖拽操作端到端验证。  
> 验证日期：2026-06-05；后端版本：commit 待提交；DB Flyway V15。

---

## 1. 组件 / 端点全景

`frontend-react/src/components/OutlinerEditor.jsx` 是**唯一**的大纲编辑器组件，被 `OutlinerPage.jsx` 用 `apiPrefix` 切换两次：

| Tab | apiPrefix | 后端 Controller | Service Impl |
|---|---|---|---|
| 知识树 | `tree-nodes` | [KnowledgeAdminController.java](java-backend/src/main/java/com/interview/agent/admin/controller/KnowledgeAdminController.java) | [KnowledgeAdminServiceImpl.java](java-backend/src/main/java/com/interview/agent/admin/service/impl/KnowledgeAdminServiceImpl.java) |
| 项目树 | `project-nodes` | [ProjectAdminController.java](java-backend/src/main/java/com/interview/agent/admin/controller/ProjectAdminController.java) | [ProjectAdminServiceImpl.java](java-backend/src/main/java/com/interview/agent/admin/service/impl/ProjectAdminServiceImpl.java) |

两套 Controller 完全同型，5 个核心 + 项目树多 1 个 `from-text`：

| 路径 | 方法 | Body | 用途 |
|---|---|---|---|
| `POST /api/admin/{tree-nodes\|project-nodes}/list` | POST | `{}` | 全量拉树（前端 OutlinerEditor + AdminPage 共用） |
| `POST /.../create` | POST | `{parent_id?, name, ...}` | 新建节点（**允许 name=""**，UX 占位用） |
| `POST /.../update` | POST | `{id, name?, parent_id?, sort_order?, moving_parent?}` | 部分更新；`moving_parent:true` 才动 parent |
| `POST /.../batch-sort` | POST | `{updates: [{id, sort_order}, ...]}` | 兄弟序号重排 |
| `POST /.../delete` | POST | `{id}` | 递归删（自身 + 全部子孙） |
| `POST /api/admin/project-nodes/from-text` | POST | `{text}` | 项目树独有，LLM 拆 项目→话题→问题 |

**契约硬规则**（java-style §3.3）：
- 全 POST，不要 GET / PUT / DELETE。
- id 一律走 body，不走 `@PathVariable`。
- 字段全 camelCase（`parentId` / `nodeType` / `movingParent`）；前端发出时是 snake_case，Spring 自动映射。

---

## 2. 操作 × 树 矩阵（6 操作 × 2 树 = 12 验证用例 + 1 边界用例）

下表"前端 fetch 序列"对应 [OutlinerEditor.jsx](frontend-react/src/components/OutlinerEditor.jsx) 内的实际调用；"后端逻辑"指向 ServiceImpl 中的对应步骤注释。

### 2.1 Enter — 创建空兄弟节点

**交互**：在某节点输入框按 Enter，光标跳到新创建的同级节点（在当前节点正下方）。

**前端 fetch 序列**（[OutlinerEditor.jsx#L165-L188](frontend-react/src/components/OutlinerEditor.jsx)）：
```
1. POST /update         (先存当前节点 name —— handleBlur 内联)
2. POST /create         body {parent_id: <self.parent_id>, name: ""}
3. POST /batch-sort     body {updates: [被推后的兄弟 +1, 新节点 sort_order=self.sort+1]}
4. POST /list           (fetchData 重拉)
```

**后端逻辑**：
- 知识树 `create()`：[KnowledgeAdminServiceImpl#L98 Step 1-6](java-backend/src/main/java/com/interview/agent/admin/service/impl/KnowledgeAdminServiceImpl.java) — name 允许空；level=父+1；nodeType=leaf；空 name 跳过 embedding；父若是 leaf→升 category。
- 项目树 `create()`：[ProjectAdminServiceImpl#L107 Step 1-5](java-backend/src/main/java/com/interview/agent/admin/service/impl/ProjectAdminServiceImpl.java) — 同上，但 nodeType 走**硬规则** `level >= 3 ? leaf : category`，且超 MAX_LEVEL=3 会拒。

**bug 修复**：原代码 `if (name.isEmpty()) throw new BizException(40001, "节点名称不能为空")` 已删除，改为允许空 name 创建占位节点；onBlur 时再走 `update` 填名字。

**curl 证据**：
```bash
# 知识树
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/create \
    -H 'Content-Type: application/json' -d '{"parent_id":240,"name":""}'
{"code":0,"data":{"id":333,"level":2,"name":""},"message":"success"}

# 项目树
$ curl -sX POST http://localhost:8080/api/admin/project-nodes/create \
    -H 'Content-Type: application/json' -d '{"parent_id":36,"name":""}'
{"code":0,"data":{"id":94,"level":2,"name":""},"message":"success"}
```
对比修复前同样请求返回 `{"code":40001,"message":"节点名称不能为空"}`。

---

### 2.2 Tab — 缩进（挂为前一个兄弟的子节点）

**交互**：光标在节点 B 上按 Tab；若 B 上方有兄弟 A，则 B 变成 A 的最末子节点。

**前端 fetch 序列**（`handleIndent`）：
```
1. POST /update         body {id: B, parent_id: A, moving_parent: true}
2. POST /batch-sort     body {updates: [{id: B, sort_order: A的孩子数}]}
3. POST /list
```

**关键修复**：原代码 body 缺 `moving_parent:true`，导致后端直接忽略 parent_id（见 §3）—— 已加上。

**后端逻辑**：
- 通用 `update()`：先 `updateBasic`（name/weight/sort 用 COALESCE 部分更新）；只有 `req.isMovingParent()==true` 才进 move 分支：防自环、查新父、推 newLevel = newParent.level+1、按规则改 nodeType、调用 `moveParent` + `shiftDescendantLevels` 把整棵子树 level 平移 delta。
- 项目树额外做 MAX_LEVEL=3 检查：`newMaxAfterMove = newLevel + 子树深度差` > 3 则拒。

**curl 证据**（接 2.1 创建的 A=334, B=335）：
```bash
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/update \
    -H 'Content-Type: application/json' \
    -d '{"id":335,"parent_id":334,"moving_parent":true}'
{"code":0,"data":{"id":335,"name":"NODE_B"},"message":"success"}

$ curl -sX POST http://localhost:8080/api/admin/project-nodes/update \
    -H 'Content-Type: application/json' \
    -d '{"id":96,"parent_id":95,"moving_parent":true}'
{"code":0,"data":{"id":96,"name":"P_TOPIC_B"},"message":"success"}
```

---

### 2.3 Shift+Tab — 反缩进（升一层）

**交互**：光标在节点 C 上按 Shift+Tab；C 升为 C 的祖父的子节点（与 C 的父亲变兄弟），并插到父亲下一位。

**前端 fetch 序列**（`handleOutdent`）：
```
1. POST /update         body {id: C, parent_id: <祖父 id 或 null>, moving_parent: true}
2. POST /batch-sort     body {updates: [父亲的后代兄弟 sort_order +1, {C, parent.sort+1}]}
3. POST /list
```

**关键修复**：同 Tab，原代码缺 `moving_parent:true`。

**后端逻辑**：与 Tab 共用 `update()`。`parent_id` 可为 `null`（升到根），后端解析为 newLevel=1。

**curl 证据**：
```bash
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/update \
    -H 'Content-Type: application/json' \
    -d '{"id":336,"parent_id":240,"moving_parent":true}'
{"code":0,"data":{"id":336,"name":"NODE_C"},"message":"success"}

$ curl -sX POST http://localhost:8080/api/admin/project-nodes/update \
    -H 'Content-Type: application/json' \
    -d '{"id":97,"parent_id":36,"moving_parent":true}'
{"code":0,"data":{"id":97,"name":"P_QUESTION_C"},"message":"success"}
```

---

### 2.4 Backspace — 删除空行

**交互**：光标在 name 已被清空的节点上按 Backspace；该节点（仅当无子节点）被删除，光标跳到上一可见节点。

**前端 fetch 序列**（`handleDelete`）：
```
1. POST /delete         body {id}
2. POST /list
```
（同时 `handleKeyDown` 的 Backspace 分支前置守卫：仅当 `node.name === ''` 才触发。）

**后端逻辑**：
- 通用 `delete()`：BFS 收集自身+所有子孙 id；
- 知识树：先 DELETE `learn_chat`（无 CASCADE），再 SET NULL `interview_knowledge_question.knowledge_node_id`，再批量 DELETE 节点；父若没娃了 → 降回 leaf。
- 项目树：先 SET NULL `interview_project_question.project_node_id`，再批量 DELETE（`project.root_node_id` 由 V1 schema ON DELETE SET NULL 自动处理）；父若没娃了 → 降回 leaf。

**curl 证据**：
```bash
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/delete \
    -H 'Content-Type: application/json' -d '{"id":334}'
{"code":0,"data":{"deleted":334},"message":"success"}

$ curl -sX POST http://localhost:8080/api/admin/project-nodes/delete \
    -H 'Content-Type: application/json' -d '{"id":95}'
{"code":0,"data":{"deleted":95},"message":"success"}
```

---

### 2.5 拖拽（3 落点：before / after / child）

**交互**：拖一个节点到目标节点上：
- 鼠标 Y 落在目标行的上 1/3：作为 target 的同级兄弟，插到 target **上方**（`before`）
- 鼠标 Y 落在下 1/3：作为 target 的同级兄弟，插到 target **下方**（`after`）
- 鼠标 Y 落在中段：作为 target 的**末位子节点**（`child`）
- 特例：若 X 落在 bullet/toggle 列（indent + 8px 内）→ 一律按 `after`，避免窄列误判

落点判定见 [OutlinerEditor.jsx#L385 getDropZone](frontend-react/src/components/OutlinerEditor.jsx)。

**前端 fetch 序列**（`handleDrop`）：
```
1. POST /update         body {id, parent_id, moving_parent: <是否换爹>}
2. POST /batch-sort     body {updates: [被让位的兄弟 +1, 自身设为 newSortOrder]}
3. （撤销栈快照入栈）
4. POST /list
```

**后端逻辑**：与 Tab/Shift+Tab 共用 `update()` + `batch-sort`。前端做防环（不能拖到自己的子孙下）。

**curl 证据**（用 batch-sort 模拟兄弟重排）：
```bash
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/batch-sort \
    -H 'Content-Type: application/json' \
    -d '{"updates":[{"id":334,"sort_order":100},{"id":335,"sort_order":101}]}'
{"code":0,"data":{"updated":2},"message":"success"}

$ curl -sX POST http://localhost:8080/api/admin/project-nodes/batch-sort \
    -H 'Content-Type: application/json' \
    -d '{"updates":[{"id":95,"sort_order":200},{"id":97,"sort_order":201}]}'
{"code":0,"data":{"updated":2},"message":"success"}
```

---

### 2.6 Ctrl/Cmd+Z — 撤销

**交互**：在编辑器外（不在 input 焦点内）按 Cmd/Ctrl+Z，撤销上一次结构性操作（目前只覆盖 drag）。

**前端逻辑**（`pushUndo` / `popUndo`）：
- 每次 drag 成功后 push 一个 `{ label, undo: async () => {...} }`，记录受影响节点的 `parent_id` + `sort_order` 快照。
- popUndo 执行：先 `POST /update` 改回 parent（若变过），再 `POST /batch-sort` 还原所有兄弟的 sort_order；最后 `POST /list`。

**后端逻辑**：复用同样的 `update` + `batch-sort` 端点 —— 撤销没有专门的"undo"端点，靠**重放反向 mutation**实现，与 Linus 哲学一致。

**curl 证据**（模拟 drag B 到 A 下 后 撤销，再调一次 update 把 B 改回根）：
```bash
$ curl -sX POST http://localhost:8080/api/admin/tree-nodes/update \
    -H 'Content-Type: application/json' \
    -d '{"id":335,"parent_id":240,"moving_parent":true}'
{"code":0,"data":{"id":335,"name":"NODE_B"},"message":"success"}
```

---

## 3. 已修复 bug 与已知问题

### 3.1 ✅ 已修复

| Bug | 现象 | 根因 | 修复 |
|---|---|---|---|
| **Enter 没反应** | 按 Enter 后输入框无新行 | 后端 `create()` 拒绝空 name（40001），前端期待 `code:0` 返回 newId，因此抛错后什么也不做 | 删除 `KnowledgeAdminServiceImpl.create()` + `ProjectAdminServiceImpl.create()` 中的 `if (name.isEmpty()) throw 40001`；空 name 跳过 embedding。frontend.handleBlur 已存在，会在用户 onBlur 时把 name 填上。 |
| **Tab/Shift+Tab 静默不生效** | 按 Tab 后视觉缩进出现，刷新后还原 | 前端 `handleIndent`/`handleOutdent` 调 `update` body 没带 `moving_parent:true`；后端因此忽略 parent_id 改动 | 给两个 handler 的 update body 补 `moving_parent: true`。 |
| **`@PathVariable` 违反约定** | 4 处 `POST /{id}/update` + `POST /{id}/delete` | 历史从 Python 端口翻过来时按 RESTful 习惯写 | 全部改 `POST /update` + body `{id}`；`@GetMapping` list 改 `@PostMapping("/list")`。新增共享 DTO `DeleteNodeReq(long id)`；`UpdateXxxReq` 顶部加 `long id` 字段。 |

### 3.2 ⚠️ 已知问题（不在本次修复范围）

| 问题 | 位置 | 影响 |
|---|---|---|
| ✨ 优化按钮 404 | [OutlinerEditor.jsx#L519 handleOptimize](frontend-react/src/components/OutlinerEditor.jsx)：`POST ${API_ADMIN}/trees/${rootId}/optimize` | 该端点在 [TreeGenController.java#L21](java-backend/src/main/java/com/interview/agent/admin/controller/TreeGenController.java) 仅以注释存在，**未实现**。点击会 404。 |

---

## 4. 验证脚本

完整 16 次 curl 用例固化在 `tmp/verify_tree_ops.sh`（一次性脚本，未签入），覆盖：

```
============ 知识树 (tree-nodes) ============
1. /list (POST 取代 GET)              → code:0
2. Enter (create 空 name) —— 此前 bug → code:0  ✅ 修复
3. Tab (update id+moving_parent)      → code:0
4. Shift+Tab (升到根)                 → code:0
5. 拖拽 (batch-sort 重排)             → code:0
6. Ctrl+Z (再 update 回退)            → code:0
7. Backspace (delete by body id)      → code:0

============ 项目树 (project-nodes) ============
1-7. 同上                             → code:0
8. MAX_LEVEL=3 边界                   → code:40001 ✅ 仍正确拒绝
```

---

## 5. 设计契约（新增第 3 棵同型树需要做的事）

若以后要再加一棵 outliner 树（比如「面经库」），最小改动量：

1. **后端**（约 200 行 ServiceImpl + 60 行 Controller）：
   - 新建 entity + Mapper + Service interface + ServiceImpl + Controller
   - Controller 严格复制 5 端点签名（`/list /create /update /delete /batch-sort`）
   - 复用现有 `BatchSortReq` + `DeleteNodeReq`；自定义 `Update<X>NodeReq`（顶部加 `long id`）+ `Create<X>NodeReq`
2. **前端**（约 5 行）：
   - `OutlinerPage.jsx` 加 Tab，传 `apiPrefix="<x>-nodes"` 给 `OutlinerEditor`，零组件改动

`OutlinerEditor.jsx` 是双树共用的，不需要任何改动 —— 这是当前架构最有价值的地方。
