# S5 — 知识树 LLM 生成模块（一期：from-text + from-generate）

> **范围**：Admin 端两个 LLM 驱动的"造树"端点 —— 从 Markdown 文本解析、从需求描述生成。**不含**多模态（from-mm、from-image）、optimize、merge —— 这些都推迟到二期。
> **对应模块**：原 [JAVA_REWRITE_PLAN.md §2.2](../../../docs/JAVA_REWRITE_PLAN.md)。
> **Python 对照**：[backend/api/admin/tree_gen.py](../../../backend/api/admin/tree_gen.py) + [backend/services/tree_gen.py](../../../backend/services/tree_gen.py)
> **状态**：✅ Scope A 完成（from-text + from-generate + 两层去重）

---

## 1. 使用的表

| 表 | 操作 | 备注 |
|---|---|---|
| `knowledge_node` | INSERT（递归整树）+ UPDATE（`sort_order` 回填）| 复用 S1 的 `KnowledgeNodeMapper` |
| `"user"` | SELECT `profile_text` WHERE id=1 | 一期写死 user_id=1（多用户期再加列） |

---

## 2. 与其他模块的交互

### 2.1 对外提供
- `POST /api/admin/trees/from-text`：从 Markdown / 缩进文本解析并落库
- `POST /api/admin/trees/from-generate`：从树名 + 需求描述 → LLM 生成整棵树并落库

返回结构：`{ rootId, name, leafCount }`，统一包在 `ApiResponse.success(...)` 里。

### 2.2 依赖（上游）
| 依赖 | 用途 |
|---|---|
| `KnowledgeNodeMapper.insertReturningId(...)` | 递归插入每个节点 |
| `KnowledgeNodeMapper.updateSortOrder(id, sort)` | 插入后回填同级序号（INSERT SQL 内 sort 写死为 0） |
| `KnowledgeNodeMapper.findRoots()` *(新增)* | 取所有根节点，做同名 / 语义去重 |
| `UserMapper.findProfileText(1L)` *(新增)* | 取用户画像，注入 from-generate 的 Prompt |
| `EmbeddingService.embed(text)` | 节点向量，**单点失败降级为 NULL，不阻断整树** |
| `LlmChatService.chat(prompt, temperature, scene)` | LLM 调用（3 次重试） |
| `JsonUtil.extractJson(...)` | 容错解析 LLM 输出（提取首个 ```json``` 段或裸 JSON） |
| `PromptLoader.render(path, vars)` *(新增)* | 加载 `classpath:prompts/tree/*.txt`，`{key}` 占位符替换 |

### 2.3 下游消费
- 写完后的 `knowledge_node` 整棵树即被 S2 查询接口 / S3 Study 闭环消费。

---

## 3. 核心流程

### 3.1 from-text
```
POST /api/admin/trees/from-text  { "text": "<markdown>" }
  ↓
读 prompts/tree/parse-text.txt → 渲染 {text} → LLM (温度 0.3)
  ↓
JsonUtil.extractJson → TreeNodeJson（递归 Record，含 name / interview_weight / children）
  ↓
checkDuplicateByName(rootName)  ← 同名精确 + LLM 语义两层
  ↓ 通过
saveRecursive(rootJson, parentId=null, level=1, ancestors=[])
  ├ insertReturningId（sort_order=0）→ id
  ├ updateSortOrder(id, sort)  ← 回填真实序号
  ├ safeEmbed("祖先1 / 祖先2 / 当前name") → embedding（失败 null）
  └ 递归 children
  ↓
返回 { rootId, name, leafCount }
```

### 3.2 from-generate
同上，区别：
- Prompt 模板 `prompts/tree/generate.txt`，注入 `{tree_name}`、`{requirements}`、`{profile_text}`
- 温度 0.6（生成场景）
- 走完同样的去重 + 递归落库

### 3.3 两层去重（`checkDuplicateByName`）
```
Step 1 — 精确同名（case-insensitive equals）→ 命中即 throw BizException 40901
Step 2 — LLM 语义判定：
  喂 prompts/tree/duplicate-check.txt（{new_name} + {existing_names}）
  期望输出 { "duplicate": bool, "matched_name": str }
  命中即 throw BizException 40901
  LLM 调用失败 → WARN 日志，不阻断（保守放过）
```

---

## 4. 关键约束 / 陷阱

1. **embedding 文本含父路径**：`String.join(" / ", ancestors) + " / " + name`，避免"Set"被 Redis Set / JS Set 混淆。
2. **sort_order 两步走**：`INSERT` SQL 中 sort 写死 0，递归循环里再 `updateSortOrder(childId, i)`。避免改 Mapper 接口。
3. **embedding 单点失败不阻断**：`safeEmbed` 捕获全部异常返回 null，让 DashScope 抖动不影响整棵树。
4. **LLM 3 次重试**：全失败抛 `BizException 50000`。
5. **`user` 是 PG 保留字**：Mapper SQL 里必须 `"user"` 双引号。
6. **Prompt 占位符是单大括号 `{key}`**：与 Python f-string 不同，无需 `{{` 转义。
7. **多用户预留**：一期 `DEFAULT_USER_ID=1L` 写死；多用户期再从 SecurityContext 取。

---

## 5. 文件清单

### 新增
- `admin/dto/CreateTreeFromTextReq.java`
- `admin/dto/CreateTreeFromGenerateReq.java`
- `admin/dto/TreeGenResp.java`
- `admin/dto/TreeNodeJson.java`（递归 Record，含 `@JsonProperty("interview_weight")`）
- `admin/controller/TreeGenController.java`
- `admin/service/TreeGenService.java`（接口）
- `admin/service/impl/TreeGenServiceImpl.java`（含嵌套 `SaveResult` / `DuplicateCheckResult` Record）
- `prompts/PromptLoader.java`（`@Component`，`ConcurrentHashMap` 缓存）
- `user/mapper/UserMapper.java`（一个方法：`findProfileText`）
- `resources/prompts/tree/parse-text.txt`
- `resources/prompts/tree/generate.txt`
- `resources/prompts/tree/duplicate-check.txt`

### 修改
- `knowledge/mapper/KnowledgeNodeMapper.java`：新增 `findRoots()`

---

## 6. 验证记录

```bash
# 1) from-text（Markdown 解析）
curl -X POST localhost:8080/api/admin/trees/from-text \
  -H "Content-Type: application/json" \
  -d '{"text":"# Java集合框架\n## List 接口\n- ArrayList\n- LinkedList\n## Map 接口\n- HashMap\n- ConcurrentHashMap"}'
# → {"code":0,"data":{"rootId":1,"name":"Java集合框架","leafCount":4},"message":"success"}

# 2) from-generate（LLM 造树）
curl -X POST localhost:8080/api/admin/trees/from-generate \
  -H "Content-Type: application/json" \
  -d '{"treeName":"Redis","requirements":"覆盖3年后端面试高频考点"}'
# → {"code":0,"data":{"rootId":53,"name":"Redis","leafCount":58},"message":"success"}

# 3) 精确同名去重
curl -X POST localhost:8080/api/admin/trees/from-generate -d '{"treeName":"Redis","requirements":""}'
# → {"code":40901,"message":"已存在同名知识树「Redis」，请更换名称或合并"}

# 4) LLM 语义去重
curl -X POST localhost:8080/api/admin/trees/from-generate -d '{"treeName":"Redis面试知识点","requirements":""}'
# → {"code":40901,"message":"与已有知识树「Redis」语义重复，请更换名称或合并"}
```

---

## 7. 二期 TODO（未实现）

| 端点 | Python 路径 | 优先级 | 说明 |
|---|---|---|---|
| `POST /from-mm` | 多模态（文+图） | 中 | 需要图片上传链路 |
| `POST /from-image` | 纯图片 OCR | 低 | 同上 |
| `POST /optimize` | 树结构优化 | 中 | LLM 重组层级 / 剪枝 |
| `POST /merge` | 合并两棵树 | 低 | 冲突解决策略待定 |
