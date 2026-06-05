# S6 — 项目树 Admin 模块（项目树 CRUD + 文本解析）

> **范围**：Admin 端对 `project_node` 表的 5 个 CRUD 端点 + 1 个 `from-text`（LLM 把项目描述解析为 项目→话题→题目 三层树，并同步建 `project` 元数据行）。
> **对应模块**：原 [JAVA_REWRITE_PLAN.md §3](../../../docs/JAVA_REWRITE_PLAN.md)。
> **Python 对照**：[backend/api/admin/project_nodes.py](../../../backend/api/admin/project_nodes.py) + [backend/services/project_node.py](../../../backend/services/project_node.py) + [backend/prompts/project_prompts.py](../../../backend/prompts/project_prompts.py)。
> **状态**：✅ 已实现 + 验证通过

---

## 0. 关键决策（待确认 / 与 Python 差异）

| # | 决策 | 选项 | 倾向 | 备注 |
|---|---|---|---|---|
| 1 | **包路径** | A. `admin/`（与 S1 KnowledgeAdmin 同包）<br>B. 新建 `project/admin/` | **A** | 保持与 S1 一致；类名以 `Project` 开头区分（`ProjectAdminController` / `ProjectAdminService`） |
| 2 | **DB 迁移** | A. 不需要（V1 已有 `project_node` + `project`）<br>B. 新增 V15 微调列 | **A** | V1 schema 已对齐 Python；无需新迁移 |
| 3 | **Prompt 存放** | A. 跟 S5 走 classpath `.txt`（`PromptLoader`）<br>B. 跟 S3/S4 走 DB `prompt_template` + Flyway seed | **B** | 与 S3/S4 体系一致，方便线上改 prompt 不重启；新增 V15 seed `project/parse-text` + `project/dup-check` |
| 4 | **level / node_type 规则** | A. 硬规则 `level >= 3 → leaf`（Python 项目树）<br>B. 按"有无子节点"动态（S1 知识树） | **A** | 项目树**固定三层**（项目→话题→题目）；这是项目树和知识树的本质差异 |
| 5 | **embedding 覆盖范围** | A. 仅叶子（level=3）<br>B. 全部节点（含 level=1/2 category） | **B** | 与 Python 一致；非叶 embedding 给 S8 Interview Matcher 做"匹配不上叶子时 fallback 到 category 新增子节点"用 |
| 6 | **embedding 文本格式** | "祖先1 / 祖先2 / 当前name" | 同 Python | 避免不同上下文重名拿到同向量 |
| 7 | **embedding 失败处理** | WARN + NULL（不阻断） | 同 S1/S5 | dev 环境允许 DashScope 抖动 |
| 8 | **删除节点的 FK 处理** | `interview_project_question.project_node_id` 置 NULL（保留事实数据）<br>`project.root_node_id` 由 V1 schema 声明 `ON DELETE SET NULL` 自动处理 | 同 Python | `project_session` 引 `project_id` 不引 node_id，不受影响 |
| 9 | **from-text 是否同步建 `project` 行** | 是 | 同 Python | 这是用户视角"项目"的**唯一创建入口**（拷打页只查/编辑，不创建）；`project.name=root.name`、`project.description=raw_text`、`project.root_node_id=root_id` |
| 10 | **项目名去重粒度** | 精确同名 + LLM 语义 | 同 Python + S5 | dup-check prompt 与 S5 知识树独立（不共用，语义不同：项目名 vs 知识根名） |
| 11 | **改父 (`movingParent`)** | 显式标记字段（与 S1 一致） | 同 S1 | record 反序列化无法区分"不传"vs"传 null"；用 `movingParent:true` 触发 |
| 12 | **改名是否回滚 embedding** | 否（创建时一次性写入） | 同 Python | 简化；记入"未做" |
| 13 | **批量排序** | `PUT /batch-sort` 传 `[{id, sortOrder}]`，循环 update | 同 S1 | 单事务，部分失败回滚 |
| 14 | **递归删除策略** | 自底向上（叶子先删避免 FK 自引用冲突） | 同 Python | V1 schema `parent_id` 未声明 CASCADE |
| 15 | **不复用 S1 `KnowledgeAdminService`** | 各自独立 | — | 两棵树规则差异大（level 硬/柔、是否同步建 `project`）；不抽公共基类 |
| 16 | **前端是否复用** | 复用现有 `OutlinerEditor` + `OutlinerPage`（已支持 `apiPrefix="project-nodes"` Tab） | **复用** | 见下文 §6 前端章节；Java 端点契约必须**严格对齐** OutlinerEditor 调用（路径 / 字段名 camelCase 风格已与 S1 一致），不需新增前端文件 |

---

## 1. 使用的表（V1 已建好，不新增）

| 表 | 操作 | 关键字段 |
|---|---|---|
| `project_node` | INSERT / SELECT / UPDATE / DELETE | id, parent_id, name, level(1/2/3), node_type('category'/'leaf'), sort_order, embedding(1024), user_id |
| `project` | INSERT / SELECT | id, name, description, root_node_id（删树时 SET NULL）|
| `interview_project_question` | UPDATE | 删 node 前置空 `project_node_id`（保留事实） |
| `prompt_template` | 只读 | V15 新增 `project/parse-text` + `project/dup-check` |

> Schema 见 [V1__init_schema.sql](../../src/main/resources/db/migration/V1__init_schema.sql) §3–4。

---

## 2. 与其他模块的交互

### 2.1 本模块对外提供
- `ProjectNodeMapper`（包路径 `com.interview.agent.admin.mapper` 或 `project/mapper`）：跨模块共享的 mapper（S7 Project Grilling / S8 Interview 都要读）
- `ProjectNode` Record：跨模块共享 entity
- `ProjectAdminService.create(parentId, name)`：暴露给 S8 Interview Matcher（匹配不上时自动创建新叶子，类比 S1）

### 2.2 本模块依赖
| 依赖 | 用途 | 来源 |
|---|---|---|
| `LlmInvoker` | from-text + dup-check 调 LLM | S0 |
| `PromptService` | DB 取 `project/parse-text` + `project/dup-check` | S0 |
| `EmbeddingService` | 节点 embedding 生成（失败降级 NULL） | S0 |
| `KnowledgeNodeMapper` 风格 | 参考 S1 mapper 结构 | S1 |

### 2.3 下游消费（未来模块）
- **S7 Project Grilling**：选项目 → 选话题 → 选题，全靠 `project_node` 三层树
- **S8 Interview**：解析后做项目题 embedding 匹配；缺失时自动调 `ProjectAdminService.create()` 新增叶子

---

## 3. API 契约

路径前缀：`/api/admin/project-nodes`。**全部走 POST + body 传参**（含列表 / 含 id），遵 `java-style.md` + 与前端 OutlinerEditor 调用对齐。响应/请求字段名一律 **snake_case**（Spring Jackson 全局配置）。

| 方法 | 路径 | 入参 | 出参 (`data`) |
|---|---|---|---|
| GET  | `` | — | `[{id, parent_id, name, level, node_type, sort_order}, ...]`（按 level → sort_order → id） |
| POST | `` | `{parent_id?, name}` | `{id, name, level}` |
| POST | `/batch-sort` | `{updates:[{id, sort_order},...]}` | `{updated:N}` |
| POST | `/{id}/update` | `{name?, parent_id?, sort_order?, moving_parent?}` | `{id, name}` |
| POST | `/{id}/delete` | — | `{deleted:id}` |
| POST | `/from-text` | `{text}` | `{root_id, project_id, name, leaf_count}` |

外层统一 `ApiResponse{code, data, message}`。

### 3.1 错误码
| code | 场景 |
|---|---|
| 40001 | 参数校验失败（name 空 / 文本空 / 挂到自己下面） |
| 40400 | 资源不存在（节点 id / 父节点不存在） |
| 40901 | 项目名重复（精确同名 / LLM 语义命中） |
| 50000 | LLM 解析 3 次重试全失败 |

---

## 4. 业务规则

### 4.1 新建（`POST /`）
- 无 `parent_id` → `level=1, node_type='category'`
- 有 `parent_id` → `level = parent.level + 1`；`node_type = (level >= 3 ? 'leaf' : 'category')`
- **项目树固定 3 层硬限**：若 `level > 3` → 报 `40001`（禁止在 level=3 节点下再加子节点；防拖拉拽 / 手改 parent_id 破坏结构）
- **不做「父 leaf→category」自动升级**（与 S1 知识树差异）：项目树 nodeType 严格按 level 派生，与 Python `project_node.create_node` 一致
- 同步生成 embedding，文本 = "祖先链 / 当前 name"（失败 WARN + NULL，不阻断）

### 4.2 移动（`POST /{id}/update` + `moving_parent=true`）
- 新 `level = new_parent.level + 1`（无 parent→ 1）
- **项目树固定 3 层硬限**：取被移动子树的最深 level，计算「移动后最深 = newLevel + (子树原最深 - 节点原 level)」，若 > 3 → 报 `40001`
- 然后 `moveParent` + `shiftDescendantLevels`（一起平移 level 并按硬规则重写 node_type）

### 4.2 修改（`PUT /{id}`）
- `name / sortOrder` 用 COALESCE 合并
- `movingParent=true` 时改 `parent_id` 并按新父重算 `level` + `node_type`（硬规则）
- **不动 embedding**（改名不重算）

### 4.3 递归删除（`DELETE /{id}`）
```
Step 1: BFS 收集 nodeId 集合（自身 + 全部子孙）
Step 2: UPDATE interview_project_question SET project_node_id=NULL WHERE project_node_id IN (...)
Step 3: 自底向上 DELETE（叶子先）
Step 4: 若 parent 删后无剩余子节点 → parent.node_type = 'leaf'
Step 5: project.root_node_id 由 DB SET NULL（V1 声明的级联）
```

### 4.4 from-text（核心）
```
Step 1: 调 LlmInvoker(prompt=project/parse-text, vars={text})
        → JSON {name, children:[{name, children:[{name}]}]}（三层；中间层可为空但 parser 要兜底）
        重试 3 次
Step 2: dup-check（精确同名 + LLM 语义）
        - 精确：roots 列表 case-insensitive equals
        - 语义：LlmInvoker(prompt=project/dup-check, vars={new_name, existing_names})
          → JSON {duplicate:bool, matched_name?:str}
          LLM 失败 → WARN，保守放过
Step 3: 递归 saveTree(node, parentId, level)
        - INSERT project_node（sort_order=数组下标）
        - safeEmbed("祖先/...name") → embedding
Step 4: INSERT project（name=root.name, description=raw_text, root_node_id=root.id）
Step 5: commit
Step 6: 返 {rootId, projectId, name, leafCount}
```

---

## 5. Prompt（DB `prompt_template`，V15 seed）

| key | 用途 | 入参变量 | 输出 |
|---|---|---|---|
| `project/parse-text` | 把用户项目描述解析为三层树 | `text` | JSON 树 `{name, children:[...]}` |
| `project/dup-check` | 判定新项目名是否与已有项目语义重复 | `new_name`, `existing_names`（每行一个） | JSON `{duplicate:bool, matched_name?:str}` |

> 直接从 Python [backend/prompts/project_prompts.py](../../../backend/prompts/project_prompts.py) 翻译，保持语义一致。

---

## 6. 文件清单（计划）

### Java 新增
- `project/entity/ProjectNode.java` — record【按 S1 实际布局：按领域包，不是 admin/entity】
- `project/entity/Project.java` — record（项目元数据）
- `project/mapper/ProjectNodeMapper.java` — @注解 CRUD + `findAllOrdered` / `findRoots` / `insertWith[out]Embedding` / `updateSortOrder` / `moveParent` / `shiftDescendantLevels` / `deleteByIds`
- `project/mapper/ProjectMapper.java` — `insertReturningId`
- `project/mapper/InterviewProjectQuestionMapper.java` — 仅 `nullOutByNodeIds`
- `admin/dto/CreateProjectNodeReq.java` / `UpdateProjectNodeReq.java` / `ProjectNodeView.java` / `ProjectFromTextReq.java` / `ProjectFromTextResp.java`
- `admin/service/ProjectAdminService.java` + `impl/ProjectAdminServiceImpl.java`（合并 from-text 同一 Impl，避免拆二个 Service）
- `admin/controller/ProjectAdminController.java`
- `db/migration/V15__seed_project_prompts.sql`

### Java 修改
- 无（V1 schema 已就绪；S5 的 `PromptLoader` / `LlmInvoker` 直接复用）

### 前端（**复用已有，不新增文件**）
现有 React 实现已经覆盖项目树管理：

| 文件 | 职责 | S6 关心点 |
|---|---|---|
| [frontend-react/src/components/OutlinerEditor.jsx](../../../frontend-react/src/components/OutlinerEditor.jsx) | 通用幕布风格大纲编辑器，靠 `apiPrefix` prop 切换后端 | `apiPrefix="project-nodes"` 已用 |
| [frontend-react/src/pages/OutlinerPage.jsx](../../../frontend-react/src/pages/OutlinerPage.jsx) | 顶部 Tab 切「知识树 / 项目拷打」，项目 Tab 用 `placeholders=['项目名','话题','问题']`，调 `POST /project-nodes/from-text` | 调用契约见下表 |

**OutlinerEditor → 后端契约**（Java 必须实现完全一致的路径 + 字段）：

| 操作 | 前端调用 | Java 必须提供 |
|---|---|---|
| 列表 | `POST ${API_ADMIN}/project-nodes/list` body `{}` | `POST /api/admin/project-nodes/list` → `[{id, parent_id, name, level, node_type, sort_order}, ...]`（**snake_case**；Spring Jackson 全局配置已将 Java record 的 camelCase 字段自动序列化为 JSON snake_case） |
| 新建 | `POST ${API_ADMIN}/project-nodes/create` body `{parent_id, name}` | `POST /api/admin/project-nodes/create` → `{id, name, level}` |
| 改 | `POST ${API_ADMIN}/project-nodes/update` body `{id, name?, parent_id?, sort_order?, moving_parent?}` | `POST /api/admin/project-nodes/update`（id 必填，走 body） |
| 删 | `POST ${API_ADMIN}/project-nodes/delete` body `{id}` | `POST /api/admin/project-nodes/delete` |
| 排序 | `POST ${API_ADMIN}/project-nodes/batch-sort` body `{updates:[{id,sort_order}]}` | `POST /api/admin/project-nodes/batch-sort` |
| 文本建项 | `POST ${API_ADMIN}/project-nodes/from-text` body `{text}` | `POST /api/admin/project-nodes/from-text` |

> ⚠️ **注意 method**：前端 OutlinerEditor 改/删/排序**全部用 POST**（不是 RESTful 的 PUT/DELETE），与 S1 KnowledgeAdminController 保持一致。§3 表格里的 PUT 需修正为 POST。

> Python 老前端走 `react-router-dom 7` 的 `/admin/:tab` 路由（`tab=tree|project`），Java 后端只要把对应 6 个端点实现完整即可，不用动前端代码。

---

## 7. 验收清单

> **状态**：✅ 已实现 + 验证通过（下表完成）

- [x] Flyway V15 启动自动 seed 2 个 prompt（`project/parse-text` / `project/dup-check`）
- [x] `POST /api/admin/project-nodes/create` 新建根 → level=1, node_type=category
- [x] 在根下新建 → level=2, node_type=category
- [x] 在 level=2 下新建 → level=3, node_type=leaf（硬规则）
- [x] 在 level=3 下再新建 → 报 `40001`（项目树固定 3 层硬限，防拖拉拽破坏结构）
- [x] `POST /{id}/update` 改名 + 排序 + 移父（带 moving_parent）均生效（包含子树 level 平移 + nodeType 重评）
- [x] `moving_parent` 后子树最深 level > 3 → 报 `40001`（例：把 level=2 子树挂到 level=3 leaf 下）
- [x] `POST /batch-sort` 一次更新多条
- [x] `POST /{id}/delete` 递归删；删前 `interview_project_question.project_node_id` 置 NULL；删后 `project.root_node_id` 由 DB 自动 SET NULL
- [x] `POST /from-text` 解析 + 去重 + 双表写入（实验项目「电商订单系统」：root_id=11 + project_id=1 + 18 leaves）
- [x] `from-text` 同名重复 → 40901（「电商订单系统」重复报错验证）
- [x] `from-text` LLM 语义重复 → 40901（「商品订单管理平台」判定为同一项目验证）
- [x] 全部节点（含 level=1/2/3）`embedding IS NOT NULL`（DashScope 正常时：25/25）
- [x] embedding 单点失败不阻断整树（代码路径验证：safeEmbed catch all exceptions → null 走 insertWithoutEmbedding）
- [x] 所有响应 `{code:0, data, message}`
- [x] 前端 `/admin/project` Tab 直接可用（列表、新建、改、删、排序、from-text 6 个动作端到端通），不动前端代码

---

## 8. 未做（明确推迟）

- 改名后重算 embedding
- `project` 表的独立编辑端点（tech_stack / role / highlights）
- Multi-user 隔离（一期 user_id=1）
- `interview_project_question` 的 mapper 拆独立模块（Interview 模块未来再建，本期临时塞 admin）
