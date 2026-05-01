"""
知识树初始化服务
- 生成骨架（一级+二级分类）
- 展开叶子（三级知识点+权重）
- 清空旧树 + 写入新树
"""
import asyncio
import logging
from typing import AsyncGenerator

from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.study import MasteryRecord, MasteryHistory, Conversation, ConversationMessage
from backend.models.interview import UserAnswerEmbedding
from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.tree_prompts import TREE_SKELETON_PROMPT, TREE_EXPAND_PROMPT

logger = logging.getLogger(__name__)


async def generate_skeleton(profile_text: str) -> dict:
    """
    Step 1: 生成一级+二级骨架。
    返回: {"categories": [{"name": ..., "children": [{"name": ...}]}]}
    """
    llm = get_llm(temperature=0.3, max_tokens=4096)
    prompt = TREE_SKELETON_PROMPT.format(profile_text=profile_text)

    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            return parse_llm_json(resp.content)
        except Exception as e:
            logger.warning(f"骨架生成第{attempt+1}次失败: {e}")
    raise RuntimeError("骨架生成失败，请重试")


async def expand_subcategory(
    profile_text: str,
    category_name: str,
    subcategory_name: str,
) -> list[dict]:
    """
    Step 2: 将一个二级分类展开为三级叶子知识点。
    返回: [{"name": ..., "interview_weight": 5, "sort_order": 1}]
    """
    llm = get_llm(temperature=0.3, max_tokens=2048)
    prompt = TREE_EXPAND_PROMPT.format(
        profile_text=profile_text,
        category_name=category_name,
        subcategory_name=subcategory_name,
    )

    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            result = parse_llm_json(resp.content)
            return result.get("leaves", [])
        except Exception as e:
            logger.warning(f"展开「{subcategory_name}」第{attempt+1}次失败: {e}")
    logger.error(f"展开「{subcategory_name}」彻底失败")
    return []


async def init_knowledge_tree(
    profile_text: str,
    db: AsyncSession,
) -> AsyncGenerator[dict, None]:
    """
    初始化知识树（流式进度）：
    1. 清空旧树
    2. 生成骨架
    3. 逐个展开二级分类为叶子
    每一步 yield 进度消息。
    """
    # 1. 清空旧数据（按外键依赖顺序删除）
    await db.execute(delete(ConversationMessage))
    await db.execute(delete(MasteryHistory))
    await db.execute(delete(Conversation))
    await db.execute(delete(MasteryRecord))
    await db.execute(delete(UserAnswerEmbedding))
    # knowledge_node 有自引用外键（parent_id），先置空再删
    await db.execute(update(KnowledgeNode).values(parent_id=None))
    await db.execute(delete(KnowledgeNode))
    await db.commit()
    yield {"step": "clear", "message": "已清空旧知识树"}

    # 2. 生成骨架
    yield {"step": "skeleton", "message": "正在生成知识树骨架..."}
    skeleton = await generate_skeleton(profile_text)
    categories = skeleton.get("categories", [])
    if not categories:
        yield {"step": "error", "message": "骨架生成失败，未返回分类"}
        return

    # 统计总任务数（二级分类数量）
    total_subcats = sum(len(c.get("children", [])) for c in categories)
    yield {
        "step": "skeleton_done",
        "message": f"骨架生成完毕：{len(categories)} 个一级分类，{total_subcats} 个二级分类",
        "category_count": len(categories),
        "subcategory_count": total_subcats,
    }

    # 3. 写入一级+二级，并收集展开任务
    expand_tasks = []  # (category_name, subcategory_name, sub_db_node)

    for cat in categories:
        cat_node = KnowledgeNode(
            name=cat["name"],
            level=1,
            node_type="category",
            sort_order=cat.get("sort_order", 0),
        )
        db.add(cat_node)
        await db.flush()

        for sub in cat.get("children", []):
            sub_node = KnowledgeNode(
                parent_id=cat_node.id,
                name=sub["name"],
                level=2,
                node_type="category",
                sort_order=sub.get("sort_order", 0),
            )
            db.add(sub_node)
            await db.flush()
            expand_tasks.append((cat["name"], sub["name"], sub_node))

    await db.commit()

    # 4. 逐个展开二级为三级叶子（并发 3 个）
    semaphore = asyncio.Semaphore(3)
    completed = 0
    total_leaves = 0

    async def expand_one(cat_name: str, sub_name: str, sub_node: KnowledgeNode):
        nonlocal completed, total_leaves
        async with semaphore:
            leaves = await expand_subcategory(profile_text, cat_name, sub_name)
            for leaf in leaves:
                db.add(KnowledgeNode(
                    parent_id=sub_node.id,
                    name=leaf["name"],
                    level=3,
                    node_type="leaf",
                    interview_weight=leaf.get("interview_weight", 3),
                    sort_order=leaf.get("sort_order", 0),
                ))
            total_leaves += len(leaves)
            completed += 1

    # 分批执行并 yield 进度
    batch_size = 3
    for i in range(0, len(expand_tasks), batch_size):
        batch = expand_tasks[i:i + batch_size]
        await asyncio.gather(*(expand_one(*t) for t in batch))
        await db.commit()
        names = [t[1] for t in batch]
        yield {
            "step": "expanding",
            "message": f"已展开：{', '.join(names)}",
            "completed": completed,
            "total": len(expand_tasks),
            "total_leaves": total_leaves,
        }

    yield {
        "step": "done",
        "message": f"知识树初始化完成：{len(categories)} 个一级分类，{len(expand_tasks)} 个二级分类，{total_leaves} 个知识点",
        "category_count": len(categories),
        "subcategory_count": len(expand_tasks),
        "leaf_count": total_leaves,
    }
