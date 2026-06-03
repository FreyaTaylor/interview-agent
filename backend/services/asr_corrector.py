"""ASR 转写纠错服务。

职责：
  - 修正面试 turns 里的技术术语错别字（按发音对齐，参考领域词典）
  - 删除短噪声 turn（纯语气词、≤4 字无信息量）
  - 保留原 turn id 和 speaker，content 重写

调用时机：split_into_turns + repair_turns 之后、parse_interview_text 主流程之前。
失败时返回原 turns，不阻塞下游解析。
"""
import logging

from backend.prompts.interview_prompts import ASR_CORRECTION_PROMPT
from backend.services.interview_turns import render_turns_for_llm
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)

# 单次纠错的 turn 字数上限；超过则分批（保留 turn 边界，不切到一半）
# 给的宽一些，纠错任务上下文越完整越准
CORRECT_BATCH_CHAR_LIMIT = 6000


def _chunk_turns_by_char(turns: list[dict], limit: int) -> list[list[dict]]:
    """按字符数把 turns 分批，单个 turn 即使超长也独占一批。"""
    if not turns:
        return []
    batches: list[list[dict]] = []
    current: list[dict] = []
    current_len = 0
    for t in turns:
        clen = len(t.get("content") or "")
        if current and current_len + clen > limit:
            batches.append(current)
            current = [t]
            current_len = clen
        else:
            current.append(t)
            current_len += clen
    if current:
        batches.append(current)
    return batches


async def _correct_one_batch(llm, turns: list[dict]) -> list[dict] | None:
    """单批纠错。返回 None 表示 LLM 失败，调用方应回退原 turns。"""
    dialogue = render_turns_for_llm(turns)
    prompt = ASR_CORRECTION_PROMPT.format(dialogue=dialogue)
    try:
        resp = await llm.ainvoke(prompt)
        raw = resp.content if hasattr(resp, "content") else str(resp)
        data = parse_llm_json(raw)
    except Exception as e:
        logger.warning(f"ASR 纠错 LLM 调用失败: {type(e).__name__}: {e}")
        return None

    out_turns = (data or {}).get("turns")
    if not isinstance(out_turns, list):
        logger.warning("ASR 纠错返回结构异常，缺 turns 字段")
        return None

    # 校验：id 必须在原 batch 范围内，speaker/content 必须存在
    valid_ids = {t["id"]: t for t in turns}
    cleaned: list[dict] = []
    for ot in out_turns:
        try:
            tid = int(ot.get("id"))
        except (TypeError, ValueError):
            continue
        if tid not in valid_ids:
            continue
        speaker = ot.get("speaker") or valid_ids[tid].get("speaker") or ""
        content = (ot.get("content") or "").strip()
        if not content:
            continue
        cleaned.append({"id": tid, "speaker": speaker, "content": content})
    # 按 id 升序
    cleaned.sort(key=lambda x: x["id"])
    return cleaned


async def correct_asr_turns(turns: list[dict]) -> list[dict]:
    """对完整 turns 列表做 ASR 纠错 + 噪声 turn 删除。

    保证：
      - 返回的 turn id 是原 id 的子集（删除的 turn 直接消失）
      - speaker 与原一致
      - 任一 batch LLM 失败 → 该 batch 原样保留，不影响其他 batch
    """
    if not turns:
        return turns
    batches = _chunk_turns_by_char(turns, CORRECT_BATCH_CHAR_LIMIT)
    # 纠错 prompt 输出 token 与输入接近，max_tokens 留宽裕一些
    llm = get_llm(temperature=0.0, max_tokens=8192, timeout=120)

    result: list[dict] = []
    deleted_count = 0
    for batch in batches:
        corrected = await _correct_one_batch(llm, batch)
        if corrected is None:
            # 失败 → 该 batch 原样保留
            result.extend(batch)
            continue
        result.extend(corrected)
        deleted_count += len(batch) - len(corrected)

    result.sort(key=lambda x: x["id"])
    logger.info(
        f"ASR 纠错完成：{len(turns)} turns → {len(result)} turns（删除噪声 {deleted_count}），"
        f"分 {len(batches)} 批"
    )
    return result
