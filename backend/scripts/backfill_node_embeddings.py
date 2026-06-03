"""
一次性脚本：为所有 level=3 且 embedding 为空的知识点节点生成 embedding。

用法：
    PYTHONPATH=. python backend/scripts/backfill_node_embeddings.py
"""
import asyncio
import logging

from sqlalchemy import select, update

from backend.database import async_session_factory
from backend.models.knowledge import KnowledgeNode
from backend.services.embedding import get_embedding

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)


async def _build_text_for_node(node: KnowledgeNode, session) -> str:
    """构造叶子节点的 embedding 输入文本：父路径 + 节点名"""
    # 取父级链路（最多到一级）
    parts: list[str] = [node.name]
    parent_id = node.parent_id
    while parent_id is not None:
        parent = await session.get(KnowledgeNode, parent_id)
        if parent is None:
            break
        parts.append(parent.name)
        parent_id = parent.parent_id
    return " / ".join(reversed(parts))


async def main() -> None:
    async with async_session_factory() as session:
        # 仅处理 embedding 为空的叶子节点
        result = await session.execute(
            select(KnowledgeNode)
            .where(KnowledgeNode.level == 3, KnowledgeNode.embedding.is_(None))
        )
        nodes = result.scalars().all()
        total = len(nodes)
        logger.info(f"待处理叶子节点数：{total}")

        ok = 0
        fail = 0
        for i, node in enumerate(nodes, 1):
            text = await _build_text_for_node(node, session)
            vec = await get_embedding(text)
            if vec is None:
                fail += 1
                logger.warning(f"[{i}/{total}] {text} 获取 embedding 失败")
                continue
            await session.execute(
                update(KnowledgeNode)
                .where(KnowledgeNode.id == node.id)
                .values(embedding=vec)
            )
            ok += 1
            if i % 10 == 0 or i == total:
                await session.commit()
                logger.info(f"[{i}/{total}] 已提交，成功={ok} 失败={fail}")
        await session.commit()
        logger.info(f"完成。成功={ok} 失败={fail} 总数={total}")


if __name__ == "__main__":
    asyncio.run(main())
