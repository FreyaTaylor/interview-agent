"""
学习讲解服务 — 知识内容生成 + 探索对话 + 内容融合

职责：
  1. 获取/生成知识点讲解内容（Skill 生成 Markdown + LLM 生成面试题）
  2. 删除讲解内容及关联对话
  3. 探索对话（LLM 回复 + 实时融合到讲解文章）
  4. 获取对话历史
  5. 将对话内容合并到讲解文章
"""
import logging
import re

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.learn import KnowledgeContent, LearnChat
from backend.models.qa import StudyQuestion
from backend.services import qa_aggregate
from backend.services.llm import get_llm, parse_llm_json
from backend.skills.learn_content_skill import execute_content_skill
from backend.prompts.learn_prompts import (
    LEARN_CHAT_PROMPT, LEARN_MERGE_PROMPT,
    LEARN_CHAT_MERGE_SUBTOPIC_PROMPT, LEARN_CHAT_NEW_SUBTOPIC_PROMPT,
)

logger = logging.getLogger(__name__)


# ========== 内部工具 ==========

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


def _split_subtopics(content: str) -> list[dict]:
    """将 Markdown 内容按 ### 或 #### 标题分割出子话题列表（兼容两级粒度）。"""
    parts = re.split(r'^(#{3,4} .+)$', content, flags=re.MULTILINE)
    subtopics = []
    for i in range(1, len(parts), 2):
        title = re.sub(r'^#{3,4} ', '', parts[i]).strip()
        body = parts[i + 1] if i + 1 < len(parts) else ''
        full_text = parts[i] + body
        subtopics.append({"title": title, "text": full_text.strip()})
    return subtopics


def _normalize_for_match(s: str) -> str:
    """匹配前的标准化：去掉所有空白和常见 Markdown 修饰符，使前端选区文本可与原文比对。"""
    if not s:
        return ""
    # 去掉 markdown 强调/分隔符号
    s = re.sub(r'[\*_`~|>#\-]+', '', s)
    # 折叠所有空白（包括全角空格、换行、tab）
    s = re.sub(r'\s+', '', s)
    return s


def _find_subtopic_by_quote(content: str, quoted_text: str) -> dict | None:
    """根据引用文本匹配所属子话题（容忍空白与 Markdown 修饰符差异）。"""
    subtopics = _split_subtopics(content)
    if not subtopics:
        return None

    # 1) 严格子串匹配
    for st in subtopics:
        if quoted_text in st["text"]:
            return st

    # 2) 标准化后子串匹配（处理 ** 加粗、跨行选择、表格符号等）
    norm_quote = _normalize_for_match(quoted_text)
    if norm_quote and len(norm_quote) >= 4:
        best = None
        best_overlap = 0
        for st in subtopics:
            norm_st = _normalize_for_match(st["text"])
            if norm_quote in norm_st:
                return st
            # 3) 取引用的连续片段尝试匹配；若引用很长，截取前/中/后 12 字
            for probe in {norm_quote[:12], norm_quote[-12:], norm_quote[len(norm_quote)//2:len(norm_quote)//2 + 12]}:
                if len(probe) >= 6 and probe in norm_st:
                    if len(probe) > best_overlap:
                        best = st
                        best_overlap = len(probe)
        if best:
            return best

    # 4) 兜底：按标题关键词
    for st in subtopics:
        if any(kw in quoted_text for kw in st["title"].split() if len(kw) >= 2):
            return st
    return None


def _replace_subtopic(content: str, old_text: str, new_text: str) -> str:
    """在内容中替换指定子话题文本"""
    return content.replace(old_text.strip(), new_text.strip(), 1)


# ========== 获取/生成讲解内容 ==========

async def get_or_generate_content(db: AsyncSession, kp_id: int) -> dict:
    """
    获取知识点讲解内容。不存在则 LLM 生成并落库。
    高频面试题统一从 `study_question` 表读取（与答题页同源），
    首次访问时懒生成。
    """
    node = await db.get(KnowledgeNode, kp_id)
    if not node:
        raise ValueError("知识点不存在")

    # 查已有内容
    existing = (await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )).scalar_one_or_none()

    # 掌握度由 question_attempt 派生；last_studied_at 暂不再展示
    mastery_level, _ = await qa_aggregate.get_kp_mastery(db, kp_id)

    # 题目源统一为 study_question（只对叶子节点生成）
    questions = await _load_study_questions_for_learn(db, node)

    def _build_result(content, generated, qs):
        return {
            "knowledge_point_id": kp_id,
            "knowledge_point_name": node.name,
            "content": content,
            "questions": qs,
            "mastery_level": mastery_level,
            "last_studied_at": None,
            "generated": generated,
        }

    if existing:
        return _build_result(existing.content, False, questions)

    # LLM 生成讲解文本
    all_nodes = (await db.execute(select(KnowledgeNode))).scalars().all()
    category_path = _get_category_path(kp_id, all_nodes)

    try:
        content_text = await execute_content_skill(node.name, category_path)
    except RuntimeError as e:
        raise RuntimeError(str(e))

    # 防并发重复插入
    existing_check = (await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )).scalar_one_or_none()
    if existing_check:
        return _build_result(existing_check.content, False, questions)

    # 内容生成完成后，**同步**生成 5 道题 + rubric + 范例答案，落库到 study_question
    if node.node_type == "leaf":
        try:
            await _generate_and_persist_questions(db, node, content_text, category_path=category_path)
        except Exception as e:
            logger.warning(f"生成 study_question 失败: {e}")

    kc = KnowledgeContent(knowledge_point_id=kp_id, content=content_text)
    db.add(kc)
    await db.commit()

    # 重新读取（包含刚生成的题目）
    questions = await _load_study_questions_for_learn(db, node)
    return _build_result(content_text, True, questions)


async def _load_study_questions_for_learn(
    db: AsyncSession, node: KnowledgeNode,
) -> list[dict]:
    """为学习页“高频面试题”列表加载 study_question 行。

    - 仅对 leaf 知识点返回；中间/根节点返回空
    - 字段：id / question / sort_order / recommended_answer
    - 不再独立懒生成；题目由 `_generate_and_persist_questions` 在内容生成时一并写入
    """
    if node.node_type != "leaf":
        return []
    rows = (await db.execute(
        select(StudyQuestion)
        .where(StudyQuestion.knowledge_point_id == node.id)
        .order_by(StudyQuestion.sort_order, StudyQuestion.id)
    )).scalars().all()
    return [
        {
            "id": r.id,
            "question": r.content,
            "sort_order": r.sort_order,
            "recommended_answer": r.recommended_answer,
        }
        for r in rows
    ]


async def ensure_kp_studied(db: AsyncSession, kp_id: int) -> None:
    """确保某 leaf 知识点的 KnowledgeContent + study_question 都已生成。

    用于 ExamPage 入口：若用户直接进答题页而没访问过学习页，自动触发完整生成。
    幂等：已存在 KnowledgeContent 则直接返回（题目与内容同生成，不会出现一个有一个无）。
    """
    existing = (await db.execute(
        select(KnowledgeContent.id).where(KnowledgeContent.knowledge_point_id == kp_id)
    )).scalar_one_or_none()
    if existing:
        return
    # 内容都没有 → 走完整生成流程
    await get_or_generate_content(db, kp_id)


# ========== 题目生成（与讲解内容同源） ==========

async def _generate_and_persist_questions(
    db: AsyncSession, node: KnowledgeNode, content_text: str,
    count: int = 5, category_path: str = "",
) -> list[StudyQuestion]:
    """基于讲解内容 + 知识点路径，一次 LLM 调用生成 count 道题 + rubric + 范例答案。

    `category_path` 是必须的：避免同名概念误导（如 Redis Set 误判为 JS Set）。
    幂等保护：若该 kp 已存在题目则直接返回当前列表。
    """
    existing = (await db.execute(
        select(StudyQuestion)
        .where(StudyQuestion.knowledge_point_id == node.id)
        .order_by(StudyQuestion.sort_order, StudyQuestion.id)
    )).scalars().all()
    if existing:
        return list(existing)

    items = await _llm_generate_questions_with_answers(
        node.name, category_path, content_text, count,
    )
    rows: list[StudyQuestion] = []
    for i, q in enumerate(items):
        content = (q.get("question") or "").strip()
        if not content:
            continue
        rubric = q.get("rubric") or []
        if not isinstance(rubric, list):
            rubric = []
        rec = q.get("recommended_answer")
        if isinstance(rec, list):
            rec = [str(x).strip() for x in rec if str(x).strip()] or None
        elif isinstance(rec, str):
            rec = rec.strip() or None
        else:
            rec = None
        rows.append(StudyQuestion(
            knowledge_point_id=node.id,
            content=content,
            rubric_template=rubric,
            recommended_answer=rec,
            sort_order=i,
        ))
    for r in rows:
        db.add(r)
    await db.flush()
    for r in rows:
        await db.refresh(r)
    return rows


async def _llm_generate_questions_with_answers(
    kp_name: str, category_path: str, content_text: str, count: int,
    existing_questions: list[str] | None = None,
) -> list[dict]:
    """调 LLM 一次性产出 {question, rubric, recommended_answer} × count。

    `category_path` 例：`redis → 数据结构与底层实现 → 基础类型 → Set 去重与集合运算`。
    必须传入，避免同名概念被 LLM 误判（如 Redis Set 误会为 JS Set）。
    `existing_questions` 传入后，LLM 会避免重复、从不同角度出题。
    """
    # 控制传入的讲解长度，避免 prompt 过长
    context = (content_text or "")[:3000]
    avoid_section = ""
    if existing_questions:
        avoid_lines = "\n".join(f"- {q}" for q in existing_questions[:10])
        avoid_section = f"\n\n## ⚠ 避免重复以下已存在题目（请从**不同角度**出题）\n{avoid_lines}"
    prompt = f"""你是一位资深技术面试官。请基于以下"知识讲解"为知识点「{kp_name}」一次性生成 {count} 道面试题。

## 所属分类路径（领域约束）
{category_path or "（未提供）"}

## ⚠️ 领域约束
**必须严格按「所属分类路径」确定题目领域！**
- 路径以 redis 开头 → 题目仅限 Redis 相关，不要错为 JS / Java / Python 同名概念
- 路径以 mysql 开头 → 题目仅限 MySQL 相关
- 即使知识点名称和其他技术同名（如"Set""线程池"），也必须出当前领域的题
- 例：redis → 数据结构 → Set → 出 Redis Set (SADD/SUNION/intset 等)，不是 JS Set

## 出题要求
1. 生成 **{count} 道题**，覆盖该知识点不同考察角度，由浅入深
2. 题目简洁直接（≤25 字），面试官口吻；**一题只问一个核心点**
3. 每题给出 3-5 个 Rubric 评分点，所有 score 之和=100
4. **题干-评分一致性（必须严格）**：
    - rubric 只能覆盖该题题干明确问到的范围，不能扩展到同主题其他维度
    - 例：题干问"如何触发"，rubric 只能是触发相关（配置、命令、自动条件），不能出现"文件结构/写入流程"
    - 不要出"大而全"题（如把触发机制、文件结构、写入流程合在一个题里）
5. 每题给出 `recommended_answer`：**3-5 条要点的字符串数组**
   - 第一人称、直接陈述（不要"我会先说..."这种元叙述）
   - 每条 30-80 字，包含关键概念/原理/数据/对比

## 知识讲解（参考素材）
{context}{avoid_section}

严格按下面 JSON 输出：
```json
{{
  "questions": [
    {{
      "question": "题目内容（≤25字）",
      "rubric": [
        {{"key_point": "关键点（≤8字）", "score": 25}}
      ],
      "recommended_answer": ["要点1", "要点2", "要点3"]
    }}
  ]
}}
```"""
    llm = get_llm(temperature=0.4, max_tokens=4096)
    try:
        resp = await llm.ainvoke(prompt)
        data = parse_llm_json(resp.content) or {}
        return data.get("questions") or []
    except Exception:
        logger.exception("生成 study_question 失败")
        return []


async def regenerate_kp_questions(db: AsyncSession, kp_id: int) -> list[dict]:
    """重新生成某 leaf 知识点的所有面试题（含 recommended_answer）。

    策略：保留有作答历史的题目（避免破坏 question_attempt FK），
    删除无作答的题目；再调 LLM 补齐到目标数量。
    返回新题目列表。
    """
    from backend.models.qa import QuestionAttempt  # 避免循环导入

    node = await db.get(KnowledgeNode, kp_id)
    if not node or node.node_type != "leaf":
        raise ValueError("知识点不存在或非叶子节点")

    # 找无作答的题目，可安全删除
    rows = (await db.execute(
        select(StudyQuestion).where(StudyQuestion.knowledge_point_id == kp_id)
    )).scalars().all()
    for r in rows:
        has_attempt = (await db.execute(
            select(QuestionAttempt.id).where(
                QuestionAttempt.question_type == "study",
                QuestionAttempt.question_id == r.id,
            ).limit(1)
        )).scalar_one_or_none()
        if not has_attempt:
            await db.delete(r)
    await db.flush()

    # 拉取讲解内容作为生成素材
    content_row = (await db.execute(
        select(KnowledgeContent.content).where(KnowledgeContent.knowledge_point_id == kp_id)
    )).scalar_one_or_none()

    # 重新生成：每次点击都产出 5 道新题，与保留题并存；总数封顶 15 道避免雪崩
    remaining = (await db.execute(
        select(StudyQuestion).where(StudyQuestion.knowledge_point_id == kp_id)
    )).scalars().all()
    TOTAL_CAP = 15
    NEW_BATCH = 5
    need = min(NEW_BATCH, max(0, TOTAL_CAP - len(remaining)))
    if need > 0:
        all_nodes = (await db.execute(select(KnowledgeNode))).scalars().all()
        category_path = _get_category_path(kp_id, all_nodes)
        existing_qs = [r.content for r in remaining]
        items = await _llm_generate_questions_with_answers(
            node.name, category_path, content_row or "", need,
            existing_questions=existing_qs,
        )
        base_order = (max((r.sort_order for r in remaining), default=-1)) + 1
        for i, q in enumerate(items):
            content = (q.get("question") or "").strip()
            if not content:
                continue
            rubric = q.get("rubric") or []
            if not isinstance(rubric, list):
                rubric = []
            rec = q.get("recommended_answer")
            if isinstance(rec, list):
                rec = [str(x).strip() for x in rec if str(x).strip()] or None
            elif isinstance(rec, str):
                rec = rec.strip() or None
            else:
                rec = None
            db.add(StudyQuestion(
                knowledge_point_id=kp_id,
                content=content,
                rubric_template=rubric,
                recommended_answer=rec,
                sort_order=base_order + i,
            ))
    await db.commit()

    # 返回最终列表
    final_rows = (await db.execute(
        select(StudyQuestion)
        .where(StudyQuestion.knowledge_point_id == kp_id)
        .order_by(StudyQuestion.sort_order, StudyQuestion.id)
    )).scalars().all()
    return [
        {
            "id": r.id,
            "question": r.content,
            "sort_order": r.sort_order,
            "recommended_answer": r.recommended_answer,
        }
        for r in final_rows
    ]


# ========== 删除讲解内容 ==========

async def delete_content(db: AsyncSession, kp_id: int) -> None:
    """删除已生成的知识点讲解内容及关联对话记录"""
    existing = (await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == kp_id)
    )).scalar_one_or_none()
    if not existing:
        raise ValueError("该知识点暂无讲解内容")

    await db.delete(existing)

    chat_result = await db.execute(
        select(LearnChat).where(LearnChat.knowledge_point_id == kp_id)
    )
    for chat in chat_result.scalars().all():
        await db.delete(chat)

    await db.commit()


# ========== 探索对话 ==========

async def chat(db: AsyncSession, knowledge_point_id: int, message: str, quoted_text: str | None = None) -> dict:
    """
    探索对话：LLM 回复用户 + 实时融合到讲解内容。
    返回 {reply, updated_subtopic, updated_content}。
    """
    node = await db.get(KnowledgeNode, knowledge_point_id)
    if not node:
        raise ValueError("知识点不存在")

    # 获取知识内容
    kc = (await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == knowledge_point_id)
    )).scalar_one_or_none()
    content_text = kc.content if kc else "（尚未生成讲解内容）"

    # 对话历史
    history = (await db.execute(
        select(LearnChat)
        .where(LearnChat.knowledge_point_id == knowledge_point_id)
        .order_by(LearnChat.id)
    )).scalars().all()
    chat_history = "\n".join(
        f"{'用户' if c.role == 'user' else 'AI'}: {c.content}" for c in history[-10:]
    ) or "（暂无）"

    # 匹配引用子话题
    matched_subtopic = None
    if quoted_text:
        matched_subtopic = _find_subtopic_by_quote(content_text, quoted_text)

    user_input = message.strip()
    if quoted_text:
        user_input = f"【引用】{quoted_text}\n\n{user_input}"

    # LLM 对话回复
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
        raise RuntimeError("对话失败，请重试")

    # 实时融合到讲解内容
    updated_subtopic = None
    updated_content = None
    # merge_status: merged(改到已有子话题) / created(新建子话题) / skipped(LLM 判重复) /
    #               no_match(引用未命中任何子话题) / no_change(LLM 没改) / failed / parse_error
    merge_status = "skipped"
    if kc:
        try:
            if matched_subtopic:
                merge_prompt = LEARN_CHAT_MERGE_SUBTOPIC_PROMPT.format(
                    subtopic_text=matched_subtopic["text"],
                    chat_reply=reply,
                )
                merge_resp = await llm.ainvoke(merge_prompt)
                merged_text = merge_resp.content.strip()
                if merged_text and '####' in merged_text:
                    idx = merged_text.index('####')
                    merged_text = merged_text[idx:].strip()
                if merged_text and merged_text != matched_subtopic["text"]:
                    updated_subtopic = merged_text
                    kc.content = _replace_subtopic(kc.content, matched_subtopic["text"], merged_text)
                    updated_content = kc.content
                    merge_status = "merged"
                    await db.flush()
                else:
                    merge_status = "no_change"
            elif quoted_text:
                # 用户引用了文本但未命中子话题
                merge_status = "no_match"
            else:
                existing_subtopics = _split_subtopics(content_text)
                existing_desc = "\n\n".join(
                    f"【{i+1}】{st['text']}" for i, st in enumerate(existing_subtopics)
                ) or "（暂无）"
                new_prompt = LEARN_CHAT_NEW_SUBTOPIC_PROMPT.format(
                    knowledge_point=node.name,
                    existing_subtopics=existing_desc,
                    chat_reply=reply,
                    user_question=message.strip(),
                )
                new_resp = await llm.ainvoke(new_prompt)
                raw = new_resp.content.strip()

                if raw.startswith("SKIP"):
                    merge_status = "skipped"
                elif raw.startswith("MERGE:"):
                    lines = raw.split('\n', 1)
                    directive = lines[0]
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
                            for di in sorted(delete_indices, reverse=True):
                                if 0 <= di < len(existing_subtopics) and di != merge_idx:
                                    kc.content = kc.content.replace(existing_subtopics[di]["text"], "", 1).strip()
                            kc.content = _replace_subtopic(kc.content, existing_subtopics[merge_idx]["text"], merged_text)
                            kc.content = re.sub(r'\n{3,}', '\n\n', kc.content)
                            updated_content = kc.content
                            merge_status = "merged"
                            await db.flush()
                        else:
                            merge_status = "parse_error"
                    except (ValueError, IndexError) as e:
                        logger.warning(f"解析 MERGE 指令失败: {e}")
                        merge_status = "parse_error"
                elif raw.startswith("NEW"):
                    new_text = raw.split('\n', 1)[1].strip() if '\n' in raw else ""
                    if new_text and '####' in new_text:
                        new_text = new_text[new_text.index('####'):].strip()
                    if new_text and new_text.startswith('####'):
                        updated_subtopic = new_text
                        kc.content = kc.content.rstrip() + "\n\n" + new_text
                        updated_content = kc.content
                        merge_status = "created"
                        await db.flush()
                    else:
                        merge_status = "parse_error"
        except Exception as e:
            logger.warning(f"子话题融合失败（不影响对话）: {e}")
            merge_status = "failed"

    # 保存对话记录
    db.add(LearnChat(
        knowledge_point_id=knowledge_point_id,
        role="user", content=message.strip(), quoted_text=quoted_text,
    ))
    db.add(LearnChat(
        knowledge_point_id=knowledge_point_id,
        role="assistant", content=reply,
    ))
    await db.commit()

    return {
        "reply": reply,
        "updated_subtopic": updated_subtopic,
        "updated_content": updated_content,
        "merge_status": merge_status,
    }


# ========== 对话历史 ==========

async def get_chat_history(db: AsyncSession, kp_id: int) -> list[dict]:
    """获取知识点的对话历史"""
    result = await db.execute(
        select(LearnChat)
        .where(LearnChat.knowledge_point_id == kp_id)
        .order_by(LearnChat.id)
    )
    return [{
        "role": c.role,
        "content": c.content,
        "quoted_text": c.quoted_text,
        "created_at": c.created_at.isoformat() if c.created_at else None,
    } for c in result.scalars().all()]


# ========== 合并对话到讲解 ==========

async def merge_chat_to_content(db: AsyncSession, knowledge_point_id: int, chat_messages: list[str]) -> str:
    """将对话中有价值的内容融入知识讲解文章，返回合并后内容"""
    kc = (await db.execute(
        select(KnowledgeContent).where(KnowledgeContent.knowledge_point_id == knowledge_point_id)
    )).scalar_one_or_none()
    if not kc:
        raise ValueError("讲解内容不存在")

    chat_content = "\n\n".join(chat_messages)
    llm = get_llm(temperature=0.1, max_tokens=8192)
    prompt = LEARN_MERGE_PROMPT.format(
        original_content=kc.content,
        chat_content=chat_content,
    )

    try:
        resp = await llm.ainvoke(prompt)
        merged = resp.content.strip()
        if merged.startswith("```markdown"):
            merged = merged[len("```markdown"):].strip()
        if merged.startswith("```"):
            merged = merged[3:].strip()
        if merged.endswith("```"):
            merged = merged[:-3].strip()
        lines = merged.split('\n')
        first_section = next((i for i, l in enumerate(lines) if l.strip().startswith('### ')), 0)
        if first_section > 0:
            merged = '\n'.join(lines[first_section:]).strip()
    except Exception as e:
        logger.error(f"合并失败: {e}")
        raise RuntimeError("合并失败，请重试")

    additions = kc.user_additions or []
    additions.append({"chat_content": chat_content, "timestamp": "now"})
    kc.content = merged
    kc.user_additions = additions
    await db.commit()

    return merged
