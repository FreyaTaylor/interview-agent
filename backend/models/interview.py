"""
面试复盘相关模型
- interview_record: 面试文本记录
- algorithm_question: 算法题记录
- hr_question: HR 题记录
- project_question: 项目拷打问题（持久化）
- user_answer_embedding: 用户回答向量（Agent 长期记忆）
"""
from typing import Any
from sqlalchemy import BigInteger, SmallInteger, Integer, String, Text, ForeignKey
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship
from pgvector.sqlalchemy import Vector

from backend.models.base import Base, TimestampMixin


class InterviewRecord(TimestampMixin, Base):
    """面试文本记录 — 用户上传的面试文本及其解析结果"""
    __tablename__ = "interview_record"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    study_session_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("study_session.id"), nullable=False
    )
    raw_text: Mapped[str] = mapped_column(Text, nullable=False)
    parsed_questions: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    cluster_result: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
    summary_report: Mapped[str | None] = mapped_column(Text, nullable=True)


class AlgorithmQuestion(TimestampMixin, Base):
    """算法题记录 — 从面试文本中提取的算法题"""
    __tablename__ = "algorithm_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    interview_record_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("interview_record.id"), nullable=True
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    leetcode_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    leetcode_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    user_performance: Mapped[str | None] = mapped_column(String(20), nullable=True)


class HrQuestion(TimestampMixin, Base):
    """HR 题记录 — 简单记录，不纳入掌握度"""
    __tablename__ = "hr_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    interview_record_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("interview_record.id"), nullable=True
    )
    question: Mapped[str] = mapped_column(Text, nullable=False)
    answer: Mapped[str | None] = mapped_column(Text, nullable=True)


class ProjectQuestion(TimestampMixin, Base):
    """项目拷打问题 — 持久化存储，跨面试累积"""
    __tablename__ = "project_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    project_name: Mapped[str] = mapped_column(String(200), nullable=False)
    topic: Mapped[str] = mapped_column(String(200), nullable=False)
    questions: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # ["q1", "q2"]
    suggested_answer: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # ["建议1", "建议2"]
    interview_count: Mapped[int] = mapped_column(Integer, server_default="1")  # 被问到的次数


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
    embedding: Mapped[Any] = mapped_column(Vector(1536), nullable=True)  # DashScope embedding
    score: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)  # 当次得分
