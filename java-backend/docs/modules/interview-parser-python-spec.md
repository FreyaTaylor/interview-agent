# 面试复盘模块 · Python 实现完全复刻规范（Java 重写依据）

> 目的：把 Python 侧（`backend/`）面试解析/ASR/评分/落库的**全部逻辑与细节**固化在此，
> 作为 Java 重写的唯一依据。Java 实现必须与本文逐条对齐，**不允许“看起来差不多”的近似实现**。
>
> 复刻原则（anti-drift）：
> 1. **常量逐字一致**：所有阈值/上限/重试次数/温度/并发度照抄，不得“拍脑袋”改值。
> 2. **流水线顺序一致**：步骤顺序即业务语义，不得调换、合并或省略任一步。
> 3. **Prompt 逐字一致**：DB `prompt_template` 里 seed 的内容必须与 `backend/prompts/interview_prompts.py` **完全相同**（含每一条 ⚠️/示例/JSON 模板）。这是历史“不复制”问题的根因。
> 4. **行为分支一致**：失败回退、空值处理、去重 key、排序规则都要照搬。
>
> Python 源文件清单：
> - `backend/services/interview_parser.py` — 解析主链路
> - `backend/services/interview_turns.py` — turn 切分/修复/分块/渲染
> - `backend/services/asr.py` — 音频转写
> - `backend/services/asr_corrector.py` — ASR 纠错 + 噪声删除
> - `backend/services/interview_scorer.py` — 分类型评分 + 总评
> - `backend/services/interview_matcher.py` — 知识点/项目节点匹配
> - `backend/services/interview_storage.py` — 新表落库 / 权重 / 答案向量
> - `backend/services/interview_crud.py` — finalize 编排
> - `backend/prompts/interview_prompts.py` — 全部 prompt

---

## 0. 数据结构约定

### turn（单次发言）
```
{ id:int(全局唯一,0起), speaker:"面试官"|"我"|"", content:str, char_start:int, char_end:int }
```

### group（LLM 输出的话题分组，新 schema）
```
category : "knowledge" | "project" | "other"
tag      : 知识点短标签 / 项目拷打主题 / other 子类(leetcode|hr|system_design|misc)
project_name : 仅 project
questions : [规范化书面提问...]
user_answer : 候选人回答技术摘要(无则"")
original_dialogue : "面试官：xxx\n我：xxx"
turn_ids : [覆盖的 turn 编号升序]
```
归一化后追加的 legacy 字段（见 §5.6）：`type / knowledge_point / topic / title`。

---

## 1. 顶层流水线 `parse_interview_text(raw_text) -> {turns, groups, summary}`

严格按以下顺序（`interview_parser.py`）：

| # | 步骤 | 函数 | 关键点 |
|---|------|------|--------|
| 0 | 原文 → turns | `split_into_turns` | 全局 id，带 char 偏移 |
| 0 | 修复破碎 turn | `repair_turns` | 启发式合并 ASR 切错 |
| 0.5 | ASR 纠错 + 删噪声 turn | `correct_asr_turns` | 失败回退原 turns；空则提前返回 |
| 1 | 分块 | `chunk_turns(chunk_size=1200)` | 每段渲染 `render_turns_for_llm` |
| 1 | 并发解析 | `_parse_single_chunk` | `Semaphore(5)`；LLM `temperature=0.1, max_tokens=4096, timeout=120` |
| 1 | turn_ids 清洗 | 内联 | 只保留本段合法 id，去重升序 |
| 2 | 跨段 embedding 合并 | `_merge_by_embedding_boundary` | cos≥0.82 |
| 3 | 同项目话题合并 | `_merge_project_topics` | LLM 判重 |
| 4 | other 去重 | `_dedup_other_groups` | key=(tag,首问lower) |
| 5 | LeetCode 补全 | `_enrich_leetcode_groups` | 并发 skill |
| 6 | legacy 字段 | `_normalize_to_legacy_schema` | |
| 6.5 | answer anchor 重排 turn_ids | `_regroup_by_answer_anchors` | 以“我”为锚 |
| 6.6 | 吸收孤儿面试官组 | `_absorb_orphan_interviewer_groups` | 紧邻合并到下一组 |
| 7 | 回填 original_dialogue | 内联 | 空且有 turn_ids 时按 turns 拼 |
| 8 | 遗漏二次检查（仅单段） | 内联 LLM | 追加一个 other/misc 组 |

边界返回：
- turns 为空 → `{turns:[], groups:[], summary:"面试文本为空"}`
- 纠错后空 → `{..., summary:"纠错后内容为空"}`
- 合并后无 group → `{turns, groups:[], summary:"解析失败，请重试"}`

---

## 2. turns 切分 / 修复 / 分块（`interview_turns.py`）

### 2.1 `split_into_turns(text)`
- 正则：`(?:^|\n)([ \t]*)(面试官|我)\s*[:：]\s*`，`re.MULTILINE`。
- content 起点 = 匹配末尾（说话人前缀**不入** content）；终点 = 下一匹配起点或文末；`content.rstrip()`；空则跳过。
- id 连续递增（跳过空 content 后仍连续）。
- 无任何匹配 → `_split_by_blank_lines`（按 `\n\s*\n` 切，speaker=""）。
- 结果为空也回退 `_split_by_blank_lines`。

### 2.2 `repair_turns(turns)` — 保守合并 ASR 破碎片段
常量：
```
_CONT_START   = ("种","吗","呢","啊","呀","哈","嘛","咯")  # 续接字
_HANG_END     = ("那","和","与","或","及","就","且")        # 悬挂连词
_TERMINATORS  = set("。？！.?!；;")
_SHORT_FRAG_LEN = 8
```
`_should_merge_to_prev(prev, cur)`（任一命中即合并到 prev，speaker 沿用 prev）：
- prev 的 content 先 `rstrip("，,。.？?！!；;、 \t\n")` 取末字 `prev_last`；cur 取首字 `cur_first`。
- 信号1：`cur_first in _CONT_START 且 len(cur)<8`。
- 信号2：`prev_last in _HANG_END 且 len(cur)<8 且 cur 不含任何 _TERMINATORS`。
合并方式：`prev.content = prev.content.rstrip() + cur.content.lstrip()`；`prev.char_end = cur.char_end`。最后 id 重新连续编号。

### 2.3 `render_turns_for_llm(turns)`
每行：`[t{id}] {speaker}: {content}`；speaker 为空时无前缀（`[t{id}] {content}`）。

### 2.4 `chunk_turns(turns, chunk_size=1200)`
- 单 turn 估长 `t_len = len(content) + 8`。
- 空 current 且 `t_len>chunk_size` → 该 turn 自成一段。
- `current_len + t_len > chunk_size 且 current 非空` → 切段。
- 否则累加。末尾 current 收尾。

---

## 3. 单段解析 `_parse_single_chunk` + turn_ids 清洗

- `total>1` 时拼接 context：
  > `\n## 当前段位置\n这是面试记录的第 {idx+1}/{total} 段，请只解析本段内容。本段已按面试官提问作为边界切分，开头是一个完整的提问；如有话题与其他段重叠由系统后处理合并，本段不必猜测上下文。`
  `total==1` 时 context=""。
- prompt = `INTERVIEW_PARSE_PROMPT.format(raw_text=chunk_text, context=context)`。
- **重试 3 次**：捕获 JSONDecodeError/IndexError 记 warning，其它异常记 error，全失败返回 None。
- 清洗：`valid_ids = 本段 turn id 集合`；每组 `turn_ids` 只保留 `int(x) in valid_ids`，去重升序。

---

## 4. 跨段 embedding 合并 `_merge_by_embedding_boundary`

逐段拼接，仅比较「已合并结果末组 last」vs「当前段首组 first」：
1. `_is_same_group(last, first)` → 直接合并：
   - category 不同 → False；
   - project：`project_name` 与 `tag` 都相等；
   - 否则 `tag` 相等。
2. 否则若 `category` 相同 → 算 embedding：
   - 签名 `_group_signature(g)`：
     - project：`[项目]{project_name}·{tag}: {首问[:120]}`
     - 其它：`[{category}]{tag}: {首问[:120]}`
   - `get_embedding` 两签名 → `_cosine`；`sim >= 0.82` 判同。
   - embedding 异常仅 warning，不影响主流程。
3. 判同 → `_merge_continuation(last, first)`，并 `merged.extend(groups[1:])`；否则整段追加。

`_merge_continuation(base, cont)`：
- questions 去重追加（保序）；
- user_answer / original_dialogue：非空则 `prev + "\n" + cont`（prev 空则直接取 cont）；
- `turn_ids = sorted(set(base ∪ cont))`。

`_cosine`：标准点积/模；任一模为 0 返回 0.0。

> Java 注意：当前 Java 实现缺该 embedding 合并（只做 exact tag）。必须补 DashScope embedding 调用 + 0.82 阈值。

---

## 5. 后处理

### 5.1 `_merge_project_topics(groups, llm)`
- 拆 `non_project` / `projects`；`projects<=1` 直接返回原 groups。
- 按 `project_name`（空→"未命名项目"）分桶；单话题桶直接保留。
- 多话题桶：构造 `topic_list`，用**内联 merge_prompt**（见下）让 LLM 返回 `{"merge_groups":[[1,3],[2],...]}`（1-based）。
  - 每个 merge 组取首个为 base，其余并入：questions 拼接、user_answer 用 `\n` 连接、original_dialogue 用 `\n---\n` 连接、`turn_ids=sorted(set(∪))`；最后 questions 去重保序。
  - 异常 → 保留原 topics。
- 返回 `non_project + merged_projects`。

merge_prompt（逐字）：
```
以下是同一个项目「{proj_name}」下的多个面试话题，请判断哪些话题在语义上重复或高度相似，应该合并。

话题列表：
{topic_list 每行 "i. tag"}

请返回合并方案。如果某些话题应合并，用数组表示（如 [1,3] 表示话题1和3合并）。不需要合并的单独成组。
```json
{"merge_groups": [[1, 3], [2], [4, 5, 6]]}
```
只返回 JSON，不要其他内容。如果都不需要合并，每个单独成组即可。
```

### 5.2 `_dedup_other_groups(groups)`
- 非 other 原样进 `result`。
- other：`key = f"{tag}::{首问.strip().lower()}"`；重复则并入既有（questions 去重追加、user_answer/original_dialogue 仅在既有为空时补、turn_ids 并集排序）。
- 返回 `result + 去重后的 other`（注意：other 组被移到列表末尾）。

### 5.3 `_enrich_leetcode_groups(groups)`
- 目标 = `category==other 且 tag==leetcode`。
- 每个并发调 `fetch_leetcode_info(text)`；text=首问，若有 user_answer 追加 `\n{user_answer[:200]}`。
- 命中写 `leetcode_title/leetcode_slug/leetcode_url/leetcode_difficulty`。
- `asyncio.gather(..., return_exceptions=True)`。

### 5.4 `_normalize_to_legacy_schema(groups)`
- knowledge → `type=knowledge, knowledge_point=tag or "未命名"`
- project → `type=project, topic=tag or ""`
- other：leetcode→`type=algorithm, title=leetcode_title or 首问(默认"未知算法题")`；hr→`type=hr`；其它→`type=other`
- 无 category → `type=other`

### 5.5 `_regroup_by_answer_anchors(groups, turns)` — 以“我”为锚重写 turn_ids
- 建 `speakers={id:speaker}`，`sorted_tids`。
- `me_to_group`：每个属于某 group 的“我”turn → 首次出现的 group index。
- 无任何“我”锚点 → 原样返回。
- 扫 `sorted_tids`，遇到被认领的“我”turn（位置 pos，组 gi）：把区间 `(last_anchor_pos, pos]` 的所有 turn 划给 gi；更新 `last_anchor_pos=pos`。
- 有锚点的 group：`turn_ids=sorted(set(new_ids))`；无锚点 group 保留原 turn_ids。
- 语义：面试官“承上启下”turn 归到它引出的下一个回答所在 group。

### 5.6 `_absorb_orphan_interviewer_groups(groups, turns)`
- 按 group 最小 turn_id 排序扫描。
- 当前 group 全是面试官 turn，且 `max(ids)+1 == min(next_ids)`（紧邻）→ 把 ids 并入 next_g（`sorted(set)`），标记 drop。
- 返回去掉 drop 的 groups。

### 5.7 回填 original_dialogue（内联）
- 对 `original_dialogue` 为空且有 turn_ids 的 group：按 turn_ids 顺序拼 `"{speaker}：{content}"`（speaker 空则无前缀），`\n` 连接。

### 5.8 遗漏二次检查（仅 `len(chunks)==1`）
- 汇总所有 questions，用内联 check_prompt 让 LLM 返回 `{"missed":[...]}`。
- 有遗漏 → 追加一个 `{category:other, tag:misc, type:other, questions:missed, user_answer:"", original_dialogue:"", turn_ids:[]}`，并写 `result["missed_count"]`。
- 异常仅 warning。

check_prompt（逐字）：
```
请对比以下面试原文和已提取的问题列表，检查是否有遗漏的面试提问。

## 面试原文
{raw_text}

## 已提取的问题（{n}个）
{每行 "i. question"}

## 要求
如果有遗漏的面试提问，按JSON格式返回遗漏的问题。如果没有遗漏，返回空数组。
只返回被遗漏的面试官提问，不要重复已提取的。
```json
{"missed": ["遗漏的问题1", "遗漏的问题2"]}
```
```

---

## 6. ASR 转写（`asr.py`）

常量：`MAX_FILE_SIZE=300MB`，`ALLOWED_EXTENSIONS={.mp3,.wav,.m4a,.flac,.ogg,.wma,.aac}`。

`transcribe_audio` 链路：
1. `_convert_to_mono`：ffprobe 探测声道；多声道用 ffmpeg `-ac 1 -ar 16000` 转单声道 16k。（**Java 当前缺失，必须补**）
2. `_upload_to_dashscope_oss`：上传得可访问 URL。
3. `_submit_transcription_task`：提交 Paraformer-v2 异步任务。
4. `_poll_transcription_task`：`max_wait=600s`，每 3s 轮询。
5. `_parse_transcription_output`：下载 `transcription_url`（**3 次重试**，timeout=(10,120)），按 `speaker_id` 合并连续句 → "说话人A/说话人B"。
6. `_normalize_speaker_roles`：取前 1500 字让 LLM 判定谁是面试官/候选人，用占位符 `__IV__`/`__ME__` 替换后再换回「面试官」/「我」，避免误替换。

> Java 当前：缺 mono 转换、缺下载 3 次重试（仅单次）、角色归一化被放在 parser 而非 asr。需对齐到 asr。

---

## 7. ASR 纠错（`asr_corrector.py`）

`correct_asr_turns(turns)`：
- `_chunk_turns_by_char(limit=6000)` 分批。
- 每批 `_correct_one_batch`：LLM `temperature=0.0, max_tokens=8192`，prompt = `ASR_CORRECTION_PROMPT.format(dialogue=render_turns_for_llm(batch))`。
- LLM 返回保留的 turn 子集（**可删除噪声 turn**，id 跳号正常）；按返回的 id 重建 content。
- **批失败保留该批原始 turns**，不阻塞。
- 全流程返回新的 turns 列表（id 在 parser 侧已是全局，纠错只改 content/删 turn）。

---

## 8. 分类型评分（`interview_scorer.py`）

`score_interview_group(group)` 按 `type` 选不同 prompt + 不同输出结构：

| type | prompt | 关键输出字段 |
|------|--------|-------------|
| knowledge | `INTERVIEW_SCORE_PROMPT` | `total_score, feedback, items[{key_point,score,hit,matched_text}], recommended_answer[]` |
| project | `INTERVIEW_PROJECT_SCORE_PROMPT` | `rating(1-5), rating_label, impression, highlights[], improvements[], follow_up_risks[], suggested_answer[]` |
| algorithm | `INTERVIEW_ALGORITHM_SCORE_PROMPT` | `feedback, description, example, suggested_approach, leetcode_id, leetcode_url` |
| hr | `INTERVIEW_HR_SCORE_PROMPT` | `feedback, suggestion` |

`score_all_groups(groups)`：`Semaphore(5)` 并发，返回 `(scored_groups, total_score_sum, scored_count)`（只有 knowledge 计入分值汇总）。

`generate_overall_analysis(scored_groups, company, position)`：
- 按 type 组织 `scored_summary` 行 → `INTERVIEW_OVERALL_ANALYSIS_PROMPT`。
- 输出 `pass_probability, overall_label, comment, signals[], review_points[]`。

> Java 当前用固定 DTO（score+comment+rubric_items），**不是分类型**。必须改为 4 套 prompt + 4 套输出结构 + 总评。

---

## 9. 节点匹配（`interview_matcher.py`）

`match_nodes(groups, db)`：
- knowledge：`match_nearest_knowledge_node`（embedding skill）；无匹配 → 在「未命名知识点」root 下 `_create_orphan_leaf`。
- project：三级 `match_or_create_project_root / topic / question`。

> Java `finalizeInterview` 当前**缺 match_nodes**，需补。

---

## 10. 落库与副作用（`interview_storage.py`）

- `store_new_interview_tables`：按 type 写 `InterviewKnowledgeQuestion / InterviewProjectQuestion / InterviewOtherQuestion`。
- `update_knowledge_weights`：knowledge 命中节点 `interview_weight += 1`（上限 5）。
- `store_answer_embeddings`：knowledge/project 的答案 → `UserAnswerEmbedding`。

---

## 11. finalize 编排（`interview_crud.py`）

`finalize_interview` 顺序：
1. 校验入参。
2. 由 turns 重建 dialogue / questions / user_answer。
3. `match_nodes`（§9）。
4. 创建 `InterviewRecord`。
5. `score_all_groups`（§8）。
6. `update_knowledge_weights`。
7. `store_answer_embeddings`。
8. `store_new_interview_tables`。
9. `generate_overall_analysis` → 回写。

平均分阈值：`>=70 "较高"`，`>=50 "一般"`，否则 `"较低"`。

> Java `finalizeInterview` 当前缺 3/6/7（match_nodes、权重、答案向量）。

---

## 12. 常量速查（必须逐字一致）

| 常量 | 值 | 位置 |
|------|----|------|
| CHUNK_SIZE | 1200 | parser |
| BOUNDARY_SIM_THRESHOLD | 0.82 | parser |
| 单段解析重试 | 3 | parser |
| 解析并发 Semaphore | 5 | parser |
| LLM 解析温度/max_tokens/timeout | 0.1 / 4096 / 120 | parser |
| repair `_SHORT_FRAG_LEN` | 8 | turns |
| chunk t_len 额外开销 | +8 | turns |
| 纠错分批字符上限 | 6000 | asr_corrector |
| 纠错温度/max_tokens | 0.0 / 8192 | asr_corrector |
| ASR 轮询 max_wait/间隔 | 600s / 3s | asr |
| transcription 下载重试 | 3，timeout=(10,120) | asr |
| 角色归一化取样字符 | 1500 | asr |
| 评分并发 Semaphore | 5 | scorer |
| interview_weight 上限 | 5 | storage |
| 平均分阈值 | 70 / 50 | crud |

---

## 13. Prompt 清单（DB seed 必须与 Python 逐字一致）

`backend/prompts/interview_prompts.py`：
- `ASR_CORRECTION_PROMPT`（含完整 ASR 错别字词典 + 输出 JSON 模板）→ DB key `interview/asr-correct`
- `INTERVIEW_PARSE_PROMPT`（第零~五步全文，含说话人判别/技术域拆分/tag 规范化/turn_ids 规则）→ `interview/parse`
- `INTERVIEW_SCORE_PROMPT` → `interview/score-knowledge`
- `INTERVIEW_PROJECT_SCORE_PROMPT` → `interview/score-project`
- `INTERVIEW_ALGORITHM_SCORE_PROMPT` → `interview/score-algorithm`
- `INTERVIEW_HR_SCORE_PROMPT` → `interview/score-hr`
- `INTERVIEW_OVERALL_ANALYSIS_PROMPT` → `interview/overall-analysis`
- 内联 prompt（项目话题合并 / 遗漏检查 / 角色归一化）：可内联在代码或独立 key，但文本须与 Python 一致。

> **历史 bug 根因**：V20/V22 seed 的 `interview/parse` 是“精简版”，丢了第零步说话人判别、第三步技术域拆分、tag 规范化等关键约束 → 全部归到 other/待分类、模块只剩两句。重写时必须把 Python 全文搬进 seed。

---

## 14. Java 已知差异清单（重写时逐项消除）

| 模块 | Python | Java 当前 | 动作 |
|------|--------|-----------|------|
| parse prompt | 完整第零~五步 | 精简版 | seed 全文 |
| 跨段合并 | embedding cos≥0.82 | 仅 exact tag | 补 embedding |
| 项目话题合并 | LLM merge_groups | 无 | 新增 |
| other 去重 | (tag,首问) | 部分 | 对齐 |
| leetcode 补全 | skill | 部分 | 对齐 |
| answer anchor 重排 | 有 | 有(待校验) | 校验 |
| 孤儿组吸收 | 有 | 有(待校验) | 校验 |
| ASR mono 转换 | 有 | 无 | 新增 |
| ASR 下载重试 | 3 | 1 | 补 |
| 角色归一化位置 | asr | parser | 迁回 asr |
| 纠错 correct_asr_turns | 有 | 无/弱 | 新增 |
| 评分 | 4 套分类型 | 1 套固定 | 重写 |
| match_nodes | 有 | finalize 缺 | 补 |
| 权重/答案向量 | 有 | finalize 缺 | 补 |
