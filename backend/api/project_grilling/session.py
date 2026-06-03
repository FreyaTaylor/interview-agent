"""
项目拷打 API — 主流程（attempts）

资源：
  POST /api/project-grilling/attempts                       开始作答（body: question_id = project_node L3 id）
  POST /api/project-grilling/attempts/{attempt_id}/turn     提交一轮回答
  POST /api/project-grilling/attempts/{attempt_id}/finish   结束并综合打分
  GET  /api/project-grilling/attempts/{attempt_id}          作答详情
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services import qa_aggregate, qa_engine
from backend.services.project_qa_strategy import ProjectQAStrategy

router = APIRouter(prefix="/api/project-grilling", tags=["project-grilling"])
_strategy = ProjectQAStrategy()


class StartAttemptReq(BaseModel):
    question_id: int   # project_node.id (level=3 leaf)


class TurnReq(BaseModel):
    answer: str


@router.post("/attempts", summary="开始拷打一道题")
async def start_attempt_route(
    req: StartAttemptReq, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """为某个项目 L3 题目创建一次拷打会话。

    - 入参 question_id = `project_node.id`（level=3）
    - 调 `qa_engine.start_attempt(strategy=ProjectQAStrategy)`：写 `question_attempt` 一行，初始化 dialog
    - 联动：前端 ProjectGrillingPage 点击题目时发起
    """
    try:
        result = await qa_engine.start_attempt(db, _strategy, req.question_id)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(404, str(e))


@router.post("/attempts/{attempt_id}/turn", summary="提交一轮回答 → 范例 + 可选追问")
async def turn_route(
    attempt_id: int, req: TurnReq, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """提交用户对当前题的一个回答。

    - 委托 `qa_engine.process_turn`：(1) 写 user 轮次。(2) LLM 生范例并判是否追问 → 动态 covered 推进。(3) 超过 5 轮或覆盖完返回可以 finish 信号。
    """
    try:
        result = await qa_engine.process_turn(db, _strategy, attempt_id, req.answer)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except RuntimeError as e:
        raise HTTPException(500, str(e))


@router.post("/attempts/{attempt_id}/finish", summary="结束本题并综合打分")
async def finish_route(
    attempt_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """结束本题：由 Rubric LLM 对全部 dialog 打出 4×25 维度分 → 写入 `question_attempt.rubric_result` + `score` → 刷新父节点话题分平均。"""
    try:
        result = await qa_engine.finish_attempt(db, _strategy, attempt_id)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(404, str(e))
    except RuntimeError as e:
        raise HTTPException(500, str(e))


@router.get("/attempts/{attempt_id}", summary="作答详情（完整 dialog）")
async def get_attempt_route(
    attempt_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """返回某次拷打的全部 dialog + 评分，供前端重启会话。"""
    try:
        return ApiResponse.ok(data=await qa_engine.get_attempt(db, attempt_id))
    except ValueError as e:
        raise HTTPException(404, str(e))


@router.get("/questions/{question_id}/attempts", summary="该题的所有拷打历史")
async def list_question_attempts_route(
    question_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """返回某 L3 题目的全部拷打记录（含 dialog/分数），按时间倒序。"""
    return ApiResponse.ok(
        data=await qa_aggregate.list_question_attempts(db, "project", question_id)
    )
