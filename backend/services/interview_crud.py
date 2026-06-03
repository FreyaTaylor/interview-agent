"""
面试复盘 CRUD 服务 — 项目问题管理 + 其他问题聚合 + 历史记录 + 重复检测

职责：
  1. 完整面试解析编排（创建 record → 解析 → 评分 → 落新 3 表）
  2. 项目拷打问题聚合（按 project_name 跨面试统计）
  3. 其他问题聚合（leetcode / hr 按 tag 去重统计）
  4. 重复检测、覆盖、历史列表与详情
"""
import hashlib
import logging

from sqlalchemy import select, desc, delete as sql_delete
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.interview import (
    InterviewRecord,
    InterviewKnowledgeQuestion, InterviewProjectQuestion, InterviewOtherQuestion,
)
from backend.models.knowledge import KnowledgeNode
from backend.models.project_node import ProjectNode
from backend.services.interview_parser import parse_interview_text
from backend.services.interview_matcher import match_nodes
from backend.services.interview_scorer import score_all_groups, generate_overall_analysis
from backend.services.interview_storage import (
    store_new_interview_tables,
    update_knowledge_weights,
    store_answer_embeddings,
)

logger = logging.getLogger(__name__)


# ========== 面试解析编排（拆分为两阶段：preview + finalize） ==========

async def preview_parse_interview(text: str) -> dict:
    """
    预览解析（Step 1 only）：跑 LLM 分组分类，**不落库**。
    用户后续在校对页编辑后调 finalize_interview 提交。
    返回: {turns, groups, summary}
    """
    parsed = await parse_interview_text(text)
    return {
        "turns": parsed.get("turns", []),
        "groups": parsed.get("groups", []),
        "summary": parsed.get("summary", ""),
    }


def _rebuild_group_dialogue(group: dict, turn_by_id: dict) -> str:
    """根据 group.turn_ids 重新拼 original_dialogue（用户编辑后必须重拼）"""
    lines = []
    for tid in group.get("turn_ids") or []:
        t = turn_by_id.get(tid)
        if not t:
            continue
        speaker = t.get("speaker") or ""
        prefix = f"{speaker}：" if speaker else ""
        lines.append(f"{prefix}{t.get('content', '')}")
    return "\n".join(lines)


async def finalize_interview(
    db: AsyncSession,
    turns: list[dict],
    groups: list[dict],
    company: str = "",
    position: str = "",
) -> dict:
    """
    Finalize（Step 2~6）：接受用户校对后的 turns + groups，落库 + 跑下游。
    与 parse_and_score_interview 的差别：跳过 Step 1，直接用前端给的结构化数据。
    raw_text 由 turns 拼出来存档（turns 是 source of truth）。
    """
    # —— 输入校验 ——
    if not turns or not groups:
        return {"error": True, "message": "校对结果为空"}

    valid_ids = {t["id"] for t in turns if "id" in t}
    for g in groups:
        for tid in g.get("turn_ids") or []:
            if tid not in valid_ids:
                return {"error": True, "message": f"group 包含无效 turn_id={tid}"}

    # —— 从 turns 拼 raw_text（保留校对结果作为档案）——
    raw_text_lines = []
    for t in turns:
        speaker = t.get("speaker") or ""
        prefix = f"{speaker}：" if speaker else ""
        raw_text_lines.append(f"{prefix}{t.get('content', '')}")
    raw_text = "\n".join(raw_text_lines)

    # —— 重拼每个 group 的 original_dialogue（用户改 turn 归属/文字后必须重拼）——
    # 同时从 turns 抽 user_answer / questions（前端校对页不传这俩字段，但下游 scorer 强依赖）
    # 还要把统一字段 tag 映射回下游兼容的 knowledge_point（type=knowledge 时）
    turn_by_id = {t["id"]: t for t in turns}
    for g in groups:
        g["original_dialogue"] = _rebuild_group_dialogue(g, turn_by_id)
        if g.get("type") == "knowledge" and not g.get("knowledge_point"):
            g["knowledge_point"] = g.get("tag") or "未命名"
        # project 类：把 tag 映射成 topic（拷打主题），前端展示 + 评分都依赖它
        if g.get("type") == "project" and not g.get("topic"):
            g["topic"] = g.get("tag") or ""
        # 从 turns 抽 questions（面试官）/ user_answer（我）
        tids = g.get("turn_ids") or []
        q_lines, a_lines = [], []
        for tid in tids:
            t = turn_by_id.get(tid)
            if not t:
                continue
            content = (t.get("content") or "").strip()
            if not content:
                continue
            if t.get("speaker") == "我":
                a_lines.append(content)
            else:
                q_lines.append(content)
        if not g.get("questions"):
            g["questions"] = q_lines
        if not g.get("user_answer"):
            g["user_answer"] = "\n".join(a_lines)

    # ===== Step 2: 匹配知识树 / 项目树 =====
    enriched_groups = await match_nodes(groups, db)

    # ===== Step 3: 创建 InterviewRecord =====
    record = InterviewRecord(
        raw_text=raw_text,
        company=company or None,
        position=position or None,
        text_hash=hashlib.sha256(raw_text.strip().encode()).hexdigest(),
        parsed_questions={"groups": enriched_groups, "turns": turns},
        cluster_result={"summary": ""},
    )
    db.add(record)
    await db.flush()
    await db.commit()

    # ===== Step 4: 评分 + 掌握度 =====
    scored_groups, total_score_sum, scored_count = await score_all_groups(enriched_groups, db)

    # ===== Step 5: 副作用 =====
    await update_knowledge_weights(scored_groups, db)
    await store_answer_embeddings(scored_groups, db)
    await store_new_interview_tables(scored_groups, record.id, db)
    await db.commit()

    # ===== Step 6: 整体分析 + 回写 =====
    overall_analysis = await generate_overall_analysis(scored_groups, company, position)

    stats = {"knowledge": 0, "algorithm": 0, "hr": 0, "project": 0, "other": 0}
    for g in scored_groups:
        t = g.get("type", "other")
        stats[t] = stats.get(t, 0) + 1

    avg_score = round(total_score_sum / scored_count) if scored_count > 0 else 0
    pass_estimate = "较高" if avg_score >= 70 else "一般" if avg_score >= 50 else "较低"

    record.avg_score = avg_score
    record.pass_estimate = pass_estimate
    record.parsed_questions = {
        "groups": scored_groups,
        "overall_analysis": overall_analysis,
        "turns": turns,
    }
    record.summary_report = overall_analysis.get("comment", "") if overall_analysis else ""
    # finalize 成功 → 清空 draft（若有）
    record.draft_turns = None
    record.draft_groups = None
    await db.commit()

    return {
        "record_id": record.id,
        "groups": scored_groups,
        "turns": turns,
        "summary": "",
        "stats": stats,
        "avg_score": avg_score,
        "pass_estimate": pass_estimate,
        "overall_analysis": overall_analysis,
    }


# ========== 重复检测 ==========

async def check_duplicate(db: AsyncSession, text_hash: str) -> dict:
    """根据文本 hash 检测重复面试记录"""
    existing = (await db.execute(
        select(InterviewRecord).where(InterviewRecord.text_hash == text_hash).limit(1)
    )).scalar_one_or_none()
    if existing:
        return {
            "duplicate": True,
            "record_id": existing.id,
            "company": existing.company,
            "position": existing.position,
            "created_at": existing.created_at.isoformat() if existing.created_at else None,
            "avg_score": existing.avg_score,
        }
    return {"duplicate": False}


# ========== 覆盖旧记录 ==========

async def overwrite_record(db: AsyncSession, record_id: int) -> None:
    """删除指定面试记录及其在 3 张新表里的关联数据。不存在则抛 ValueError。"""
    record = await db.get(InterviewRecord, record_id)
    if not record:
        raise ValueError("记录不存在")

    await db.execute(sql_delete(InterviewKnowledgeQuestion).where(
        InterviewKnowledgeQuestion.interview_record_id == record.id))
    await db.execute(sql_delete(InterviewProjectQuestion).where(
        InterviewProjectQuestion.interview_record_id == record.id))
    await db.execute(sql_delete(InterviewOtherQuestion).where(
        InterviewOtherQuestion.interview_record_id == record.id))

    await db.delete(record)
    await db.commit()


async def recalibrate_record(
    db: AsyncSession,
    record_id: int,
    turns: list[dict],
    groups: list[dict],
) -> dict:
    """继续校准：用新的 turns + groups 覆盖原面试记录。

    流程：
      1. 读旧 record，保留 company/position
      2. overwrite_record 删除旧 record + 3 张子表（embeddings 由 finalize 重写覆盖）
      3. finalize_interview 走完整下游（匹配 + 评分 + 落库 + overall）
    返回 finalize 结果（含新的 record_id）。
    """
    record = await db.get(InterviewRecord, record_id)
    if not record:
        raise ValueError("记录不存在")
    company = record.company or ""
    position = record.position or ""

    await overwrite_record(db, record_id)
    return await finalize_interview(db, turns, groups, company, position)


async def save_draft(
    db: AsyncSession,
    record_id: int | None,
    turns: list[dict],
    groups: list[dict],
    company: str = "",
    position: str = "",
) -> dict:
    """保存校准草稿——不触发解析/评分，仅落库 draft_turns/draft_groups。

    两种场景：
      - record_id=None → 新上传面试首次保存：创建一条「草稿态」record
        （parsed_questions=None，draft_* 填充）
      - record_id 已知  → 更新现有 record 的 draft 字段；不动 parsed_questions
    返回：{record_id, is_draft_only, has_parsed}
    """
    if record_id is not None:
        record = await db.get(InterviewRecord, record_id)
        if not record:
            raise ValueError("记录不存在")
        record.draft_turns = turns
        record.draft_groups = groups
        if company:
            record.company = company.strip() or None
        if position:
            record.position = position.strip() or None
    else:
        # 从 turns 拼 raw_text（与 finalize 一致）
        lines = []
        for t in turns:
            speaker = t.get("speaker") or ""
            prefix = f"{speaker}：" if speaker else ""
            lines.append(f"{prefix}{t.get('content', '')}")
        raw_text = "\n".join(lines)
        record = InterviewRecord(
            raw_text=raw_text,
            company=(company or "").strip() or None,
            position=(position or "").strip() or None,
            text_hash=hashlib.sha256(raw_text.strip().encode()).hexdigest(),
            parsed_questions=None,
            cluster_result=None,
            draft_turns=turns,
            draft_groups=groups,
        )
        db.add(record)
        await db.flush()
    await db.commit()
    has_parsed = bool((record.parsed_questions or {}).get("groups"))
    return {
        "record_id": record.id,
        "is_draft_only": not has_parsed,
        "has_parsed": has_parsed,
    }


# ========== 历史面试 ==========

async def update_record_meta(db: AsyncSession, record_id: int, company: str | None, position: str | None) -> dict:
    """更新记录的公司/岗位字段。不存在则抛 ValueError。"""
    record = await db.get(InterviewRecord, record_id)
    if not record:
        raise ValueError("记录不存在")
    record.company = (company or "").strip() or None
    record.position = (position or "").strip() or None
    await db.commit()
    return {"id": record.id, "company": record.company, "position": record.position}


async def _refresh_matched_names(groups: list[dict], db: AsyncSession) -> None:
    """根据 matched_node_id / matched_project_id 批量刷新 group 中的展示名，
    使其与管理页上当前的节点名保持同步。无 id 或节点已删的项保留原名/置空。"""
    if not groups:
        return
    node_ids = {g.get("matched_node_id") for g in groups if g.get("matched_node_id")}
    proj_ids = {g.get("matched_project_id") for g in groups if g.get("matched_project_id")}
    node_map: dict[int, str] = {}
    proj_map: dict[int, str] = {}
    if node_ids:
        rows = (await db.execute(
            select(KnowledgeNode.id, KnowledgeNode.name).where(KnowledgeNode.id.in_(node_ids))
        )).all()
        node_map = {nid: name for nid, name in rows}
    if proj_ids:
        rows = (await db.execute(
            select(ProjectNode.id, ProjectNode.name).where(ProjectNode.id.in_(proj_ids))
        )).all()
        proj_map = {pid: name for pid, name in rows}
    for g in groups:
        nid = g.get("matched_node_id")
        if nid and nid in node_map:
            g["matched_node_name"] = node_map[nid]
        pid = g.get("matched_project_id")
        if pid and pid in proj_map:
            g["matched_project_name"] = proj_map[pid]


async def get_history_list(db: AsyncSession) -> list[dict]:
    """获取所有面试记录摘要，按时间倒序。带打点标识草稿/已解析/带未提交修改。"""
    records = (await db.execute(
        select(InterviewRecord).order_by(desc(InterviewRecord.created_at))
    )).scalars().all()
    return [{
        "id": r.id,
        "company": r.company,
        "position": r.position,
        "avg_score": r.avg_score,
        "pass_estimate": r.pass_estimate,
        "created_at": r.created_at.isoformat() if r.created_at else None,
        "has_parsed": bool((r.parsed_questions or {}).get("groups")),
        "has_draft": bool(r.draft_turns and r.draft_groups),
    } for r in records]


async def get_history_detail(db: AsyncSession, record_id: int) -> dict:
    """获取单条面试记录完整数据。不存在则抛 ValueError。"""
    record = await db.get(InterviewRecord, record_id)
    if not record:
        raise ValueError("记录不存在")

    parsed = record.parsed_questions or {}
    groups = parsed.get("groups", [])
    turns = parsed.get("turns", [])
    # 名字按 id 同步到管理页最新值（管理页改了名/项目节点改了名 → 这里跟着变）
    await _refresh_matched_names(groups, db)
    summary = (record.cluster_result or {}).get("summary", "")
    overall_analysis = parsed.get("overall_analysis") or {"comment": record.summary_report or ""}

    stats = {"knowledge": 0, "algorithm": 0, "hr": 0, "project": 0, "other": 0}
    for g in groups:
        t = g.get("type", "other")
        stats[t] = stats.get(t, 0) + 1

    # 解析失败/脏数据：groups 为空 → 前端展示错误横幅而不是空白页
    parse_error = len(groups) == 0

    return {
        "record_id": record.id,
        "company": record.company,
        "position": record.position,
        "raw_text": record.raw_text,
        "groups": groups,
        "turns": turns,
        "summary": summary,
        "stats": stats,
        "avg_score": record.avg_score or 0,
        "pass_estimate": record.pass_estimate or "",
        "overall_analysis": overall_analysis,
        "parse_error": parse_error,
        "created_at": record.created_at.isoformat() if record.created_at else None,
        # 草稿状态：校对弹框调起后会优先用 draft_turns/draft_groups
        "has_draft": bool(record.draft_turns and record.draft_groups),
        "has_parsed": bool(parsed.get("groups")),
        "draft_turns": record.draft_turns or None,
        "draft_groups": record.draft_groups or None,
    }

