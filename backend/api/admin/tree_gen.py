"""
管理 API — 知识树生成 / 优化 / 合并 路由
"""
import base64
import logging

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.tree_gen import (
    create_tree_from_text, create_tree_from_generate, create_tree_from_image,
    create_tree_from_mm, optimize_tree, merge_trees,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/admin", tags=["管理-树生成"])


class CreateTreeFromTextRequest(BaseModel):
    text: str


class CreateTreeFromGenerateRequest(BaseModel):
    tree_name: str
    requirements: str = ""


class MergeTreesRequest(BaseModel):
    source_id: int
    target_id: int


@router.post("/trees/from-text", summary="从文本/Markdown创建知识树")
async def create_tree_text(
    req: CreateTreeFromTextRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """接收一大段 Markdown / 纯文本，调 LLM 解析为三层知识树然后入库。调用方：Admin Outliner “文本导入” Tab。"""
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="文本内容不能为空")
    try:
        result = await create_tree_from_text(req.text.strip(), db)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        return ApiResponse.error(40901, str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/trees/from-generate", summary="LLM生成知识树")
async def create_tree_generate(
    req: CreateTreeFromGenerateRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """给定根名称 + 可选要求，由 LLM 从零生成一棵完整知识树并入库。"""
    if not req.tree_name.strip():
        raise HTTPException(status_code=400, detail="树名称不能为空")
    try:
        result = await create_tree_from_generate(req.tree_name.strip(), req.requirements.strip(), db)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        return ApiResponse.error(40901, str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/trees/from-image", summary="从截图解析知识树")
async def create_tree_image(
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """上传思维导图 / 大纲截图，调多模态 LLM 视觉识别为知识树。限制 10MB。"""
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="请上传图片文件")
    content = await file.read()
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="图片不能超过10MB")
    image_b64 = base64.b64encode(content).decode()
    try:
        result = await create_tree_from_image(image_b64, file.content_type, db)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        return ApiResponse.error(40901, str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/trees/from-mm", summary="从.mm文件导入知识树")
async def create_tree_mm(
    file: UploadFile = File(...),
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """导入 FreeMind .mm 思维导图文件。解析 XML 树后直接映射为知识节点，不走 LLM。"""
    if not file.filename or not file.filename.endswith(".mm"):
        raise HTTPException(status_code=400, detail="请上传 .mm 文件")
    content = await file.read()
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="文件不能超过10MB")
    try:
        result = await create_tree_from_mm(content, db)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        return ApiResponse.error(40901, str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/trees/{root_id}/optimize", summary="LLM优化知识树（查漏补缺）")
async def optimize_tree_api(
    root_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """读现有树完整结构 → 调 LLM 提出增装 / 重组建议 → 加固到数据库。"""
    try:
        result = await optimize_tree(root_id, db)
        return ApiResponse.ok(data=result)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/trees/merge", summary="合并知识树")
async def merge_trees_api(
    req: MergeTreesRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """将 source 树以 LLM 语义去重后合并进 target 树。"""
    try:
        result = await merge_trees(req.source_id, req.target_id, db)
        return ApiResponse.ok(data=result)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
