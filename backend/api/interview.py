"""
面试复盘 API — 薄路由层
"""
import logging
import os
import tempfile

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.interview_crud import (
    preview_parse_interview,
    finalize_interview,
    check_duplicate as svc_check_duplicate,
    overwrite_record, recalibrate_record, save_draft,
    get_history_list, get_history_detail,
    update_record_meta,
)
from backend.services.asr import transcribe_audio, validate_audio_file

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/interview", tags=["面试复盘"])


# ---- 请求模型 ----

class UploadTextRequest(BaseModel):
    text: str
    company: str = ""
    position: str = ""


class FinalizeRequest(BaseModel):
    """前端校对后提交：turns + groups 是 source of truth，raw_text 后端从 turns 拼。"""
    turns: list[dict]
    groups: list[dict]
    company: str = ""
    position: str = ""


class CheckDuplicateRequest(BaseModel):
    text_hash: str


class OverwriteRequest(BaseModel):
    record_id: int


class UpdateMetaRequest(BaseModel):
    company: str = ""
    position: str = ""


# ---- 面试解析（两阶段：preview → 用户校对 → finalize） ----

@router.post("/preview-parse", summary="Step 1: LLM 解析为结构化 turns+groups，不落库")
async def preview_parse_route(req: UploadTextRequest) -> ApiResponse:
    """面试复盘 Step 1——预解析，不写数据库。

    - 入参：`text` 原始面试文本（语音转写后或手动输入）
    - 委托 `interview_crud.preview_parse_interview` → `interview_parser.parse_interview_text` 由 LLM 拆分为 turns/groups
    - 返回：`{turns, groups, summary?}`供 InterviewReviewPage 供用户校对
    - 未识别出问题 → 40001 业务错；LLM 调用异常 → 50001
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="面试文本不能为空")
    try:
        result = await preview_parse_interview(req.text)
    except Exception as e:
        logger.error(f"面试预览解析异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"解析服务异常: {type(e).__name__}，请重试")
    if not result.get("groups"):
        return ApiResponse.error(code=40001, message=result.get("summary") or "未能从文本中识别出面试提问")
    return ApiResponse.ok(data=result)


@router.post("/finalize", summary="Step 2: 接收校对后的 turns+groups，落库 + 评分")
async def finalize_route(req: FinalizeRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """面试复盘 Step 2——落库与 LLM 评分。

    - 入参：用户校对过的 `turns` + `groups` + 公司/岗位
    - 委托 `interview_crud.finalize_interview`：
      1. `interview_matcher.match_nodes` 将 group 题目匹配到知识树叶/项目树叶（未命中可能新建 ProjectNode）
      2. `interview_scorer.score_all_groups` LLM 逐组 rubric 评分
      3. `interview_storage.store_new_interview_tables` 写入 `InterviewRecord` + 3 张问题子表
      4. `store_answer_embeddings` 将回答向量化入 `user_answer_embedding`（Agent 长期记忆）
    - 返回：`{record_id, avg_score, pass_estimate, overall_analysis, …}`
    """
    try:
        result = await finalize_interview(db, req.turns, req.groups, req.company, req.position)
    except Exception as e:
        logger.error(f"面试 finalize 异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"提交失败: {type(e).__name__}")
    if result.get("error"):
        return ApiResponse.error(code=40001, message=result["message"])
    return ApiResponse.ok(data=result)


@router.post("/parse", summary="一步直解：preview + finalize 合并（跳过用户校对）")
async def parse_route(req: UploadTextRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """一键直解模式：preview_parse + finalize 串联，不跳校对页。

    - 适用：InterviewPage "⚡ 直接解析" 按钮
    - 流程：先 LLM 预解析 → 如果有 groups 则直接 finalize
    - 错误处理同上面两路由
    """
    if not req.text.strip():
        raise HTTPException(status_code=400, detail="面试文本不能为空")
    try:
        preview = await preview_parse_interview(req.text)
        if not preview.get("groups"):
            return ApiResponse.error(code=40001, message=preview.get("summary") or "未识别出面试提问")
        result = await finalize_interview(
            db, preview["turns"], preview["groups"], req.company, req.position,
        )
    except Exception as e:
        logger.error(f"面试 parse 异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"解析失败: {type(e).__name__}")
    if result.get("error"):
        return ApiResponse.error(code=40001, message=result["message"])
    return ApiResponse.ok(data=result)


# ---- 语音上传 ----

@router.post("/upload-audio", summary="上传面试录音，ASR 转写为文本")
async def upload_audio(file: UploadFile = File(...)):
    """语音上传 + ASR 转写。

    - 接受：mp3/wav/m4a/flac，≤3000MB
    - 流程：临时落盘 → `asr.transcribe_audio`（DashScope 或其他 ASR）→ 返回文本 → 删除临时文件
    - 不落库，调用方需额外汇报 `/preview-parse` 或 `/parse`
    """
    # UploadFile.filename 在某些客户端可能为 None，兜底避免 splitext/validate 抛 TypeError
    filename = file.filename or "upload.bin"
    err = validate_audio_file(filename, file.size or 0)
    if err:
        raise HTTPException(status_code=400, detail=err)

    suffix = os.path.splitext(filename)[1] or ".bin"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        content = await file.read()
        tmp.write(content)
        tmp_path = tmp.name

    try:
        text = await transcribe_audio(tmp_path)
        return ApiResponse.ok(data={"text": text, "filename": filename})
    except Exception as e:
        logger.error(f"语音转写失败: {e}")
        raise HTTPException(status_code=500, detail=f"语音转写失败: {e}")
    finally:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)


# ---- 重复检测 ----

@router.post("/check-duplicate", summary="检测面试文本是否重复")
async def check_duplicate_route(req: CheckDuplicateRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """根据文本 hash 检测是否与已有记录重复。

    - 调用方：InterviewPage 提交前预检
    - 与 `InterviewRecord.text_hash` 索引查重；重复返回 `{duplicate: true, record_id, company, position, …}` 供用户选择覆盖或取消
    """
    return ApiResponse.ok(data=await svc_check_duplicate(db, req.text_hash))


@router.post("/overwrite", summary="覆盖已有面试记录")
async def overwrite_route(req: OverwriteRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """删除指定 record 及其 3 张问题子表记录（不含 embeddings，按业务选择保留）。

    - 调用方：InterviewPage 检出重复后点 "覆盖"
    - 调用后用户需重新走 finalize 流程才会生成新记录
    """
    try:
        await overwrite_record(db, req.record_id)
        return ApiResponse.ok(data={"deleted": True})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


# ---- 历史面试 ----

@router.get("/history", summary="获取历史面试列表")
async def get_history(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回面试记录列表（按创建时间倒序）。调用方：InterviewPage 左侧列表。"""
    return ApiResponse.ok(data=await get_history_list(db))


@router.get("/history/{record_id}", summary="获取历史面试详情")
async def get_history_detail_route(record_id: int, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回某条面试的完整快照：turns / groups / rubric 评分 / overall。调用方：InterviewPage 详情抽屉。"""
    try:
        return ApiResponse.ok(data=await get_history_detail(db, record_id))
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


class RecalibrateRequest(BaseModel):
    """继续校准：复用校对页提交的 turns + groups，覆盖旧记录。"""
    turns: list[dict]
    groups: list[dict]


@router.post("/history/{record_id}/recalibrate", summary="继续校准：用新的 turns+groups 覆盖旧记录")
async def recalibrate_route(
    record_id: int, req: RecalibrateRequest, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """覆盖式重算：删除旧 record + 子表，按新结构重新匹配/评分/落库。返回新 record_id（注意 id 会变）。"""
    try:
        result = await recalibrate_record(db, record_id, req.turns, req.groups)
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"面试继续校准异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"校准失败: {type(e).__name__}")
    if result.get("error"):
        return ApiResponse.error(code=40001, message=result["message"])
    return ApiResponse.ok(data=result)


class SaveDraftRequest(BaseModel):
    """保存校准草稿：record_id 为空 → 新建草稿态 record；非空 → 更新已有 record 的 draft 字段。"""
    record_id: int | None = None
    turns: list[dict]
    groups: list[dict]
    company: str = ""
    position: str = ""


@router.post("/draft", summary="保存校准草稿（不触发解析/评分）")
async def save_draft_route(req: SaveDraftRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """保存当前校对页编辑结果为草稿。

    - record_id=None → 新建一条「草稿态」record（parsed_questions=None）
    - record_id 已知  → 更新已有 record 的 draft 字段；不动 parsed_questions
    - 不跑 LLM 匹配/评分，纯落库；适用于"编辑到一半下次继续"场景
    """
    if not req.turns or not req.groups:
        return ApiResponse.error(code=40001, message="turns / groups 不能为空")
    try:
        result = await save_draft(
            db, req.record_id, req.turns, req.groups, req.company, req.position,
        )
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"保存草稿异常: {type(e).__name__}: {e}")
        return ApiResponse.error(code=50001, message=f"保存失败: {type(e).__name__}")
    return ApiResponse.ok(data=result)


@router.delete("/history/{record_id}", summary="删除面试记录")
async def delete_history_route(record_id: int, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """删除一条面试记录及其在 3 张子表里的问题。复用 `overwrite_record` 服务。"""
    try:
        await overwrite_record(db, record_id)  # 复用：删除记录 + 关联题目 + 空 session
        return ApiResponse.ok(data={"deleted": True})
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.patch("/history/{record_id}", summary="更新面试记录的公司/岗位")
async def update_history_route(record_id: int, req: UpdateMetaRequest, db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """仅更新元数据字段（company / position），不动解析结果。调用方：InterviewPage 加载后手动补公司名。"""
    try:
        return ApiResponse.ok(data=await update_record_meta(db, record_id, req.company, req.position))
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
