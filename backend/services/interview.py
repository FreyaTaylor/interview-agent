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
from backend.prompts.interview_prompts import INTERVIEW_PARSE_PROMPT

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


async def parse_interview_text(raw_text: str) -> dict:
    """
    解析面试文本，返回聚类结果。包含二次检查防遗漏。
    长文本自动重试一次。
    Returns: {"groups": [...], "summary": "..."}
    """
    prompt = INTERVIEW_PARSE_PROMPT.format(raw_text=raw_text)
    llm = _get_llm(temperature=0.1)

    result = None
    last_error = None
    for attempt in range(2):  # 最多重试一次
        try:
            response = await llm.ainvoke(prompt)
            result = _parse_json(response.content)
            break
        except (json.JSONDecodeError, IndexError) as e:
            last_error = e
            logger.warning(f"面试文本解析 JSON 失败(第{attempt+1}次): {e}\nLLM 输出前200字: {response.content[:200] if response else 'N/A'}")
            continue
        except Exception as e:
            last_error = e
            logger.error(f"面试文本解析异常(第{attempt+1}次): {type(e).__name__}: {e}")
            continue

    if result is None:
        logger.error(f"面试文本解析最终失败: {last_error}")
        return {"groups": [], "summary": f"解析失败: {type(last_error).__name__}，请重试"}

    # 二次检查：让 LLM 对比原文和提取结果，找遗漏
    groups = result.get("groups", [])
    all_questions = []
    for g in groups:
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
            # 把遗漏的问题加到 other 组
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
