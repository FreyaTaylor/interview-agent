"""
管理 API 包 — 知识树节点 CRUD + 项目节点 CRUD + 树生成
"""
from fastapi import APIRouter

from backend.api.admin.tree_nodes import router as tree_nodes_router
from backend.api.admin.tree_gen import router as tree_gen_router
from backend.api.admin.project_nodes import router as project_nodes_router

# 导出统一 router 供 main.py 使用
router = APIRouter()
router.include_router(tree_nodes_router)
router.include_router(tree_gen_router)
router.include_router(project_nodes_router)
