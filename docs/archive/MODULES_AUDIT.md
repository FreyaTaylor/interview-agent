# 模块审计与联动文档（2026-05-28）

本文档由对每个用户模块（除登录外）做**端到端浏览器走查 + 后端路由 + 服务 + 数据表 + 前端组件**的全链路审计后产出。  
覆盖：知识树/作答（ExamPage）、项目拷打（ProjectGrillingPage）、面试复盘（InterviewPage）、个人资料（ProfilePage）、管理后台（Outliner）。

走查环境：
- 后端：FastAPI on `http://127.0.0.1:8000`
- 前端：Vite on `http://localhost:5173`，登录用户 `FreyaTaylor`
- DB：PostgreSQL 16 + pgvector `ivy@localhost:5432/interview_agent`

---

## 0. 本次审计中执行的清理动作

| 类别 | 处理 | 说明 |
|---|---|---|
| 死代码 — 前端组件 | **删除** [frontend-react/src/components/ProjectEditPanel.jsx](frontend-react/src/components/ProjectEditPanel.jsx) | 定义后从未被任何页面 import；调用一组后端不存在的 `/api/interview/project-questions*` CRUD 端点。 |
| 死代码 — 后端路由 | **删除** `GET /api/interview/project-questions` | 唯一调用方就是上面已删的 ProjectEditPanel。 |
| 死代码 — 后端路由 | **删除** `GET /api/interview/other-questions` | 前端从无调用，业务上 leetcode/hr 聚合无入口。 |
| 死代码 — 后端服务 | **删除** [backend/services/interview_crud.py](backend/services/interview_crud.py) 中 `get_project_questions` 和 `get_other_questions` 两个函数 | 失去调用方后清除，同时移除未再使用的 `defaultdict` 引用。 |
| 死代码 — 后端路由 | **删除** `GET /api/project-grilling/projects/{project_id}/attempts` | 前端从无调用；项目作答历史前端不再展示这一聚合视图。 |
| 残留外键 — 模型字段 | **删除** [backend/models/interview.py](backend/models/interview.py) 中 `InterviewRecord.study_session_id` 字段映射 | 列已在 migration 007 `DROP COLUMN`；模型残留导致 `SELECT *` 报 500（实际 bug 修复）。 |
| 残留外键 — DB 表 | 已废 | `study_session`、`conversation`、`conversation_message`、`mastery_record`、`mastery_history` 这 5 张表在 migration 007 已 `DROP TABLE`，本次不再需要二次操作。 |

> 注：`POST /api/interview/parse`（一步直解）和 `POST /api/interview/overwrite` 之前怀疑是死路由，**实际仍在用**（InterviewPage 直解模式 + 覆盖已有记录），保留。

---

## 1. 知识树 / 题目作答（ExamPage）

### 1.1 用户走查结论 ✅
| 操作 | 结果 |
|---|---|
| `/exam/:kpId` 加载 | 左侧知识目录 + 中栏题目列表 + 右侧空态正常 |
| 点击 `Q0`（B+树与B树的区别） | 启动 attempt（status=in_progress），右栏出现"第 0 / 4 轮追问" + 主问题气泡 |
| 提交回答（Enter） | 触发 `/turn`，per-turn LLM 返回 `covered=true` + `follow_up_question=null` |
| 自动结束 | 跟随 `should_finish` 调用 `/finish`，触发综合打分 LLM → `final_score=100`，4 项 rubric 各 25 分，含原文 `matched_text` |
| 列表分数回写 | Q0 徽章由"未作答"刷新为"100"，tooltip "已作答 1 次" |

### 1.2 涉及路由（前端 → 后端）
| Method | Path | 入口 | Service | 关键表 |
|---|---|---|---|---|
| GET | `/api/knowledge/tree` | KnowledgeTreePage 初始/ExamPage 左栏 | `knowledge_node.build_knowledge_tree` | `knowledge_node` + `question_attempt`（派生掌握度） |
| GET | `/api/study/knowledge-points/{kp_id}/questions` | ExamPage 中栏 | `study_qa_strategy.ensure_study_questions` + `qa_aggregate.get_question_score/count` | `study_question`、`question_attempt` |
| POST | `/api/study/attempts` | ExamPage 点题 | `qa_engine.start_attempt`（策略=`StudyQAStrategy`） | `study_question` 读 / `question_attempt` 写 |
| POST | `/api/study/attempts/{id}/turn` | ExamPage 回答 | `qa_engine.process_turn` → per-turn LLM | `question_attempt.dialog` JSONB 累加 |
| POST | `/api/study/attempts/{id}/finish` | 自动 / "结束并打分" | `qa_engine.finish_attempt` → final-score LLM | 写 `question_attempt`: status, final_score, rubric_result |
| GET | `/api/study/attempts/{id}` | useQAFlow 复用 | `qa_engine.get_attempt` | 读 `question_attempt` |
| GET | `/api/study/knowledge-points` | （目录推荐场景，当前未在 ExamPage 使用） | 排序公式：`weight * (1 - mastery/100) * 0.8`（已学），未学按 `weight * 1.0` |

### 1.3 联动模块（数据流）
```
KnowledgeNode(leaf)
  ├─ ensure_study_questions ── LLM 懒生成 ──→ StudyQuestion (5题/知识点)
  │       ↑
  │       └── 首次访问触发；二次访问直接读
  └─ qa_aggregate.get_kp_mastery_map ←── QuestionAttempt(finished, 最近3次平均)
                                                     ↑
                                                     └─ qa_engine.finish_attempt 写
```

### 1.4 关键文件
- 前端：[frontend-react/src/pages/ExamPage.jsx](frontend-react/src/pages/ExamPage.jsx)、[hooks/useQAFlow.js](frontend-react/src/hooks/useQAFlow.js)、[components/ConversationView.jsx](frontend-react/src/components/ConversationView.jsx)、[components/QuestionList.jsx](frontend-react/src/components/QuestionList.jsx)
- 后端路由：[backend/api/study.py](backend/api/study.py)、[backend/api/knowledge.py](backend/api/knowledge.py)
- 后端服务：[backend/services/qa_engine.py](backend/services/qa_engine.py)、[backend/services/study_qa_strategy.py](backend/services/study_qa_strategy.py)、[backend/services/qa_aggregate.py](backend/services/qa_aggregate.py)、[backend/services/knowledge_node.py](backend/services/knowledge_node.py)
- 模型：[backend/models/knowledge.py](backend/models/knowledge.py)（`KnowledgeNode`）、[backend/models/study.py](backend/models/study.py)（`StudyQuestion`）、[backend/models/qa.py](backend/models/qa.py)（`QuestionAttempt`）
- 提示词：[backend/prompts/qa_per_turn_prompt.py](backend/prompts/qa_per_turn_prompt.py)、[backend/prompts/qa_final_score_prompt.py](backend/prompts/qa_final_score_prompt.py)、[backend/prompts/study_prompts.py](backend/prompts/study_prompts.py)（题目生成）

---

## 2. 项目拷打（ProjectGrillingPage）

### 2.1 用户走查结论 ✅
| 操作 | 结果 |
|---|---|
| `/grilling/:projectId` 加载 | 左栏画像（项目下拉、准备度、项目事实、薄弱点）+ 中栏 5 个话题手风琴 + 右栏空态 |
| 项目下拉 | "智能客服项目"、"滚动预测重构" 双选项可切换 |
| 展开"检索架构设计" | 加载 3 道题 Q0~Q2，"未作答"徽章 |
| `start_attempt` / `process_turn` / `finish_attempt` | 复用与 study 同一 `qa_engine`；策略不同（`ProjectQAStrategy`）→ prompt 强调项目细节、追问"为什么 / 怎么做" |
| `finish_attempt` 完成后 | 异步 `asyncio.create_task(extract_and_apply)` 更新 `project_user_profile`，下次加载画像可见 |

### 2.2 涉及路由
| Method | Path | Service | 关键表 |
|---|---|---|---|
| GET | `/api/project-grilling/projects` | `project_grilling.project_crud.list_projects` | `project`、派生准备度自 `question_attempt` |
| GET | `/api/project-grilling/projects/{id}/profile` | `project_profile.get_or_create_profile` | `project_user_profile` |
| GET | `/api/project-grilling/projects/{id}/dimensions` | `qa_aggregate.get_topic_score` per topic | `project_node`(L2) + `question_attempt` |
| GET | `/api/project-grilling/topics/{id}/questions` | `qa_aggregate.get_question_score/count` per leaf | `project_node`(L3) + `question_attempt` |
| POST/PUT/GET | `/api/project-grilling/attempts*` | 同 study；策略=`ProjectQAStrategy` | `project_node` 读 / `question_attempt` 写（多态 `question_type='project'`） |

### 2.3 联动模块（数据流）
```
Project(level=1) ─┐
ProjectNode(L1=项目根, L2=话题, L3=题目叶子)
                  │
                  ├─ list_projects ── 计算准备度（topic 平均分均值）
                  │
                  └─ qa_engine ── ProjectQAStrategy ──┐
                                                     │
                                                     ├─ per-turn LLM（带 project_profile.render_for_prompt）
                                                     │
                                                     └─ finish → 异步 extract_and_apply → ProjectUserProfile 累积
```

### 2.4 关键文件
- 前端：[frontend-react/src/pages/ProjectGrillingPage.jsx](frontend-react/src/pages/ProjectGrillingPage.jsx)
- 后端路由：[backend/api/project_grilling/session.py](backend/api/project_grilling/session.py)、[history.py](backend/api/project_grilling/history.py)、[projects.py](backend/api/project_grilling/projects.py)
- 后端服务：[backend/services/project_qa_strategy.py](backend/services/project_qa_strategy.py)、[project_profile.py](backend/services/project_profile.py)、[project_grilling/project_crud.py](backend/services/project_grilling/project_crud.py)、[project_node.py](backend/services/project_node.py)
- 模型：[backend/models/project.py](backend/models/project.py)（`Project`、`ProjectUserProfile`）、[project_node.py](backend/models/project_node.py)（`ProjectNode`）

---

## 3. 面试复盘（InterviewPage）

### 3.1 用户走查结论 ⚠️ → ✅
- 初次访问 `/interview` → 500，控制台 CORS 报错。
- **根因**：`InterviewRecord.study_session_id` 字段还在 model 中，但 migration 007 已 `DROP COLUMN`，`SELECT *` 抛 UndefinedColumn。
- **修复**：移除 model 字段映射；FastAPI 自动重载后 `/api/interview/history` 返回 200。
- 重新加载页面：显示"暂无面试记录"空态、语音/文本切换、公司/职位输入、文件拖拽框，两个按钮（"📝 预解析"、"⚡ 直接解析"）正常禁用。

### 3.2 涉及路由（全部仍在使用）
| Method | Path | Service | 调用方 |
|---|---|---|---|
| GET | `/api/interview/history` | `interview_crud.get_history_list` | InterviewPage 列表 |
| GET | `/api/interview/history/{id}` | `interview_crud.get_history_detail` | InterviewPage 详情 |
| DELETE | `/api/interview/history/{id}` | `interview_crud.overwrite_record` | InterviewPage 删除按钮 |
| PATCH | `/api/interview/history/{id}` | `interview_crud.update_record_meta` | InterviewPage 编辑元数据 |
| POST | `/api/interview/upload-audio` | `asr.transcribe_audio` | 语音模式 |
| POST | `/api/interview/check-duplicate` | `interview_crud.check_duplicate` | text_hash 防重 |
| POST | `/api/interview/preview-parse` | `interview_crud.preview_parse_interview` → `interview_parser.parse_interview_text` | 校对模式 |
| POST | `/api/interview/finalize` | `interview_crud.finalize_interview` → `match_nodes` + `score_all_groups` + `interview_storage.store_*` | 校对模式落库 |
| POST | `/api/interview/parse` | preview + finalize 合并 | 直解模式 |
| POST | `/api/interview/overwrite` | `interview_crud.overwrite_record` | 重复时覆盖 |

### 3.3 联动模块（数据流）
```
原始文本（语音→ASR / 直接文本）
   │
   ├─ check_duplicate (text_hash) ──→ 提示用户是否覆盖
   │
   ├─ preview_parse_interview ─── LLM 解析 ── turns + groups
   │                                          │
   │                              用户在 InterviewReviewPage 校对
   │                                          ↓
   └─ finalize_interview
        ├─ match_nodes (interview_matcher + 向量匹配 skill)
        │     ├─→ KnowledgeNode（命中知识树叶子）
        │     └─→ ProjectNode（命中项目树叶子，或新建）
        ├─ score_all_groups (interview_scorer + LLM rubric)
        ├─ store_new_interview_tables → InterviewRecord +
        │     InterviewKnowledgeQuestion / InterviewProjectQuestion / InterviewOtherQuestion
        └─ store_answer_embeddings → UserAnswerEmbedding (pgvector)
```

### 3.4 关键文件
- 前端：[frontend-react/src/pages/InterviewPage.jsx](frontend-react/src/pages/InterviewPage.jsx)、[InterviewReviewPage.jsx](frontend-react/src/pages/InterviewReviewPage.jsx)
- 后端路由：[backend/api/interview.py](backend/api/interview.py)
- 后端服务：[backend/services/interview_crud.py](backend/services/interview_crud.py)、[interview_parser.py](backend/services/interview_parser.py)、[interview_matcher.py](backend/services/interview_matcher.py)、[interview_scorer.py](backend/services/interview_scorer.py)、[interview_storage.py](backend/services/interview_storage.py)、[asr.py](backend/services/asr.py)
- 模型：[backend/models/interview.py](backend/models/interview.py)（5 张表）

---

## 4. 个人资料（ProfilePage）

### 4.1 用户走查结论 ✅
- `/profile` 展示用户名、画像内容（多行 markdown 风格简介）、编辑按钮、退出登录按钮。
- 已加载现有画像：3 年 Java 后端，目标 大厂 Java/agent 方向，含技术栈、项目经验、薄弱方向、擅长方向、面试计划。

### 4.2 涉及路由
| Method | Path | Service | 表 |
|---|---|---|---|
| GET | `/api/user/profile` | `profile.get_profile` | `user.profile` JSONB 字段 |
| PUT | `/api/user/profile` | `profile.update_profile` | 写入同上 |

### 4.3 联动
- 画像内容会被 `tree_gen.create_tree_from_generate` 和 `learn.get_or_generate_content` 作为系统提示词的一部分，让 LLM 据此个性化（口径来自 `extract_profile_prompt.py`）。
- 修改画像无副作用（不会重置知识树或答题历史）。

### 4.4 关键文件
- 前端：[frontend-react/src/pages/ProfilePage.jsx](frontend-react/src/pages/ProfilePage.jsx)
- 后端：[backend/api/profile.py](backend/api/profile.py)、[backend/services/profile.py](backend/services/profile.py)

---

## 5. 管理后台（Admin / Outliner）

### 5.1 用户走查结论 ✅
- `/admin` → 自动重定向 `/admin/tree`。
- "🌳 知识树" tab：加载完整知识树（rdis/mysql/java/消息队列等顶层），树形可编辑，节点带权重星标（`★` × interview_weight），无错误。
- 切换到 "🔨 项目拷打" tab → `/admin/project`：列出"滚动预测重构"、"智能客服项目" 两个项目根 + 各自话题/问题三层结构，所有节点可编辑/删除。

### 5.2 涉及路由（知识树）
| Method | Path | Service |
|---|---|---|
| GET | `/api/admin/tree-nodes` | `knowledge_node.get_all_nodes` |
| POST | `/api/admin/tree-nodes` | `knowledge_node.create_node` |
| PUT | `/api/admin/tree-nodes/batch-sort` | `knowledge_node.batch_update_sort` |
| PUT | `/api/admin/tree-nodes/{id}` | `knowledge_node.update_node` |
| DELETE | `/api/admin/tree-nodes/{id}` | `knowledge_node.delete_node_recursive` |
| POST | `/api/admin/trees/from-text` | `tree_gen.create_tree_from_text` |
| POST | `/api/admin/trees/from-generate` | `tree_gen.create_tree_from_generate`（LLM） |
| POST | `/api/admin/trees/from-image` | `tree_gen.create_tree_from_image`（多模态 LLM） |
| POST | `/api/admin/trees/from-mm` | `tree_gen.create_tree_from_mm`（FreeMind .mm） |
| POST | `/api/admin/trees/{root_id}/optimize` | `tree_gen.optimize_tree`（查漏补缺） |
| POST | `/api/admin/trees/merge` | `tree_gen.merge_trees` |

### 5.3 涉及路由（项目）
| Method | Path | Service |
|---|---|---|
| GET | `/api/admin/project-nodes` | `project_node.get_all_nodes` |
| POST | `/api/admin/project-nodes/from-text` | `project_node.create_project_from_text` |
| POST/PUT/DELETE `/api/admin/project-nodes[/...]` | 同 CRUD 模式 | `project_node.*` |

### 5.4 联动
- 知识树修改 → 立即影响 ExamPage 的左栏目录、`/api/study/knowledge-points` 的推荐列表。
- 项目节点修改 → 立即影响 ProjectGrillingPage 的项目下拉、话题手风琴。
- 「LLM 生成」流程会调用 `embedding.compute_embedding` 为新节点回填向量，供 `interview_matcher` 后续相似度匹配复用。

### 5.5 关键文件
- 前端：[frontend-react/src/pages/OutlinerPage.jsx](frontend-react/src/pages/OutlinerPage.jsx)、[components/OutlinerEditor.jsx](frontend-react/src/components/OutlinerEditor.jsx)
- 后端：[backend/api/admin/](backend/api/admin/) 整套、[backend/services/knowledge_node.py](backend/services/knowledge_node.py)、[project_node.py](backend/services/project_node.py)、[tree_gen.py](backend/services/tree_gen.py)
- 提示词：[backend/prompts/tree_prompts.py](backend/prompts/tree_prompts.py)、[project_prompts.py](backend/prompts/project_prompts.py)

---

## 6. 跨模块数据契约总览

```
┌───────────────────────────────────────────────────────────────────────────┐
│                            user.profile (JSONB)                            │
│            ↓ 作为系统提示词的"画像"，多处 LLM 调用都引用                       │
└──────────┬────────────────────────────────────────────────────────────────┘
           │
   ┌───────┴────────┐                            ┌────────────────────┐
   │  KnowledgeNode │ ← Admin tree-nodes ──→     │    ProjectNode     │
   │  (3 层:        │                            │   (3 层: 项目/      │
   │   类目/章/叶子) │                            │   话题/问题)        │
   └───┬────────────┘                            └────────┬───────────┘
       │ 叶子 + LLM                                       │ 叶子
       ↓                                                  ↓
  StudyQuestion (5题/叶子)                          ProjectNode L3 直接当题
       │                                                  │
       └──────────────┬───────────────────────────────────┘
                      ↓
              ┌──────────────┐
              │ QuestionAttempt │ ← qa_engine（per-turn LLM + final-score LLM）
              │ (多态:          │
              │ study/project)  │
              └────┬─────────┬─┘
                   │         │
        qa_aggregate         project_profile.extract_and_apply
        ─ kp 掌握度           ─ 提取项目事实、薄弱点
        ─ 题目最近3次均分     ─ 写入 project_user_profile
        ─ topic 派生分        ─ 反过来塞给下一次 per-turn prompt
        ─ project 准备度
```

外部输入（**面试复盘**）：
```
原始面试文本/录音 → interview_parser (LLM) → match_nodes (向量+LLM)
                                                  │
                                ┌─────────────────┼─────────────────┐
                                ↓                 ↓                 ↓
                  KnowledgeNode 命中    ProjectNode 命中    Other（leetcode/hr）
                                │                 │
                       InterviewKnowledgeQ  InterviewProjectQ
                                │                 │
                                └──┬──────────────┘
                                   ↓
                     interview_scorer (LLM rubric)
                                   ↓
                       InterviewRecord 保存解析+评分快照
                                   ↓
                     store_answer_embeddings → UserAnswerEmbedding (Agent 长期记忆)
```

---

## 7. 验证后的"模块联动是否正确" 结论

| 联动 | 状态 |
|---|---|
| Admin 知识树修改 → ExamPage 左栏目录刷新 | ✅ 共用 `knowledge_node` 表，新增节点立即可见 |
| ExamPage 答题完成 → 题目徽章/知识点掌握度刷新 | ✅ `qa_aggregate` 派生算法实时从 `question_attempt` 计算 |
| ProjectGrillingPage 答题完成 → 项目画像更新 | ✅ `finish_attempt` 后异步 `asyncio.create_task(extract_and_apply)`；下次访问可见 |
| InterviewPage finalize → 题目入库到对应项目树 | ✅ `interview_matcher` 把命中节点写入 `interview_project_question.project_node_id`，并新增 ProjectNode 叶子（若未命中） |
| InterviewPage finalize → 用户回答向量入库 | ✅ `store_answer_embeddings` 写 `user_answer_embedding`（pgvector） |
| Admin 项目编辑 → ProjectGrillingPage 下拉刷新 | ✅ `list_projects` 实时读 `project` 表 |
| Profile 修改 → tree_gen / learn 个性化 | ✅ `user.profile` JSONB 透传给系统提示词 |
| `study_session` 旧表残留引用 | ✅ 本次已移除模型字段，DB 列已 drop |

---

## 8. 后续可改进项（非紧急）

1. **统一异常返回** — 部分路由用 `HTTPException` 抛 4xx，部分用 `ApiResponse.error(code=40001)`；建议统一为 `ApiResponse.error` + FastAPI 全局异常处理器。
2. **`InterviewRecord.parsed_questions` JSONB 与三张 `interview_*_question` 表的同步关系** — 当前是双写，存在数据漂移风险，建议二选一。
3. **`agents/` 目录现仅占位** — 主要 Agent 逻辑分布在 services（`qa_engine` 即是事实上的对话 Agent）；若计划迁 LangGraph，再正式建一套。
4. **`backend/scripts/migrations/` 仍是裸 SQL** — `CONVENTIONS.md` 写的是 Alembic，但实操是 `.sql` 手跑；考虑迁 Alembic。
5. **ProjectEditPanel 删除后** — 现在面试问题（`interview_project_question`）只有 finalize 时写入、无单独管理 UI；如果需要可在 Admin 加一个"面试问题快查"页面而不是恢复 ProjectEditPanel。

---

> 本审计同时附带了"为所有 service / 路由函数补详细 docstring" 的代码改动，见 git diff。
