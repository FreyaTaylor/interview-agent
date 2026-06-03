"""
学习页面 API — 薄路由层
"""
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.learn import (
    get_or_generate_content, delete_content as svc_delete_content,
    chat as svc_chat, get_chat_history, merge_chat_to_content,
    regenerate_kp_questions,
)

router = APIRouter(prefix="/api/learn", tags=["学习"])


class ChatRequest(BaseModel):
    knowledge_point_id: int
    message: str
    quoted_text: str | None = None


class MergeRequest(BaseModel):
    knowledge_point_id: int
    chat_messages: list[str]


@router.get("/content/{kp_id}", summary="获取知识点讲解内容")
async def get_content(kp_id: int, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """拉取（或首次生成）某个知识点的 Markdown 讲解。

    - 委托 `learn.get_or_generate_content`：命中缓存（`knowledge_content` 表）则直返；未命中调 LLM 生成后入库
    - 调用方：LearnPage 打开知识点
    - 联动：生成时会带上 `user.profile` 、知识点路径作为定制上下文
    """
    try:
        result = await get_or_generate_content(db, kp_id)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/content/{kp_id}", summary="删除知识点讲解内容")
async def delete_content(kp_id: int, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """清除该知识点缓存的讲解内容，下次访问会重新由 LLM 生成。调用方：LearnPage "重生成" 按钮。"""
    try:
        await svc_delete_content(db, kp_id)
        return ApiResponse.ok(data={"deleted": True})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.post("/content/{kp_id}/regenerate-questions", summary="重新生成知识点面试题")
async def regenerate_questions_route(
    kp_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """重新生成某知识点的面试题（含范例答案）。

    - 保留已有作答历史的题目，仅删除无作答的题目并重新生成
    - 用于回填历史数据中 `recommended_answer` 为空的旧题
    """
    try:
        items = await regenerate_kp_questions(db, kp_id)
        return ApiResponse.ok(data={"questions": items})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.post("/chat", summary="学习探索对话")
async def chat_route(req: ChatRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """针对某个知识点的探索式问答（不是考题）。

    - 入参：`knowledge_point_id`、`message`（用户问题）、`quoted_text`（可选邀试引用当前讲解中某段）
    - 委托 `learn.chat`：拼讲解 + 历史对话 + 用户画像 → LLM 返回回复并写入 `learn_chat`
    """
    try:
        result = await svc_chat(db, req.knowledge_point_id, req.message, req.quoted_text)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/chat-history/{kp_id}", summary="获取对话历史")
async def chat_history_route(kp_id: int, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回某知识点下的完整对话记录，供 LearnPage 恢复会话。表：`learn_chat`。"""
    return ApiResponse.ok(data=await get_chat_history(db, kp_id))


@router.post("/merge-chat", summary="将对话内容合并到讲解文章")
async def merge_chat_route(req: MergeRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """把某些对话轮次由 LLM 提炼后补充到讲解文章。

    - 入参：`chat_messages` 是用户选中的对话 ID 或原文列表
    - 委托 `learn.merge_chat_to_content`：读现有 content → LLM 重写 → 更新 `knowledge_content`
    """
    try:
        content = await merge_chat_to_content(db, req.knowledge_point_id, req.chat_messages)
        return ApiResponse.ok(data={"content": content})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
