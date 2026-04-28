"""
学习对话 API
核心接口：开始学习 → 提交回答 → 自由探索 → 查看知识点列表
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode, Question, RubricItem
from backend.models.study import (
    StudySession, Conversation, ConversationMessage, MasteryRecord,
)
from backend.schemas.common import ApiResponse
from backend.schemas.study import (
    StartStudyRequest, SubmitAnswerRequest, ExploreRequest,
    QuestionResponse, ScoreResponse, ExploreResponse,
    KnowledgePointBrief, RubricItemResult,
    ExtensionResponse, ExtensionItem,
)
from backend.agents.study_agent import study_graph
from backend.services.rubric import generate_extension_questions
from backend.config import settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/study", tags=["学习"])


@router.get("/knowledge-points", summary="获取所有叶子知识点列表")
async def list_knowledge_points(
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """获取所有叶子节点知识点（可学习的），含掌握度信息"""
    # 查询所有叶子节点
    stmt = (
        select(KnowledgeNode)
        .where(KnowledgeNode.node_type == "leaf")
        .order_by(KnowledgeNode.interview_weight.desc(), KnowledgeNode.id)
    )
    result = await db.execute(stmt)
    nodes = result.scalars().all()

    # 查询掌握度
    mastery_stmt = select(MasteryRecord).where(MasteryRecord.user_id == 1)
    mastery_result = await db.execute(mastery_stmt)
    mastery_map = {m.knowledge_point_id: m for m in mastery_result.scalars().all()}

    # 查询父节点名称（二级分类）
    parent_ids = [n.parent_id for n in nodes if n.parent_id]
    parent_map = {}
    if parent_ids:
        parent_stmt = select(KnowledgeNode).where(KnowledgeNode.id.in_(parent_ids))
        parent_result = await db.execute(parent_stmt)
        parent_map = {p.id: p.name for p in parent_result.scalars().all()}

    items = []
    for node in nodes:
        mastery = mastery_map.get(node.id)
        items.append(KnowledgePointBrief(
            id=node.id,
            name=node.name,
            parent_name=parent_map.get(node.parent_id),
            interview_weight=node.interview_weight,
            mastery_level=mastery.mastery_level if mastery else 0,
            study_count=mastery.study_count if mastery else 0,
        ))

    return ApiResponse.ok(data=items)


@router.post("/start", summary="开始学习一个知识点")
async def start_study(
    req: StartStudyRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    选择一个知识点开始学习：
    1. 创建 study_session + conversation
    2. 从该知识点的题库中选一题
    3. 返回题目
    """
    # 验证知识点存在且是叶子节点
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node or node.node_type != "leaf":
        raise HTTPException(status_code=404, detail="知识点不存在或不是叶子节点")

    # 查询该知识点的问题（Phase 0 用种子数据，不做懒生成）
    q_stmt = (
        select(Question)
        .where(Question.knowledge_point_id == req.knowledge_point_id)
        .options(selectinload(Question.rubric_items))
        .order_by(Question.sort_order)
        .limit(1)
    )
    q_result = await db.execute(q_stmt)
    question = q_result.scalar_one_or_none()
    if not question:
        raise HTTPException(status_code=404, detail="该知识点暂无题目（Phase 0 需要先运行 seed_data）")

    # 创建学习会话
    session = StudySession(source_type="manual_select", title=f"学习: {node.name}")
    db.add(session)
    await db.flush()

    # 创建对话
    conv = Conversation(
        study_session_id=session.id,
        knowledge_point_id=req.knowledge_point_id,
        question_id=question.id,
    )
    db.add(conv)
    await db.flush()

    # 记录出题消息
    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=question.content,
        message_type="question",
    )
    db.add(msg)

    # 调用 Agent（出题节点）
    agent_state = {
        "action": "start",
        "knowledge_point_id": req.knowledge_point_id,
        "knowledge_point_name": node.name,
        "user_input": "",
        "question_id": question.id,
        "question_content": question.content,
        "rubric_items": [
            {"key_point": r.key_point, "score": r.score}
            for r in question.rubric_items
        ],
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "explore_count": 0,
        "explore_history": "",
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    await db.commit()

    return ApiResponse.ok(data=QuestionResponse(
        conversation_id=conv.id,
        session_id=session.id,
        knowledge_point_name=node.name,
        question_id=question.id,
        question_content=question.content,
    ))


@router.post("/answer", summary="提交回答并获取评分")
async def submit_answer(
    req: SubmitAnswerRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    提交回答，Agent 基于 Rubric 评分：
    1. 加载对话上下文（题目 + Rubric）
    2. 调用 LLM 打分
    3. 存储评分结果
    4. 更新掌握度
    """
    # 加载对话及关联的题目和 Rubric
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    question_stmt = (
        select(Question)
        .where(Question.id == conv.question_id)
        .options(selectinload(Question.rubric_items))
    )
    q_result = await db.execute(question_stmt)
    question = q_result.scalar_one_or_none()
    if not question:
        raise HTTPException(status_code=404, detail="题目不存在")

    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    # 记录用户回答消息
    user_msg = ConversationMessage(
        conversation_id=conv.id,
        role="user",
        content=req.answer,
        message_type="answer",
    )
    db.add(user_msg)

    # 调用 Agent（打分节点）
    agent_state = {
        "action": "answer",
        "knowledge_point_id": conv.knowledge_point_id,
        "knowledge_point_name": node.name if node else "",
        "user_input": req.answer,
        "question_id": question.id,
        "question_content": question.content,
        "rubric_items": [
            {"key_point": r.key_point, "score": r.score}
            for r in question.rubric_items
        ],
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "explore_count": 0,
        "explore_history": "",
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    # 更新对话记录
    conv.user_answer = req.answer
    conv.score = result["score"]
    conv.rubric_result = result["rubric_result"]
    conv.feedback = result["feedback"]

    # 记录评分消息
    score_msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["feedback"],
        message_type="scoring",
    )
    db.add(score_msg)

    # 更新掌握度记录
    mastery_stmt = select(MasteryRecord).where(
        MasteryRecord.user_id == 1,
        MasteryRecord.knowledge_point_id == conv.knowledge_point_id,
    )
    mastery_result = await db.execute(mastery_stmt)
    mastery = mastery_result.scalar_one_or_none()
    if mastery:
        mastery.mastery_level = result["score"]
        mastery.study_count += 1
    else:
        mastery = MasteryRecord(
            knowledge_point_id=conv.knowledge_point_id,
            mastery_level=result["score"],
            study_count=1,
        )
        db.add(mastery)

    await db.commit()

    # 构建响应
    rubric_items_result = [
        RubricItemResult(**item) for item in result["rubric_result"].get("items", [])
    ]
    return ApiResponse.ok(data=ScoreResponse(
        conversation_id=conv.id,
        total_score=result["score"],
        rubric_result=rubric_items_result,
        feedback=result["feedback"],
        standard_answer=question.standard_answer or "",
        follow_up=result["rubric_result"].get("follow_up", ""),
    ))


@router.post("/extensions", summary="获取拓展问题及答案")
async def get_extensions(
    req: SubmitAnswerRequest,  # 复用 conversation_id + answer（answer 字段此处忽略）
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    根据当前对话的知识点和题目，LLM 生成 3 个拓展面试题及答案
    """
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    question = await db.get(Question, conv.question_id)
    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    result = await generate_extension_questions(
        knowledge_point=node.name if node else "",
        question=question.content if question else "",
    )

    extensions = [
        ExtensionItem(**ext) for ext in result.get("extensions", [])
    ]
    return ApiResponse.ok(data=ExtensionResponse(
        conversation_id=conv.id,
        extensions=extensions,
    ))


@router.post("/explore", summary="自由探索追问")
async def explore(
    req: ExploreRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    自由探索：用户追问，Agent 回答（最多 5 轮）
    """
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    if conv.explore_count >= settings.MAX_EXPLORE_ROUNDS:
        return ApiResponse.ok(data=ExploreResponse(
            conversation_id=conv.id,
            answer=f"探索已达上限（{settings.MAX_EXPLORE_ROUNDS}轮），建议进入下一个知识点。",
            explore_count=conv.explore_count,
            max_explore=settings.MAX_EXPLORE_ROUNDS,
        ))

    question = await db.get(Question, conv.question_id)
    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    # 获取已有探索历史
    history_stmt = (
        select(ConversationMessage)
        .where(
            ConversationMessage.conversation_id == conv.id,
            ConversationMessage.message_type == "explore",
        )
        .order_by(ConversationMessage.created_at)
    )
    history_result = await db.execute(history_stmt)
    history_messages = history_result.scalars().all()
    explore_history = "\n".join(
        f"{'用户追问' if m.role == 'user' else 'Agent回答'}：{m.content}"
        for m in history_messages
    )

    # 记录用户追问
    user_msg = ConversationMessage(
        conversation_id=conv.id,
        role="user",
        content=req.question,
        message_type="explore",
    )
    db.add(user_msg)

    # 调用 Agent（探索节点）
    agent_state = {
        "action": "explore",
        "knowledge_point_id": conv.knowledge_point_id,
        "knowledge_point_name": node.name if node else "",
        "user_input": req.question,
        "question_id": conv.question_id or 0,
        "question_content": question.content if question else "",
        "rubric_items": [],
        "score": conv.score or 0,
        "rubric_result": conv.rubric_result or {},
        "feedback": conv.feedback or "",
        "explore_count": conv.explore_count,
        "explore_history": explore_history,
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    # 记录 Agent 回答
    agent_msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["agent_response"],
        message_type="explore",
    )
    db.add(agent_msg)

    # 更新探索轮数
    conv.explore_count = result["explore_count"]

    await db.commit()

    return ApiResponse.ok(data=ExploreResponse(
        conversation_id=conv.id,
        answer=result["agent_response"],
        explore_count=result["explore_count"],
        max_explore=settings.MAX_EXPLORE_ROUNDS,
    ))
