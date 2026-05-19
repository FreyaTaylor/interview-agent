"""
语音识别服务 — DashScope Paraformer ASR
支持本地文件识别，自动检测说话人分离
"""
import asyncio
import logging
from pathlib import Path

import dashscope
from dashscope.audio.asr import Recognition

from backend.config import settings

logger = logging.getLogger(__name__)

# 支持的音频格式
ALLOWED_EXTENSIONS = {".mp3", ".wav", ".m4a", ".flac", ".ogg", ".wma", ".aac"}
MAX_FILE_SIZE = 100 * 1024 * 1024  # 100MB


async def transcribe_audio(file_path: str) -> str:
    """
    调用 DashScope Paraformer 进行语音识别。
    使用 Recognition API（支持本地文件），开启说话人分离。
    返回: 转写后的对话文本（带说话人标注）
    """
    dashscope.api_key = settings.DASHSCOPE_API_KEY

    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, _recognize_file, file_path)
    return result


def _recognize_file(file_path: str) -> str:
    """同步执行语音识别（在线程池中执行）"""
    recognition = Recognition(
        model="paraformer-v2",
        format="auto",
        sample_rate=16000,
        callback=None,
    )

    result = recognition.call(
        file=file_path,
        diarization_enabled=True,
        speaker_count=2,  # 面试场景默认 2 人
    )

    if result.status_code != 200:
        raise RuntimeError(f"ASR 转写失败: {result.message}")

    return _format_result(result)


def _format_result(result) -> str:
    """将识别结果格式化为带说话人标注的文本"""
    try:
        sentences = result.get_sentence()
        if not sentences:
            # 降级：直接取纯文本
            text = result.get_sentence() or ""
            return str(text) if text else "转写结果为空"

        # 按说话人分组，合并连续同一说话人的句子
        lines = []
        current_speaker = None
        current_text = []

        for s in sentences:
            speaker_id = s.get("spk_id", s.get("speaker_id", 0))
            text = s.get("text", "").strip()
            if not text:
                continue

            if speaker_id != current_speaker:
                if current_text:
                    label = _speaker_label(current_speaker)
                    lines.append(f"{label}：{''.join(current_text)}")
                current_speaker = speaker_id
                current_text = [text]
            else:
                current_text.append(text)

        if current_text:
            label = _speaker_label(current_speaker)
            lines.append(f"{label}：{''.join(current_text)}")

        return "\n".join(lines) if lines else "转写结果为空"

    except Exception as e:
        logger.warning(f"格式化转写结果失败: {e}")
        # 降级：尝试获取纯文本
        try:
            return str(result.get_sentence()) or "转写结果为空"
        except Exception:
            raise RuntimeError(f"无法解析转写结果: {e}")


def _speaker_label(speaker_id: int | None) -> str:
    """将说话人 ID 映射为角色标签"""
    if speaker_id == 0:
        return "说话人A"
    elif speaker_id == 1:
        return "说话人B"
    else:
        return f"说话人{speaker_id}"


def validate_audio_file(filename: str, file_size: int) -> str | None:
    """
    验证音频文件。返回 None 表示通过，否则返回错误信息。
    """
    ext = Path(filename).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        return f"不支持的音频格式: {ext}，支持: {', '.join(ALLOWED_EXTENSIONS)}"
    if file_size > MAX_FILE_SIZE:
        return f"文件过大: {file_size / 1024 / 1024:.1f}MB，上限 100MB"
    return None
