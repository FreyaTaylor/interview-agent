"""
答题/拷打统一模型
- study_question: 知识点下的预生成题目（管理页面初始化时生成）
- question_attempt: 一次完整作答（主问 + 所有追问，最后综合打分）

设计：
- 答题侧：question_type='study', question_id 指 study_question.id
- 拷打侧：question_type='project', question_id 指 project_node.id（level=3 叶子）
- 通过多态而非 FK 关联：避免双外键复杂度（数据完整性靠应用层保证）
"""
from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger, SmallInteger, Integer, String, Text,
    ForeignKey, DateTime, func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from backend.models.base import Base, TimestampMixin


class StudyQuestion(TimestampMixin, Base):
    """知识点下的预生成题目。

    由"知识点初始化"流程批量生成并落库；学习/答题时按 id 引用。
    一道 StudyQuestion 可被同一用户多次作答（产生多条 question_attempt）。
    """
    __tablename__ = "study_question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id", ondelete="CASCADE"), nullable=False
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # Rubric 模板：[{"key_point": "...", "weight": 20, "description": "..."}]
    rubric_template: Mapped[list[dict] | None] = mapped_column(
        JSONB, server_default="'[]'::jsonb"
    )
    # 预生成的范例回答（用户口吻），可为字符串或 list[str]
    recommended_answer: Mapped[Any | None] = mapped_column(JSONB, nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, server_default="0")


class QuestionAttempt(TimestampMixin, Base):
    """一次完整作答记录 = 主问题 + N 个追问 + 最终综合评分。

    生命周期：
      1. create (status='in_progress')          主问题入库到 dialog[0]
      2. 多次 turn (LLM 给追问 + 范例 + 覆盖判定)  追加到 dialog[]
      3. finish (status='finished')             综合 rubric 评分 + 写 final_score
      也可能 abandoned（用户切走没回来）。

    分数聚合：
      该题分数 = 最近 3 次 status='finished' 的 final_score 平均
      知识点 mastery = 该知识点下所有题分数的平均
      项目话题分 = 该话题下所有题分数的平均
      项目准备度 = 所有话题分的平均
    """
    __tablename__ = "question_attempt"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    # 多态：'study' | 'project'
    question_type: Mapped[str] = mapped_column(String(20), nullable=False)
    question_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(20), server_default="'in_progress'")
    # 0-100；仅 finished 时有值
    final_score: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    # [{"key_point": "...", "hit": true, "score": 18, "matched_text": "..."}]
    rubric_result: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    overall_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 仅拷打侧使用
    design_issues: Mapped[list[str] | None] = mapped_column(JSONB, nullable=True)
    # finish 阶段 LLM 生成的 3 个延伸深挖 Q&A（教学用，不影响打分）
    # [{"q": "...", "a": "..."}, ...]
    extension_qa: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    # 完整对话流（含每轮范例回答和覆盖判定）
    # [{role:'agent'|'user', type:'question'|'answer'|'follow_up'|'feedback',
    #   content, recommended_answer?, covered?}]
    dialog: Mapped[list[dict]] = mapped_column(JSONB, server_default="'[]'::jsonb")
    follow_up_count: Mapped[int] = mapped_column(SmallInteger, server_default="0")
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
