"""
Phase 0 种子数据
硬编码 3 个知识点 + 每个知识点 1 个问题 + 3-5 个 Rubric 关键点

运行方式：
    python -m backend.scripts.seed_data

知识点选择（匹配设计文档示例）：
1. Redis 分布式锁（分布式/分布式锁）★5
2. HashMap 原理（Java基础/集合框架）★4
3. MySQL 索引优化（数据库/MySQL）★5
"""
import asyncio
import logging
from sqlalchemy import select, text
from backend.database import engine, async_session_factory
from backend.models import Base
from backend.models.user import User
from backend.models.knowledge import KnowledgeNode, Question, RubricItem

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
        await db.flush()
        logger.info(f"创建知识树: 3 个一级 → 3 个二级 → 3 个叶子知识点")

        # ---- 3. 问题 + Rubric ----

        # 问题 1: Redis 分布式锁
        q1 = Question(
            knowledge_point_id=kp_redis_lock.id,
            content="Redis 分布式锁怎么实现？需要注意什么问题？",
            standard_answer="使用 SETNX+EX 原子命令加锁，value 存唯一标识防止误删。"
                          "释放锁时用 Lua 脚本保证 get+compare+del 原子性。"
                          "Redisson 的看门狗机制会在锁持有期间自动续期。"
                          "Redis 主从异步复制可能导致锁丢失，RedLock 是一种解决方案但有争议。",
            difficulty=1,
            sort_order=1,
        )
        db.add(q1)
        await db.flush()

        rubric_q1 = [
            RubricItem(question_id=q1.id, key_point="SETNX+EX 原子设置", score=20, sort_order=1),
            RubricItem(question_id=q1.id, key_point="value 用唯一标识防误删", score=20, sort_order=2),
            RubricItem(question_id=q1.id, key_point="Lua 脚本保证删除原子性", score=20, sort_order=3),
            RubricItem(question_id=q1.id, key_point="看门狗续期机制", score=20, sort_order=4),
            RubricItem(question_id=q1.id, key_point="主从切换丢锁 / RedLock", score=20, sort_order=5),
        ]
        db.add_all(rubric_q1)

        # 问题 2: HashMap 原理
        q2 = Question(
            knowledge_point_id=kp_hashmap.id,
            content="请讲一下 Java HashMap 的底层实现原理。",
            standard_answer="JDK 1.8 中 HashMap 底层是数组+链表+红黑树。"
                          "通过 hash(key) 计算数组下标，冲突时用链表存储。"
                          "链表长度超过 8 且数组长度 >= 64 时转为红黑树。"
                          "默认负载因子 0.75，超过阈值时扩容为 2 倍。"
                          "扩容时 rehash 采用高低位拆分，避免重新计算 hash。",
            difficulty=1,
            sort_order=1,
        )
        db.add(q2)
        await db.flush()

        rubric_q2 = [
            RubricItem(question_id=q2.id, key_point="数组+链表+红黑树结构", score=25, sort_order=1),
            RubricItem(question_id=q2.id, key_point="hash 计算与数组下标定位", score=25, sort_order=2),
            RubricItem(question_id=q2.id, key_point="链表转红黑树的条件（长度>8 且数组>=64）", score=25, sort_order=3),
            RubricItem(question_id=q2.id, key_point="扩容机制（负载因子 0.75，2 倍扩容）", score=25, sort_order=4),
        ]
        db.add_all(rubric_q2)

        # 问题 3: MySQL 索引优化
        q3 = Question(
            knowledge_point_id=kp_mysql_index.id,
            content="MySQL 索引优化有哪些常见策略？什么情况下索引会失效？",
            standard_answer="常见策略：最左前缀原则、覆盖索引、索引下推。"
                          "避免在索引列上使用函数或计算。"
                          "索引失效场景：LIKE 以 % 开头、隐式类型转换、OR 条件、"
                          "对索引列使用函数、NOT IN/NOT EXISTS、范围查询后的列。"
                          "使用 EXPLAIN 分析执行计划，关注 type、key、rows、Extra。",
            difficulty=1,
            sort_order=1,
        )
        db.add(q3)
        await db.flush()

        rubric_q3 = [
            RubricItem(question_id=q3.id, key_point="最左前缀原则", score=20, sort_order=1),
            RubricItem(question_id=q3.id, key_point="覆盖索引减少回表", score=20, sort_order=2),
            RubricItem(question_id=q3.id, key_point="索引失效场景（函数/类型转换/LIKE %开头）", score=20, sort_order=3),
            RubricItem(question_id=q3.id, key_point="EXPLAIN 执行计划分析", score=20, sort_order=4),
            RubricItem(question_id=q3.id, key_point="索引下推 / 联合索引设计", score=20, sort_order=5),
        ]
        db.add_all(rubric_q3)

        await db.commit()
        logger.info("种子数据写入完毕:")
        logger.info(f"  - 知识点: Redis分布式锁(★5), HashMap原理(★4), MySQL索引优化(★5)")
        logger.info(f"  - 问题: 3 个，每个配 4-5 个 Rubric 关键点")


if __name__ == "__main__":
    asyncio.run(seed())
