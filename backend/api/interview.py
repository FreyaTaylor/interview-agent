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
from backend.models.interview import InterviewRecord, AlgorithmQuestion, HrQuestion, ProjectQuestion, UserAnswerEmbedding
from backend.schemas.common import ApiResponse
from backend.services.interview import parse_interview_text, match_knowledge_nodes, score_interview_group, generate_overall_analysis
from backend.services.embedding import get_embedding

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

    # 5. 批量评分 knowledge + project
    scored_groups = []
    total_score_sum = 0
    scored_count = 0

    for g in enriched_groups:
        g = dict(g)
        should_score = g.get("type") in ("knowledge", "project", "algorithm", "hr")
        if should_score:
            try:
                sr = await score_interview_group(g)
                g["score_result"] = sr
                if sr and sr.get("type") == "knowledge":
                    total_score_sum += sr.get("total_score", 0)
                    scored_count += 1

                    # 更新掌握度 (EMA) — 仅 knowledge 类型
                    if g["type"] == "knowledge" and g.get("matched_node_id"):
                        mastery_stmt = select(MasteryRecord).where(
                            MasteryRecord.user_id == 1,
                            MasteryRecord.knowledge_point_id == g["matched_node_id"])
                        mastery = (await db.execute(mastery_stmt)).scalar_one_or_none()
                        if mastery:
                            mastery.mastery_level = int(0.4 * sr["total_score"] + 0.6 * mastery.mastery_level)
                            mastery.study_count += 1
                        else:
                            db.add(MasteryRecord(
                                knowledge_point_id=g["matched_node_id"],
                                mastery_level=sr["total_score"], study_count=1))
            except Exception as e:
                logger.error(f"评分失败: {g.get('knowledge_point', g.get('project_name'))}: {e}")
                g["score_result"] = None
        else:
            g["score_result"] = None
        scored_groups.append(g)

    # 6. 更新面试频率 interview_weight（被问到的知识点权重 +1）
    for g in scored_groups:
        if g.get("type") == "knowledge" and g.get("matched_node_id"):
            node = await db.get(KnowledgeNode, g["matched_node_id"])
            if node and node.interview_weight < 5:
                node.interview_weight = min(5, node.interview_weight + 1)

    # 7. 用户回答 embedding → Agent 长期记忆
    for g in scored_groups:
        user_answer = g.get("user_answer", "").strip()
        if not user_answer:
            continue
        g_type = g.get("type")
        if g_type in ("knowledge", "project"):
            kp_name = g.get("knowledge_point") or g.get("matched_node_name") or ""
            if g_type == "project":
                kp_name = f"{g.get('project_name', '')} · {g.get('topic', '')}"
            questions_text = " | ".join(g.get("questions", []))
            embed_text = f"问题: {questions_text}\n回答: {user_answer}"
            embedding = await get_embedding(embed_text)
            score_val = None
            sr = g.get("score_result")
            if sr and sr.get("type") == "knowledge":
                score_val = sr.get("total_score")
            db.add(UserAnswerEmbedding(
                knowledge_point_id=g.get("matched_node_id"),
                source="interview",
                knowledge_point_name=kp_name,
                question_text=questions_text,
                answer_text=user_answer,
                embedding=embedding,
                score=score_val,
            ))

    # 8. 项目问题落库（语义合并）
    for g in scored_groups:
        if g.get("type") != "project":
            continue
        proj_name = g.get("project_name", "").strip()
        topic = g.get("topic", "").strip()
        if not proj_name:
            continue
        # 查找已有记录（同 project_name + topic）
        existing_stmt = select(ProjectQuestion).where(
            ProjectQuestion.user_id == 1,
            ProjectQuestion.project_name == proj_name,
            ProjectQuestion.topic == topic,
        )
        existing = (await db.execute(existing_stmt)).scalar_one_or_none()
        new_qs = g.get("questions", [])
        suggested = g.get("score_result", {}).get("suggested_answer", []) if g.get("score_result") else []
        if existing:
            # 合并 questions 去重
            old_qs = existing.questions or []
            merged = list(dict.fromkeys(old_qs + new_qs))
            existing.questions = merged
            if suggested:
                existing.suggested_answer = suggested
            existing.interview_count = (existing.interview_count or 1) + 1
        else:
            db.add(ProjectQuestion(
                project_name=proj_name,
                topic=topic,
                questions=new_qs,
                suggested_answer=suggested,
            ))

    await db.commit()

    # 7. 整体分析（面试官视角系统性评价）
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
        key = (r.title or "").strip().lower()
        if key in algo_map:
            algo_map[key]["count"] += 1
        else:
            algo_map[key] = {"title": r.title, "leetcode_id": r.leetcode_id, "count": 1}
    algos = list(algo_map.values())

    # HR题：按问题文本去重
    hr_stmt = select(HrQuestion).order_by(HrQuestion.id)
    hr_result = await db.execute(hr_stmt)
    hr_seen = set()
    hrs = []
    for r in hr_result.scalars().all():
        key = (r.question or "").strip().lower()
        if key not in hr_seen:
            hr_seen.add(key)
            hrs.append({"question": r.question, "answer": r.answer})

    return ApiResponse.ok(data={"algorithm": algos, "hr": hrs})
