"""
学习对话 API
核心接口：开始学习 → 提交回答（含追问打分）→ 下一题 → 查看知识点列表
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.attributes import flag_modified

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.models.learn import KnowledgeContent
from backend.models.study import (
    StudySession, Conversation, ConversationMessage, MasteryRecord,
)
from backend.schemas.common import ApiResponse
from backend.schemas.study import (
    StartStudyRequest, StartWithAnswerRequest, SubmitAnswerRequest, NextQuestionRequest,
    ScoreResponse,
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
            "answer": s.get("answer", ""),
            "score": s.get("score", 0),
            "missed": missed,
        })
    return history


@router.get("/knowledge-points", summary="获取推荐学习的 Top 10 知识点")
async def list_knowledge_points(
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    推荐 Top 10 知识点，按优先级排序：
    priority = interview_weight × (1.0 - mastery_level/100)
    未学过的略优先于学过但掌握度低的
    """
    stmt = (
        select(KnowledgeNode)
        .where(KnowledgeNode.node_type == "leaf")
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

    # 计算优先级并排序
    items = []
    for node in nodes:
        mastery = mastery_map.get(node.id)
        mastery_level = mastery.mastery_level if mastery else 0
        study_count = mastery.study_count if mastery else 0
        # 未学过：priority = weight × 1.0
        # 学过但低掌握度：priority = weight × (1 - mastery/100) × 0.8
        if study_count == 0:
            priority = node.interview_weight * 1.0
        else:
            priority = node.interview_weight * (1.0 - mastery_level / 100) * 0.8
        items.append({
            "brief": KnowledgePointBrief(
                id=node.id,
                name=node.name,
                parent_name=parent_map.get(node.parent_id),
                interview_weight=node.interview_weight,
                mastery_level=mastery_level,
                study_count=study_count,
            ),
            "priority": priority,
        })

    # 按优先级降序，取 Top 10
    items.sort(key=lambda x: x["priority"], reverse=True)
    top10 = [item["brief"] for item in items[:10]]

    return ApiResponse.ok(data=top10)


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

    # 查全局已出过的题目（跨知识点去重）
    all_convs = await db.execute(
        select(Conversation.learning_summaries).where(
            Conversation.user_id == 1,
            Conversation.learning_summaries.isnot(None),
        )
    )
    global_asked = []
    for (summaries,) in all_convs:
        if summaries:
            for s in summaries:
                if s.get("question"):
                    global_asked.append(s["question"])

    # 调用 Agent 动态出题（带历史上下文）
    agent_state = {
        "action": "start",
        "knowledge_point_name": node.name,
        "user_input": "",
        "question_history": question_history,
        "global_asked_questions": global_asked,
        "question_content": "",
        "rubric_items": [],
        "pending_questions": [],
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

    # 保存所有待答题目到 conversation（JSONB）
    all_questions = [{"question": result["question_content"], "rubric": result["rubric_items"]}]
    all_questions.extend(result.get("pending_questions", []))
    conv.pending_questions = result.get("pending_questions", [])

    # 记录出题消息
    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=result["question_content"],
        message_type="question",
    )
    db.add(msg)

    await db.commit()

    return ApiResponse.ok(data={
        "conversation_id": conv.id,
        "session_id": session.id,
        "knowledge_point_name": node.name,
        "question_content": result["question_content"],
        "question_round": 1,
        "all_questions": [q["question"] for q in all_questions],
    })


@router.post("/exam-start", summary="开始答题（使用已有题目）")
async def exam_start(
    req: StartStudyRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    答题模式：从 knowledge_content.questions 获取题目，不调 LLM 出题。
    创建 conversation 用于后续评分。
    """
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node:
        raise HTTPException(status_code=404, detail="知识点不存在")
    if node.node_type != "leaf":
        raise HTTPException(status_code=400, detail="请选择叶子知识点答题")

    # 从 knowledge_content 获取已生成的题目
    kc_result = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == req.knowledge_point_id)
    )
    kc = kc_result.scalar_one_or_none()
    questions = kc.questions if kc else []

    # 没有题目时自动触发生成
    if not questions:
        from backend.skills.learn_content_skill import execute_content_skill
        from backend.services.llm import get_llm, parse_llm_json
        from backend.prompts.learn_prompts import LEARN_QUESTIONS_PROMPT

        # 生成讲解内容
        all_nodes = (await db.execute(select(KnowledgeNode))).scalars().all()
        path = []
        cur = node
        while cur:
            path.append(cur.name)
            cur = next((n for n in all_nodes if n.id == cur.parent_id), None) if cur.parent_id else None
        path.reverse()
        category_path = " → ".join(path)

        try:
            content_text = await execute_content_skill(node.name, category_path)
        except Exception:
            content_text = ""

        # 生成面试题
        llm = get_llm(temperature=0.3, max_tokens=4096)
        try:
            q_resp = await llm.ainvoke(LEARN_QUESTIONS_PROMPT.format(knowledge_point=node.name))
            q_data = parse_llm_json(q_resp.content)
            questions = q_data.get("questions", [])
        except Exception:
            questions = []

        if content_text or questions:
            if kc:
                kc.content = content_text or kc.content
                kc.questions = questions or kc.questions
            else:
                kc = KnowledgeContent(knowledge_point_id=req.knowledge_point_id, content=content_text, questions=questions)
                db.add(kc)
            await db.flush()

    if not questions:
        raise HTTPException(status_code=500, detail="题目生成失败，请重试")

    # 创建会话和对话
    session = StudySession(source_type="exam", title=f"答题: {node.name}")
    db.add(session)
    await db.flush()

    # 第一题
    first_q = questions[0]
    remaining = questions[1:] if len(questions) > 1 else []

    # 使用已生成的 rubric，没有则从答案兜底生成
    first_rubric = first_q.get("rubric") or _answer_to_rubric(first_q.get("answer", ""))

    conv = Conversation(
        study_session_id=session.id,
        knowledge_point_id=req.knowledge_point_id,
        question_round=1,
        current_question=first_q["question"],
        current_rubric=first_rubric,
        learning_summaries=[],
        pending_questions=[{
            "question": q["question"],
            "answer": q.get("answer", ""),
            "rubric": q.get("rubric"),
        } for q in remaining],
        status="questioning",
    )
    db.add(conv)
    await db.flush()

    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=first_q["question"],
        message_type="question",
    )
    db.add(msg)
    await db.commit()

    # 查询每道题的历史答题记录
    history_convs = (await db.execute(
        select(Conversation).where(
            Conversation.knowledge_point_id == req.knowledge_point_id,
            Conversation.user_id == 1,
            Conversation.learning_summaries.isnot(None),
            Conversation.id != conv.id,
        ).order_by(Conversation.created_at.desc())
    )).scalars().all()

    question_history = {}  # question_text -> { score, answer, time }
    for hc in history_convs:
        for s in (hc.learning_summaries or []):
            q = s.get("question", "")
            if q and q not in question_history:
                question_history[q] = {
                    "score": s.get("score", 0),
                    "answer": (s.get("answer", "") or "")[:100],
                }

    return ApiResponse.ok(data={
        "conversation_id": conv.id,
        "knowledge_point_name": node.name,
        "question_content": first_q["question"],
        "question_round": 1,
        "all_questions": [q["question"] for q in questions],
        "question_history": question_history,
    })


def _answer_to_rubric(answer: str) -> list[dict]:
    """将参考答案拆分为 rubric 评分关键点"""
    if not answer:
        return [{"key_point": "回答要点", "score": 100}]
    # 按换行或编号分割
    import re
    points = re.split(r'\n+|\d+[.、)\uff09]\s*', answer)
    points = [p.strip() for p in points if p.strip() and len(p.strip()) > 2]
    if not points:
        return [{"key_point": answer[:50], "score": 100}]
    score_each = 100 // len(points)
    remainder = 100 - score_each * len(points)
    rubric = []
    for i, p in enumerate(points):
        s = score_each + (1 if i < remainder else 0)
        rubric.append({"key_point": p[:30], "score": s})
    return rubric


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
        "global_asked_questions": [],
        "question_content": conv.current_question,
        "rubric_items": conv.current_rubric,
        "pending_questions": [],
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
        "answer": req.answer,
        "score": result["score"],
        "rubric_result": result["rubric_result"],
        "summary": result["summary"],
        "round": conv.question_round,
    })
    conv.learning_summaries = summaries
    # 显式标记 JSONB 字段已修改
    flag_modified(conv, "learning_summaries")

    # 如果 LLM 决定追问，更新当前题目为追问题目
    follow_up = result.get("follow_up")
    # 硬性限制：同一题最多追问 5 次
    follow_up_count = len([s for s in summaries if s.get("round") == conv.question_round]) - 1
    if follow_up and follow_up_count >= 5:
        logger.info(f"追问已达 5 次上限，强制结束")
        follow_up = None
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

    # 每次评分都更新掌握度（含追问中）
    # 算法：每道题取原题×60%+追问平均×40%，掌握度 = 所有题平均分 × 完成比例
    summaries = conv.learning_summaries or []
    answered_rounds = set()
    all_round_scores = []
    for s in summaries:
        r = s.get("round", 0)
        if r not in answered_rounds:
            answered_rounds.add(r)
    for r in answered_rounds:
        r_scores = [x.get("score", 0) for x in summaries if x.get("round") == r]
        if r_scores:
            fs = r_scores[0]
            fups = r_scores[1:] if len(r_scores) > 1 else []
            favg = sum(fups) / len(fups) if fups else fs
            all_round_scores.append(int(fs * 0.6 + favg * 0.4))

    pending_count = len(conv.pending_questions or [])
    total_questions = len(answered_rounds) + pending_count
    avg_score = sum(all_round_scores) / len(all_round_scores) if all_round_scores else result["score"]
    mastery_level = int(avg_score * len(answered_rounds) / total_questions) if total_questions > 0 else result["score"]
    logger.info(f"掌握度: kp={conv.knowledge_point_id}, 完成{len(answered_rounds)}/{total_questions}题, 平均{avg_score:.0f}分, 掌握度={mastery_level}%")

    mastery_stmt = select(MasteryRecord).where(
        MasteryRecord.user_id == 1,
        MasteryRecord.knowledge_point_id == conv.knowledge_point_id,
    )
    mastery_result_db = await db.execute(mastery_stmt)
    mastery = mastery_result_db.scalar_one_or_none()
    if mastery:
        mastery.mastery_level = mastery_level
        mastery.study_count = len(answered_rounds)
    else:
        mastery = MasteryRecord(
            knowledge_point_id=conv.knowledge_point_id,
            mastery_level=mastery_level,
            study_count=len(answered_rounds),
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
    ext_qs = result["rubric_result"].get("extension_questions", [])
    overall_summary = result["rubric_result"].get("overall_summary", "")
    rubric_total = result["rubric_result"].get("rubric_total", 100)
    return ApiResponse.ok(data=ScoreResponse(
        conversation_id=conv.id,
        total_score=result["score"],
        rubric_total=rubric_total,
        rubric_result=rubric_items_result,
        feedback=result["feedback"],
        recommended_answer=rec_answer,
        extension_questions=ext_qs if not follow_up else [],
        overall_summary=overall_summary if not follow_up else "",
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
    1. 优先从 pending_questions 取（去除已被追问覆盖的）
    2. pending 用完则重新生成
    """
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    node = await db.get(KnowledgeNode, conv.knowledge_point_id)

    # 收集已回答过的所有题目（含追问）及命中的关键点
    answered_questions = set()
    hit_key_points = set()
    for s in (conv.learning_summaries or []):
        if s.get("question"):
            answered_questions.add(s["question"])
        for item in s.get("rubric_result", {}).get("items", []):
            if item.get("hit"):
                hit_key_points.add(item.get("key_point", "").strip().lower())

    # 从 pending 中过滤已被覆盖的题目
    pending = conv.pending_questions or []
    filtered = []
    skipped_scores = []
    for q in pending:
        q_rubric = q.get("rubric") or []
        if q_rubric and hit_key_points:
            # 检查该题的 rubric 关键点是否已被追问覆盖 ≥ 70%
            covered = sum(1 for r in q_rubric if r.get("key_point", "").strip().lower() in hit_key_points)
            coverage = covered / len(q_rubric) if q_rubric else 0
            if coverage >= 0.7:
                # 该题被覆盖，计算赋分
                score = sum(r.get("score", 0) for r in q_rubric if r.get("key_point", "").strip().lower() in hit_key_points)
                skipped_scores.append({"question": q["question"], "score": score, "covered": True})
                logger.info(f"题目「{q['question']}」被追问覆盖 {coverage:.0%}，自动赋 {score} 分")
                # 记录到 learning_summaries
                summaries = conv.learning_summaries or []
                summaries.append({
                    "question": q["question"],
                    "answer": "(追问已覆盖)",
                    "score": score,
                    "rubric_result": {"items": [
                        {**r, "hit": r.get("key_point", "").strip().lower() in hit_key_points, "matched_text": "追问中已回答"}
                        for r in q_rubric
                    ]},
                    "summary": [],
                    "round": conv.question_round + 1,
                    "auto_covered": True,
                })
                conv.learning_summaries = summaries
                flag_modified(conv, "learning_summaries")
                continue
        filtered.append(q)

    if filtered:
        next_q = filtered[0]
        conv.pending_questions = filtered[1:]
        # 答题模式：优先用已有 rubric，没有则从 answer 生成
        if "rubric" not in next_q or not next_q.get("rubric"):
            if "answer" in next_q:
                next_q["rubric"] = _answer_to_rubric(next_q["answer"])
    else:
        # 检查是否是答题模式（exam），如果是则不再生成新题
        session = await db.get(StudySession, conv.study_session_id)
        if session and session.source_type == "exam":
            conv.status = "finished"
            await db.commit()
            return ApiResponse.ok(data={
                "finished": True,
                "message": "全部题目已完成",
                "skipped_questions": skipped_scores,
            })

        all_convs = await db.execute(
            select(Conversation.learning_summaries).where(
                Conversation.user_id == 1,
                Conversation.learning_summaries.isnot(None),
            )
        )
        global_asked = []
        for (summaries,) in all_convs:
            if summaries:
                for s in summaries:
                    if s.get("question"):
                        global_asked.append(s["question"])

        agent_state = {
            "action": "next",
            "knowledge_point_name": node.name if node else "",
            "user_input": "",
            "question_history": _build_question_history(conv),
            "global_asked_questions": global_asked,
            "question_content": "",
            "rubric_items": [],
            "pending_questions": [],
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
        next_q = {"question": result["question_content"], "rubric": result["rubric_items"]}
        conv.pending_questions = result.get("pending_questions", [])

    # 更新 conversation
    conv.question_round += 1
    conv.current_question = next_q["question"]
    conv.current_rubric = next_q.get("rubric", [])
    conv.status = "questioning"

    # 记录出题消息
    msg = ConversationMessage(
        conversation_id=conv.id,
        role="agent",
        content=next_q["question"],
        message_type="question",
    )
    db.add(msg)

    await db.commit()

    return ApiResponse.ok(data={
        "conversation_id": conv.id,
        "session_id": conv.study_session_id,
        "knowledge_point_name": node.name if node else "",
        "question_content": next_q["question"],
        "question_round": conv.question_round,
        "skipped_questions": skipped_scores,
    })


@router.post("/stop-followup", summary="停止追问，返回本轮总结")
async def stop_followup(
    req: NextQuestionRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """停止追问，生成本轮总结和扩展题"""
    conv = await db.get(Conversation, req.conversation_id)
    if not conv:
        raise HTTPException(status_code=404, detail="对话不存在")

    conv.current_question = None
    conv.current_rubric = None
    conv.status = "answered"

    # 用 LLM 生成本轮总结
    node = await db.get(KnowledgeNode, conv.knowledge_point_id)
    summaries = conv.learning_summaries or []
    current_round = conv.question_round

    # 构建本轮所有问答
    round_qa = []
    for s in summaries:
        if s.get("round") == current_round:
            round_qa.append(f"问: {s.get('question', '')}")
            if s.get("answer"):
                round_qa.append(f"答: {s['answer']}")
            round_qa.append(f"得分: {s.get('score', 0)}")

    conversation_text = "\n".join(round_qa) if round_qa else "无"

    # 调用 LLM 生成总结
    from backend.services.llm import get_llm
    llm = get_llm(temperature=0.3, max_tokens=2048)
    summary_prompt = f"""请对以下面试问答进行总结。知识点：{node.name if node else ''}

问答记录：
{conversation_text}

请输出：
1. overall_summary：2-3 句话总结候选人的表现
2. extension_questions：3 个扩展面试题（含简要答案）

严格按 JSON 格式输出：
{{"overall_summary": "...", "extension_questions": [{{"question": "...", "answer": "..."}}]}}
"""
    overall_summary = ""
    ext_qs = []
    try:
        resp = await llm.ainvoke(summary_prompt)
        from backend.services.llm import parse_llm_json
        data = parse_llm_json(resp.content)
        overall_summary = data.get("overall_summary", "")
        ext_qs = data.get("extension_questions", [])
    except Exception as e:
        logger.warning(f"总结生成失败: {e}")
        overall_summary = "本轮追问已结束。"

    await db.commit()

    return ApiResponse.ok(data={
        "overall_summary": overall_summary,
        "extension_questions": ext_qs,
    })


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
            "global_asked_questions": [],
            "question_content": conv.current_question,
            "rubric_items": conv.current_rubric,
            "pending_questions": [],
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
        flag_modified(conv, "learning_summaries")

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


@router.get("/exam-progress/{kp_id}", summary="获取知识点答题进度")
async def exam_progress(
    kp_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """
    返回该知识点的答题历史摘要：
    - 已掌握 / 待加强 的关键点
    - 历史得分
    - 总体掌握度
    """
    # 获取该知识点所有历史对话
    convs = (await db.execute(
        select(Conversation)
        .where(
            Conversation.knowledge_point_id == kp_id,
            Conversation.user_id == 1,
            Conversation.learning_summaries.isnot(None),
        )
        .order_by(Conversation.created_at.desc())
    )).scalars().all()

    if not convs:
        return ApiResponse.ok(data={"has_history": False})

    # 汇总所有题目的评分
    mastered = {}    # key_point -> best_score
    weak = {}        # key_point -> latest_score
    total_scores = []
    all_questions = []

    for conv in convs:
        for s in (conv.learning_summaries or []):
            q = s.get("question", "")
            score = s.get("score", 0)
            total_scores.append(score)
            if q not in all_questions:
                all_questions.append(q)
            for item in s.get("rubric_result", {}).get("items", []):
                kp = item.get("key_point", "")
                if not kp:
                    continue
                if item.get("hit"):
                    mastered[kp] = max(mastered.get(kp, 0), item.get("score", 0))
                else:
                    weak[kp] = item.get("score", 0)

    # 已掌握的从待加强中移除
    for k in mastered:
        weak.pop(k, None)

    # 掌握度
    mastery = (await db.execute(
        select(MasteryRecord).where(
            MasteryRecord.user_id == 1,
            MasteryRecord.knowledge_point_id == kp_id,
        )
    )).scalar_one_or_none()

    last_conv = convs[0] if convs else None

    return ApiResponse.ok(data={
        "has_history": True,
        "mastery_level": mastery.mastery_level if mastery else 0,
        "study_count": mastery.study_count if mastery else 0,
        "last_studied_at": last_conv.created_at.isoformat() if last_conv else None,
        "total_questions_answered": len(all_questions),
        "avg_score": round(sum(total_scores) / len(total_scores)) if total_scores else 0,
        "mastered_points": list(mastered.keys()),
        "weak_points": list(weak.keys()),
    })
