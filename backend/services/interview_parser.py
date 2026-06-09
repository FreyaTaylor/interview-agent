"""
面试解析模块 — 文本 → 分组结构

流水线：
    长文本 → Q&A 边界分段 → LLM 并发解析 → embedding 跨段边界合并
          → 同项目话题合并 → other 类去重 → LeetCode skill 补全
          → 转 legacy schema → 二次检查遗漏问题

对外只暴露 parse_interview_text。
LLM 输出新 schema 字段：
    category ∈ {knowledge, project, other}
    tag       — 知识点名 / 项目话题 / other 子类 (leetcode/hr/system_design/misc)
    project_name (仅 project)
    questions / user_answer / original_dialogue
旧前端依赖 type/knowledge_point/topic/title 字段，由 _normalize_to_legacy_schema 补全。

设计文档：docs/INTERVIEW_PARSER_DESIGN.md
"""
import asyncio
import json
import logging
import math
import re

from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json
from backend.services.asr_corrector import correct_asr_turns
from backend.services.interview_turns import (
    split_into_turns,
    repair_turns,
    chunk_turns,
    render_turns_for_llm,
)
from backend.skills.leetcode_skill import fetch_leetcode_info
from backend.prompts.interview_prompts import INTERVIEW_PARSE_PROMPT

logger = logging.getLogger(__name__)


CHUNK_SIZE = 1200  # 每段约 1200 字符 — 小一点更稳健：单次输出 token 少，不易触发 LLM 超长重试
QA_BOUNDARY_RE = re.compile(r"(?:^|\n)\s*面试官\s*[：:]")  # Q 边界 = "面试官：" 出现处
BOUNDARY_SIM_THRESHOLD = 0.82  # embedding 余弦相似度 ≥ 此值 → 判定跨段同 topic


# ============================================================
# 文本分段 — 按 Q&A 边界切，不在一对 Q&A 中间切
# ============================================================

def _split_text(text: str) -> list[str]:
    """
    按"面试官："出现位置作为切点；累积若干完整 Q&A 直到接近 CHUNK_SIZE 才切。
    若整文找不到 Q 标记（说话人未规范化），回退到旧的换行切分。
    """
    if len(text) <= CHUNK_SIZE:
        return [text]

    # 找所有 Q 边界位置（含开头）
    q_positions = [m.start() for m in QA_BOUNDARY_RE.finditer(text)]
    if not q_positions or q_positions[0] != 0:
        q_positions = [0] + q_positions

    # 没识别到 Q 边界 → 回退老逻辑
    if len(q_positions) <= 1:
        return _split_text_fallback(text)

    # 把文本切成"以面试官提问为头"的 Q&A 单元
    units: list[str] = []
    for i, start in enumerate(q_positions):
        end = q_positions[i + 1] if i + 1 < len(q_positions) else len(text)
        unit = text[start:end].strip()
        if unit:
            units.append(unit)

    # 累积 Q&A 单元到 CHUNK_SIZE 才切；单个超长的 Q&A 自成一段
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0
    for u in units:
        # 单个 Q&A 已经超长 → 它单独成段（再大也不在内部切）
        if not current and len(u) > CHUNK_SIZE:
            chunks.append(u)
            continue
        if current_len + len(u) > CHUNK_SIZE and current:
            chunks.append("\n".join(current))
            current = [u]
            current_len = len(u)
        else:
            current.append(u)
            current_len += len(u) + 1
    if current:
        chunks.append("\n".join(current))

    return chunks


def _split_text_fallback(text: str) -> list[str]:
    """老式按换行/标点切（当文本里没有"面试官："标记时用）。"""
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0
    for line in text.split('\n'):
        if current_len + len(line) > CHUNK_SIZE and current:
            chunks.append('\n'.join(current))
            current = [line]
            current_len = len(line)
        else:
            current.append(line)
            current_len += len(line) + 1
    if current:
        chunks.append('\n'.join(current))

    final: list[str] = []
    for chunk in chunks:
        while len(chunk) > CHUNK_SIZE * 1.5:
            cut = CHUNK_SIZE
            for sep in ['。', '？', '！', '\n', '，', ',']:
                pos = chunk.rfind(sep, 0, CHUNK_SIZE + 200)
                if pos > CHUNK_SIZE // 2:
                    cut = pos + 1
                    break
            final.append(chunk[:cut])
            chunk = chunk[cut:]
        if chunk.strip():
            final.append(chunk)
    return final if final else [text]


# ============================================================
# 单段 LLM 解析（独立、可并发）
# ============================================================

async def _parse_single_chunk(
    llm,
    chunk_text: str,
    chunk_idx: int,
    total: int,
) -> dict | None:
    """
    解析单段文本。各段独立、无跨段上下文 — 可全并发；
    跨段的话题续接/重名合并在 _merge_by_embedding_boundary 里用 embedding 处理。
    """
    if total > 1:
        context = (
            f"\n## 当前段位置\n这是面试记录的第 {chunk_idx+1}/{total} 段，请只解析本段内容。"
            f"\n本段已按面试官提问作为边界切分，开头是一个完整的提问；"
            f"如有话题与其他段重叠由系统后处理合并，本段不必猜测上下文。"
        )
    else:
        context = ""

    prompt = INTERVIEW_PARSE_PROMPT.format(raw_text=chunk_text, context=context)

    for attempt in range(3):
        try:
            response = await llm.ainvoke(prompt)
            return parse_llm_json(response.content)
        except (json.JSONDecodeError, IndexError) as e:
            logger.warning(f"分段{chunk_idx+1}解析JSON失败(第{attempt+1}次): {e}")
        except Exception as e:
            logger.error(f"分段{chunk_idx+1}解析异常(第{attempt+1}次): {type(e).__name__}: {e}")
    return None


# ============================================================
# 跨段边界合并：embedding 余弦相似度判同 topic
# ============================================================

def _is_same_group(a: dict, b: dict) -> bool:
    """快速判同：category + tag 完全相同（LLM 偶尔同名直接命中，省一次 embedding）。"""
    if a.get("category") != b.get("category"):
        return False
    if a.get("category") == "project":
        return (
            (a.get("project_name") or "").strip() == (b.get("project_name") or "").strip()
            and (a.get("tag") or "").strip() == (b.get("tag") or "").strip()
        )
    return (a.get("tag") or "").strip() == (b.get("tag") or "").strip()


def _merge_continuation(base: dict, cont: dict) -> None:
    """把 cont 视为 base 的延续段，把 questions/user_answer/original_dialogue/turn_ids 拼到 base。"""
    seen = set(base.get("questions") or [])
    for q in cont.get("questions") or []:
        if q and q not in seen:
            base.setdefault("questions", []).append(q)
            seen.add(q)
    if (cont.get("user_answer") or "").strip():
        prev = (base.get("user_answer") or "").strip()
        base["user_answer"] = (prev + "\n" + cont["user_answer"]) if prev else cont["user_answer"]
    if (cont.get("original_dialogue") or "").strip():
        prev = (base.get("original_dialogue") or "").strip()
        base["original_dialogue"] = (prev + "\n" + cont["original_dialogue"]) if prev else cont["original_dialogue"]
    # turn_ids 取并集并排序
    merged_ids = sorted(set((base.get("turn_ids") or []) + (cont.get("turn_ids") or [])))
    base["turn_ids"] = merged_ids


def _group_signature(g: dict) -> str:
    """构造用于 embedding 的话题签名：tag + 首问截断，避免把整段对话喂给 embedding。"""
    cat = g.get("category") or ""
    tag = (g.get("tag") or "").strip()
    proj = (g.get("project_name") or "").strip()
    first_q = ((g.get("questions") or [""])[0] or "").strip()[:120]
    if cat == "project":
        return f"[项目]{proj}·{tag}: {first_q}"
    return f"[{cat}]{tag}: {first_q}"


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


async def _merge_by_embedding_boundary(chunk_results: list[list[dict]]) -> list[dict]:
    """
    依次拼接各段分组；遇到段边界时，用 embedding 相似度判定"上段末 vs 下段首"是否同 topic：
    - tag 字符串完全相同 → 直接合并（不调 embedding）
    - 否则算 cosine ≥ 阈值 → 合并（这能抓"GC vs JVM 垃圾回收"这类异名同 topic）
    - 否则按新分组追加
    """
    merged: list[dict] = []
    for ci, groups in enumerate(chunk_results):
        if not groups:
            continue
        if not merged:
            merged.extend(groups)
            continue

        last = merged[-1]
        first = groups[0]
        is_dup = False

        if _is_same_group(last, first):
            is_dup = True
            logger.info(f"分段{ci+1}开头与上段「{last.get('tag', '?')}」同名直连，合并对话片段")
        elif last.get("category") == first.get("category"):
            try:
                emb_a, emb_b = await asyncio.gather(
                    get_embedding(_group_signature(last)),
                    get_embedding(_group_signature(first)),
                )
                if emb_a and emb_b:
                    sim = _cosine(emb_a, emb_b)
                    if sim >= BOUNDARY_SIM_THRESHOLD:
                        is_dup = True
                        logger.info(
                            f"分段{ci+1}开头与上段「{last.get('tag', '?')}」"
                            f"语义相似(cos={sim:.2f})，合并对话片段"
                        )
            except Exception as e:
                logger.warning(f"边界 embedding 判定失败（不影响主流程）: {e}")

        if is_dup:
            _merge_continuation(last, first)
            merged.extend(groups[1:])
        else:
            merged.extend(groups)
    return merged


# ============================================================
# 后处理
# ============================================================

async def _merge_project_topics(groups: list[dict], llm) -> list[dict]:
    """同项目下用 LLM 判断哪些 tag 语义重复并合并 questions/user_answer/original_dialogue。"""
    non_project = [g for g in groups if g.get("category") != "project"]
    projects = [g for g in groups if g.get("category") == "project"]

    if len(projects) <= 1:
        return groups

    by_name: dict[str, list[dict]] = {}
    for g in projects:
        name = (g.get("project_name") or "").strip() or "未命名项目"
        by_name.setdefault(name, []).append(g)

    merged_projects: list[dict] = []
    for proj_name, topics in by_name.items():
        if len(topics) <= 1:
            merged_projects.extend(topics)
            continue

        topic_list = [f"{i+1}. {t.get('tag', '未知')}" for i, t in enumerate(topics)]
        merge_prompt = f"""以下是同一个项目「{proj_name}」下的多个面试话题，请判断哪些话题在语义上重复或高度相似，应该合并。

话题列表：
{chr(10).join(topic_list)}

请返回合并方案。如果某些话题应合并，用数组表示（如 [1,3] 表示话题1和3合并）。不需要合并的单独成组。
```json
{{
  "merge_groups": [[1, 3], [2], [4, 5, 6]]
}}
```
只返回 JSON，不要其他内容。如果都不需要合并，每个单独成组即可。"""

        try:
            resp = await llm.ainvoke(merge_prompt)
            merge_result = parse_llm_json(resp.content)
            for mg in merge_result.get("merge_groups", []):
                indices = [idx - 1 for idx in mg if 1 <= idx <= len(topics)]
                if not indices:
                    continue
                base = dict(topics[indices[0]])
                for idx in indices[1:]:
                    t = topics[idx]
                    base["questions"] = (base.get("questions") or []) + (t.get("questions") or [])
                    if t.get("user_answer", "").strip():
                        existing = base.get("user_answer", "").strip()
                        base["user_answer"] = (existing + "\n" + t["user_answer"]) if existing else t["user_answer"]
                    if t.get("original_dialogue", "").strip():
                        existing = base.get("original_dialogue", "").strip()
                        base["original_dialogue"] = (existing + "\n---\n" + t["original_dialogue"]) if existing else t["original_dialogue"]
                    # turn_ids 合并去重
                    base["turn_ids"] = sorted(set((base.get("turn_ids") or []) + (t.get("turn_ids") or [])))
                # questions 去重
                seen = set()
                unique_q = []
                for q in base.get("questions", []):
                    if q not in seen:
                        seen.add(q)
                        unique_q.append(q)
                base["questions"] = unique_q
                merged_projects.append(base)

            logger.info(f"项目「{proj_name}」: {len(topics)}个话题 → {len(merge_result.get('merge_groups', []))}组")
        except Exception as e:
            logger.warning(f"项目话题合并失败（保留原始）: {e}")
            merged_projects.extend(topics)

    return non_project + merged_projects


def _dedup_other_groups(groups: list[dict]) -> list[dict]:
    """other 类按 (tag, 第一个问题) 去重 —— 分段解析时可能重复抽到同一道 leetcode/hr 题。"""
    result: list[dict] = []
    other_seen: dict[str, dict] = {}

    for g in groups:
        if g.get("category") != "other":
            result.append(g)
            continue
        tag = g.get("tag", "misc")
        first_q = (g.get("questions") or [""])[0].strip().lower()
        key = f"{tag}::{first_q}"
        if key in other_seen:
            existing = other_seen[key]
            eq = existing.get("questions", [])
            for q in g.get("questions", []):
                if q not in eq:
                    eq.append(q)
            existing["questions"] = eq
            if g.get("user_answer") and not existing.get("user_answer"):
                existing["user_answer"] = g["user_answer"]
            if g.get("original_dialogue") and not existing.get("original_dialogue"):
                existing["original_dialogue"] = g["original_dialogue"]
            existing["turn_ids"] = sorted(set((existing.get("turn_ids") or []) + (g.get("turn_ids") or [])))
        else:
            other_seen[key] = g

    return result + list(other_seen.values())


async def _enrich_leetcode_groups(groups: list[dict]) -> list[dict]:
    """对 other+tag=leetcode 分组并发调 LeetCode skill 补题目信息（title/slug/url/difficulty）。"""
    targets = [g for g in groups if g.get("category") == "other" and g.get("tag") == "leetcode"]
    if not targets:
        return groups

    async def _enrich_one(g: dict) -> None:
        text = (g.get("questions") or [""])[0]
        if g.get("user_answer"):
            text = f"{text}\n{g['user_answer'][:200]}"
        info = await fetch_leetcode_info(text)
        if info:
            g["leetcode_title"] = info["title"]
            g["leetcode_slug"] = info["slug"]
            g["leetcode_url"] = info["url"]
            g["leetcode_difficulty"] = info.get("difficulty")

    await asyncio.gather(*[_enrich_one(g) for g in targets], return_exceptions=True)
    return groups


def _normalize_to_legacy_schema(groups: list[dict]) -> list[dict]:
    """把 LLM 新 schema 字段补一份 legacy 字段，老前端代码不用改。"""
    out: list[dict] = []
    for g in groups:
        g = dict(g)
        cat = g.get("category")
        tag = g.get("tag")
        if cat == "knowledge":
            g["type"] = "knowledge"
            g["knowledge_point"] = tag or "未命名"
        elif cat == "project":
            g["type"] = "project"
            g["topic"] = tag or ""
        elif cat == "other":
            if tag == "leetcode":
                g["type"] = "algorithm"
                g["title"] = g.get("leetcode_title") or (g.get("questions") or ["未知算法题"])[0]
            elif tag == "hr":
                g["type"] = "hr"
            else:
                g["type"] = "other"
        else:
            g["type"] = "other"
        out.append(g)
    return out


def _regroup_by_answer_anchors(groups: list[dict], turns: list[dict]) -> list[dict]:
    """以“我”的回答 turn 为话题锚点重写 turn_ids。

    规则：
      - LLM 原本声明的 “我” turn_ids 保留（作为该 group 的锚点）
      - 每个“我”turn 与上一个“我”turn 之间的所有面试官 turn（以及与它同位的面试官 turn），全部归到它所在的 group。
      - 这样：面试官“承上启下”的 turn会被归到“下一个话题”（它引出的那个回答所在的 group），
        解决 LLM 把转场 turn 误贴给上一个话题的问题。
      - 没有任何“我”turn 的 group（如只有题面的纯面试官 turn / HR问题未回答）：保留原 turn_ids。
    """
    if not groups or not turns:
        return groups
    speakers = {t["id"]: t.get("speaker") for t in turns}
    sorted_tids = sorted(speakers.keys())

    # 每个“我”turn id → group index
    me_to_group: dict[int, int] = {}
    for gi, g in enumerate(groups):
        for tid in g.get("turn_ids") or []:
            if speakers.get(tid) == "我" and tid not in me_to_group:
                me_to_group[tid] = gi

    if not me_to_group:
        return groups

    # 重新分配：扫过所有 turn，遇到被认领的 “我” turn 时将 (上一锚点, 当前] 区间全部归给该 group
    new_ids: dict[int, list[int]] = {gi: [] for gi in range(len(groups))}
    has_anchor: dict[int, bool] = {gi: False for gi in range(len(groups))}
    last_anchor_pos = -1
    for pos, tid in enumerate(sorted_tids):
        if speakers.get(tid) != "我":
            continue
        gi = me_to_group.get(tid)
        if gi is None:
            # 这个“我”turn 没人认领（理论上不该发生），但它会被后面被认领的 group “吭下”，不重置锚点
            continue
        has_anchor[gi] = True
        for p in range(last_anchor_pos + 1, pos + 1):
            new_ids[gi].append(sorted_tids[p])
        last_anchor_pos = pos

    for gi, g in enumerate(groups):
        if has_anchor[gi]:
            g["turn_ids"] = sorted(set(new_ids[gi]))
        # 否则（无“我”的 group）保留原 turn_ids
    return groups


def _absorb_orphan_interviewer_groups(groups: list[dict], turns: list[dict]) -> list[dict]:
    """合并"纯面试官且位于下一组之前"的孤儿 group 到下一组。

    场景：LLM 把面试官的过渡性话语（"嗯了解了，那再问下一题…"）或某道题的题面
    单独成组，没有任何"我"的回答；甚至该题的回答被 LLM 误划进了后一个话题，导致
    题面 turn 与下一组首 turn 之间不严格相邻。anchor 算法不会触碰这种纯面试官组，
    但它实际属于下一题的"题面/前言"，应当合并入下一组（把问题挂到后组）。判定条件：
      - 当前 group 所有 turn 都是面试官；
      - 当前 group 的最大 turn_id < 下一组的最小 turn_id（整体位于下一组之前）。
    被吸收的组保留下一组的 category/tag。
    """
    if not groups:
        return groups
    speakers = {t["id"]: t.get("speaker") for t in turns}
    # 按 group 首 turn 排序后扫描
    indexed = sorted(
        enumerate(groups),
        key=lambda x: min(x[1]["turn_ids"]) if x[1].get("turn_ids") else 10**9,
    )
    drop: set[int] = set()
    for i in range(len(indexed) - 1):
        gi, g = indexed[i]
        ids = g.get("turn_ids") or []
        if not ids or gi in drop:
            continue
        # 纯面试官？
        if not all(speakers.get(tid) == "面试官" for tid in ids):
            continue
        _, next_g = indexed[i + 1]
        next_ids = next_g.get("turn_ids") or []
        if not next_ids:
            continue
        if max(ids) >= min(next_ids):
            continue
        next_g["turn_ids"] = sorted(set(next_ids + ids))
        drop.add(gi)
    if not drop:
        return groups
    return [g for gi, g in enumerate(groups) if gi not in drop]


# ============================================================
# 对外入口
# ============================================================

async def parse_interview_text(raw_text: str) -> dict:
    """
    解析面试文本 → {"turns": [...], "groups": [...], "summary": "..."}
    流程：切 turns（全局 id） → 按 turns 分段 → 各段并发解析（LLM 直接返回全局 turn_ids）
          → 跨段合并 → 项目话题合并 → other 去重 → LeetCode 补全 → legacy 兼容 → 遗漏检查
    """
    # 0）原文 → 结构化 turns（全局唯一 id），后续 LLM 引用 id 而不是再复制原文
    turns = split_into_turns(raw_text)
    turns = repair_turns(turns)  # 启发式修复 ASR 切错的破碎 turn
    if not turns:
        return {"turns": [], "groups": [], "summary": "面试文本为空"}

    # 0.5）ASR 纠错 + 删除短噪声 turn（按发音对齐修复技术术语错别字）
    # 失败时内部回退原 turns，不阻塞下游
    turns = await correct_asr_turns(turns)
    if not turns:
        return {"turns": [], "groups": [], "summary": "纠错后内容为空"}

    # 1）按 turns 分段并发；每段渲染成 "[tN] 面试官: ..." 喂给 LLM，全局 id 直接返回，免去 remap
    chunks = chunk_turns(turns, chunk_size=CHUNK_SIZE)
    llm = get_llm(temperature=0.1, max_tokens=4096, timeout=120)
    logger.info(f"面试文本 {len(raw_text)} 字 → {len(turns)} 个 turns，分 {len(chunks)} 段并发解析")

    sem = asyncio.Semaphore(5)

    async def _parse_with_sem(idx: int, chunk_turns_: list[dict]) -> list[dict]:
        async with sem:
            chunk_text = render_turns_for_llm(chunk_turns_)
            result = await _parse_single_chunk(llm, chunk_text, idx, len(chunks))
            groups = (result or {}).get("groups", []) or []
            # 校验/裁剪 turn_ids 范围，按升序排序
            valid_ids = {t["id"] for t in chunk_turns_}
            for g in groups:
                raw_ids = g.get("turn_ids") or []
                cleaned = sorted({
                    int(x) for x in raw_ids
                    if isinstance(x, (int, float)) and int(x) in valid_ids
                })
                g["turn_ids"] = cleaned
            return groups

    chunk_results: list[list[dict]] = await asyncio.gather(
        *[_parse_with_sem(i, c) for i, c in enumerate(chunks)]
    )

    # 2）跨段边界合并
    all_groups = await _merge_by_embedding_boundary(chunk_results)

    if not all_groups:
        return {"turns": turns, "groups": [], "summary": "解析失败，请重试"}

    # 3）同项目话题合并
    all_groups = await _merge_project_topics(all_groups, llm)
    # 4）other 去重
    all_groups = _dedup_other_groups(all_groups)
    # 5）LeetCode 补全
    all_groups = await _enrich_leetcode_groups(all_groups)
    # 6）legacy 字段
    all_groups = _normalize_to_legacy_schema(all_groups)

    # 6.5）以“我”为话题锚点重写 turn_ids：面试官的承上启下 turn 默认归到下一话题
    all_groups = _regroup_by_answer_anchors(all_groups, turns)
    # 6.6）吸收"纯面试官 + 紧贴下一组"的孤儿 group（LLM 把过渡问题单独成组的边界 case）
    all_groups = _absorb_orphan_interviewer_groups(all_groups, turns)

    # 7）若 original_dialogue 为空但有 turn_ids，从 turns 拼出 dialogue 给评分 prompt 用
    turn_by_id = {t["id"]: t for t in turns}
    for g in all_groups:
        if not (g.get("original_dialogue") or "").strip() and g.get("turn_ids"):
            lines = []
            for tid in g["turn_ids"]:
                t = turn_by_id.get(tid)
                if not t:
                    continue
                speaker = t.get("speaker") or ""
                prefix = f"{speaker}：" if speaker else ""
                lines.append(f"{prefix}{t['content']}")
            if lines:
                g["original_dialogue"] = "\n".join(lines)

    result = {"turns": turns, "groups": all_groups, "summary": ""}

    # 8）二次检查（仅单段）
    if len(chunks) == 1:
        all_questions = []
        for g in all_groups:
            all_questions.extend(g.get("questions", []))
        check_prompt = f"""请对比以下面试原文和已提取的问题列表，检查是否有遗漏的面试提问。

## 面试原文
{raw_text}

## 已提取的问题（{len(all_questions)}个）
{chr(10).join(f'{i+1}. {q}' for i, q in enumerate(all_questions))}

## 要求
如果有遗漏的面试提问，按JSON格式返回遗漏的问题。如果没有遗漏，返回空数组。
只返回被遗漏的面试官提问，不要重复已提取的。
```json
{{"missed": ["遗漏的问题1", "遗漏的问题2"]}}
```"""
        try:
            check_resp = await llm.ainvoke(check_prompt)
            missed = (parse_llm_json(check_resp.content) or {}).get("missed", [])
            if missed:
                logger.info(f"二次检查发现 {len(missed)} 个遗漏问题: {missed}")
                result["groups"].append({
                    "category": "other",
                    "tag": "misc",
                    "type": "other",
                    "questions": missed,
                    "user_answer": "",
                    "original_dialogue": "",
                    "turn_ids": [],
                })
                result["missed_count"] = len(missed)
        except Exception as e:
            logger.warning(f"二次检查失败（不影响主流程）: {e}")

    return result
