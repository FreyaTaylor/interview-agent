"""
Phase 0 种子数据
硬编码 3 个知识点（知识树结构）
题目和 Rubric 不再预存，由 LLM 动态生成

运行方式：
    python -m backend.scripts.seed_data

知识点选择（匹配设计文档示例）：
1. Redis 分布式锁（分布式/分布式锁）★5
2. HashMap 原理（Java基础/集合框架）★4
3. MySQL 索引优化（数据库/MySQL）★5
"""
import asyncio
import logging
from sqlalchemy import select
from backend.database import engine, async_session_factory
from backend.models import Base
from backend.models.user import User
from backend.models.knowledge import KnowledgeNode

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


async def seed():
    """写入种子数据"""
    # 创建表
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with async_session_factory() as db:
        # 检查是否已有数据
        existing = await db.execute(select(KnowledgeNode).limit(1))
        if existing.scalar_one_or_none():
            logger.info("数据库已有数据，跳过种子数据写入")
            return

        # ---- 1. 默认用户 ----
        admin = User(username="admin", password="admin", role="admin")
        db.add(admin)
        await db.flush()
        logger.info(f"创建默认用户: admin (id={admin.id})")

        # ---- 2. 知识树结构 ----
        # 一级分类
        cat_java = KnowledgeNode(name="Java基础", level=1, node_type="category", sort_order=1)
        cat_distributed = KnowledgeNode(name="分布式", level=1, node_type="category", sort_order=2)
        cat_db = KnowledgeNode(name="数据库", level=1, node_type="category", sort_order=3)
        db.add_all([cat_java, cat_distributed, cat_db])
        await db.flush()

        # 二级分类
        cat_collection = KnowledgeNode(
            name="集合框架", level=2, node_type="category",
            parent_id=cat_java.id, sort_order=1,
        )
        cat_lock = KnowledgeNode(
            name="分布式锁", level=2, node_type="category",
            parent_id=cat_distributed.id, sort_order=1,
        )
        cat_mysql = KnowledgeNode(
            name="MySQL", level=2, node_type="category",
            parent_id=cat_db.id, sort_order=1,
        )
        db.add_all([cat_collection, cat_lock, cat_mysql])
        await db.flush()

        # 三级叶子节点（知识点）
        kp_hashmap = KnowledgeNode(
            name="HashMap 原理", level=3, node_type="leaf",
            parent_id=cat_collection.id, interview_weight=4, sort_order=1,
        )
        kp_redis_lock = KnowledgeNode(
            name="Redis 分布式锁", level=3, node_type="leaf",
            parent_id=cat_lock.id, interview_weight=5, sort_order=1,
        )
        kp_mysql_index = KnowledgeNode(
            name="MySQL 索引优化", level=3, node_type="leaf",
            parent_id=cat_mysql.id, interview_weight=5, sort_order=1,
        )
        db.add_all([kp_hashmap, kp_redis_lock, kp_mysql_index])

        await db.commit()
        logger.info("种子数据写入完毕:")
        logger.info(f"  - 知识树: 3 个一级 → 3 个二级 → 3 个叶子知识点")
        logger.info(f"  - 题目由 LLM 动态生成，不再预存")


if __name__ == "__main__":
    asyncio.run(seed())
