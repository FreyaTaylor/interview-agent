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
from backend.models.study import StudySession, Conversation, ConversationMessage, MasteryRecord
from backend.models.interview import InterviewRecord, AlgorithmQuestion, HrQuestion
from backend.schemas.common import ApiResponse
from backend.services.interview import parse_interview_text, match_knowledge_nodes
from backend.agents.study_agent import study_graph

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

    # 5. 批量评分所有 knowledge 类型的知识点
    scored_groups = []
    total_score_sum = 0
    knowledge_count = 0

    for g in enriched_groups:
        g = dict(g)
        if g.get("type") == "knowledge" and g.get("matched_node_id") and g.get("user_answer", "").strip():
            try:
                # 出题
                gen_state = {
                    "action": "start", "knowledge_point_name": g.get("matched_node_name", ""),
                    "user_input": "", "question_history": [],
                    "question_content": "", "rubric_items": [],
                    "score": 0, "rubric_result": {}, "feedback": "",
                    "follow_up": None, "follow_up_rubric": [], "summary": [],
                    "agent_response": "", "phase": "",
                }
                gen_result = await study_graph.ainvoke(gen_state)

                # 评分
                score_state = {
                    "action": "answer", "knowledge_point_name": g.get("matched_node_name", ""),
                    "user_input": g["user_answer"],
                    "question_history": [],
                    "question_content": gen_result["question_content"],
                    "rubric_items": gen_result["rubric_items"],
                    "score": 0, "rubric_result": {}, "feedback": "",
                    "follow_up": None, "follow_up_rubric": [], "summary": [],
                    "agent_response": "", "phase": "",
                }
                score_result = await study_graph.ainvoke(score_state)

                g["score_result"] = {
                    "question": gen_result["question_content"],
                    "total_score": score_result.get("score", 0),
                    "feedback": score_result.get("feedback", ""),
                    "rubric_result": score_result.get("rubric_result", {}).get("items", []),
                    "recommended_answer": score_result.get("rubric_result", {}).get("recommended_answer", []),
                }
                total_score_sum += score_result.get("score", 0)
                knowledge_count += 1

                # 更新掌握度 (EMA)
                mastery_stmt = select(MasteryRecord).where(
                    MasteryRecord.user_id == 1,
                    MasteryRecord.knowledge_point_id == g["matched_node_id"])
                mastery = (await db.execute(mastery_stmt)).scalar_one_or_none()
                if mastery:
                    mastery.mastery_level = int(0.4 * score_result.get("score", 0) + 0.6 * mastery.mastery_level)
                    mastery.study_count += 1
                else:
                    db.add(MasteryRecord(
                        knowledge_point_id=g["matched_node_id"],
                        mastery_level=score_result.get("score", 0), study_count=1))
            except Exception as e:
                logger.error(f"评分失败: {g.get('knowledge_point')}: {e}")
                g["score_result"] = None
        else:
            g["score_result"] = None
        scored_groups.append(g)

    await db.commit()

    # 统计
    stats = {"knowledge": 0, "algorithm": 0, "hr": 0, "project": 0, "other": 0}
    for g in scored_groups:
        t = g.get("type", "other")
        stats[t] = stats.get(t, 0) + 1

    avg_score = round(total_score_sum / knowledge_count) if knowledge_count > 0 else 0

    return ApiResponse.ok(data={
        "record_id": record.id,
        "session_id": session.id,
        "groups": scored_groups,
        "summary": summary,
        "stats": stats,
        "avg_score": avg_score,
        "pass_estimate": "较高" if avg_score >= 70 else "一般" if avg_score >= 50 else "较低",
    })
