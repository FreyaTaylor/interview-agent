"""
学习页面 API — 知识讲解 + 探索对话
"""
import logging
import re
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
    LEARN_CHAT_MERGE_SUBTOPIC_PROMPT, LEARN_CHAT_NEW_SUBTOPIC_PROMPT,
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
        q_prompt = LEARN_QUESTIONS_PROMPT.format(knowledge_point=node.name, category_path=category_path)
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


@router.delete("/content/{kp_id}", summary="删除知识点讲解内容")
async def delete_content(
    kp_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """删除已生成的知识点讲解内容，同时清空相关对话记录。"""
    result = await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )
    existing = result.scalar_one_or_none()
    if not existing:
        raise HTTPException(status_code=404, detail="该知识点暂无讲解内容")

    await db.delete(existing)

    # 同时清空对话记录
    chat_result = await db.execute(
        select(LearnChat).where(LearnChat.knowledge_point_id == kp_id)
    )
    for chat in chat_result.scalars().all():
        await db.delete(chat)

    await db.commit()
    return ApiResponse.ok(data={"deleted": True})


class ChatRequest(BaseModel):
    knowledge_point_id: int
    message: str
    quoted_text: str | None = None


def _split_subtopics(content: str) -> list[dict]:
    """将 Markdown 内容按 #### 分割出子话题列表，返回 [{title, text, start, end}]"""
    parts = re.split(r'^(#### .+)$', content, flags=re.MULTILINE)
    subtopics = []
    for i in range(1, len(parts), 2):
        title = parts[i].replace('#### ', '').strip()
        body = parts[i + 1] if i + 1 < len(parts) else ''
        full_text = parts[i] + body
        subtopics.append({"title": title, "text": full_text.strip()})
    return subtopics


def _find_subtopic_by_quote(content: str, quoted_text: str) -> dict | None:
    """根据引用文本匹配所属子话题（简单文本包含匹配）"""
    subtopics = _split_subtopics(content)
    if not subtopics:
        return None
    # 优先精确包含匹配
    for st in subtopics:
        if quoted_text in st["text"]:
            return st
    # 其次标题关键词匹配
    for st in subtopics:
        if any(kw in quoted_text for kw in st["title"].split() if len(kw) >= 2):
            return st
    return None


def _replace_subtopic(content: str, old_text: str, new_text: str) -> str:
    """在内容中替换指定子话题文本"""
    return content.replace(old_text.strip(), new_text.strip(), 1)


@router.post("/chat", summary="学习探索对话")
async def chat(
    req: ChatRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """探索对话：回复用户 + 实时融合到讲解内容"""
    node = await db.get(KnowledgeNode, req.knowledge_point_id)
    if not node:
        raise HTTPException(status_code=404, detail="知识点不存在")

    # 获取知识内容
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
        f"{'用户' if c.role == 'user' else 'AI'}: {c.content}" for c in history[-10:]
    ) or "（暂无）"

    # 匹配引用所属子话题
    matched_subtopic = None
    if req.quoted_text:
        matched_subtopic = _find_subtopic_by_quote(content_text, req.quoted_text)

    # 构建用户输入
    user_input = req.message.strip()
    if req.quoted_text:
        user_input = f"【引用】{req.quoted_text}\n\n{user_input}"

    # Step 1: LLM 对话回复
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

    # Step 2: 实时融合到讲解内容
    updated_subtopic = None
    updated_content = None
    if kc:
        try:
            if matched_subtopic:
                # 有引用 → 融合到匹配的子话题
                merge_prompt = LEARN_CHAT_MERGE_SUBTOPIC_PROMPT.format(
                    subtopic_text=matched_subtopic["text"],
                    chat_reply=reply,
                )
                merge_resp = await llm.ainvoke(merge_prompt)
                merged_text = merge_resp.content.strip()
                # 去掉可能的前导语
                if merged_text and '####' in merged_text:
                    idx = merged_text.index('####')
                    merged_text = merged_text[idx:].strip()
                if merged_text and merged_text != matched_subtopic["text"]:
                    updated_subtopic = merged_text
                    kc.content = _replace_subtopic(kc.content, matched_subtopic["text"], merged_text)
                    updated_content = kc.content
                    await db.flush()
            else:
                # 无引用 → LLM 判断融入已有子话题 or 新建
                existing_subtopics = _split_subtopics(content_text)
                existing_desc = "\n\n".join(
                    f"【{i+1}】{st['text']}" for i, st in enumerate(existing_subtopics)
                ) or "（暂无）"
                new_prompt = LEARN_CHAT_NEW_SUBTOPIC_PROMPT.format(
                    knowledge_point=node.name,
                    existing_subtopics=existing_desc,
                    chat_reply=reply,
                    user_question=req.message.strip(),
                )
                new_resp = await llm.ainvoke(new_prompt)
                raw = new_resp.content.strip()

                if raw.startswith("SKIP"):
                    pass  # 无价值内容，不融合
                elif raw.startswith("MERGE:"):
                    # 融入已有子话题，可能附带删除冗余子话题
                    lines = raw.split('\n', 1)
                    directive = lines[0]  # e.g. "MERGE:2,DELETE:5"
                    try:
                        parts = directive.split(',')
                        merge_idx = int(parts[0].replace("MERGE:", "").strip()) - 1
                        delete_indices = []
                        for p in parts[1:]:
                            if p.strip().startswith("DELETE:"):
                                delete_indices.append(int(p.strip().replace("DELETE:", "").strip()) - 1)

                        merged_text = lines[1].strip() if len(lines) > 1 else ""
                        if '####' in merged_text:
                            merged_text = merged_text[merged_text.index('####'):].strip()
                        if 0 <= merge_idx < len(existing_subtopics) and merged_text:
                            updated_subtopic = merged_text
                            # 先删除冗余子话题（从后往前删，避免索引偏移）
                            for di in sorted(delete_indices, reverse=True):
                                if 0 <= di < len(existing_subtopics) and di != merge_idx:
                                    kc.content = kc.content.replace(existing_subtopics[di]["text"], "", 1).strip()
                            # 再替换目标子话题
                            kc.content = _replace_subtopic(kc.content, existing_subtopics[merge_idx]["text"], merged_text)
                            # 清理多余空行
                            kc.content = re.sub(r'\n{3,}', '\n\n', kc.content)
                            updated_content = kc.content
                            await db.flush()
                    except (ValueError, IndexError) as e:
                        logger.warning(f"解析 MERGE 指令失败: {e}")
                elif raw.startswith("NEW"):
                    new_text = raw.split('\n', 1)[1].strip() if '\n' in raw else ""
                    if new_text and '####' in new_text:
                        new_text = new_text[new_text.index('####'):].strip()
                    if new_text and new_text.startswith('####'):
                        updated_subtopic = new_text
                        kc.content = kc.content.rstrip() + "\n\n" + new_text
                        updated_content = kc.content
                        await db.flush()
        except Exception as e:
            logger.warning(f"子话题融合失败（不影响对话）: {e}")

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

    return ApiResponse.ok(data={
        "reply": reply,
        "updated_subtopic": updated_subtopic,
        "updated_content": updated_content,
    })


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
        # 去掉 LLM 前导语（如"好的，这是..."），只保留从 ### 开始的正文
        lines = merged.split('\n')
        first_section = next((i for i, l in enumerate(lines) if l.strip().startswith('### ')), 0)
        if first_section > 0:
            merged = '\n'.join(lines[first_section:]).strip()
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
