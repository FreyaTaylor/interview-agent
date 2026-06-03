"""
学习答题 API — 薄路由层。

资源：
  GET  /api/study/knowledge-points                       推荐知识点列表（含派生 mastery）
  GET  /api/study/knowledge-points/{kp_id}/questions     某知识点的题目列表（含题目分）
  POST /api/study/attempts                               开始作答（body: question_id）
  POST /api/study/attempts/{attempt_id}/turn             提交一轮回答（body: answer）
  POST /api/study/attempts/{attempt_id}/finish           结束并综合打分
  GET  /api/study/attempts/{attempt_id}                  作答详情
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.schemas.common import ApiResponse
from backend.services import qa_engine, qa_aggregate
from backend.services.learn import ensure_kp_studied
from backend.models.qa import StudyQuestion
from backend.services.study_qa_strategy import StudyQAStrategy

router = APIRouter(prefix="/api/study", tags=["学习"])
_strategy = StudyQAStrategy()


class StartAttemptReq(BaseModel):
    question_id: int


class TurnReq(BaseModel):
    answer: str


@router.get("/knowledge-points", summary="推荐 Top N 知识点（按优先级）")
async def list_knowledge_points(
    top_n: int = 10, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """推荐下一批该复习的叶子知识点。

    - 入参 `top_n`：返回条数上限（默认10）
    - 流程：
      1. 拉所有 `leaf` 节点 + 所在章节名
      2. 从 `qa_aggregate.get_kp_mastery` 调出掌握度 (mastery, study_count)
      3. 计算优先度：未学 = `weight*1.0`；已学 = `weight*(1-mastery/100)*0.8`
      4. 按优先度降序、取 Top N
    - 联动：读 `knowledge_node` + 派生自 `question_attempt`；ExamPage 未直接调用（目录由 `/api/knowledge/tree` 提供），保留给后续推荐面使用。
    """
    nodes = (await db.execute(
        select(KnowledgeNode).where(KnowledgeNode.node_type == "leaf")
    )).scalars().all()

    parent_ids = list({n.parent_id for n in nodes if n.parent_id})
    parent_map: dict[int, str] = {}
    if parent_ids:
        parent_map = {
            p.id: p.name
            for p in (await db.execute(
                select(KnowledgeNode).where(KnowledgeNode.id.in_(parent_ids))
            )).scalars().all()
        }

    items: list[dict] = []
    for n in nodes:
        mastery, study_count = await qa_aggregate.get_kp_mastery(db, n.id)
        # 未学过 → 满优先；学过 → 按 (1 - mastery/100) 折扣
        if study_count == 0:
            priority = n.interview_weight * 1.0
        else:
            priority = n.interview_weight * (1.0 - mastery / 100) * 0.8
        items.append({
            "id": n.id,
            "name": n.name,
            "parent_name": parent_map.get(n.parent_id),
            "interview_weight": n.interview_weight,
            "mastery_level": mastery,
            "study_count": study_count,
            "_priority": priority,
        })
    items.sort(key=lambda x: x["_priority"], reverse=True)
    for item in items:
        item.pop("_priority", None)
    return ApiResponse.ok(data=items[:top_n])


@router.get("/knowledge-points/{kp_id}/questions", summary="某知识点下的题目（含题目分）")
async def list_kp_questions(
    kp_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """返回某知识点下的所有题目及其近期得分。

    - 只接受 `node_type=='leaf'` 的知识点
    - **幂等生成**：若该 kp 还未生成 KnowledgeContent + study_question，自动触发一次完整生成
      （题目与讲解同源）
    - 逐题从 `qa_aggregate.get_question_score / get_question_attempt_count` 拉最近3次均分 + 作答次数
    - 调用方：ExamPage 中栏题目列表
    """
    kp = await db.get(KnowledgeNode, kp_id)
    if not kp:
        raise HTTPException(404, "知识点不存在")
    if kp.node_type != "leaf":
        raise HTTPException(400, "请选择叶子知识点")

    # 确保讲解+题目都已生成（首次访问会触发 LLM，耗时较长）
    await ensure_kp_studied(db, kp_id)

    questions = (await db.execute(
        select(StudyQuestion)
        .where(StudyQuestion.knowledge_point_id == kp_id)
        .order_by(StudyQuestion.sort_order, StudyQuestion.id)
    )).scalars().all()

    items = []
    for q in questions:
        score = await qa_aggregate.get_question_score(db, "study", q.id)
        cnt = await qa_aggregate.get_question_attempt_count(db, "study", q.id)
        items.append({
            "id": q.id,
            "content": q.content,
            "sort_order": q.sort_order,
            "score": score,        # null 表示从未作答
            "attempt_count": cnt,
        })
    return ApiResponse.ok(data={
        "knowledge_point_id": kp.id,
        "knowledge_point_name": kp.name,
        "questions": items,
    })


@router.post("/attempts", summary="开始作答一道题")
async def start_attempt_route(
    req: StartAttemptReq, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """创建一条新的作答记录。

    - 入参 `question_id`：study_question.id
    - 委托 `qa_engine.start_attempt`（策略=`StudyQAStrategy`）：读题 + 创建 `question_attempt`（status=in_progress, dialog=[主问题 bubble]）
    - 返回 attempt 完整状态（id / dialog / max_follow_ups 等）供前端 hook 初始化
    - 异常：题不存在 → 404
    """
    try:
        result = await qa_engine.start_attempt(db, _strategy, req.question_id)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        raise HTTPException(404, str(e))


@router.post("/attempts/{attempt_id}/turn", summary="提交一轮回答 → 返回范例 + 可选追问")
async def turn_route(
    attempt_id: int, req: TurnReq, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """提交本轮回答，拿到范例答案与（可选）追问。

    - 入参：`attempt_id` + `answer`。
    - 委托 `qa_engine.process_turn`：调 per-turn LLM 返回 `{covered, recommended_answer, follow_up_question?}` 并追加到 `question_attempt.dialog`
    - 如果 LLM 返回 `follow_up_question=null` 或达到 `max_follow_ups`，前端会自动 finish
    - 异常：4xx 使用状态不合法；LLM 失败 500
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
    """结束作答并调 final-score LLM 进行整轮综合评分。

    - 委托 `qa_engine.finish_attempt`：
      1. 拼 dialog 与题目送给 final-score LLM
      2. LLM 返回 `{rubric_result, final_score, overall_summary}`并写入 `question_attempt`
      3. status 变 `finished`（若无用户答复则 `abandoned`）
    - 作用面：该题后续 `qa_aggregate.get_question_score` 会含本次分数、知识点掌握度同步变动
    """
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
    """拉一个 attempt 的完整 dialog 与评分结果，useQAFlow 刷新用。"""
    try:
        return ApiResponse.ok(data=await qa_engine.get_attempt(db, attempt_id))
    except ValueError as e:
        raise HTTPException(404, str(e))


@router.get("/questions/{question_id}/attempts", summary="该题的所有作答历史")
async def list_question_attempts_route(
    question_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """返回某题的全部作答记录（含 dialog/分数），按时间倒序。供 ExamPage 历史面板展示。"""
    return ApiResponse.ok(
        data=await qa_aggregate.list_question_attempts(db, "study", question_id)
    )
