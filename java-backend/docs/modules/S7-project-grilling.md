# S7 — Project Grilling 项目拷打模块

> 状态：✅ v1 已实现（S7.1 - S7.4）→ 🔄 **v2 重设计中**（见 §8，把 study 同款状态机换成"面试官自由追问"模式）
> 依赖：S0（基础设施）/ S3（Study attempt — v1 复用，v2 分离）/ S6（项目树 admin — 题目数据源）
> Python 对照：[backend/api/project_grilling/](../../../backend/api/project_grilling/) + [backend/services/project_grilling/](../../../backend/services/project_grilling/) + `services/project_qa_strategy.py` + `services/project_profile.py` + `services/qa_aggregate.py`

> ⚠️ **§1 - §7 描述的是 v1 实现**（即 study 同款 5 条硬规则状态机）。v1 的核心问题：项目场景没有"标准答案"，covered / mastery / horizontal / deep_dive 这套语义是错位的。**v2 见 §8**，v2 上线后 §1 - §7 中与状态机相关的内容会改写。

---

## 1. 目标

把"用户对自己项目的真题"以**多轮拷打**的方式过一遍：选项目 → 选话题 → 选题 → 主问 + 至多 4 轮追问 → 综合打分 → **异步抽取项目画像**累积到下次拷打使用。

与 Study 的关键差异（不是简单复用）：
1. **prompt 关注设计深度 / 量化指标**，不是知识点覆盖
2. **prompt 注入项目画像**（`project_facts` + `weak_points`），让 LLM 越问越精准
3. **finish 后 fire-and-forget** 调 `ProjectProfileService.extractAndApply` 更新画像（乐观锁 + 重试 3 次）
4. **finish 输出多两个字段**：`design_issues`（设计缺陷）+ `extension_qa`（延伸 Q&A）
5. **分数聚合**多一层：question 分 → topic 分 → project readiness（项目准备度）

---

## 2. 复用 vs 新增

### 2.1 直接复用（不改）

| 资源 | 来源 | 复用方式 |
|---|---|---|
| `question_attempt` 表 | V1 schema | 多态使用 `question_type='project'` |
| `QuestionAttempt` record | `study/entity` | 字段已含 `designIssues` / `extensionQa` |
| `Project` / `ProjectNode` | `project/entity` (S6) | 直接读 |
| `LlmInvoker` / `JsonUtil` / `JsonbTypeHandler` | infra | 调 LLM + JSONB 序列化 |
| `PromptRepository` | `prompts/` | DB-first prompt 加载 |

### 2.2 新增 / 修改

| 文件 | 类型 | 说明 |
|---|---|---|
| `project/entity/ProjectUserProfile.java` | 新增 | record，对应 V1 表 |
| `project/mapper/ProjectUserProfileMapper.java` | 新增 | findByProjectUser / upsertEmpty / updateWithLock |
| `project/service/ProjectGrillingService.java` + Impl | 新增 | 9 端点编排 |
| `project/service/ProjectQaStrategy.java` | 新增 | per-turn / final-score 调用 + 解析 |
| `project/service/ProjectProfileService.java` + Impl | 新增 | 画像 CRUD + 异步抽取 + 乐观锁 |
| `project/service/ProjectScoreAggregateService.java` + Impl | 新增 | question/topic/readiness 三级聚合 |
| `project/controller/ProjectGrillingController.java` | 新增 | 9 端点路由 |
| `project/dto/*.java` | 新增 | ~12 个 DTO record |
| `db/migration/V16__seed_grilling_prompts.sql` | 新增 | 3 个 prompt（per_turn / final_score / extract_profile） |
| `prompts/PromptKeys.java` | 修改 | + 3 个 key |
| `Application.java` | 修改 | 加 `@EnableAsync`（fire-and-forget 用） |
| `study/mapper/QuestionAttemptMapper.java` | **不改** | study 用 study 的；project 自己写一个 mapper（见 §3.1） |

---

## 3. 设计决策（讨论点 — 实现前要拍板）

### 3.1 question_attempt 是 study 一份 mapper、project 再一份？还是泛化？

`question_attempt` 是多态表，study 已经写了 `QuestionAttemptMapper`，里面绝大多数 SQL 写死了 `question_type='study'`。Project 怎么办：

| 选项 | 说明 | 优 | 劣 |
|---|---|---|---|
| **A. 各自一份 Mapper** | 在 `project/mapper/` 写 `ProjectAttemptMapper`，SQL 写死 `'project'` | 类型安全；命名清晰；与现有 study 风格一致；改动孤立 | 有 ~30 行 SQL 与 study 雷同 |
| B. 泛化原 mapper 加 type 参数 | `findInProgress(userId, questionType, questionId)` etc. | DRY | 失去 SQL 层的类型守卫；调用方容易传错 type 字符串；S3 已经稳定的代码被波及 |
| C. 抽 BaseAttemptMapper + 子类 | 用 MyBatis 继承 | 学院派 | MyBatis 注解 mapper 的继承不天然，得加 XML / interface default method，过度抽象 |

**推荐 A**：保持现有 study mapper 不动，新增 `ProjectAttemptMapper`，复制少量 SQL（变化点：`question_type` 字面量 + insert 时 'project'）。代码冗余 ≈ 30 行，换来清晰边界。

---

### 3.2 attempt 流程 — 复用 study 的 service 还是独立？

Study 已有 `StudyAttemptServiceImpl`，里面有 5 条 follow_up_type 状态机校正、in_progress 唯一守卫、幂等 finish。Project 的流程几乎一样，**但**有几个不能复用的点：

- **prompt 注入项目画像**（study 没有）
- **finish 收尾钩子不同**（study 刷 KP mastery；project 触发画像抽取）
- **finish 落库字段不同**（project 多 `design_issues` / `extension_qa`）
- **mapper 不同**（study mapper hardcode 'study'）

| 选项 | 说明 |
|---|---|
| A. 抽 `AttemptOrchestrator` 接口 + 两个子实现 | DRY，但要把 strategy / mapper / aggregate 都改成 SPI 注入 |
| **B. 各写一份 service** | 复用 ~30 行的状态机硬规则，可接受 |

**推荐 B**：两套总代码 ~600 行 vs 抽象成本 ~150 行，不划算。Python 那边因为是动态语言、protocol 抽象代价低才用 Strategy；Java 这种规模直接复制更直白。

---

### 3.3 异步画像抽取：Spring 怎么 fire-and-forget？

Python 用 `asyncio.create_task(profile_svc.extract_and_apply(...))` + 独立 DB session。Java 选项：

| 选项 | 说明 |
|---|---|
| **A. `@Async` + `TaskExecutor`** | 在 Application 加 `@EnableAsync`；ProfileService.extractAndApply 标 `@Async`，方法返 `void`。最简单。**事务隔离自动满足**：@Async 方法在新线程跑，自带新的事务边界（默认 REQUIRES_NEW 或自然新事务） |
| B. `CompletableFuture.runAsync` | 显式提交到线程池，调用方写更繁琐 |
| C. `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` | 事务提交后才触发，最稳但配置复杂 |

**推荐 A**：方法签名自然、与现有代码风格一致；finish 事务提交后画像表的 `WHERE version=?` 才能读到最新版本（@Async 默认提交后再开始，不会读到脏数据）。

⚠️ 注意：@Async 必须从 **另一个 bean** 调用才生效（同 bean 内调用走代理失效）。所以 `extractAndApply` 必须在 `ProjectProfileServiceImpl`，由 `ProjectGrillingServiceImpl.finish()` 跨 bean 调用。

---

### 3.4 乐观锁怎么落

Python：`UPDATE ... WHERE id=? AND version=?` + 检查 `rowcount==1`，最多重试 3 次，每次重读 profile。

Java 完全同款：

```java
for (int retry = 0; retry < MAX_RETRY; retry++) {
    ProjectUserProfile p = profileMapper.findByProjectUser(projectId, userId);
    Map<String, Object> patch = callExtractLlm(...);  // 见 §3.5
    Object newFacts = applyFactsPatch(p.projectFacts(), patch);
    Object newWeak = applyWeakPointsPatch(p.weakPoints(), patch);
    int affected = profileMapper.updateWithLock(p.id(), p.version(), newFacts, newWeak);
    if (affected == 1) return;     // 成功
    log.info("ProjectUserProfile {} 版本冲突，重试 {}", p.id(), retry + 1);
}
log.warn("extract_and_apply 在 {} 次重试后仍失败 (project={})", MAX_RETRY, projectId);
```

`updateWithLock` SQL：

```sql
UPDATE project_user_profile
SET project_facts = #{facts,typeHandler=...JsonbTypeHandler,jdbcType=OTHER},
    weak_points = #{weakPoints,typeHandler=...JsonbTypeHandler,jdbcType=OTHER},
    version = #{oldVersion} + 1,
    updated_at = NOW()
WHERE id = #{id} AND version = #{oldVersion}
```

---

### 3.5 patch 合并的 4 个纯函数

Python 在 `services/project_profile.py` 里有 `_apply_facts_patch` + `_apply_weak_points_patch`。逻辑：
- **facts**：先 update（按 old 原文匹配并替换）→ 再 remove（按原文）→ 再 add（去重，超 50 条丢前面）
- **weak_points**：先按 resolved 列表删 → 加 add（按 point 文本去重，超 20 条丢前面）

Java 移到 `ProjectProfileServiceImpl` 的 private static 工具方法里（List<String> facts / List<Map<String,Object>> weakPoints）。**不动 Python 现有规则**，照搬。

---

### 3.6 dimensions / readiness 聚合用哪个 service？

Study 已有 `ScoreAggregateService` 计算 KP mastery。Project 需要：
- `questionScore(leafId)` — 该 L3 叶子最近 N=3 次平均
- `topicScore(topicId)` — 该话题下所有 L3 平均（未答按 0）
- `projectReadiness(projectId)` — 项目下所有 L2 话题的话题分平均

| 选项 | 说明 |
|---|---|
| A. 加到 `ScoreAggregateService` | 单一聚合接口 |
| **B. 新建 `ProjectScoreAggregateService`** | 按模块切分 |

**推荐 B**：模块化清晰，job 分摊不挤一处。命名 `ProjectScoreAggregateService` 与 study 侧的 `ScoreAggregateService` 形成对称。

---

### 3.7 path variable vs body

`java-style.md "API 形式"` 明确规定**全 POST + body，id 禁用 `@PathVariable`**。Study controller 已走同样风格（`/attempt-start /attempt-turn /attempt-detail`）。Project Grilling 一致采用 POST + body。

前端影响：`frontend-react/src/pages/ProjectGrillingPage.jsx` 当前 4 个读接口用的是 GET path-variable（从 Python 版本继承）。**本模块一併改为 POST + body**。

路径命名采 study 体例（`/<资源>-<动作>`）：
- `POST /api/project-grilling/projects-list`  body `{}`
- `POST /api/project-grilling/profile-detail` body `{project_id}`
- `POST /api/project-grilling/dimensions-list` body `{project_id}`
- `POST /api/project-grilling/topic-questions` body `{topic_id}`
- `POST /api/project-grilling/attempt-start`   body `{question_id}`
- `POST /api/project-grilling/attempt-turn`    body `{attempt_id, user_answer}`
- `POST /api/project-grilling/attempt-finish`  body `{attempt_id}`
- `POST /api/project-grilling/attempt-detail`  body `{attempt_id}`
- `POST /api/project-grilling/attempts-history` body `{question_id}`

---

## 4. 端点详表（全 POST + body）

| 路径 | 入参 body | 出参核心字段 |
|---|---|---|
| `/projects-list` | `{}` | `[{id, name, description, tech_stack, role, highlights, real_question_count, readiness_score}]` |
| `/profile-detail` | `{project_id}` | `{project_facts:[], weak_points:[], version}` |
| `/dimensions-list` | `{project_id}` | `[{id, name, question_count, attempt_count, avg_score}]` |
| `/topic-questions` | `{topic_id}` | `{topic_id, topic_name, questions:[{id, content, score, attempt_count}]}` |
| `/attempt-start` | `{question_id}` | `{attempt_id, question_content, topic_name, dialog, current_step, max_steps}` |
| `/attempt-turn` | `{attempt_id, user_answer}` | `{recommended_answer, follow_up_type, follow_up_question, current_step, max_steps, dialog, can_finish}` |
| `/attempt-finish` | `{attempt_id}` | `{final_score, rubric_result, overall_summary, design_issues, extension_qa, dialog}` |
| `/attempt-detail` | `{attempt_id}` | 完整快照（含 dialog） |
| `/attempts-history` | `{question_id}` | `[{attempt_id, status, final_score, dialog, ...}]`（倒序） |

---

## 5. 实现顺序（建议 4 步）

| 步骤 | 范围 | 验收 |
|---|---|---|
| **S7.1** 只读 | 4 个 GET（projects / profile / dimensions / topic-questions）+ 实体 + mapper + 聚合 service | curl 4 个 GET 都 code:0 |
| **S7.2** Attempt 三件套（同步） | start / turn / finish + ProjectQaStrategy（含画像 block 注入）+ ProjectAttemptMapper + V16 prompt seed | 完整跑通 1 题：start → 4 轮 turn → finish，dialog 落库正常，rubric_result/design_issues/extension_qa 都有 |
| **S7.3** 异步画像 | ProjectProfileService.extractAndApply（@Async + 乐观锁重试） | finish 后查 profile 表，project_facts/weak_points 在 1-2s 内更新；强行模拟版本冲突看到重试日志 |
| **S7.4** 历史 + 联调 | `/attempt-detail` + `/attempts-history` + 前端 ProjectGrillingPage 9 处 fetch 改为 POST+body 端到端走通 | 浏览器点项目 → 选题 → 答题 → finish → 看到画像更新 |

---

## 6. 边界与硬约束

| 约束 | 值 | 来源 |
|---|---|---|
| 一题最多追问轮数 | 4（主问 + 4 追问 = 5 轮） | `DEFAULT_MAX_FOLLOW_UPS` Python 一致 |
| 同 user + question 最多 1 个 in_progress | DB 守卫 | start 端点拒绝 |
| MAX_FACTS | 50 | 超 50 条丢前面（保留最新） |
| MAX_WEAK_POINTS | 20 | 同上 |
| 乐观锁 MAX_RETRY | 3 | 超过则放弃本轮抽取 |
| user_id | 写死 1L | 一期单用户 |
| LLM 温度 | per-turn 0.2 / final 0.1 / extract 0.2 | 与 Python 对齐 |
| LLM max_tokens | per-turn 2048 / final 3072 / extract 2048 | 与 Study 类似 |

---

## 7. 风险与未决

| 风险 | 影响 | 缓解 |
|---|---|---|
| @Async 测试不易 | 单测难 mock 异步线程行为 | 集成测试 + 等待 1-2s 后查 DB；或注入 `SyncTaskExecutor` |
| LLM 出 patch 格式不规范 | 画像可能不更新 | `JsonUtil.extractJson` 已经容错；失败就直接放弃本轮 |
| version 一直冲突 | 画像更新不上 | 重试 3 次后放弃；下一次拷打会重新抽取 |
| 现有 V1 schema 中有 `project_session` / `project_session_message` 表（Python 历史包袱） | 这两张表 Java 侧不再写入 | **保留表不动**，但 Java 不引用，新数据走 question_attempt |

---

## 8. v2 状态机重设计：面试官模式

> 此节是 v1 (§1–§7) 的**替换版**，不是补丁。v2 上线后 `ProjectAttemptServiceImpl.turn()` / `ProjectQaStrategy.perTurn()` / per-turn prompt / 前端 ConversationView 项目分支都要重写。
> 触发原因：v1 直接复用 Study 的「covered / mastery / horizontal / deep_dive」5 条硬规则，但项目场景**没有标准答案**，这套规则语义全部错位（见 §8.1 表）。

### 8.1 设计原则

> Agent 是**面试官**，不是阅卷老师。目的是通过对话**还原项目真相 + 暴露漏洞**，帮用户准备面试。

| v1 规则 | 在 Study 里的语义 | 在项目场景**为什么错** | v2 的处理 |
|---|---|---|---|
| `covered=true/false` | 是否命中 rubric 标准答案 | 项目没标准答案 | 删除；改为 `signals.clarity`（不展示）|
| `mastery=high/mid/low` | 对知识点的掌握度 | 项目场景是"事实清不清楚"不是"会不会" | 删除；改为 `signals.credibility`（不展示）|
| `horizontal` 一次封顶 | 漏点提醒一次性补全 | 项目里多次发现漏洞才正常 | 删除分类，自由追问 |
| `deep_dive` 仅 `mastery=high` | 答得好才深挖 | **反了**——答得模糊更需要追问搞清楚 | 删除 |
| `MAX_FOLLOW_UPS=4` 硬上限 | 知识点判定够了就行 | 项目深聊需要更多轮 | 改为 **6 轮硬上限**，允许同一问题换种问法重问 |

### 8.2 turn 输出契约（LLM JSON schema v2）

LLM per-turn prompt 必须输出以下结构：

```jsonc
{
  // —— 面试官点评，自然语言一段话（不是 bullets）——
  "interviewer_note": "听起来你们用了 lazy load 配合接口拆分，目标是降首屏。我比较关心两点：一是骨架数据缓存策略没说更新机制；二是 70% 命中率口径是不是只统计了非编辑场景。",

  // —— 本轮发现的漏洞，结构化用于累加进画像 weak_points ——
  "gaps_found": [
    {"category": "缓存策略", "point": "Redis 30s TTL 没说主动失效机制"},
    {"category": "数据口径", "point": "70% 命中率统计场景不明确"}
  ],

  // —— 内部信号，仅用于 LLM 自身下轮决策；后端落库但前端不渲染 ——
  "signals": {
    "clarity":     "clear" | "vague" | "unclear",
    "credibility": "solid" | "doubtful" | "fishy"
  },

  // —— 下一步：next_question 与 wrap_up_reason 互斥 ——
  "next_question":   "你刚提到 70% 命中 skeleton，剩下 30% 走 detail 的 P99 是多少？" 或 null,
  "wrap_up_reason":  null 或 "已挖到 3 个关键漏洞，事实清楚"
}
```

后端**唯一硬兜底**：`follow_up_count >= 6` → 强制 `next_question = null`；其他全交 LLM 自决。

### 8.3 dialog 项形态（DB schema 兼容）

v1 一轮追加 2 条（feedback + follow_up）；**v2 保留此分离**（用户偏好：评价 / 追问视觉上分两块，评价默认折叠）。字段语义变化：

```jsonc
// agent feedback 项（v2）— 前端默认折叠
{
  "role": "agent",
  "type": "feedback",
  "note": "听起来你们用了 lazy load…",         // ← 原 content，改字段名以区别 study 的 recommended_answer
  "gaps_found": [ {category, point}, … ],     // ← 新增
  "signals":    { clarity, credibility }      // ← 新增，前端不渲染
}

// agent follow_up 项（v2）— 与 v1 兼容，但删除 follow_up_type
{
  "role": "agent",
  "type":  "follow_up",
  "content": "你刚提到 70% 命中 skeleton，剩下 30% 走 detail 的 P99 是多少？"
  // follow_up_type 字段删除（不再分类）
}
```

> DB 层 `question_attempt.dialog` 是 JSONB，无 schema migration；只是项内 schema 变。
> 老的 v1 attempt 历史（type=feedback 且无 note 字段）前端按 v1 渲染保持兼容；新 attempt 按 v2 渲染。

### 8.4 prompt 设计要点

新 prompt key：`project/per-turn-v2`（v1 的 `project/per-turn` 保留作回退）。要点：

1. **角色**：明确"你是资深技术面试官，正在拷打候选人讲他自己做过的项目"。
2. **没有标准答案**：禁止生成"标准答法"、"应该这样答" — 改成"我的疑虑 / 我会怎么追问"。
3. **画像注入**：把 `project_facts` + `weak_points` 拼到 prompt 头部（与 v1 同），LLM 据此避免重复挖已知漏洞。
4. **gaps_found 增量原则**：本轮只输出**本轮新发现**的漏洞，不要把对话里所有历史漏洞重新列一遍（防 token 爆 + 重复）。
5. **wrap_up 自决**：什么时候 LLM 应该结束？prompt 给出指引：
   - 事实已经清楚（5W1H 都问过了）
   - 已经挖到 ≥3 个关键漏洞
   - 用户思路充分展示，再问只是重复
6. **重问的容忍度**：明确告诉 LLM——如果觉得用户没听懂，可以换一种说法再问同一个点；这不算"重复"。
7. **最后一轮特殊提示**（current_step == max_steps - 1）：prompt 末尾追加 `"⚠️ 这是最后一轮追问，请把还想问的合并问完，本轮 next_question 不再有下次。"`。

### 8.5 信号字段为什么不展示

`signals.clarity / credibility` 故意**不给用户看**：

- 避免给用户"评分压力"——面试官不会当面说"你这话我觉得可疑"
- LLM 用这俩信号决定**这轮要不要继续挖**（vague → 倾向追问；fishy → 重点追问）
- 落库供调试 / 后续数据分析，但 controller response 不返回（DTO 不含该字段）

### 8.6 finish 输出 v2

```jsonc
{
  "dimensions": {
    "fact_clarity":   8,    // 事实清晰度 0-10
    "design_quality": 6,    // 设计合理性
    "depth":          7,    // 思考深度
    "communication":  9     // 表达能力
  },
  "final_score": 75,         // 加权 = round((fact_clarity*0.3 + design_quality*0.3 + depth*0.25 + communication*0.15) * 10)
  "overall_summary": "如果是真实面试官，整体印象是…",
  "design_issues":   [...],  // 累积的 gaps_found 提炼（去重 + 归类）
  "extension_qa":    [...]   // 延伸题（面试官可能继续问的）
}
```

变化：
- 删除 `rubric_result`（项目无 rubric）→ DB 字段仍保留但写 `null`
- `final_score` 从 LLM 直出 → **后端按权重计算**（避免 LLM 评分漂移）
- 权重写死在 service 里：`fact_clarity 0.30 / design_quality 0.30 / depth 0.25 / communication 0.15`

### 8.7 前端渲染

`ConversationView` / `AttemptHistoryPanel` / `AnswerInput` 加 `mode` prop：`"study"`（默认）| `"grilling"`。

**Grilling 模式 turn 渲染**（用户视角）：

```
┌────────────────────────────────────┐
│ 👤 你的回答                          │
│ 之前是全链路数据拉取，因为页面刚加载… │
├────────────────────────────────────┤
│ 👔 面试官点评                     ▾  │  ← 默认折叠，点击展开
│ （展开后显示 interviewer_note）       │
│                                    │
│ 🔍 本轮疑点                          │  ← 默认展示（这是用户关心的）
│ • [缓存策略] Redis 30s TTL 没说更新   │
│ • [数据口径] 70% 命中率统计场景不明确  │
├────────────────────────────────────┤
│ ❓ 追问                              │  ← 独立卡片，醒目
│ 你刚提到 70% 命中 skeleton，剩下 30%  │
│ 走 detail 的 P99 是多少？             │
└────────────────────────────────────┘
```

`signals` 不渲染。

### 8.8 实施步骤 S7.5（v1 → v2 切换）

| 步骤 | 范围 | 验收 |
|---|---|---|
| **S7.5.1** prompt 重写 | 新建 V17 migration seed `project/per-turn-v2` + `project/final-score-v2`；旧 prompt key 保留 | psql 查到两条新 prompt |
| **S7.5.2** Strategy 重写 | 新 `ProjectGrillingStrategy`（独立，不复用 ProjectQaStrategy） → 返回 `PerTurnV2` record（含 interviewerNote / gapsFound / signals / nextQuestion / wrapUpReason） | 单元跑通 LLM 调用 |
| **S7.5.3** Service.turn 重写 | `ProjectAttemptServiceImpl.turn()` 删除 5 条硬规则，只留 1 条 6 轮上限；dialog 项按 §8.3 写；最后一轮特殊 prompt | curl 一轮看 dialog 落库 |
| **S7.5.4** Service.finish v2 | `finish()` 权重计算 final_score；rubric_result 写 null；签名不变 | finish 后查 DB dimensions/score |
| **S7.5.5** DTO 调整 | `AttemptTurnResponse` 改字段（删 covered/mastery/followUpType，加 interviewerNote/gapsFound/wrapUpReason）；signals **不放进 DTO** | API 契约更新 |
| **S7.5.6** 前端 mode 分支 | ConversationView / AttemptHistoryPanel 加 `mode='grilling'` 分支；新 UI 按 §8.7；旧 v1 attempt 历史降级渲染 | 浏览器端到端 |
| **S7.5.7** 文档清理 | 把 §1 - §7 中状态机相关描述更新为指向 §8 | 文档自洽 |

### 8.9 决策记录（已拍板，避免反复）

| 项 | 决定 | 备注 |
|---|---|---|
| 追问是否分类 | **不分类**，LLM 自由 | 简化 prompt + 增加自然度 |
| 硬上限轮数 | **6** | v1 是 4，v2 多 2 轮容纳"换种问法重问" |
| 同一问题重问 | **允许** | 仅当 LLM 判断用户没听懂；不计入特殊处理 |
| 最终分形态 | **多维 + 加权总分** | 后端算权重，不交 LLM |
| 画像抽取时机 | **不变**，仍 finish 后异步抽 | 沿用 S7.3 链路 |
| 与 Study 复用度 | **彻底分离** | 新 `ProjectGrillingStrategy`，不再继承 `ProjectQaStrategy` |
| dialog 项是否合并 | **保留分离**（feedback + follow_up 两条） | 用户偏好：评价默认折叠，追问独立卡片 |
| signals 是否给用户 | **不给** | 避免评分压力 |
| 最后一轮特殊 prompt | **要** | prompt 末尾追加"这是最后一轮，合并问完" |
