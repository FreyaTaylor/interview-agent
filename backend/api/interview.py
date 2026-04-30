"""
面试复盘 API
- 上传面试文本 → LLM 解析聚类 → 匹配知识树 → 逐个学习
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.study import StudySession
from backend.models.interview import InterviewRecord, AlgorithmQuestion, HrQuestion
from backend.schemas.common import ApiResponse
from backend.services.interview import parse_interview_text, match_knowledge_nodes

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
    parsed = await parse_interview_text(req.text)
    groups = parsed.get("groups", [])
    summary = parsed.get("summary", "")

    if not groups:
        raise HTTPException(status_code=400, detail="未能从文本中识别出面试提问，请检查内容")

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
    for g in enriched_groups:
        if g["type"] == "algorithm":
            db.add(AlgorithmQuestion(
                interview_record_id=record.id,
                title=g.get("title", "未知算法题"),
                leetcode_id=g.get("leetcode_id"),
            ))
        elif g["type"] == "hr":
            for q in g.get("questions", []):
                db.add(HrQuestion(
                    interview_record_id=record.id,
                    question=q,
                ))

    await db.commit()

    # 统计
    stats = {"knowledge": 0, "algorithm": 0, "hr": 0}
    for g in enriched_groups:
        stats[g.get("type", "knowledge")] = stats.get(g.get("type", "knowledge"), 0) + 1

    return ApiResponse.ok(data=ParseResult(
        record_id=record.id,
        session_id=session.id,
        groups=enriched_groups,
        summary=summary,
        stats=stats,
    ))
