"""
面试复盘相关模型
- interview_record: 面试文本记录
- interview_knowledge_question: 知识类面试问题（关联知识树叶子节点）
- interview_project_question: 项目类面试问题（关联 project_node 叶子节点）
- interview_other_question: 其他类面试问题（leetcode/hr等，JSONB扩展）
- user_answer_embedding: 用户回答向量（Agent 长期记忆）
"""
from typing import Any
from sqlalchemy import BigInteger, SmallInteger, Integer, String, Text, ForeignKey
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column
from pgvector.sqlalchemy import Vector

from backend.models.base import Base, TimestampMixin


class InterviewRecord(TimestampMixin, Base):
    """面试文本记录 — 用户上传的面试文本及其解析结果"""
    __tablename__ = "interview_record"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    # 注：早期面试复盘借用了 study_session 容器，study_session_id 字段已在
    # migration 007_qa_attempt_refactor.sql 中 DROP COLUMN，整个 study_session 表也已废弃。
    raw_text: Mapped[str] = mapped_column(Text, nullable=False)
    company: Mapped[str | None] = mapped_column(String(200), nullable=True)
    position: Mapped[str | None] = mapped_column(String(200), nullable=True)
    text_hash: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    avg_score: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    pass_estimate: Mapped[str | None] = mapped_column(String(20), nullable=True)
    parsed_questions: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    cluster_result: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    summary_report: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 校准草稿：用户在校对页"保存"未触发解析时的中间状态
    # finalize / recalibrate 成功后清空；加载详情时若有 draft 优先使用 draft
    draft_turns: Mapped[list[dict[str, Any]] | None] = mapped_column(JSONB, nullable=True)
    draft_groups: Mapped[list[dict[str, Any]] | None] = mapped_column(JSONB, nullable=True)


class UserAnswerEmbedding(TimestampMixin, Base):
    """用户回答向量 — Agent 长期记忆，存储用户对每个知识点的理解"""
    __tablename__ = "user_answer_embedding"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    knowledge_point_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=True
    )
    source: Mapped[str] = mapped_column(String(20), nullable=False)  # 'interview' | 'study'
    knowledge_point_name: Mapped[str] = mapped_column(String(200), nullable=False)
    question_text: Mapped[str] = mapped_column(Text, nullable=False)  # 面试官的问题
    answer_text: Mapped[str] = mapped_column(Text, nullable=False)  # 用户的回答
    embedding: Mapped[Any] = mapped_column(Vector(1024), nullable=True)  # DashScope embedding
    score: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)  # 当次得分


# ============================================================
# 新版面试问题表（3类分流）
# ============================================================

class InterviewKnowledgeQuestion(TimestampMixin, Base):
    """知识类面试问题 — 关联知识树叶子节点，embedding 匹配"""
    __tablename__ = "interview_knowledge_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    interview_record_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("interview_record.id"), nullable=False
    )
    knowledge_node_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=True
    )  # nullable: 未分类时为空
    tag: Mapped[str] = mapped_column(String(100), nullable=False)  # AI 提取的知识点名，如 "HashMap"
    questions: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)  # ["主问", "追问1", "追问2"]
    user_answer: Mapped[str | None] = mapped_column(Text, nullable=True)  # 用户回答摘要
    original_dialogue: Mapped[str | None] = mapped_column(Text, nullable=True)  # 原始对话片段
    score_result: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)  # 评分结果


class InterviewProjectQuestion(TimestampMixin, Base):
    """项目类面试问题 — 关联项目根节点"""
    __tablename__ = "interview_project_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    interview_record_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("interview_record.id"), nullable=False
    )
    project_node_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("project_node.id"), nullable=True
    )  # nullable: 未分类时为空
    project_name: Mapped[str] = mapped_column(String(200), nullable=False)  # AI 提取的项目名
    questions: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)  # ["主问", "追问1"]
    user_answer: Mapped[str | None] = mapped_column(Text, nullable=True)
    original_dialogue: Mapped[str | None] = mapped_column(Text, nullable=True)
    score_result: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)


class InterviewOtherQuestion(TimestampMixin, Base):
    """其他类面试问题 — leetcode/hr/场景题等，每条一题"""
    __tablename__ = "interview_other_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    interview_record_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("interview_record.id"), nullable=False
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)  # 题目内容
    tag: Mapped[str] = mapped_column(String(50), nullable=False)  # "leetcode" | "hr" | "system_design" | ...
    user_answer: Mapped[str | None] = mapped_column(Text, nullable=True)
    extra: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)  # 按 tag 存不同结构
