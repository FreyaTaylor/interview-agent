"""
面试落库 — 写入 3 张分类表 + 知识权重 + 用户回答向量
"""
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.interview import (
    InterviewKnowledgeQuestion,
    InterviewProjectQuestion,
    InterviewOtherQuestion,
    UserAnswerEmbedding,
)
from backend.services.embedding import get_embedding

logger = logging.getLogger(__name__)


# ============================================================
# 写入 3 张分类表
# ============================================================

async def store_new_interview_tables(
    scored_groups: list[dict],
    record_id: int,
    db: AsyncSession,
) -> None:
    """
    按 type 分流写入：
      knowledge → interview_knowledge_question（关联知识树叶子）
      project   → interview_project_question  （关联项目根节点）
      algorithm/hr/other → interview_other_question（按 tag 区分，extra JSONB 存附加结构）
    """
    for g in scored_groups:
        t = g.get("type")
        questions = g.get("questions") or []
        user_answer = g.get("user_answer") or None
        original = g.get("original_dialogue") or None
        score_result = g.get("score_result")

        if t == "knowledge":
            # 知识类：面试详情页可以从 interview_knowledge_question 查同一知识点的历史问答
            db.add(InterviewKnowledgeQuestion(
                interview_record_id=record_id,
                knowledge_node_id=g.get("matched_node_id"),  # 为空表示未匹配上任何知识节点
                tag=(g.get("knowledge_point") or "未命名")[:100],  # AI 抽的原始名（供手动归类参考）
                questions=questions,
                user_answer=user_answer,
                original_dialogue=original,
                score_result=score_result,
            ))
        elif t == "project":
            # 项目类：按 project_name 跨面试聚合，呈现说“这个项目被问过哪些话题”
            db.add(InterviewProjectQuestion(
                interview_record_id=record_id,
                project_node_id=g.get("matched_project_id"),
                project_name=(g.get("project_name") or "未命名项目")[:200],
                questions=questions,
                user_answer=user_answer,
                original_dialogue=original,
                score_result=score_result,
            ))
        else:
            # algorithm/hr/other 统一进 other 表，tag 字段区分
            # legacy type → 新 tag 的映射：algorithm→leetcode, hr→hr, 其他看 g["tag"]
            tag = {"algorithm": "leetcode", "hr": "hr"}.get(t) or (g.get("tag") or "misc")
            content = g.get("title") or (questions[0] if questions else "(空)")
            extra: dict = {}

            if tag == "leetcode":
                # LeetCode skill 补全的字段—— 前端从这里渲染指向力扣的跳转链接
                for k_src, k_dst in (("leetcode_url", "url"), ("leetcode_slug", "slug"), ("leetcode_difficulty", "difficulty")):
                    if g.get(k_src):
                        extra[k_dst] = g[k_src]
                # 评分结果里的题解
                if score_result:
                    for k in ("feedback", "description", "example", "suggested_approach"):
                        if score_result.get(k):
                            extra[k] = score_result[k]
            elif tag == "hr":
                if score_result:
                    for k in ("feedback", "suggestion"):
                        if score_result.get(k):
                            extra[k] = score_result[k]
                if questions:
                    extra["questions"] = questions  # HR 一组里可能有多题（“自我介绍”+“期望薪资”）
            else:
                # system_design / scenario / misc — 保留完整评分结果（未来可能扩展）
                if score_result:
                    extra["score_result"] = score_result
                if questions:
                    extra["questions"] = questions

            db.add(InterviewOtherQuestion(
                interview_record_id=record_id,
                content=content,
                tag=tag,
                user_answer=user_answer,
                extra=extra or None,
            ))


# ============================================================
# 副作用：知识权重 + 用户回答向量
# ============================================================

async def update_knowledge_weights(scored_groups: list[dict], db: AsyncSession) -> None:
    """面试中出现的知识点，把 interview_weight 提升 1（上限 5）— 后续推荐时优先复习。"""
    for g in scored_groups:
        if g.get("type") == "knowledge" and g.get("matched_node_id"):
            node = await db.get(KnowledgeNode, g["matched_node_id"])
            if node and node.interview_weight < 5:
                node.interview_weight = min(5, node.interview_weight + 1)


async def store_answer_embeddings(scored_groups: list[dict], db: AsyncSession) -> None:
    """把 knowledge/project 类的用户回答向量化，写入 user_answer_embedding（Agent 长期记忆）。"""
    for g in scored_groups:
        user_answer = (g.get("user_answer") or "").strip()
        if not user_answer:
            continue
        g_type = g.get("type")
        if g_type not in ("knowledge", "project"):
            continue

        if g_type == "project":
            kp_name = f"{g.get('project_name', '')} · {g.get('topic', '')}"
        else:
            kp_name = g.get("knowledge_point") or g.get("matched_node_name") or ""

        questions_text = " | ".join(g.get("questions", []))
        embedding = await get_embedding(f"问题: {questions_text}\n回答: {user_answer}")

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
