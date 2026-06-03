"""
项目拷打 API 包 — 聚合三个子 router 为统一 `router` 对外暴露。

外部仍可用：
    from backend.api.project_grilling import router
保持 main.py 的注册路径不变。

子模块划分：
- schemas.py   请求模型
- projects.py  项目 CRUD + 维度列表
- session.py   会话主流程（start/answer/next/finish/stop）
- history.py   历史查询
"""
from fastapi import APIRouter

from .history import router as _history_router
from .projects import router as _projects_router
from .session import router as _session_router

router = APIRouter()
router.include_router(_projects_router)
router.include_router(_session_router)
router.include_router(_history_router)

__all__ = ["router"]
