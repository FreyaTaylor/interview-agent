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
    )


def _parse_json(content: str) -> dict:
    content = content.strip()
    if "```json" in content:
        content = content.split("```json")[1].split("```")[0].strip()
    elif "```" in content:
        content = content.split("```")[1].split("```")[0].strip()
    return json.loads(content)


async def parse_interview_text(raw_text: str) -> dict:
    """
    解析面试文本，返回聚类结果
    Returns: {"groups": [...], "summary": "..."}
    """
    prompt = INTERVIEW_PARSE_PROMPT.format(raw_text=raw_text)
    llm = _get_llm(temperature=0.1)
    response = await llm.ainvoke(prompt)

    try:
        return _parse_json(response.content)
    except (json.JSONDecodeError, IndexError) as e:
        logger.error(f"面试文本解析 JSON 失败: {e}\nLLM 输出: {response.content}")
        return {"groups": [], "summary": "解析失败，请重试"}


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

    # 确保有"面试复盘"一级分类（用于挂新知识点）
    review_cat = None
    cat_stmt = select(KnowledgeNode).where(
        KnowledgeNode.name == "面试复盘", KnowledgeNode.level == 1
    )
    cat_result = await db.execute(cat_stmt)
    review_cat = cat_result.scalar_one_or_none()
    if not review_cat:
        review_cat = KnowledgeNode(
            name="面试复盘", level=1, node_type="category",
            sort_order=99, is_user_created=False,
        )
        db.add(review_cat)
        await db.flush()

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
                # 自动创建新叶子节点
                new_node = KnowledgeNode(
                    parent_id=review_cat.id,
                    name=g.get("knowledge_point", "未知知识点"),
                    level=2, node_type="leaf",
                    interview_weight=3, is_user_created=False,
                )
                db.add(new_node)
                await db.flush()
                g["matched_node_id"] = new_node.id
                g["matched_node_name"] = new_node.name
                g["auto_created"] = True
                # 更新 leaf_map 防止同名重复创建
                leaf_map[kp_name] = new_node

        enriched.append(g)
    return enriched
