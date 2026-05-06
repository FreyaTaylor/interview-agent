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
from backend.prompts.tree_prompts import (
    TREE_SKELETON_PROMPT, TREE_EXPAND_PROMPT,
    TREE_FROM_IMAGE_PROMPT, TREE_EXPAND_FROM_OUTLINE_PROMPT,
)
from backend.config import settings

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
    node_path: str,
    existing_leaves: list[str] = None,
) -> list[dict]:
    """
    Step 2: 将一个分类展开为完整子树（一次 LLM 调用，返回嵌套结构）。
    返回: [{"name": ..., "interview_weight": 5, "children": [...]} or {"name": ..., "interview_weight": 4}]
    """
    llm = get_llm(temperature=0.3, max_tokens=4096)
    existing_text = "（暂无）" if not existing_leaves else "\n".join(f"- {l}" for l in existing_leaves)
    prompt = TREE_EXPAND_PROMPT.format(
        profile_text=profile_text,
        node_path=node_path,
        existing_leaves=existing_text,
    )

    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            result = parse_llm_json(resp.content)
            return result.get("children", [])
        except Exception as e:
            logger.warning(f"展开「{node_path}」第{attempt+1}次失败: {e}")
    logger.error(f"展开「{node_path}」彻底失败")
    return []
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
        cat_name = cat["name"]
        # 跳过项目经验类（从面试复盘获取）
        if "项目" in cat_name and ("经验" in cat_name or "实战" in cat_name):
            logger.info(f"跳过项目经验分类: {cat_name}")
            continue
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

    # 4. 逐个展开二级为完整子树（并发 3 个，全局去重）
    semaphore = asyncio.Semaphore(3)
    completed = 0
    total_leaves = 0
    all_leaf_names: list[str] = []  # 全局已有叶子名，用于去重

    def _collect_leaf_names(children: list[dict]) -> list[str]:
        """从嵌套结构中收集所有叶子名"""
        names = []
        for c in children:
            if c.get("children"):
                names.extend(_collect_leaf_names(c["children"]))
            else:
                names.append(c["name"].strip())
        return names

    async def _write_children(parent_id: int, parent_level: int, children: list[dict]) -> int:
        """递归写入嵌套子树到 DB，返回叶子数"""
        leaf_count = 0
        for i, child in enumerate(children):
            name = child["name"].strip()
            has_kids = bool(child.get("children"))
            level = parent_level + 1
            node_type = "category" if has_kids else "leaf"

            # 叶子去重
            if not has_kids:
                if name.lower() in [n.lower() for n in all_leaf_names]:
                    continue
                all_leaf_names.append(name)

            node = KnowledgeNode(
                parent_id=parent_id,
                name=name,
                level=level,
                node_type=node_type,
                interview_weight=child.get("interview_weight", 3),
                sort_order=i,
            )
            db.add(node)
            await db.flush()

            if has_kids:
                leaf_count += await _write_children(node.id, level, child["children"])
            else:
                leaf_count += 1
        return leaf_count

    async def expand_one(cat_name: str, sub_name: str, sub_node: KnowledgeNode):
        nonlocal completed, total_leaves
        async with semaphore:
            node_path = f"{cat_name} → {sub_name}"
            children = await expand_subcategory(profile_text, node_path, existing_leaves=list(all_leaf_names))
            added = await _write_children(sub_node.id, sub_node.level, children)
            total_leaves += added
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

    # 5. 清理空的二级分类（展开失败或全部去重的）
    empty_subs = await db.execute(
        select(KnowledgeNode).where(
            KnowledgeNode.level == 2,
            ~KnowledgeNode.id.in_(
                select(KnowledgeNode.parent_id).where(KnowledgeNode.parent_id.isnot(None))
            )
        )
    )
    empty_count = 0
    for empty_node in empty_subs.scalars().all():
        await db.delete(empty_node)
        empty_count += 1
    if empty_count:
        await db.commit()
        logger.info(f"清理 {empty_count} 个空二级分类")

    yield {
        "step": "done",
        "message": f"知识树初始化完成：{len(categories)} 个一级分类，{total_leaves} 个知识点",
        "category_count": len(categories),
        "subcategory_count": len(expand_tasks) - empty_count,
        "leaf_count": total_leaves,
    }


async def extract_tree_from_image(image_base64: str) -> dict:
    """
    从用户上传的脑图图片中提取知识树结构。
    使用 DashScope VL 模型（通义千问视觉）。
    """
    import httpx
    url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    headers = {"Authorization": f"Bearer {settings.DASHSCOPE_API_KEY}", "Content-Type": "application/json"}
    body = {
        "model": "qwen-vl-max",
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{image_base64}"}},
                    {"type": "text", "text": TREE_FROM_IMAGE_PROMPT},
                ],
            }
        ],
        "max_tokens": 4096,
    }
    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.post(url, headers=headers, json=body)
        resp.raise_for_status()
        content = resp.json()["choices"][0]["message"]["content"]
    return parse_llm_json(content)


async def expand_from_outline(profile_text: str, user_outline: str) -> dict:
    """
    基于用户的知识大纲做查漏补缺。
    """
    llm = get_llm(temperature=0.3, max_tokens=8192)
    prompt = TREE_EXPAND_FROM_OUTLINE_PROMPT.format(
        profile_text=profile_text,
        user_outline=user_outline,
    )
    resp = await llm.ainvoke(prompt)
    return parse_llm_json(resp.content)


async def init_tree_from_image(
    image_base64: str,
    profile_text: str,
    db: AsyncSession,
) -> AsyncGenerator[dict, None]:
    """
    从图片初始化知识树：
    1. 清空旧树
    2. VL 模型提取图片中的知识树
    3. LLM 查漏补缺
    4. 写入 DB
    """
    # 1. 清空
    await db.execute(delete(ConversationMessage))
    await db.execute(delete(MasteryHistory))
    await db.execute(delete(Conversation))
    await db.execute(delete(MasteryRecord))
    await db.execute(delete(UserAnswerEmbedding))
    await db.execute(update(KnowledgeNode).values(parent_id=None))
    await db.execute(delete(KnowledgeNode))
    await db.commit()
    yield {"step": "clear", "message": "已清空旧知识树"}

    # 2. 提取图片
    yield {"step": "extract", "message": "正在识别图片中的知识点..."}
    try:
        extracted = await extract_tree_from_image(image_base64)
    except Exception as e:
        logger.error(f"图片识别失败: {e}")
        yield {"step": "error", "message": f"图片识别失败: {e}"}
        return

    # 转为文本大纲
    outline_lines = []
    for cat in extracted.get("categories", []):
        outline_lines.append(cat["name"])
        for sub in cat.get("children", []):
            leaves = sub.get("leaves", [])
            outline_lines.append(f"  {sub['name']}: {', '.join(leaves) if leaves else ''}")
    user_outline = "\n".join(outline_lines)

    cat_count = len(extracted.get("categories", []))
    leaf_count = sum(len(l) for c in extracted.get("categories", []) for s in c.get("children", []) for l in [s.get("leaves", [])])
    yield {"step": "extracted", "message": f"识别到 {cat_count} 个一级分类，{leaf_count} 个知识点", "outline": user_outline}

    # 3. LLM 查漏补缺
    yield {"step": "expanding", "message": "正在查漏补缺..."}
    try:
        expanded = await expand_from_outline(profile_text, user_outline)
    except Exception as e:
        logger.error(f"查漏补缺失败: {e}")
        yield {"step": "error", "message": f"扩充失败: {e}"}
        return

    # 4. 写入 DB
    categories = expanded.get("categories", [])
    total_leaves = 0
    for cat in categories:
        cat_name = cat["name"]
        if "项目" in cat_name and ("经验" in cat_name or "实战" in cat_name):
            continue
        cat_node = KnowledgeNode(
            name=cat_name, level=1, node_type="category",
            sort_order=cat.get("sort_order", 0),
        )
        db.add(cat_node)
        await db.flush()

        for sub in cat.get("children", []):
            sub_node = KnowledgeNode(
                parent_id=cat_node.id, name=sub["name"],
                level=2, node_type="category",
                sort_order=sub.get("sort_order", 0),
            )
            db.add(sub_node)
            await db.flush()

            for leaf in sub.get("leaves", []):
                if isinstance(leaf, str):
                    leaf = {"name": leaf, "interview_weight": 3, "is_new": False}
                db.add(KnowledgeNode(
                    parent_id=sub_node.id, name=leaf["name"],
                    level=3, node_type="leaf",
                    interview_weight=leaf.get("interview_weight", 3),
                ))
                total_leaves += 1

    await db.commit()

    new_count = sum(
        1 for c in categories for s in c.get("children", []) for l in s.get("leaves", [])
        if isinstance(l, dict) and l.get("is_new")
    )

    yield {
        "step": "done",
        "message": f"知识树初始化完成：{len(categories)} 个分类，{total_leaves} 个知识点（其中 {new_count} 个为补充）",
        "category_count": len(categories),
        "leaf_count": total_leaves,
        "new_count": new_count,
    }
