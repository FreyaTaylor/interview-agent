"""
面试原文 → 结构化 turns

每个 turn 是一次发言（speaker 切换处切分）。给每个 turn 全局唯一 id，
后续 LLM 引用 id 而不是再复制原文片段，前端按 id 定位。

输出结构：
    [
        {"id": 0, "speaker": "面试官", "content": "...", "char_start": 0, "char_end": 12},
        {"id": 1, "speaker": "我", "content": "...", "char_start": 13, "char_end": 80},
        ...
    ]

切分规则：
    - 以行首 "面试官: / 面试官： / 我: / 我：" 作为 turn 边界（前后可有空白）
    - 该行的说话人前缀本身不进入 content
    - 找不到任何说话人标记时，按双换行切段，speaker 留空
"""
from __future__ import annotations

import re

# 行首说话人前缀：面试官 / 我（兼容中英文冒号、前后空白）
_SPEAKER_RE = re.compile(r"(?:^|\n)([ \t]*)(面试官|我)\s*[:：]\s*", re.MULTILINE)


def split_into_turns(text: str) -> list[dict]:
    """把原文切成 turns 列表（带全局 id 和原文字符偏移）。"""
    if not text:
        return []

    matches = list(_SPEAKER_RE.finditer(text))
    if not matches:
        # 没有说话人标记 → 按空行切段，speaker 留空，便于纯笔记式输入也能定位
        return _split_by_blank_lines(text)

    turns: list[dict] = []
    for i, m in enumerate(matches):
        # content 起点：匹配末尾（说话人前缀之后）
        content_start = m.end()
        # content 终点：下一个匹配的起点（或文末）
        content_end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        content = text[content_start:content_end].rstrip()
        if not content:
            continue
        turns.append({
            "id": len(turns),
            "speaker": m.group(2),
            "content": content,
            "char_start": content_start,
            "char_end": content_start + len(content),
        })
    return turns or _split_by_blank_lines(text)


def _split_by_blank_lines(text: str) -> list[dict]:
    """fallback：按双换行切段，speaker 留空。"""
    turns: list[dict] = []
    cursor = 0
    for part in re.split(r"\n\s*\n", text):
        stripped = part.strip()
        if not stripped:
            cursor += len(part) + 2  # 近似估算，无需精确
            continue
        start = text.find(stripped, cursor)
        if start < 0:
            start = cursor
        end = start + len(stripped)
        turns.append({
            "id": len(turns),
            "speaker": "",
            "content": stripped,
            "char_start": start,
            "char_end": end,
        })
        cursor = end
    if not turns:
        turns.append({
            "id": 0,
            "speaker": "",
            "content": text.strip(),
            "char_start": 0,
            "char_end": len(text),
        })
    return turns


# ============================================================
# ASR 切错修复（启发式合并）
# ============================================================

# 续接字：基本只能跟在另一字后面，单独开句无意义。命中即与上一 turn 合并。
_CONT_START = ("种", "吗", "呢", "啊", "呀", "哈", "嘛", "咯")
# 上一 turn 结尾的"悬挂连词"：命中说明被腰斩
_HANG_END = ("那", "和", "与", "或", "及", "就", "且")
# 句末标点（含中英文）
_TERMINATORS = set("。？！.?!；;")
# 修复时允许的"短碎片"长度
_SHORT_FRAG_LEN = 8


def _should_merge_to_prev(prev: dict | None, cur: dict) -> bool:
    """判定当前 turn 是否应当合并到上一个 turn（speaker 沿用上一个）。

    保守策略：只在以下任一强信号命中时合并：
    1) 当前 turn 以续接字开头（如「种」「吗」「呢」），且当前 turn 较短（< 8 字）。
    2) 上一个 turn 以悬挂连词结尾（如「那」「和」「或」），且当前 turn 较短无句末标点。
    """
    if not prev:
        return False
    cur_content = (cur.get("content") or "").strip()
    if not cur_content:
        return False

    prev_content = (prev.get("content") or "").rstrip("，,。.？?！!；;、 \t\n")
    if not prev_content:
        return False
    prev_last = prev_content[-1]
    cur_first = cur_content[0]

    # 信号 1：续接字开头 + 当前短
    if cur_first in _CONT_START and len(cur_content) < _SHORT_FRAG_LEN:
        return True

    # 信号 2：上句悬挂连词结尾 + 当前短 + 无句末标点
    if (
        prev_last in _HANG_END
        and len(cur_content) < _SHORT_FRAG_LEN
        and not any(c in _TERMINATORS for c in cur_content)
    ):
        return True

    return False


def repair_turns(turns: list[dict]) -> list[dict]:
    """合并被 ASR 切错的破碎 turn 到上一个，speaker 沿用上一个。

    - 重新连续编号 id，char_start/char_end 取合并后的首末。
    - 输入空返回空。
    - 保守合并：见 _should_merge_to_prev。
    """
    if not turns:
        return turns
    out: list[dict] = []
    for t in turns:
        prev = out[-1] if out else None
        if _should_merge_to_prev(prev, t):
            prev["content"] = prev["content"].rstrip() + t["content"].lstrip()
            if "char_end" in t:
                prev["char_end"] = t["char_end"]
        else:
            out.append(dict(t))
    # 重新连续编号
    for i, t in enumerate(out):
        t["id"] = i
    return out


def render_turns_for_llm(turns: list[dict]) -> str:
    """把 turns 渲染回带 [tN] 标记的对话，供 LLM 解析。

    输出形如：
        [t0] 面试官: ...
        [t1] 我: ...

    保留 speaker 前缀让 LLM 仍能从文本特征判定说话人；turn id 让它能直接引用。
    """
    lines: list[str] = []
    for t in turns:
        speaker = t.get("speaker") or ""
        prefix = f"{speaker}: " if speaker else ""
        lines.append(f"[t{t['id']}] {prefix}{t['content']}")
    return "\n".join(lines)


def chunk_turns(turns: list[dict], chunk_size: int = 1200) -> list[list[dict]]:
    """把 turns 切成多段，每段累计字符不超过 chunk_size。
    单 turn 超长时它自成一段。
    """
    if not turns:
        return []
    chunks: list[list[dict]] = []
    current: list[dict] = []
    current_len = 0
    for t in turns:
        t_len = len(t["content"]) + 8  # 算上 [tN] speaker: 前缀的大致开销
        if not current and t_len > chunk_size:
            chunks.append([t])
            continue
        if current_len + t_len > chunk_size and current:
            chunks.append(current)
            current = [t]
            current_len = t_len
        else:
            current.append(t)
            current_len += t_len
    if current:
        chunks.append(current)
    return chunks
