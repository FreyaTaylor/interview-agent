"""
面试复盘相关模型（Phase 2 使用，此处预先定义）
- interview_record: 面试文本记录
- algorithm_question: 算法题记录
- hr_question: HR 题记录
"""
from typing import Any
from sqlalchemy import BigInteger, SmallInteger, Integer, String, Text, ForeignKey
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

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
