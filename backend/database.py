"""
数据库连接管理
使用 SQLAlchemy 2.0 async 模式 + asyncpg 驱动
"""
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker

from backend.config import settings

# 创建异步引擎
engine = create_async_engine(
    settings.DATABASE_URL,
    echo=False,  # 生产环境关闭 SQL 日志
    pool_size=5,
    max_overflow=10,
)

# 异步 session 工厂
async_session_factory = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


async def get_db():
    """FastAPI 依赖注入：获取数据库 session"""
    async with async_session_factory() as session:
        yield session
