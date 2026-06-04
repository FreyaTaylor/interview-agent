# S3 — Study 模块（学习闭环：选题 → 多轮答题 → 评分 → 派生掌握度）

> **范围**：在已有讲解 + 题目（S4 Learn 已懒生成）基础上，用户做题、LLM 评分、写回 `knowledge_node.mastery_level` 形成"以考代学"核心闭环。
> **对应模块**：[JAVA_REWRITE_PLAN.md §5](../../../docs/JAVA_REWRITE_PLAN.md)（实现顺序 S3，紧跟 S4 之后）。
> **Python 对照**：[backend/services/qa_engine.py](../../../backend/services/qa_engine.py)、[backend/services/study_qa_strategy.py](../../../backend/services/study_qa_strategy.py)、[backend/services/qa_aggregate.py](../../../backend/services/qa_aggregate.py)、[backend/prompts/qa_per_turn_prompt.py](../../../backend/prompts/qa_per_turn_prompt.py)、[backend/prompts/qa_final_score_prompt.py](../../../backend/prompts/qa_final_score_prompt.py)、[backend/api/study.py](../../../backend/api/study.py)。
> **状态**：✅ 已完成（V11 prompt seed + V13 per-turn 状态机版本）。

---

## 0. 关键决策（与 Python / 旧 PLAN 的差异）

| # | 决策 | 选项 | 理由 |
|---|---|---|---|
| 1 | **不做 `GET /knowledge-points` 推荐列表** | 搁置 | 一期前端从知识树点叶子直接进 Study；推荐排序后续再说 |
| 2 | **题目首生 = Learn ensureContent 时连带 + 独立 regenerate 端点** | A+C | ensureQuestions 已在 S4 Learn 中实现；S3 额外暴露 `POST /study/questions-regenerate` |
| 3 | **三端点状态机 start / turn / finish；turn 响应含本轮 rubric** | A+C | 与 Python 对齐；前端可即时高亮"哪些点答到了" |
| 4 | **评分实时写回 `knowledge_node.mastery_level`**（每次 finish） | B | 与 Python 一致；S2 知识树查询直接读 mastery_level 字段 |
| 5 | **per_turn prompt 输出 `{covered, mastery, feedback, hits[], follow_up_type?, follow_up_question?, can_finish}`** | A | 与 Python 对齐；hits 含 `{key_point, hit:bool, reason}` |
| 6 | **3-5 题/KP（默认 5），rubric 数组 `[{key_point, score}]` 总分 100** | 认同 | 与 Python 一致；已存于 `study_question.rubric_template` |
| 7 | **Study 完全独立，不注入 subtopic 到 prompt** | D | 题目 + reference_answer 已经够；避免 prompt 膨胀 |
| 8 | **追问决策状态机：horizontal + deep_dive** | 与 Python 完全对齐 | 一次封顶补漏 + 高掌握度持续深挖；详见 §4.4 |
| 9 | **MAX_FOLLOW_UPS = 4**（主问 + 4 追问 = 5 轮） | 沿用 | 与 Python `DEFAULT_MAX_FOLLOW_UPS` 一致 |
| 10 | **同题同用户最多 1 条 `in_progress`** | 沿用 | 重复开启 → 400；前端应先 finish 或继续作答 |

---

## 1. 使用的表（全部已在 V1 建好，不新增表）

| 表 | 操作 | 关键字段 |
|---|---|---|
| `knowledge_node` | UPDATE `mastery_level` / `study_count` | 写回 KP 掌握度 |
| `study_question` | 只读 | `content` / `rubric_template`(JSONB `[{key_point, score}]`) / `recommended_answer`(JSONB) |
| `question_attempt` | INSERT / UPDATE | `status` ∈ {in_progress, finished, abandoned}、`dialog` JSONB、`rubric_result` JSONB、`final_score`、`follow_up_count` |
| `prompt_template` | 只读（通过 `LlmInvoker`） | V11 seed `study/per-turn` + `study/final-score`；V13 重写 `study/per-turn` 为含状态机约束版本 |

### 1.1 `question_attempt.dialog` 结构（与 Python 一致）

```jsonc
[
  {"role": "agent", "type": "question",  "content": "主问题..."},
  {"role": "user",  "type": "answer",    "content": "用户回答..."},
  {"role": "agent", "type": "feedback",  "content": "范例 / 提示",
    "hits": [...], "covered": false, "mastery": "mid"},
  {"role": "agent", "type": "follow_up", "content": "追问...",
    "follow_up_type": "horizontal"},
  {"role": "user",  "type": "answer",    "content": "..."},
  ...
]
```

关键字段：
- `feedback.covered` / `feedback.mastery`：本轮 LLM 评估结果，供前端展示 & 下轮重算状态机用
- `follow_up.follow_up_type`：取值 `"horizontal"` | `"deep_dive"`；下轮 turn 据此计算 `prior_follow_up_types`

### 1.2 `rubric_result` 结构（finish 时写）

```jsonc
{
  "hits": [
    { "key_point": "...", "score_full": 30, "score_got": 25, "reason": "..." }
  ],
  "missed_key_points": ["..."],
  "design_issues": ["可选：项目题专用，study 留空"]
}
```

### 1.3 不新增表，仅靠 Flyway V11 加 2 个 prompt seed

```sql
-- V11__seed_study_prompts.sql
INSERT INTO prompt_template (key, version, template_md) VALUES
  ('study/per-turn',    1, '...'),
  ('study/final-score', 1, '...')
ON CONFLICT (key, version) DO UPDATE SET template_md = EXCLUDED.template_md;
```

---

## 2. 与其他模块的交互

### 2.1 本模块对外提供
- `ScoreAggregateService.scoreByQuestion(qid)`：题目分（最近 3 次 finished 平均）
- `ScoreAggregateService.masteryByKp(kpId)`：KP 掌握度（该 KP 下**所有**题目的题目分均值，未答题计 0）
- 这两个方法供 S2 知识树查询（按需）/ 未来 S7 项目拷打复用

### 2.2 本模块依赖
| 依赖 | 用途 | 来源 |
|---|---|---|
| `LlmInvoker` | 调 LLM + 重试 + JSON 提取 | S0/common |
| `PromptService` | DB 取 prompt 模板 | S0/prompts |
| `KnowledgeNodeMapper.findById` + `updateMastery(kpId, mastery, count)` | 读 KP 名称 / 写 mastery | S1（mastery 写法是新增） |
| `StudyQuestionMapper.findById` / `findByKp` | 取题目 + rubric_template | S4 Learn（已存在） |
| `LearnContentService.ensureContent` | regenerate-questions 前确保 KP 存在 | S4 Learn |

### 2.3 下游消费
- 前端 `StudyPage`：start/turn/finish 三步走，渲染 dialog + 实时 rubric 命中
- S2 知识树查询：mastery_level 字段由 S3 实时写回（S2 只读）

---

## 3. API 契约

本模块共 **5** 个端点，全部 POST + 动作后缀（遵 `java-style.md` API 形式约束），body 传参，snake_case JSON。

> **注意**：题目拉取与重生成（`POST /api/learn/questions`）位于 **Learn 模块**，不在本模块。Study 只管 attempt 生命周期。

### 3.1 `POST /api/study/attempt-start`
开始一次作答。

**Request**
```jsonc
{ "question_id": 9001 }
```

**Response**
```jsonc
{
  "attempt_id": 70001,
  "question": "解释为什么重写 equals 必须重写 hashCode。",
  "dialog": [
    { "role": "agent", "type": "question", "content": "解释为什么重写 equals 必须重写 hashCode。" }
  ],
  "max_steps": 5,           // 主问 + 4 追问
  "current_step": 1
}
```

**约束**：同用户 + 同题若已有 `in_progress`，返回 `40901 该题已有进行中的作答（attempt_id=...）`。

### 3.2 `POST /api/study/attempt-turn`
提交一轮回答 → LLM 评估当轮 + 决定是否追问。

**Request**
```jsonc
{
  "attempt_id": 70001,
  "user_answer": "因为 HashMap 用 hashCode 定位桶，equal 的对象..."
}
```

**Response**
```jsonc
{
  "attempt_id": 70001,
  "dialog": [...],                          // 累积全量
  "turn_rubric": {                          // 本轮 LLM 评估
    "hits": [
      { "key_point": "Object 契约", "hit": true,  "reason": "答到了" },
      { "key_point": "桶定位",       "hit": true,  "reason": "提到 HashMap" },
      { "key_point": "违反后果",     "hit": false, "reason": "未举例" }
    ],
    "feedback": "范例：违反后果举例如 HashMap.get 返回 null...",
    "covered": false,
    "mastery": "mid"
  },
  "follow_up_type": "horizontal",           // 'horizontal' | 'deep_dive' | null
  "follow_up_question": "能举一个具体的违反例子吗？",   // 与 type 同生同灭；null 时前端弹 finish
  "can_finish": false,                      // true = 应自动 finish
  "current_step": 2,
  "max_steps": 5
}
```

**逻辑**：详见 §4.1（5 条状态机硬校正）。

### 3.3 `POST /api/study/attempt-finish`
综合评分收尾。

**Request**
```jsonc
{ "attempt_id": 70001 }
```

**Response**
```jsonc
{
  "attempt_id": 70001,
  "status": "finished",
  "final_score": 82,                        // 0-100
  "rubric_result": {
    "hits": [
      { "key_point": "Object 契约", "score_full": 40, "score_got": 40, "reason": "..." },
      { "key_point": "桶定位",       "score_full": 35, "score_got": 30, "reason": "..." },
      { "key_point": "违反后果",     "score_full": 25, "score_got": 12, "reason": "..." }
    ],
    "missed_key_points": ["违反后果举例不完整"]
  },
  "overall_summary": "..."
}
```

**副作用（同事务内）**：
1. UPDATE question_attempt SET status='finished', final_score=..., rubric_result=..., finished_at=NOW()
2. 重算 `question_score(question_id)` = 最近 3 次 finished.final_score 平均
3. 重算 `mastery(kp_id)` = 该 KP 下**所有**题目分均值（未答题 / 题目分为 null 的都计 0，分母 = 该 KP 总题量）
4. UPDATE knowledge_node SET mastery_level=:mastery, study_count = study_count + 1 WHERE id=:kpId

**幂等**：若 attempt 已 finished，直接返回当前值，不重复扣分/重算。

### 3.4 `POST /api/study/attempt-detail`
取作答详情（用于刷新页面 / 历史回看）。

**Request**：`{ "attempt_id": 70001 }`
**Response**：完整 attempt 字段 + dialog + rubric_result。

### 3.5 `POST /api/study/attempts-history`
取某题作答历史（最近 N 条 finished）。

**Request**：`{ "question_id": 9001, "limit": 10 }`
**Response**：`{ "attempts": [ {id, final_score, finished_at, ...}, ... ] }`

---

## 4. 服务层

| 类 | 职责 | 对照 Python |
|---|---|---|
| `StudyController` | 5 个端点路由 + DTO 校验 | `api/study.py` |
| `StudyAttemptService` / `Impl` | start / turn / finish / detail / history 编排；MAX_FOLLOW_UPS=4 / MAX_STEPS=5 常量 | `services/qa_engine.py` |
| `StudyQaStrategy` | per_turn / final_score prompt 构造（注入 question + rubric_template + dialog） | `services/study_qa_strategy.py` |
| `ScoreAggregateService` / `Impl` | `avgQuestionScore(qid)` + `avgKpMastery(kpId)` + `refreshKpMastery(kpId)`；RECENT_N=3 | `services/qa_aggregate.py` |
| `QuestionAttemptMapper` | CRUD + `findInProgress(userId, type, qid)` + SQL 聚合 `avgQuestionScore(qid, recentN)` / `avgKpMastery(kpId, recentN)` | — |
| `StudyPrompts` 不需要类 | prompt 全部在 DB | — |

> **另：题目查询 / 重生成**（`POST /api/learn/questions`）在 **Learn 模块**处理，本模块不拥有 `StudyQuestionQueryService`。

### 4.1 attempt-turn 流程（核心）

```
Step 1: load attempt → 校验 status=in_progress
Step 2: load study_question → 取 content + rubric_template
Step 3: dialog.append({role:user, type:answer, content:user_answer})
Step 4: 计算 prior_follow_up_types（扫 dialog 中所有 type=follow_up 的 follow_up_type）
        计算 allowed_follow_up_types：
          - horizontal: prior 不含 'horizontal' 才放入（一次封顶）
          - deep_dive: 总是放入（受 MAX_FOLLOW_UPS 兜底）
Step 5: 调 LlmInvoker(prompt=study/per-turn, vars={
          question, rubric_template, dialog, current_step, max_steps,
          prior_follow_up_types, allowed_follow_up_types})
        → JSON {covered, mastery, feedback, hits, follow_up_type, follow_up_question, can_finish}
Step 6: 5 条状态机硬校正（与 Python qa_engine.py 对齐，见 §4.4）
Step 7: dialog.append({role:agent, type:feedback, content:feedback,
          hits:[...], covered, mastery})
        若校正后仍有 follow_up_question:
          dialog.append({role:agent, type:follow_up, content:..., follow_up_type:...})
          follow_up_count += 1
Step 8: UPDATE question_attempt SET dialog=:dialog, follow_up_count=:fc
Step 9: 返回 turn_rubric(含 covered/mastery) + dialog + follow_up_type + follow_up_question + can_finish
```

### 4.2 attempt-finish 流程

```
Step 1: load attempt → 若已 finished 直接返
Step 2: load study_question → rubric_template（总分校验 = 100）
Step 3: 调 LlmInvoker(prompt=study/final-score, vars={question, rubric_template, dialog})
        → JSON {final_score, rubric_result:{hits:[{key_point, score_full, score_got, reason}], missed_key_points}, overall_summary}
Step 4: 校验 sum(score_got) == final_score（容忍 ±5）
Step 5: UPDATE question_attempt: status, final_score, rubric_result, overall_summary, finished_at
Step 6: ScoreAggregateService.refreshKpMastery(kp_id)
        - questionScore = avg(最近 3 次该 question_id 的 final_score)
        - mastery = avg(该 kp 下**所有** question 的 questionScore；null/未答计 0）
        - UPDATE knowledge_node SET mastery_level=:mastery, study_count=study_count+1
Step 7: 返回收尾结果
```

### 4.3 ScoreAggregateService 公式

均值计算全部在 SQL 层完成（见 `QuestionAttemptMapper.avgQuestionScore` / `avgKpMastery`），Service 只负责写回 `knowledge_node`。

```sql
-- 题目分：最近 N 次 finished 的 final_score 均值；无记录 → null
-- avgQuestionScore(qid=:qid, recentN=3)
SELECT AVG(final_score) FROM (
  SELECT final_score FROM question_attempt
  WHERE question_type='study' AND question_id=:qid AND status='finished'
  ORDER BY finished_at DESC NULLS LAST, id DESC
  LIMIT :recentN
) t;

-- KP 掌握度：KP 下**所有**题目分的均值，未答题计 0；KP 下无任何题 → null
-- avgKpMastery(kpId=:kpId, recentN=3)
SELECT AVG(COALESCE(q_score, 0)) FROM (
  SELECT (
    SELECT AVG(final_score) FROM (
      SELECT final_score FROM question_attempt
      WHERE question_type='study' AND question_id=q.id AND status='finished'
      ORDER BY finished_at DESC NULLS LAST, id DESC LIMIT :recentN
    ) recent_a
  ) AS q_score
  FROM study_question q WHERE q.knowledge_point_id=:kpId
) per_q;
```

> **为什么未答计 0**：“掌握度”是 KP 整体覆盖率，不是“已答题的平均分”。若只平均已答题，用户只需提交 1 道高分题即可伪装全掌握。

---

### 4.4 追问决策状态机（与 Python qa_engine.py 完全对齐）

**目标**：让 LLM 不重复问同维度（horizontal 一次封顶），且仅在用户表现高水平时追问深挖（deep_dive 受 mastery=high 约束）。

**LLM 输出（被信任的部分）**：`covered: bool` / `mastery: 'high'|'mid'|'low'` / `follow_up_type: 'horizontal'|'deep_dive'|null` / `follow_up_question` / `can_finish: bool`。

**后端 5 条硬校正**（在 [`StudyAttemptServiceImpl.turn()`](../../src/main/java/com/interview/agent/study/service/impl/StudyAttemptServiceImpl.java) Step 5 内顺序执行；任一触发即清空 `follow_up_type` 与 `follow_up_question`）：

| # | 规则 | 触发条件 | 含义 |
|---|---|---|---|
| 1 | **类型必须在 allowed 列表** | `follow_up_type ∉ allowed_follow_up_types` | LLM 想问已问过的 horizontal → 拒绝 |
| 2 | **deep_dive 仅限 high** | `type=='deep_dive' && mastery!='high'` | 答得不好就别再加码 |
| 3 | **horizontal 仅限未覆盖** | `type=='horizontal' && covered==true` | 漏点都填齐了就别假装还有漏点 |
| 4 | **type 与 question 同生同灭** | `type==null \|\| question==null` | 任一缺失就不出追问 |
| 5 | **轮数上限兜底** | `follow_up_count >= MAX_FOLLOW_UPS (=4)` | 强制 `can_finish=true` |

**allowed_follow_up_types 计算**（在 LLM 调用前，基于 dialog 历史）：
```
prior_follow_up_types = [item.follow_up_type for item in dialog if item.type=='follow_up']
allowed = []
if 'horizontal' not in prior_follow_up_types:
    allowed.append('horizontal')   # 一次封顶
allowed.append('deep_dive')        # 不限次数，受总轮数兜底
```

**状态机示意**：

```
主问 → 答1 → covered? + mastery?
             ├─ covered=true & mastery=high → deep_dive → 答2 → 再判 mastery
             │                                          ├─ high → 继续 deep_dive ...
             │                                          └─ ≤mid → finish
             ├─ covered=true & mastery≤mid  → finish
             └─ covered=false               → horizontal（一次封顶）→ 答2 → mastery?
                                              ├─ high → deep_dive ...
                                              └─ ≤mid → finish
```

**设计取舍**（面试可讲）：

| 抉择 | 选了什么 | 为什么不选另一种 |
|---|---|---|
| 追问决策放哪 | 后端硬约束 + LLM 推理 | 全 LLM：输出不稳；全规则：缺自然语言灵活性 |
| 追问类型粒度 | 两类（horizontal / deep_dive） | 5+ 类：LLM 决策准确率掉；不分类：无法去重 |
| 何时深挖 | 仅 mastery=high | 总是深挖：劝退弱者；从不深挖：强者无成长 |
| 状态如何持久化 | dialog 中追加，重启 LLM 时回放 | 不存中间态：无法续答；结构化字段：schema 易变 |
| 上限强制权 | 后端 5.5 兜底 `can_finish` | 全交 LLM：可能无限追问 |

**幂等性**：每个 `attempt-turn` 是单步纯函数——读 dialog → 计算 → 写 dialog。任何时刻断线重连都能从最新 dialog 完全恢复，不依赖内存状态。

**可观测性**：`covered` / `mastery` / `follow_up_type` 全部落在 dialog JSONB 里，事后复盘能精确还原每轮决策。

---

## 5. Prompt（DB `prompt_template` 表，V11 seed）

### 5.1 `study/per-turn`（V13 重写，与 Python `STUDY_PER_TURN_PROMPT` 对齐）

输入变量：`question`、`rubric_template_json`、`dialog_render`、`current_step`、`max_steps`、`prior_follow_up_types`、`allowed_follow_up_types`。

输出 JSON：
```json
{
  "covered": true|false,
  "mastery": "high"|"mid"|"low",
  "feedback": "...",
  "hits": [{"key_point": "...", "hit": true|false, "reason": "..."}],
  "follow_up_type": "horizontal"|"deep_dive"|null,
  "follow_up_question": "..."|null,
  "can_finish": true|false
}
```

关键约束（写进 prompt）：
- `follow_up_type` 必须从 `allowed_follow_up_types` 里选，否则后端会清空（见 §4.4 校正规则 1）
- horizontal 必须把所有未覆盖 rubric 要点**一次性问完**
- deep_dive 必须针对用户已讲出的点做纵向深挖
- `follow_up_type` 与 `follow_up_question` 同生同灭

### 5.2 `study/final-score`

```
你是资深面试官，对一次完整作答打分。

【题目】{question}
【评分点（总分 100）】{rubric_template_json}
【完整对话】{dialog_render}

任务：
1. 综合用户在整轮对话中的所有回答，逐评分点给 score_got（0 ≤ score_got ≤ score_full）
2. 总分 final_score = sum(score_got)
3. 列出 missed_key_points（score_got < score_full*0.6 的点）
4. overall_summary：3-5 句总结答得好/差在哪、改进建议

严格返回 JSON：
{
  "final_score": 0-100,
  "rubric_result": {
    "hits": [{"key_point": "...", "score_full": 40, "score_got": 30, "reason": "..."}],
    "missed_key_points": ["..."]
  },
  "overall_summary": "..."
}
```

---

## 6. 验收清单

- [x] Flyway V11 启动自动执行（seed 2 个 prompt）；V13 重写 `study/per-turn`
- [x] `POST /api/study/attempt-start` 创建 in_progress；重复开同一题返 40901
- [x] `POST /api/study/attempt-turn` 返 turn_rubric.hits（含每个评分点 hit/reason）+ follow_up_question + can_finish
- [x] follow_up_count 达 MAX_FOLLOW_UPS 后 turn 强制 follow_up_question=null
- [x] `POST /api/study/attempt-finish` 写 final_score / rubric_result / status=finished；幂等
- [x] finish 后 SELECT knowledge_node 看到 mastery_level 更新 + study_count + 1
- [x] 同 KP 多题作答后，mastery_level = KP 下**所有**题目分均值（未答题计 0）
- [x] `POST /api/study/attempt-detail` 返完整 dialog + rubric_result
- [x] `POST /api/study/attempts-history` 返该题最近 N 条 finished 记录
- [x] 所有响应 `{code:0,...}` 格式
- [x] 前端 StudyPage：题目列表 → 进入答题 → 多轮对话 + 每轮 hits 高亮 → finish 看综合评分
- [x] **浏览器验证**（per `/memories/frontend-rules.md`）

> **题目 fetch / regenerate 的验收项**已迁至 Learn 模块（`POST /api/learn/questions`）。

---

## 7. 改动清单（实施时勾选）

### Java 后端
- [x] `db/migration/V11__seed_study_prompts.sql`（upsert `study/per-turn` + `study/final-score`）
- [x] `study/entity/QuestionAttempt.java`（record；JSONB 字段 `dialog` / `rubricResult` / `extensionQa` / `designIssues` 用 `Object`）
- [x] `study/mapper/QuestionAttemptMapper.java`（@注解：insert / findById / findInProgress / updateTurn / updateFinish / avgQuestionScore / avgKpMastery）
- [x] `study/dto/AttemptStartRequest.java` / `AttemptStartResponse.java`
- [x] `study/dto/AttemptTurnRequest.java` / `AttemptTurnResponse.java`
- [x] `study/dto/AttemptFinishResponse.java`
- [x] `study/dto/AttemptDetailResponse.java`
- [x] `study/dto/AttemptsHistoryRequest.java` / `AttemptsHistoryResponse.java`
- [x] `study/service/StudyAttemptService` + `Impl`（核心；start/turn/finish/detail/history）
- [x] `study/service/StudyQaStrategy`（per_turn / final_score prompt 变量装配）
- [x] `study/service/ScoreAggregateService` + `Impl`（avgQuestionScore / avgKpMastery / refreshKpMastery）
- [x] `study/controller/StudyController`（5 端点，全 POST）
- [x] `knowledge/mapper/KnowledgeNodeMapper`：`updateMastery(id, mastery)` 已存在（study_count 同步 +1）
- [x] Prompt seed：V11（per-turn + final-score）+ V13（per-turn 重写为状态机版本）

> **题目 fetch / regenerate 端点不在本模块**，归 Learn (`POST /api/learn/questions`)，见 S?-learn 模块。

### 前端
- [x] `pages/StudyPage.jsx`（题目列表 + 进入答题 + 多轮对话 + 每轮 hits 高亮 + finish 综合分页）
- [x] 复用 `components/AnswerInput.jsx`
- [x] `styles.css`：hits 命中 / 未命中颜色 + finish 评分卡片样式

### Python
- 不动

---

## 8. 未做（明确推迟）

- `GET /study/knowledge-points` 推荐列表（按优先度排序）
- broaden / deep_dive follow_up_types 状态机（一期让 LLM 自决）
- `attempt-abandon` 主动放弃端点（一期只有自然 finish；in_progress 残留会被同题再 start 时拒绝，提示用户先继续或 finish）
- mastery_level 派生策略可配置（一期硬编码"3 次平均 + KP 均值"）
- 流式 turn 响应（一期一次性返）
- 多用户隔离（一期固定 user_id=1）
