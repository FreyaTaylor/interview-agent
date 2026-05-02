"""
面试记录解析服务
- 解析面试文本 → 提取问答对 → 聚类知识点
- 匹配知识树节点
"""
import json
import logging
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.interview_prompts import (
    INTERVIEW_PARSE_PROMPT,
    INTERVIEW_SCORE_PROMPT,
    INTERVIEW_PROJECT_SCORE_PROMPT,
    INTERVIEW_OVERALL_ANALYSIS_PROMPT,
    INTERVIEW_ALGORITHM_SCORE_PROMPT,
    INTERVIEW_HR_SCORE_PROMPT,
    HR_NORMALIZE_PROMPT,
)

logger = logging.getLogger(__name__)


CHUNK_SIZE = 2000  # 每段约 2000 字符


def _split_text(text: str) -> list[str]:
    """将长文本按段落分割，每段不超过 CHUNK_SIZE"""
    if len(text) <= CHUNK_SIZE:
        return [text]

    chunks = []
    lines = text.split('\n')
    current = []
    current_len = 0

    for line in lines:
        if current_len + len(line) > CHUNK_SIZE and current:
            chunks.append('\n'.join(current))
            current = [line]
            current_len = len(line)
        else:
            current.append(line)
            current_len += len(line) + 1

    if current:
        chunks.append('\n'.join(current))

    # 如果按行分割后某段还是太长（没有换行的大段文本），按字符截断
    final = []
    for chunk in chunks:
        while len(chunk) > CHUNK_SIZE * 1.5:
            # 找一个句号/问号/感叹号的位置截断
            cut = CHUNK_SIZE
            for sep in ['。', '？', '！', '？', '\n', '，', ',']:
                pos = chunk.rfind(sep, 0, CHUNK_SIZE + 200)
                if pos > CHUNK_SIZE // 2:
                    cut = pos + 1
                    break
            final.append(chunk[:cut])
            chunk = chunk[cut:]
        if chunk.strip():
            final.append(chunk)

    return final if final else [text]


async def _parse_single_chunk(llm, chunk_text: str, chunk_idx: int, total: int, prev_groups: list[dict] | None = None) -> dict | None:
    """解析单段面试文本"""
    extra = ""
    if total > 1:
        extra = f"\n\n注意：这是面试记录的第 {chunk_idx+1}/{total} 段，请只解析本段内容。"
        if prev_groups:
            existing = []
            for g in prev_groups:
                t = g.get("type")
                if t == "knowledge":
                    existing.append(f"knowledge: {g.get('knowledge_point', '?')}")
                elif t == "project":
                    existing.append(f"project: {g.get('project_name', '?')} · {g.get('topic', '?')}")
                elif t == "algorithm":
                    existing.append(f"algorithm: {g.get('title', '?')}")
            if existing:
                extra += f"\n\n前面已提取的分组：\n" + "\n".join(f"- {e}" for e in existing)
                extra += "\n如果本段的问题属于已有分组的追问，请归入同名分组（使用相同的 knowledge_point/project_name），不要创建新分组。"
    prompt = INTERVIEW_PARSE_PROMPT.format(raw_text=chunk_text) + extra

    for attempt in range(3):
        try:
            response = await llm.ainvoke(prompt)
            return parse_llm_json(response.content)
        except (json.JSONDecodeError, IndexError) as e:
            logger.warning(f"分段{chunk_idx+1}解析JSON失败(第{attempt+1}次): {e}")
            continue
        except Exception as e:
            logger.error(f"分段{chunk_idx+1}解析异常(第{attempt+1}次): {type(e).__name__}: {e}")
            continue
    return None


async def _merge_project_topics(groups: list[dict], llm) -> list[dict]:
    """
    合并同项目下语义相似的 topic。
    1. 按 project_name 归组
    2. 同项目下多个 topic → LLM 判断哪些可以合并
    3. 合并 questions/user_answer/original_dialogue
    非 project 类型原样保留。
    """
    non_project = [g for g in groups if g.get("type") != "project"]
    projects = [g for g in groups if g.get("type") == "project"]

    if len(projects) <= 1:
        return groups

    # 按 project_name 归组
    by_name = {}
    for g in projects:
        name = (g.get("project_name") or "").strip() or "未命名项目"
        by_name.setdefault(name, []).append(g)

    merged_projects = []
    for proj_name, topics in by_name.items():
        if len(topics) <= 1:
            merged_projects.extend(topics)
            continue

        # LLM 判断同项目下哪些 topic 可以合并
        topic_list = [f"{i+1}. {t.get('topic', '未知')}" for i, t in enumerate(topics)]
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
            merge_groups = merge_result.get("merge_groups", [])

            for mg in merge_groups:
                indices = [idx - 1 for idx in mg if 1 <= idx <= len(topics)]
                if not indices:
                    continue
                # 合并：第一个 topic 作为基础，追加后续的 questions/answer/dialogue
                base = dict(topics[indices[0]])
                for idx in indices[1:]:
                    t = topics[idx]
                    # 合并 topic 名称（取第一个）
                    base["questions"] = (base.get("questions") or []) + (t.get("questions") or [])
                    if t.get("user_answer", "").strip():
                        existing = base.get("user_answer", "").strip()
                        if existing:
                            base["user_answer"] = existing + "\n" + t["user_answer"]
                        else:
                            base["user_answer"] = t["user_answer"]
                    if t.get("original_dialogue", "").strip():
                        existing = base.get("original_dialogue", "").strip()
                        if existing:
                            base["original_dialogue"] = existing + "\n---\n" + t["original_dialogue"]
                        else:
                            base["original_dialogue"] = t["original_dialogue"]
                # 去重 questions
                seen = set()
                unique_q = []
                for q in base.get("questions", []):
                    if q not in seen:
                        seen.add(q)
                        unique_q.append(q)
                base["questions"] = unique_q
                merged_projects.append(base)

            logger.info(f"项目「{proj_name}」: {len(topics)}个topic → {len(merge_groups)}组")
        except Exception as e:
            logger.warning(f"项目话题合并失败（保留原始）: {e}")
            merged_projects.extend(topics)

    return non_project + merged_projects


def _dedup_algorithm_groups(groups: list[dict]) -> list[dict]:
    """
    后处理：仅算法题去重（多段解析可能重复提取同一道题）。
    其他过滤/合并全部交给 LLM Prompt。
    """
    result = []
    algo_seen: dict[str, dict] = {}

    for g in groups:
        if g.get("type") == "algorithm":
            lc_id = g.get("leetcode_id")
            title = (g.get("title") or "").strip().lower()
            key = str(lc_id) if lc_id else title
            if key in algo_seen:
                existing = algo_seen[key]
                eq = existing.get("questions", [])
                for q in g.get("questions", []):
                    if q not in eq:
                        eq.append(q)
                existing["questions"] = eq
                if g.get("user_answer") and not existing.get("user_answer"):
                    existing["user_answer"] = g["user_answer"]
                if g.get("original_dialogue") and not existing.get("original_dialogue"):
                    existing["original_dialogue"] = g["original_dialogue"]
            else:
                algo_seen[key] = g
        else:
            result.append(g)

    return result + list(algo_seen.values())


async def parse_interview_text(raw_text: str) -> dict:
    """
    解析面试文本，返回聚类结果。
    长文本自动分段解析，最后合并所有分组。
    Returns: {"groups": [...], "summary": "..."}
    """
    chunks = _split_text(raw_text)
    llm = get_llm(temperature=0.1, max_tokens=8192, timeout=120)

    logger.info(f"面试文本 {len(raw_text)} 字，分为 {len(chunks)} 段解析")

    # 逐段解析（后续段传入已有分组上下文，避免重复提取）
    all_groups = []
    summaries = []
    for i, chunk in enumerate(chunks):
        result = await _parse_single_chunk(llm, chunk, i, len(chunks), prev_groups=all_groups if i > 0 else None)
        if result:
            all_groups.extend(result.get("groups", []))
            if result.get("summary"):
                summaries.append(result["summary"])

    if not all_groups:
        return {"groups": [], "summary": "解析失败，请重试"}

    # 合并同项目的 topic（分段解析 + 语义去重）
    all_groups = await _merge_project_topics(all_groups, llm)

    # 算法题去重（多段解析可能重复提取）
    all_groups = _dedup_algorithm_groups(all_groups)

    # 合并 summary
    summary = summaries[0] if len(summaries) == 1 else "；".join(summaries) if summaries else ""

    result = {"groups": all_groups, "summary": summary}

    # 二次检查（仅短文本做，长文本分段已经够了）
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
            check_result = parse_llm_json(check_resp.content)
            missed = check_result.get("missed", [])
            if missed:
                logger.info(f"二次检查发现 {len(missed)} 个遗漏问题: {missed}")
                result["groups"].append({
                    "type": "other",
                    "questions": missed,
                    "user_answer": "",
                    "original_dialogue": "",
                })
                result["missed_count"] = len(missed)
        except Exception as e:
            logger.warning(f"二次检查失败（不影响主流程）: {e}")

    return result


async def score_interview_group(group: dict) -> dict | None:
    """
    对面试复盘中的单个分组进行评分。
    knowledge: rubric 评分; project: 面试官印象; algorithm: 解题评价+题目描述
    """
    g_type = group.get("type")
    questions_text = "\n".join(f"- {q}" for q in group.get("questions", []))
    llm = get_llm(temperature=0.1)

    try:
        if g_type == "knowledge":
            user_answer = group.get("user_answer", "").strip()
            if not user_answer:
                return None
            prompt = INTERVIEW_SCORE_PROMPT.format(
                knowledge_point=group.get("knowledge_point", ""),
                questions=questions_text,
                user_answer=user_answer,
            )
        elif g_type == "project":
            user_answer = group.get("user_answer", "").strip()
            if not user_answer:
                return None
            prompt = INTERVIEW_PROJECT_SCORE_PROMPT.format(
                project_name=group.get("project_name", "项目"),
                topic=group.get("topic", "拷打"),
                questions=questions_text,
                user_answer=user_answer,
            )
        elif g_type == "algorithm":
            prompt = INTERVIEW_ALGORITHM_SCORE_PROMPT.format(
                title=group.get("title", "未知算法题"),
                user_answer=group.get("user_answer", "") or "未提供解题过程",
                original_dialogue=group.get("original_dialogue", "") or "无",
            )
        elif g_type == "hr":
            user_answer = group.get("user_answer", "").strip()
            if not user_answer:
                return None
            prompt = INTERVIEW_HR_SCORE_PROMPT.format(
                questions=questions_text,
                user_answer=user_answer,
            )
        else:
            return None

        response = await llm.ainvoke(prompt)
        result = parse_llm_json(response.content)

        if g_type == "project":
            return {
                "type": "project",
                "rating": result.get("rating", 3),
                "rating_label": result.get("rating_label", ""),
                "impression": result.get("impression", ""),
                "highlights": result.get("highlights", []),
                "improvements": result.get("improvements", []),
                "follow_up_risks": result.get("follow_up_risks", []),
                "suggested_answer": result.get("suggested_answer", []),
            }
        elif g_type == "algorithm":
            return {
                "type": "algorithm",
                "feedback": result.get("feedback", ""),
                "description": result.get("description", ""),
                "example": result.get("example", ""),
                "suggested_approach": result.get("suggested_approach", ""),
                "leetcode_id": result.get("leetcode_id"),
                "leetcode_url": result.get("leetcode_url"),
            }
        elif g_type == "hr":
            return {
                "type": "hr",
                "feedback": result.get("feedback", ""),
                "suggestion": result.get("suggestion", ""),
            }
        else:
            return {
                "type": "knowledge",
                "total_score": result.get("total_score", 0),
                "feedback": result.get("feedback", ""),
                "rubric_result": result.get("items", []),
                "recommended_answer": result.get("recommended_answer", []),
            }
    except Exception as e:
        logger.error(f"面试评分失败: {e}")
        return None


async def normalize_hr_questions(questions: list[str]) -> dict[str, str]:
    """
    批量归一化 HR 题：一次 LLM 调用，把所有 HR 题映射到标准问题。
    返回: {"原始问题": "标准问题"}
    """
    if not questions:
        return {}
    llm = get_llm(temperature=0.1)
    q_list = "\n".join(f"{i+1}. {q}" for i, q in enumerate(questions))
    prompt = HR_NORMALIZE_PROMPT.format(questions=q_list)
    try:
        resp = await llm.ainvoke(prompt)
        result = parse_llm_json(resp.content)
        mappings = result.get("mappings", [])
        return {m["original"]: m["normalized"] for m in mappings if "original" in m and "normalized" in m}
    except Exception as e:
        logger.warning(f"HR题归一化失败: {e}")
        return {}


async def generate_overall_analysis(
    scored_groups: list[dict],
    company: str = "",
    position: str = "",
) -> dict | None:
    """
    根据所有评分结果，生成面试官视角的整体分析。
    在所有逐题评分完成后调用一次。
    """
    # 构建摘要：让 LLM 看到全局
    lines = []
    for g in scored_groups:
        t = g.get("type", "other")
        sr = g.get("score_result")
        if t == "knowledge":
            score_info = f"（{sr['total_score']}分）" if sr and sr.get("total_score") else "（未评分）"
            lines.append(f"📖 知识点：{g.get('knowledge_point', '?')} {score_info}")
        elif t == "project":
            rating = f"（{'⭐' * sr.get('rating', 0)}）" if sr and sr.get("rating") else "（未评分）"
            lines.append(f"🔨 项目拷打：{g.get('project_name', '?')} · {g.get('topic', '?')} {rating}")
        elif t == "algorithm":
            lines.append(f"💻 算法题：{g.get('title', '?')}")
        elif t == "hr":
            qs = ", ".join(g.get("questions", [])[:2])
            lines.append(f"💬 HR题：{qs}")

    scored_summary = "\n".join(lines) if lines else "无有效数据"

    prompt = INTERVIEW_OVERALL_ANALYSIS_PROMPT.format(
        company=company or "未知",
        position=position or "未知",
        scored_summary=scored_summary,
    )

    llm = get_llm(temperature=0.3)
    try:
        response = await llm.ainvoke(prompt)
        return parse_llm_json(response.content)
    except Exception as e:
        logger.error(f"整体分析生成失败: {e}")
        return None


async def match_knowledge_nodes(
    groups: list[dict],
    db: AsyncSession,
) -> list[dict]:
    """
    将解析出的知识点名称匹配到知识树的叶子节点
    未匹配到的自动创建新的叶子节点（挂在"面试复盘"分类下）
    """
    # 加载所有叶子节点
    stmt = select(KnowledgeNode).where(KnowledgeNode.node_type == "leaf")
    result = await db.execute(stmt)
    leaves = result.scalars().all()
    leaf_map = {n.name.lower().replace(" ", ""): n for n in leaves}

    # 确保有兜底分类（未分类知识点用）
    # 同时加载所有一级/二级分类用于匹配
    cat_stmt = select(KnowledgeNode).where(KnowledgeNode.node_type == "category")
    cat_result = await db.execute(cat_stmt)
    all_cats = cat_result.scalars().all()
    # level-1 分类 name → node
    cat1_map = {c.name.lower().replace(" ", ""): c for c in all_cats if c.level == 1}
    # level-2 分类 name → node
    cat2_map = {c.name.lower().replace(" ", ""): c for c in all_cats if c.level == 2}

    enriched = []
    for g in groups:
        g = dict(g)
        if g.get("type") == "knowledge":
            kp_name = g.get("knowledge_point", "").lower().replace(" ", "")
            # 精确匹配 → 互相包含匹配
            matched = leaf_map.get(kp_name)
            if not matched:
                for db_name, node in leaf_map.items():
                    if db_name in kp_name or kp_name in db_name:
                        matched = node
                        break

            if matched:
                g["matched_node_id"] = matched.id
                g["matched_node_name"] = matched.name
            else:
                # 自动创建：找或创建合适的父分类
                category_name = g.get("category", "其他").lower().replace(" ", "")
                parent = None

                # 先匹配二级分类
                for cn, cnode in cat2_map.items():
                    if cn in category_name or category_name in cn:
                        parent = cnode
                        break

                # 再匹配一级分类
                if not parent:
                    for cn, cnode in cat1_map.items():
                        if cn in category_name or category_name in cn:
                            parent = cnode
                            break

                # 都没匹配到，创建一级分类
                if not parent:
                    cat_display = g.get("category", "其他")
                    parent = KnowledgeNode(
                        name=cat_display, level=1, node_type="category",
                        sort_order=50, is_user_created=False,
                    )
                    db.add(parent)
                    await db.flush()
                    cat1_map[category_name] = parent

                # 创建叶子节点
                new_level = parent.level + 1
                new_node = KnowledgeNode(
                    parent_id=parent.id,
                    name=g.get("knowledge_point", "未知知识点"),
                    level=new_level, node_type="leaf",
                    interview_weight=3, is_user_created=False,
                )
                db.add(new_node)
                await db.flush()
                g["matched_node_id"] = new_node.id
                g["matched_node_name"] = new_node.name
                g["auto_created"] = True
                leaf_map[kp_name] = new_node

        enriched.append(g)
    return enriched


# ============================================================
# 面试复盘编排层 — 从 API 层提取的 DB 操作
# ============================================================

async def store_algorithm_questions(
    enriched_groups: list[dict],
    record_id: int,
    db: AsyncSession,
) -> dict[int, "AlgorithmQuestion"]:
    """存储算法题（按 leetcode_id 或 title 去重 upsert），返回 group index → db record 映射"""
    from backend.models.interview import AlgorithmQuestion
    algo_db_map = {}
    for idx, g in enumerate(enriched_groups):
        if g["type"] != "algorithm":
            continue
        title = g.get("title", "未知算法题")
        lc_id = g.get("leetcode_id")
        if lc_id:
            stmt = select(AlgorithmQuestion).where(AlgorithmQuestion.leetcode_id == lc_id)
        else:
            stmt = select(AlgorithmQuestion).where(
                func.lower(AlgorithmQuestion.title) == title.strip().lower()
            )
        existing = (await db.execute(stmt)).scalar_one_or_none()
        if existing:
            algo_db_map[idx] = existing
        else:
            new_algo = AlgorithmQuestion(
                interview_record_id=record_id,
                title=title,
                leetcode_id=lc_id,
                leetcode_url=g.get("leetcode_url"),
            )
            db.add(new_algo)
            await db.flush()
            algo_db_map[idx] = new_algo
    return algo_db_map


async def store_hr_questions(
    enriched_groups: list[dict],
    record_id: int,
    db: AsyncSession,
) -> None:
    """批量归一化 HR 题后存储"""
    from backend.models.interview import HrQuestion
    all_hr_questions = []
    for g in enriched_groups:
        if g["type"] == "hr":
            all_hr_questions.extend(g.get("questions", []))
    if not all_hr_questions:
        return
    norm_map = await normalize_hr_questions(all_hr_questions)
    for q in all_hr_questions:
        normalized = norm_map.get(q, q)
        db.add(HrQuestion(
            interview_record_id=record_id,
            question=q,
            normalized_question=normalized,
        ))


async def score_all_groups(
    enriched_groups: list[dict],
    db: AsyncSession,
) -> tuple[list[dict], int, int]:
    """
    批量评分所有分组，更新掌握度。
    返回: (scored_groups, total_score_sum, scored_count)
    """
    from backend.models.study import MasteryRecord
    scored_groups = []
    total_score_sum = 0
    scored_count = 0

    for g in enriched_groups:
        g = dict(g)
        should_score = g.get("type") in ("knowledge", "project", "algorithm", "hr")
        if should_score:
            try:
                sr = await score_interview_group(g)
                g["score_result"] = sr
                if sr and sr.get("type") == "knowledge":
                    total_score_sum += sr.get("total_score", 0)
                    scored_count += 1
                    # 更新掌握度 (EMA)
                    if g["type"] == "knowledge" and g.get("matched_node_id"):
                        mastery_stmt = select(MasteryRecord).where(
                            MasteryRecord.user_id == 1,
                            MasteryRecord.knowledge_point_id == g["matched_node_id"])
                        mastery = (await db.execute(mastery_stmt)).scalar_one_or_none()
                        if mastery:
                            mastery.mastery_level = int(0.4 * sr["total_score"] + 0.6 * mastery.mastery_level)
                            mastery.study_count += 1
                        else:
                            db.add(MasteryRecord(
                                knowledge_point_id=g["matched_node_id"],
                                mastery_level=sr["total_score"], study_count=1))
            except Exception as e:
                logger.error(f"评分失败: {g.get('knowledge_point', g.get('project_name'))}: {e}")
                g["score_result"] = None
        else:
            g["score_result"] = None
        scored_groups.append(g)

    return scored_groups, total_score_sum, scored_count


async def update_algo_scores(
    scored_groups: list[dict],
    algo_db_map: dict[int, "AlgorithmQuestion"],
) -> None:
    """将评分结果回写算法题 DB 记录"""
    for idx, g in enumerate(scored_groups):
        if g.get("type") != "algorithm" or not g.get("score_result") or idx not in algo_db_map:
            continue
        algo_rec = algo_db_map[idx]
        sr = g["score_result"]
        algo_rec.description = sr.get("description") or algo_rec.description
        algo_rec.example = sr.get("example") or algo_rec.example
        algo_rec.suggested_approach = sr.get("suggested_approach") or algo_rec.suggested_approach
        algo_rec.feedback = sr.get("feedback") or algo_rec.feedback
        if sr.get("leetcode_url"):
            algo_rec.leetcode_url = sr["leetcode_url"]
        if sr.get("leetcode_id") and not algo_rec.leetcode_id:
            algo_rec.leetcode_id = sr["leetcode_id"]


async def update_knowledge_weights(
    scored_groups: list[dict],
    db: AsyncSession,
) -> None:
    """面试中出现的知识点，提升面试权重"""
    for g in scored_groups:
        if g.get("type") == "knowledge" and g.get("matched_node_id"):
            node = await db.get(KnowledgeNode, g["matched_node_id"])
            if node and node.interview_weight < 5:
                node.interview_weight = min(5, node.interview_weight + 1)


async def store_answer_embeddings(
    scored_groups: list[dict],
    db: AsyncSession,
) -> None:
    """将用户回答向量化存储，用于 Agent 长期记忆"""
    from backend.models.interview import UserAnswerEmbedding
    from backend.services.embedding import get_embedding
    for g in scored_groups:
        user_answer = g.get("user_answer", "").strip()
        if not user_answer:
            continue
        g_type = g.get("type")
        if g_type not in ("knowledge", "project"):
            continue
        kp_name = g.get("knowledge_point") or g.get("matched_node_name") or ""
        if g_type == "project":
            kp_name = f"{g.get('project_name', '')} · {g.get('topic', '')}"
        questions_text = " | ".join(g.get("questions", []))
        embed_text = f"问题: {questions_text}\n回答: {user_answer}"
        embedding = await get_embedding(embed_text)
        score_val = None
        sr = g.get("score_result")
        if sr and sr.get("type") == "knowledge":
            score_val = sr.get("total_score")
        db.add(UserAnswerEmbedding(
            knowledge_point_id=g.get("matched_node_id"),
            source="interview",
            knowledge_point_name=kp_name,
            question_text=questions_text,
            answer_text=user_answer,
            embedding=embedding,
            score=score_val,
        ))


async def store_project_questions(
    scored_groups: list[dict],
    db: AsyncSession,
) -> None:
    """项目问题落库（语义合并，去重）"""
    from backend.models.interview import ProjectQuestion
    for g in scored_groups:
        if g.get("type") != "project":
            continue
        proj_name = g.get("project_name", "").strip()
        topic = g.get("topic", "").strip()
        if not proj_name:
            continue
        existing_stmt = select(ProjectQuestion).where(
            ProjectQuestion.user_id == 1,
            ProjectQuestion.project_name == proj_name,
            ProjectQuestion.topic == topic,
        )
        existing = (await db.execute(existing_stmt)).scalar_one_or_none()
        new_qs = g.get("questions", [])
        suggested = g.get("score_result", {}).get("suggested_answer", []) if g.get("score_result") else []
        if existing:
            old_qs = existing.questions or []
            existing.questions = list(dict.fromkeys(old_qs + new_qs))
            if suggested:
                existing.suggested_answer = suggested
            existing.interview_count = (existing.interview_count or 1) + 1
        else:
            db.add(ProjectQuestion(
                project_name=proj_name,
                topic=topic,
                questions=new_qs,
                suggested_answer=suggested,
            ))
