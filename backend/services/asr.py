"""
语音识别服务 — DashScope Paraformer ASR
使用 Transcription API（paraformer-v2）支持说话人分离

核心流程：
  本地临时文件 (FastAPI 所在机器)
    │  ① ffmpeg 转单声道 wav  ── 仍在本机磁盘
    │  ② 上传到 DashScope 临时 OSS  ── 远程 ASR 唯一能读到的位置
    │  ③ 提交转写任务（任务里只带 oss:// URL，DashScope 后台自己从 OSS 拉文件）
    │  ④ 轮询任务状态
    │  ⑤ 下载 transcription.json，按 speaker_id 合并文本
    ▼
  返回 "说话人A：...\n说话人B：..."

为什么必须先传 OSS？
  DashScope 是远程 SaaS 服务，无法访问本机 /var/folders/.../tmpXXX.mp3。
  所以即使转写 API 本身只是一次 POST，也必须先把文件传到 DashScope 能读的位置。
"""
import asyncio
import logging
import os
import subprocess
import tempfile
from pathlib import Path

import requests as http_requests

from backend.config import settings

logger = logging.getLogger(__name__)

# 支持的音频格式
ALLOWED_EXTENSIONS = {".mp3", ".wav", ".m4a", ".flac", ".ogg", ".wma", ".aac"}
MAX_FILE_SIZE = 300 * 1024 * 1024  # 300MB


def _convert_to_mono(file_path: str) -> str | None:
    """
    用 ffmpeg 将音频转为单声道 wav。
    返回临时文件路径（调用方负责清理），如果已经是单声道则返回 None。
    """
    try:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0",
             "-show_entries", "stream=channels",
             "-of", "default=noprint_wrappers=1:nokey=1", file_path],
            capture_output=True, text=True, timeout=30,
        )
        channels = int(probe.stdout.strip()) if probe.stdout.strip() else 1
        if channels <= 1:
            return None

        logger.info(f"音频为 {channels} 声道，转换为单声道...")
        tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
        tmp.close()
        subprocess.run(
            ["ffmpeg", "-y", "-i", file_path, "-ac", "1", "-ar", "16000", tmp.name],
            capture_output=True, timeout=300, check=True,
        )
        return tmp.name
    except Exception as e:
        logger.warning(f"ffmpeg 转换失败: {e}")
        return None


def _upload_to_dashscope_oss(file_path: str, api_key: str) -> str:
    """
    上传本地文件到 DashScope 临时 OSS，返回 oss:// URL。

    走的是 OSS 标准的 "PostObject + Policy 凭证" 流程，不是直传 DashScope。
    凭证有时效，每次上传都重新拿。上传完成的文件 24h 后自动过期。
    """
    # ── 步骤 1：找 DashScope 拿一份临时上传凭证（policy / signature）
    # 这一步不传文件本身，只问 "我要传一个 paraformer-v2 用的文件，给我凭证"
    resp = http_requests.get(
        "https://dashscope.aliyuncs.com/api/v1/uploads",
        headers={"Authorization": f"Bearer {api_key}"},
        params={"action": "getPolicy", "model": "paraformer-v2"},
        timeout=30,
    )
    resp.raise_for_status()
    policy_data = resp.json().get("data", {})

    # 凭证里包含：上传到哪个 OSS host、放到哪个目录、签名、ACL 策略
    upload_host = policy_data["upload_host"]              # OSS 上传 endpoint
    upload_dir = policy_data["upload_dir"]                # 临时目录前缀（含日期+UUID）
    policy = policy_data["policy"]                        # base64 编码的策略
    signature = policy_data["signature"]                  # 用 access key 签的 policy
    oss_access_key_id = policy_data["oss_access_key_id"]
    x_oss_object_acl = policy_data["x_oss_object_acl"]
    x_oss_forbid_overwrite = policy_data["x_oss_forbid_overwrite"]

    # ── 步骤 2：用上面的凭证 PostObject 到 OSS（这是真正传文件字节的请求）
    filename = os.path.basename(file_path)
    object_key = f"{upload_dir}/{filename}"               # OSS 内的完整对象 key

    with open(file_path, "rb") as f:
        upload_resp = http_requests.post(
            upload_host,
            data={
                # OSS PostObject 协议要求的字段，缺一不可
                "OSSAccessKeyId": oss_access_key_id,
                "policy": policy,
                "Signature": signature,
                "key": object_key,
                "x-oss-object-acl": x_oss_object_acl,
                "x-oss-forbid-overwrite": x_oss_forbid_overwrite,
                "success_action_status": "200",           # 让 OSS 成功时返回 200 而不是 204
            },
            files={"file": (filename, f)},                # 真正的文件字节流
            timeout=600,                                  # 大文件上传给 10 分钟
        )
    if upload_resp.status_code not in (200, 204):
        raise RuntimeError(f"OSS 上传失败: HTTP {upload_resp.status_code}")

    # ── 步骤 3：拼装 oss:// URL（DashScope ASR 任务只认这种 schema）
    # 这个 URL 不是 https，外部浏览器打不开；只有 DashScope 内部服务能解析
    oss_url = f"oss://{object_key}"
    logger.info(f"文件已上传到 DashScope OSS: {oss_url}")
    return oss_url


async def transcribe_audio(file_path: str) -> str:
    """
    调用 DashScope Paraformer-v2 进行录音文件识别。
    支持说话人分离，自动处理多声道转换和 OSS 上传。
    使用 RESTful API（SDK 不支持 oss:// URL）。

    转写完成后会再调一次 LLM 判断哪个"说话人X"是面试官，
    把文本统一替换为"面试官 / 我"再返回，方便后续解析和展示。
    """
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(None, _transcribe_file, file_path)
    # 角色归一化：失败不阻塞，回退原文
    try:
        result = await _normalize_speaker_roles(result)
    except Exception as e:
        logger.warning(f"角色归一化失败，保留原始说话人标签: {e}")
    return result


def _submit_transcription_task(oss_url: str, api_key: str) -> str:
    """
    向 DashScope Paraformer-v2 提交转写任务（异步），返回 task_id。

    ★ 这就是"调用 ASR 解析 API"的核心一行 —— 下面这个 POST 请求。
    注意 input.file_urls 传的是 oss:// URL，DashScope 后台自己去 OSS 拉文件，
    所以本地 /var/folders/... 临时路径完全不会出现在这里。
    """
    resp = http_requests.post(
        "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "X-DashScope-Async": "enable",                # 异步模式：立即返回 task_id，不阻塞
            "X-DashScope-OssResourceResolve": "enable",   # 允许 input 里使用 oss:// URL
        },
        json={
            "model": "paraformer-v2",                     # 多语种 + 说话人分离模型
            "input": {"file_urls": [oss_url]},            # 只传 URL，文件本体已在 OSS
            "parameters": {
                "language_hints": ["zh", "en"],           # 中英混合面试场景
                "diarization_enabled": True,              # 开启说话人分离
                "speaker_count": 2,                       # 面试一般 2 人：面试官+候选人
            },
        },
        timeout=30,                                       # 只是提交任务，不需要长等
    )
    resp.raise_for_status()
    data = resp.json()
    # 异步任务返回结构：{"output": {"task_id": "xxx", "task_status": "PENDING"}, ...}
    task_id = data.get("output", {}).get("task_id")
    if not task_id:
        raise RuntimeError(f"提交转写任务失败: {data}")
    logger.info(f"Transcription 任务已提交: task_id={task_id}")
    return task_id


def _poll_transcription_task(task_id: str, api_key: str, max_wait: int = 600) -> dict:
    """
    轮询任务状态直到完成（SUCCEEDED / FAILED），返回完整 output。

    ASR 是异步任务（音频可能几分钟到几十分钟），DashScope 不会主动回调，
    只能客户端定期 GET /api/v1/tasks/{task_id} 查询。
    """
    import time
    url = f"https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}"
    headers = {"Authorization": f"Bearer {api_key}"}
    deadline = time.time() + max_wait                    # 最长等 10 分钟

    while time.time() < deadline:
        resp = http_requests.get(url, headers=headers, timeout=30)
        resp.raise_for_status()
        output = resp.json().get("output", {})
        status = output.get("task_status")               # PENDING / RUNNING / SUCCEEDED / FAILED
        if status in ("SUCCEEDED", "FAILED"):
            logger.info(f"Transcription 任务完成: status={status}")
            return output
        time.sleep(3)                                    # 每 3 秒查一次，平衡延迟与请求量

    raise RuntimeError(f"ASR 转写超时（{max_wait}秒）")


def _transcribe_file(file_path: str) -> str:
    """
    同步执行整个 ASR 流程：转单声道 → 上传OSS → 提交任务 → 轮询 → 解析结果。
    file_path 是本机临时文件（来自 upload_audio 路由的 tempfile）。
    """
    # 多声道音频需要先转单声道，否则 diarization 效果差。返回 None 表示已是单声道无需转换。
    mono_path = _convert_to_mono(file_path)
    actual_path = mono_path or file_path                  # 选实际要上传的文件

    try:
        api_key = settings.DASHSCOPE_API_KEY

        # 步骤 ①：把本地文件传到 DashScope 临时 OSS（远程 ASR 唯一能读到的地方）
        oss_url = _upload_to_dashscope_oss(actual_path, api_key)

        # 步骤 ②：提交异步转写任务（SDK 不支持 oss:// URL，所以用 RESTful API）
        task_id = _submit_transcription_task(oss_url, api_key)

        # 步骤 ③：轮询等待任务完成（PENDING → RUNNING → SUCCEEDED/FAILED）
        output = _poll_transcription_task(task_id, api_key)

        if output.get("task_status") != "SUCCEEDED":
            results = output.get("results", [])
            err_details = ""
            for r in results:
                if r.get("subtask_status") == "FAILED":
                    err_details += f" [{r.get('code')}: {r.get('message')}]"
            raise RuntimeError(f"ASR 转写失败: {output.get('task_status')}{err_details}")

        # 步骤 ④：从结果 URL 下载 JSON，按说话人合并成可读文本
        return _parse_transcription_output(output)
    finally:
        # 只清理本函数自己产生的 mono 临时文件；
        # 原始上传的 tempfile 由上层 upload_audio 路由的 finally 负责删除
        if mono_path:
            try:
                os.unlink(mono_path)
            except OSError:
                pass


def _parse_transcription_output(output: dict) -> str:
    """从 Transcription RESTful API 结果中提取带说话人标注的文本"""
    try:
        results = output.get("results", [])
        if not results:
            return "转写结果为空"

        first = results[0]
        if first.get("subtask_status") != "SUCCEEDED":
            msg = first.get("message", "子任务失败")
            raise RuntimeError(f"ASR 转写失败: {msg}")

        transcription_url = first.get("transcription_url")
        if not transcription_url:
            return "转写结果为空"

        # 下载 JSON 结果（OSS 跨地域可能较慢，给足超时 + 重试）
        last_err: Exception | None = None
        data = None
        for attempt in range(3):
            try:
                resp = http_requests.get(
                    transcription_url,
                    timeout=(10, 120),  # (connect, read) — read 给 120s 应对北京 OSS 慢链路
                )
                resp.raise_for_status()
                data = resp.json()
                break
            except Exception as e:
                last_err = e
                logger.warning(
                    f"下载转写 JSON 失败（第 {attempt + 1}/3 次）: {e}"
                )
        if data is None:
            raise RuntimeError(f"下载转写 JSON 重试 3 次仍失败: {last_err}")

        transcripts = data.get("transcripts", [])
        if not transcripts:
            return "转写结果为空"

        transcript = transcripts[0]
        sentences = transcript.get("sentences", [])
        if not sentences:
            return transcript.get("text", "转写结果为空")

        # 按说话人分组，合并连续同一说话人的句子
        lines = []
        current_speaker = None
        current_text = []

        for s in sentences:
            speaker_id = s.get("speaker_id", 0)
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

    except RuntimeError:
        raise
    except Exception as e:
        logger.warning(f"解析转写结果失败: {e}")
        raise RuntimeError(f"解析转写结果失败: {e}")


def _speaker_label(speaker_id: int | None) -> str:
    """将说话人 ID 映射为角色标签"""
    if speaker_id == 0:
        return "说话人A"
    elif speaker_id == 1:
        return "说话人B"
    else:
        return f"说话人{speaker_id}"


# 角色归一化 ------------------------------------------------------------------
# ASR 出来的是 "说话人A / 说话人B"，无法判断谁是面试官。
# 这里用 LLM 看一段开头（通常含寒暄+第一个问题）判断角色，再统一替换：
#   面试官 → "面试官"，候选人 → "我"
# 失败时返回原文（不阻塞落库）。
_ROLE_PROMPT = """下面是一段面试录音的转写文本，按"说话人A/B/..."分行。
请判断哪个说话人是【面试官】，哪个是【候选人/我】。
面试官通常会主动提问、引导话题；候选人通常会自我介绍、回答问题。

只输出 JSON，不要任何额外文字：
{{"interviewer": "说话人X", "candidate": "说话人Y"}}

文本片段：
{snippet}
"""


async def _normalize_speaker_roles(text: str) -> str:
    import re
    if not text or "说话人" not in text:
        return text

    # 收集出现过的说话人标签
    labels = sorted(set(re.findall(r"^(说话人[A-Z0-9]+)[：:]", text, flags=re.M)))
    if len(labels) < 2:
        # 单人录音：直接当成候选人自述
        if len(labels) == 1:
            return text.replace(f"{labels[0]}：", "我：").replace(f"{labels[0]}:", "我:")
        return text

    # 取开头一段送给 LLM 判断
    snippet = text[:1500]
    from backend.services.llm import get_llm
    from langchain_core.messages import HumanMessage
    import json as _json

    llm = get_llm(temperature=0.0, max_tokens=200, timeout=30)
    resp = await llm.ainvoke([HumanMessage(content=_ROLE_PROMPT.format(snippet=snippet))])
    content = (resp.content or "").strip()
    # 兼容 ```json ... ``` 包裹
    m = re.search(r"\{.*\}", content, re.S)
    if not m:
        logger.warning(f"LLM 角色识别返回无 JSON: {content[:200]}")
        return text
    data = _json.loads(m.group(0))
    interviewer = data.get("interviewer")
    candidate = data.get("candidate")
    if interviewer not in labels or candidate not in labels or interviewer == candidate:
        logger.warning(f"LLM 角色识别结果异常: {data}, labels={labels}")
        return text

    # 替换。先用占位符避免互相覆盖（罕见但稳妥）
    out = text
    out = out.replace(f"{interviewer}：", "__IV__：").replace(f"{interviewer}:", "__IV__:")
    out = out.replace(f"{candidate}：", "__ME__：").replace(f"{candidate}:", "__ME__:")
    out = out.replace("__IV__", "面试官").replace("__ME__", "我")
    logger.info(f"角色归一化完成: {interviewer}→面试官, {candidate}→我")
    return out


def validate_audio_file(filename: str, file_size: int) -> str | None:
    """验证音频文件。返回 None 表示通过，否则返回错误信息。"""
    ext = Path(filename).suffix.lower()
    if ext not in ALLOWED_EXTENSIONS:
        return f"不支持的音频格式: {ext}，支持: {', '.join(ALLOWED_EXTENSIONS)}"
    if file_size > MAX_FILE_SIZE:
        return f"文件过大: {file_size / 1024 / 1024:.1f}MB，上限 300MB"
    return None
