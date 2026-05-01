"""
面试记录解析服务
- 解析面试文本 → 提取问答对 → 聚类知识点
- 匹配知识树节点
"""
import json
import logging
from langchain_openai import ChatOpenAI
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import settings
from backend.models.knowledge import KnowledgeNode
from backend.prompts.interview_prompts import (
    INTERVIEW_PARSE_PROMPT,
    INTERVIEW_SCORE_PROMPT,
    INTERVIEW_PROJECT_SCORE_PROMPT,
)

logger = logging.getLogger(__name__)


def _get_llm(temperature: float = 0.1) -> ChatOpenAI:
    return ChatOpenAI(
        model=settings.DEEPSEEK_MODEL,
        api_key=settings.DEEPSEEK_API_KEY,
        base_url=settings.DEEPSEEK_BASE_URL,
        temperature=temperature,
        max_tokens=8192,
        timeout=120,
    )


def _parse_json(content: str) -> dict:
    """解析 LLM 返回的 JSON，处理 markdown 包裹和可能的截断"""
    content = content.strip()
    if "```json" in content:
        content = content.split("```json")[1]
        if "```" in content:
            content = content.split("```")[0]
        content = content.strip()
    elif "```" in content:
        content = content.split("```")[1]
        if "```" in content:
            content = content.split("```")[0]
        content = content.strip()

    try:
        return json.loads(content)
    except json.JSONDecodeError:
        # JSON 可能被截断，尝试修复
        # 补齐常见截断情况：缺少 ]} 或 }
        for suffix in [']}}', ']}', '}}', '}', ']']:
            try:
                return json.loads(content + suffix)
            except json.JSONDecodeError:
                continue
        raise


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


async def _parse_single_chunk(llm, chunk_text: str, chunk_idx: int, total: int) -> dict | None:
    """解析单段面试文本"""
    if total > 1:
        extra = f"\n\n注意：这是面试记录的第 {chunk_idx+1}/{total} 段，请只解析本段内容。"
    else:
        extra = ""
    prompt = INTERVIEW_PARSE_PROMPT.format(raw_text=chunk_text) + extra

    for attempt in range(2):
        try:
            response = await llm.ainvoke(prompt)
            return _parse_json(response.content)
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
            merge_result = _parse_json(resp.content)
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


async def parse_interview_text(raw_text: str) -> dict:
    """
    解析面试文本，返回聚类结果。
    长文本自动分段解析，最后合并所有分组。
    Returns: {"groups": [...], "summary": "..."}
    """
    chunks = _split_text(raw_text)
    llm = _get_llm(temperature=0.1)

    logger.info(f"面试文本 {len(raw_text)} 字，分为 {len(chunks)} 段解析")

    # 逐段解析
    all_groups = []
    summaries = []
    for i, chunk in enumerate(chunks):
        result = await _parse_single_chunk(llm, chunk, i, len(chunks))
        if result:
            all_groups.extend(result.get("groups", []))
            if result.get("summary"):
                summaries.append(result["summary"])

    if not all_groups:
        return {"groups": [], "summary": "解析失败，请重试"}

    # 合并同项目的 topic（分段解析 + 语义去重）
    all_groups = await _merge_project_topics(all_groups, llm)

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
            check_result = _parse_json(check_resp.content)
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
    与学习模式不同：基于面试官实际问的问题评分，不生成新问题。
    """
    g_type = group.get("type")
    user_answer = group.get("user_answer", "").strip()
    if not user_answer:
        return None

    questions_text = "\n".join(f"- {q}" for q in group.get("questions", []))
    llm = _get_llm(temperature=0.1)

    try:
        if g_type == "knowledge":
            prompt = INTERVIEW_SCORE_PROMPT.format(
                knowledge_point=group.get("knowledge_point", ""),
                questions=questions_text,
                user_answer=user_answer,
            )
        elif g_type == "project":
            prompt = INTERVIEW_PROJECT_SCORE_PROMPT.format(
                project_name=group.get("project_name", "项目"),
                topic=group.get("topic", "拷打"),
                questions=questions_text,
                user_answer=user_answer,
            )
        else:
            return None

        response = await llm.ainvoke(prompt)
        result = _parse_json(response.content)

        if g_type == "project":
            # 项目拷打：面试官整体印象，星级制
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
        else:
            # 知识点：rubric 评分
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
