"""
学习页面 API — 知识讲解 + 探索对话
"""
import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.models.learn import KnowledgeContent, LearnChat
from backend.models.study import MasteryRecord
from backend.schemas.common import ApiResponse
from backend.services.llm import get_llm, parse_llm_json
from backend.skills.learn_content_skill import execute_content_skill
from backend.prompts.learn_prompts import (
    LEARN_QUESTIONS_PROMPT, LEARN_CHAT_PROMPT, LEARN_MERGE_PROMPT,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/learn", tags=["学习"])


def _get_category_path(node_id: int, all_nodes: list[KnowledgeNode]) -> str:
    """获取知识点的分类路径，如 'Redis → 数据结构 → SDS'"""
    node_map = {n.id: n for n in all_nodes}
    path = []
    current = node_map.get(node_id)
    while current:
        path.append(current.name)
        current = node_map.get(current.parent_id) if current.parent_id else None
    path.reverse()
    return " → ".join(path)


@router.get("/content/{kp_id}", summary="获取知识点讲解内容")
async def get_content(
    kp_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """获取知识点讲解内容。如果不存在则 LLM 生成并落库。"""
    node = await db.get(KnowledgeNode, kp_id)
    if not node:
        raise HTTPException(status_code=404, detail="知识点不存在")

    # 查询已有内容
    result = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )
    existing = result.scalar_one_or_none()

    # 查询掌握度
    mastery = await db.execute(
        select(MasteryRecord).where(
            MasteryRecord.user_id == 1,
            MasteryRecord.knowledge_point_id == kp_id,
        )
    )
    mastery_record = mastery.scalar_one_or_none()

    if existing:
        return ApiResponse.ok(data={
            "knowledge_point_id": kp_id,
            "knowledge_point_name": node.name,
            "content": existing.content,
            "questions": existing.questions or [],
            "mastery_level": mastery_record.mastery_level if mastery_record else 0,
            "last_studied_at": mastery_record.last_studied_at.isoformat() if mastery_record and mastery_record.last_studied_at else None,
            "generated": False,
        })

    # LLM 生成
    all_nodes = (await db.execute(select(KnowledgeNode))).scalars().all()
    category_path = _get_category_path(kp_id, all_nodes)

    # Step 1: 通过 Skill 生成 Markdown 讲解（带模块验证）
    try:
        content_text = await execute_content_skill(node.name, category_path)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))

    # Step 2: 生成高频面试题（JSON 格式，内容短不会被截断）
    llm = get_llm(temperature=0.3, max_tokens=4096)
    questions = []
    try:
        q_prompt = LEARN_QUESTIONS_PROMPT.format(knowledge_point=node.name)
        q_resp = await llm.ainvoke(q_prompt)
        q_data = parse_llm_json(q_resp.content)
        questions = q_data.get("questions", [])
    except Exception as e:
        logger.warning(f"面试题生成失败: {e}")

    # 落库（防并发重复插入）
    # 再次检查是否已被并发请求插入
    existing_check = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )
    existing_kc = existing_check.scalar_one_or_none()
    if existing_kc:
        # 并发请求已插入，直接返回
        return ApiResponse.ok(data={
            "knowledge_point_id": kp_id,
            "knowledge_point_name": node.name,
            "content": existing_kc.content,
            "questions": existing_kc.questions or [],
            "mastery_level": mastery_record.mastery_level if mastery_record else 0,
            "last_studied_at": mastery_record.last_studied_at.isoformat() if mastery_record and mastery_record.last_studied_at else None,
            "generated": False,
        })

    kc = KnowledgeContent(
        knowledge_point_id=kp_id,
        content=content_text,
        questions=questions,
    )
    db.add(kc)
    await db.commit()

    return ApiResponse.ok(data={
        "knowledge_point_id": kp_id,
        "knowledge_point_name": node.name,
        "content": content_text,
        "questions": questions,
        "mastery_level": mastery_record.mastery_level if mastery_record else 0,
        "last_studied_at": mastery_record.last_studied_at.isoformat() if mastery_record and mastery_record.last_studied_at else None,
        "generated": True,
    })


class ChatRequest(BaseModel):
    knowledge_point_id: int
    message: str
    quoted_text: str | None = None


@router.post("/chat", summary="学习探索对话")
async def chat(
    req: ChatRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """在知识点上下文中进行探索对话"""
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node:
        raise HTTPException(status_code=404, detail="知识点不存在")

    # 获取知识内容作为上下文
    kc_result = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == req.knowledge_point_id)
    )
    kc = kc_result.scalar_one_or_none()
    content_text = kc.content if kc else "（尚未生成讲解内容）"

    # 获取对话历史
    chat_result = await db.execute(
        select(LearnChat)
        .where(LearnChat.knowledge_point_id == req.knowledge_point_id)
        .order_by(LearnChat.id)
    )
    history = chat_result.scalars().all()
    chat_history = "\n".join(
        f"{'用户' if c.role == 'user' else 'AI'}: {c.content}" for c in history[-10:]  # 最近10条
    ) or "（暂无）"

    # 处理引用
    user_input = req.message.strip()
    if req.quoted_text:
        user_input = f"【引用】{req.quoted_text}\n\n{user_input}"

    # LLM 对话
    llm = get_llm(temperature=0.3, max_tokens=4096)
    prompt = LEARN_CHAT_PROMPT.format(
        knowledge_point=node.name,
        content=content_text,
        chat_history=chat_history,
        user_input=user_input,
    )

    try:
        resp = await llm.ainvoke(prompt)
        reply = resp.content.strip()
    except Exception as e:
        logger.error(f"对话失败: {e}")
        raise HTTPException(status_code=500, detail="对话失败，请重试")

    # 保存对话记录
    user_msg = LearnChat(
        knowledge_point_id=req.knowledge_point_id,
        role="user",
        content=req.message.strip(),
        quoted_text=req.quoted_text,
    )
    ai_msg = LearnChat(
        knowledge_point_id=req.knowledge_point_id,
        role="assistant",
        content=reply,
    )
    db.add(user_msg)
    db.add(ai_msg)
    await db.commit()

    return ApiResponse.ok(data={"reply": reply})


@router.get("/chat-history/{kp_id}", summary="获取对话历史")
async def get_chat_history(
    kp_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    result = await db.execute(
        select(LearnChat)
        .where(LearnChat.knowledge_point_id == kp_id)
        .order_by(LearnChat.id)
    )
    chats = result.scalars().all()
    return ApiResponse.ok(data=[{
        "role": c.role,
        "content": c.content,
        "quoted_text": c.quoted_text,
        "created_at": c.created_at.isoformat() if c.created_at else None,
    } for c in chats])


class MergeRequest(BaseModel):
    knowledge_point_id: int
    chat_messages: list[str]  # 要合并的对话内容


@router.post("/merge-chat", summary="将对话内容合并到讲解文章")
async def merge_chat(
    req: MergeRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """将对话中有价值的内容融入到知识讲解文章中"""
    kc_result = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == req.knowledge_point_id)
    )
    kc = kc_result.scalar_one_or_none()
    if not kc:
        raise HTTPException(status_code=404, detail="讲解内容不存在")

    chat_content = "\n\n".join(req.chat_messages)

    llm = get_llm(temperature=0.1, max_tokens=8192)
    prompt = LEARN_MERGE_PROMPT.format(
        original_content=kc.content,
        chat_content=chat_content,
    )

    try:
        resp = await llm.ainvoke(prompt)
        merged = resp.content.strip()
        # 去掉可能的 markdown 包裹
        if merged.startswith("```markdown"):
            merged = merged[len("```markdown"):].strip()
        if merged.startswith("```"):
            merged = merged[3:].strip()
        if merged.endswith("```"):
            merged = merged[:-3].strip()
    except Exception as e:
        logger.error(f"合并失败: {e}")
        raise HTTPException(status_code=500, detail="合并失败，请重试")

    # 记录用户补充
    additions = kc.user_additions or []
    additions.append({"chat_content": chat_content, "timestamp": "now"})

    kc.content = merged
    kc.user_additions = additions
    await db.commit()

    return ApiResponse.ok(data={"content": merged})
