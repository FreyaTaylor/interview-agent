"""
学习对话 API
核心接口：开始学习 → 提交回答（含追问打分）→ 下一题 → 查看知识点列表
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.models.study import (
    StudySession, Conversation, ConversationMessage, MasteryRecord,
)
from backend.schemas.common import ApiResponse
from backend.schemas.study import (
    StartStudyRequest, StartWithAnswerRequest, SubmitAnswerRequest, NextQuestionRequest,
    QuestionResponse, ScoreResponse,
    KnowledgePointBrief, RubricItemResult,
)
from backend.agents.study_agent import study_graph

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/study", tags=["学习"])


def _build_question_history(conv: Conversation) -> list[dict]:
    """从 conversation 的 learning_summaries 构建出题历史上下文"""
    summaries = conv.learning_summaries or []
    history = []
    for s in summaries:
        missed = [
            item["key_point"]
            for item in s.get("rubric_result", {}).get("items", [])
            if not item.get("hit", False)
        ]
        history.append({
            "question": s.get("question", ""),
            "score": s.get("score", 0),
            "missed": missed,
        })
    return history


@router.get("/knowledge-points", summary="获取所有叶子知识点列表")
async def list_knowledge_points(
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """获取所有叶子节点知识点（可学习的），含掌握度信息"""
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

    # 查询父节点名称
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
    2. 调用 LLM 动态生成第一题
    3. 返回题目
    """
    # 验证知识点存在且是叶子节点
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node or node.node_type != "leaf":
        raise HTTPException(status_code=404, detail="知识点不存在或不是叶子节点")

    # 创建学习会话
    session = StudySession(source_type="manual_select", title=f"学习: {node.name}")
    db.add(session)
    await db.flush()

    # 创建对话
    conv = Conversation(
        study_session_id=session.id,
        knowledge_point_id=req.knowledge_point_id,
        question_round=1,
        learning_summaries=[],
        status="questioning",
    )
    db.add(conv)
    await db.flush()

    # 查询该知识点的历史学习记录（之前的 conversation），构建出题上下文
    history_stmt = (
        select(Conversation)
        .where(
            Conversation.knowledge_point_id == req.knowledge_point_id,
            Conversation.learning_summaries.isnot(None),
        )
        .order_by(Conversation.created_at.desc())
        .limit(5)  # 最近 5 次对话
    )
    history_result = await db.execute(history_stmt)
    past_convs = history_result.scalars().all()

    # 从历史对话中提取已考题目+得分+遗漏点
    question_history = []
    for pc in reversed(past_convs):  # 按时间正序
        for s in (pc.learning_summaries or []):
            missed = [
                item["key_point"]
                for item in s.get("rubric_result", {}).get("items", [])
                if not item.get("hit", False)
            ]
            question_history.append({
                "question": s.get("question", ""),
                "score": s.get("score", 0),
                "missed": missed,
            })

    # 调用 Agent 动态出题（带历史上下文）
    agent_state = {
        "action": "start",
        "knowledge_point_name": node.name,
        "user_input": "",
        "question_history": question_history,
        "question_content": "",
        "rubric_items": [],
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "follow_up": None,
        "follow_up_rubric": [],
        "summary": [],
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    # 保存动态生成的题目和 Rubric 到 conversation
    conv.current_question = result["question_content"]
    conv.current_rubric = result["rubric_items"]

    # 记录出题消息
    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["question_content"],
        message_type="question",
    )
    db.add(msg)

    await db.commit()

    return ApiResponse.ok(data=QuestionResponse(
        conversation_id=conv.id,
        session_id=session.id,
        knowledge_point_name=node.name,
        question_content=result["question_content"],
        question_round=1,
    ))


@router.post("/answer", summary="提交回答并获取评分")
async def submit_answer(
    req: SubmitAnswerRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    提交回答（首次回答或追问的回答都走这个接口），Agent 基于 Rubric 评分：
    1. 加载对话上下文（当前题目 + 当前 Rubric）
    2. 调用 LLM 打分 + 决定是否追问 + 生成小结
    3. 存储评分结果和小结
    4. 更新掌握度
    """
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")
    if not conv.current_question or not conv.current_rubric:
        raise HTTPException(status_code=400, detail="当前没有待回答的题目")

    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    # 记录用户回答消息
    user_msg = ConversationMessage(
        conversation_id=conv.id,
        role="user",
        content=req.answer,
        message_type="answer",
    )
    db.add(user_msg)

    # 调用 Agent 打分
    agent_state = {
        "action": "answer",
        "knowledge_point_name": node.name if node else "",
        "user_input": req.answer,
        "question_history": _build_question_history(conv),
        "question_content": conv.current_question,
        "rubric_items": conv.current_rubric,
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "follow_up": None,
        "follow_up_rubric": [],
        "summary": [],
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    # 追加学习小结到 conversation
    summaries = conv.learning_summaries or []
    summaries.append({
        "question": conv.current_question,
        "score": result["score"],
        "rubric_result": result["rubric_result"],
        "summary": result["summary"],
        "round": conv.question_round,
    })
    conv.learning_summaries = summaries

    # 如果 LLM 决定追问，更新当前题目为追问题目
    follow_up = result.get("follow_up")
    if follow_up:
        conv.current_question = follow_up
        conv.current_rubric = result.get("follow_up_rubric", [])
        conv.status = "questioning"

        # 记录追问消息
        follow_msg = ConversationMessage(
            conversation_id=conv.id,
            role="agent",
            content=follow_up,
            message_type="follow_up",
        )
        db.add(follow_msg)
    else:
        # 不追问，等待用户请求下一题
        conv.current_question = None
        conv.current_rubric = None
        conv.status = "answered"

    # 记录评分消息
    score_msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["feedback"],
        message_type="scoring",
    )
    db.add(score_msg)

    # 更新掌握度记录（指数移动平均：new = 0.4 * score + 0.6 * old）
    mastery_stmt = select(MasteryRecord).where(
        MasteryRecord.user_id == 1,
        MasteryRecord.knowledge_point_id == conv.knowledge_point_id,
    )
    mastery_result = await db.execute(mastery_stmt)
    mastery = mastery_result.scalar_one_or_none()
    if mastery:
        mastery.mastery_level = int(0.4 * result["score"] + 0.6 * mastery.mastery_level)
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
    rec_answer = result["rubric_result"].get("recommended_answer", [])
    if isinstance(rec_answer, str):
        rec_answer = [rec_answer] if rec_answer else []
    return ApiResponse.ok(data=ScoreResponse(
        conversation_id=conv.id,
        total_score=result["score"],
        rubric_result=rubric_items_result,
        feedback=result["feedback"],
        recommended_answer=rec_answer,
        follow_up=follow_up,
        question_round=conv.question_round,
    ))


@router.post("/next", summary="请求下一题")
async def next_question(
    req: NextQuestionRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    请求下一题：
    1. 基于历史出题记录，LLM 生成新角度的题目
    2. 更新 conversation 的当前题目
    """
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    # 调用 Agent 出下一题
    agent_state = {
        "action": "next",
        "knowledge_point_name": node.name if node else "",
        "user_input": "",
        "question_history": _build_question_history(conv),
        "question_content": "",
        "rubric_items": [],
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "follow_up": None,
        "follow_up_rubric": [],
        "summary": [],
        "agent_response": "",
        "phase": "",
    }
    result = await study_graph.ainvoke(agent_state)

    # 更新 conversation
    conv.question_round += 1
    conv.current_question = result["question_content"]
    conv.current_rubric = result["rubric_items"]
    conv.status = "questioning"

    # 记录出题消息
    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["question_content"],
        message_type="question",
    )
    db.add(msg)

    await db.commit()

    return ApiResponse.ok(data=QuestionResponse(
        conversation_id=conv.id,
        session_id=conv.study_session_id,
        knowledge_point_name=node.name if node else "",
        question_content=result["question_content"],
        question_round=conv.question_round,
    ))


@router.get("/summaries/{conversation_id}", summary="获取学习小结")
async def get_summaries(
    conversation_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """获取本次对话的所有学习小结"""
    conv = await db.get(Conversation, conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    return ApiResponse.ok(data=conv.learning_summaries or [])


@router.post("/start-with-answer", summary="从面试复盘进入学习（带回答）")
async def start_with_answer(
    req: StartWithAnswerRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    从面试复盘进入学习：
    1. 动态出题（基于面试中被问到的问题）
    2. 自动提交用户在面试中的回答进行评分
    3. 一步完成出题+评分，返回评分结果
    """
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node:
        raise HTTPException(status_code=404, detail="知识点不存在")

    # 创建会话和对话
    session = StudySession(source_type="text_upload", title=f"面试复盘: {node.name}")
    db.add(session)
    await db.flush()

    conv = Conversation(
        study_session_id=session.id,
        knowledge_point_id=req.knowledge_point_id,
        question_round=1, learning_summaries=[], status="questioning",
    )
    db.add(conv)
    await db.flush()

    # 查历史
    question_history = []
    history_stmt = (
        select(Conversation)
        .where(Conversation.knowledge_point_id == req.knowledge_point_id,
               Conversation.learning_summaries.isnot(None))
        .order_by(Conversation.created_at.desc()).limit(5)
    )
    for pc in reversed((await db.execute(history_stmt)).scalars().all()):
        for s in (pc.learning_summaries or []):
            missed = [i["key_point"] for i in s.get("rubric_result", {}).get("items", []) if not i.get("hit")]
            question_history.append({"question": s.get("question", ""), "score": s.get("score", 0), "missed": missed})

    # 出题（基于面试中的问题上下文）
    agent_state = {
        "action": "start", "knowledge_point_name": node.name,
        "user_input": "", "question_history": question_history,
        "question_content": "", "rubric_items": [],
        "score": 0, "rubric_result": {}, "feedback": "",
        "follow_up": None, "follow_up_rubric": [], "summary": [],
        "agent_response": "", "phase": "",
    }
    gen_result = await study_graph.ainvoke(agent_state)

    conv.current_question = gen_result["question_content"]
    conv.current_rubric = gen_result["rubric_items"]

    # 记录出题
    db.add(ConversationMessage(conversation_id=conv.id, role="agent",
                                content=gen_result["question_content"], message_type="question"))

    # 如果有用户回答，直接评分
    if req.user_answer.strip():
        db.add(ConversationMessage(conversation_id=conv.id, role="user",
                                    content=req.user_answer, message_type="answer"))

        score_state = {
            "action": "answer", "knowledge_point_name": node.name,
            "user_input": req.user_answer,
            "question_history": question_history,
            "question_content": conv.current_question,
            "rubric_items": conv.current_rubric,
            "score": 0, "rubric_result": {}, "feedback": "",
            "follow_up": None, "follow_up_rubric": [], "summary": [],
            "agent_response": "", "phase": "",
        }
        score_result = await study_graph.ainvoke(score_state)

        # 保存评分
        summaries = conv.learning_summaries or []
        summaries.append({
            "question": conv.current_question, "score": score_result["score"],
            "rubric_result": score_result["rubric_result"], "summary": score_result.get("summary", []),
            "round": conv.question_round,
        })
        conv.learning_summaries = summaries

        follow_up = score_result.get("follow_up")
        if follow_up:
            conv.current_question = follow_up
            conv.current_rubric = score_result.get("follow_up_rubric", [])
            db.add(ConversationMessage(conversation_id=conv.id, role="agent",
                                        content=follow_up, message_type="follow_up"))
        else:
            conv.current_question = None
            conv.current_rubric = None
            conv.status = "answered"

        db.add(ConversationMessage(conversation_id=conv.id, role="agent",
                                    content=score_result["feedback"], message_type="scoring"))

        # 更新掌握度 (EMA)
        mastery_stmt = select(MasteryRecord).where(
            MasteryRecord.user_id == 1, MasteryRecord.knowledge_point_id == conv.knowledge_point_id)
        mastery = (await db.execute(mastery_stmt)).scalar_one_or_none()
        if mastery:
            mastery.mastery_level = int(0.4 * score_result["score"] + 0.6 * mastery.mastery_level)
            mastery.study_count += 1
        else:
            mastery = MasteryRecord(knowledge_point_id=conv.knowledge_point_id,
                                     mastery_level=score_result["score"], study_count=1)
            db.add(mastery)

        await db.commit()

        rubric_items_result = [RubricItemResult(**item) for item in score_result["rubric_result"].get("items", [])]
        rec = score_result["rubric_result"].get("recommended_answer", [])
        if isinstance(rec, str): rec = [rec] if rec else []

        return ApiResponse.ok(data={
            "mode": "scored",
            "conversation_id": conv.id,
            "session_id": session.id,
            "knowledge_point_name": node.name,
            "question_content": gen_result["question_content"],
            "question_round": conv.question_round,
            "total_score": score_result["score"],
            "rubric_result": [r.model_dump() for r in rubric_items_result],
            "feedback": score_result["feedback"],
            "recommended_answer": rec,
            "follow_up": follow_up,
            "user_answer": req.user_answer,
        })
    else:
        # 没有回答，只出题
        await db.commit()
        return ApiResponse.ok(data={
            "mode": "question_only",
            "conversation_id": conv.id,
            "session_id": session.id,
            "knowledge_point_name": node.name,
            "question_content": gen_result["question_content"],
            "question_round": conv.question_round,
        })
