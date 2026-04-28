"""
FastAPI 应用入口
"""
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from backend.database import engine
from backend.models import Base
from backend.api.study import router as study_router

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用启动/关闭生命周期"""
    # 启动时：创建所有表（Phase 0 简化，不用 Alembic）
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("数据库表创建/检查完毕")
    yield
    # 关闭时：释放连接池
    await engine.dispose()
    logger.info("数据库连接已关闭")


app = FastAPI(
    title="面试备考 Agent 系统",
    description="以考代学 — 个性化面试知识学习系统",
    version="0.1.0",
    lifespan=lifespan,
)

# CORS（开发环境允许 Streamlit 跨域访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(study_router)


@app.get("/health")
async def health_check():
    return {"status": "ok", "version": "0.1.0"}
