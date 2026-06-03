"""
项目拷打 API — 项目查询（薄路由层，仅参数解包 + 服务调用）。

注意：
- 项目"创建"由 admin 解析独占（POST /api/admin/project-nodes/from-text），
  本路由不再提供 create/update/delete 端点
- 话题列表 /projects/{id}/dimensions 已迁移到 history.py（含 id / question_count），
  这里不再注册以免与 history.py 冲突
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services import project_grilling as svc

router = APIRouter(prefix="/api/project-grilling", tags=["project-grilling"])


@router.get("/projects")
async def list_projects_route(db: AsyncSession = Depends(get_db)):
    """项目列表（含真题数 + 准备度统计）。"""
    return ApiResponse.ok(await svc.list_projects(db))
