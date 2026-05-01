"""
面试复盘 API
- 上传面试文本 → LLM 解析聚类 → 匹配知识树 → 批量评分
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.models.study import StudySession
from backend.models.interview import InterviewRecord, AlgorithmQuestion, HrQuestion, ProjectQuestion
from backend.schemas.common import ApiResponse
from backend.services.interview import (
    parse_interview_text, match_knowledge_nodes,
    generate_overall_analysis,
    store_algorithm_questions, store_hr_questions, score_all_groups,
    update_algo_scores, update_knowledge_weights,
    store_answer_embeddings, store_project_questions,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/interview", tags=["面试复盘"])


class UploadTextRequest(BaseModel):
    text: str
    company: str = ""
    position: str = ""


class ParseResult(BaseModel):
    record_id: int
    session_id: int
    groups: list[dict]
    summary: str
    stats: dict  # {"knowledge": N, "algorithm": N, "hr": N}


@router.post("/parse", summary="上传面试文本并解析")
async def parse_interview(
    req: UploadTextRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    上传面试记录文本：
    1. LLM 解析提问 → 聚类为知识点组
    2. 匹配知识树节点
    3. 存储解析结果
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="面试文本不能为空")

    # 1. LLM 解析
    try:
        parsed = await parse_interview_text(req.text)
    except Exception as e:
        logger.error(f"面试文本解析异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"解析服务异常: {type(e).__name__}，请重试")

    groups = parsed.get("groups", [])
    summary = parsed.get("summary", "")

    if not groups:
        return ApiResponse.error(code=40001, message=summary or "未能从文本中识别出面试提问，请检查内容")

    # 2. 匹配知识树
    enriched_groups = await match_knowledge_nodes(groups, db)

    # 3. 创建 session + record
    title = f"面试复盘"
    if req.company:
        title += f": {req.company}"
    if req.position:
        title += f" {req.position}"

    session = StudySession(source_type="text_upload", title=title)
    db.add(session)
    await db.flush()

    record = InterviewRecord(
        study_session_id=session.id,
        raw_text=req.text,
        parsed_questions={"groups": enriched_groups},
        cluster_result={"summary": summary},
    )
    db.add(record)
    await db.flush()

    # 4. 存储算法题和 HR 题
    algo_db_map = await store_algorithm_questions(enriched_groups, record.id, db)
    await store_hr_questions(enriched_groups, record.id, db)
    await db.commit()

    # 5. 批量评分 + 更新掌握度
    scored_groups, total_score_sum, scored_count = await score_all_groups(enriched_groups, db)

    # 6. 评分结果回写 DB
    await update_algo_scores(scored_groups, algo_db_map)
    await update_knowledge_weights(scored_groups, db)

    # 7. 用户回答 embedding → Agent 长期记忆
    await store_answer_embeddings(scored_groups, db)

    # 8. 项目问题落库（语义合并）
    await store_project_questions(scored_groups, db)
    await db.commit()

    # 9. 整体分析（面试官视角系统性评价）
    overall_analysis = await generate_overall_analysis(scored_groups, req.company, req.position)

    # 统计
    stats = {"knowledge": 0, "algorithm": 0, "hr": 0, "project": 0, "other": 0}
    for g in scored_groups:
        t = g.get("type", "other")
        stats[t] = stats.get(t, 0) + 1

    avg_score = round(total_score_sum / scored_count) if scored_count > 0 else 0

    return ApiResponse.ok(data={
        "record_id": record.id,
        "session_id": session.id,
        "groups": scored_groups,
        "summary": summary,
        "stats": stats,
        "avg_score": avg_score,
        "pass_estimate": "较高" if avg_score >= 70 else "一般" if avg_score >= 50 else "较低",
        "overall_analysis": overall_analysis,
    })


@router.get("/project-questions", summary="获取所有累积的项目拷打问题")
async def get_project_questions(
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    stmt = select(ProjectQuestion).where(ProjectQuestion.user_id == 1).order_by(ProjectQuestion.project_name)
    result = await db.execute(stmt)
    rows = result.scalars().all()
    return ApiResponse.ok(data=[{
        "id": r.id,
        "project_name": r.project_name,
        "topic": r.topic,
        "questions": r.questions or [],
        "suggested_answer": r.suggested_answer or [],
        "interview_count": r.interview_count,
    } for r in rows])


@router.get("/other-questions", summary="获取所有累积的其他问题（去重）")
async def get_other_questions(
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    # 算法题：按 title 去重，记录出现次数
    algo_stmt = select(AlgorithmQuestion).order_by(AlgorithmQuestion.id)
    algo_result = await db.execute(algo_stmt)
    algo_map = {}
    for r in algo_result.scalars().all():
        # 优先按 leetcode_id 去重，否则按 title
        key = str(r.leetcode_id) if r.leetcode_id else (r.title or "").strip().lower()
        if key in algo_map:
            algo_map[key]["count"] += 1
        else:
            algo_map[key] = {
                "title": r.title,
                "leetcode_id": r.leetcode_id,
                "leetcode_url": r.leetcode_url,
                "description": r.description,
                "example": r.example,
                "suggested_approach": r.suggested_approach,
                "feedback": r.feedback,
                "count": 1,
            }
    algos = list(algo_map.values())

    # HR题：按 normalized_question 归类去重，显示出现次数
    hr_stmt = select(HrQuestion).order_by(HrQuestion.id)
    hr_result = await db.execute(hr_stmt)
    hr_map = {}
    for r in hr_result.scalars().all():
        key = (r.normalized_question or r.question or "").strip()
        if key in hr_map:
            hr_map[key]["count"] += 1
        else:
            hr_map[key] = {"question": key, "answer": r.answer, "count": 1}
    hrs = list(hr_map.values())

    return ApiResponse.ok(data={"algorithm": algos, "hr": hrs})
