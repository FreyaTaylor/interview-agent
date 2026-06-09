"""一次性生成 V23 面试 prompt seed 迁移：直接从 Python 源逐字搬运，避免手抄漂移。

规则：
- Java PromptService 用朴素 replace("{var}")，不解析双花括号。
- 故把 Python .format() 的字面量 {{ }} 还原为单花括号 { }，真实占位符（{raw_text} 等）保持单花括号。
"""
import sys
import pathlib

ROOT = pathlib.Path("/Users/ivy/PycharmProjects/interview-agent")
sys.path.insert(0, str(ROOT))

from backend.prompts.interview_prompts import (  # noqa: E402
    ASR_CORRECTION_PROMPT,
    INTERVIEW_PARSE_PROMPT,
    INTERVIEW_SCORE_PROMPT,
    INTERVIEW_PROJECT_SCORE_PROMPT,
    INTERVIEW_OVERALL_ANALYSIS_PROMPT,
    INTERVIEW_ALGORITHM_SCORE_PROMPT,
    INTERVIEW_HR_SCORE_PROMPT,
)

# (db_key, python_prompt, description)
ITEMS = [
    ("interview/parse", INTERVIEW_PARSE_PROMPT,
     "S8 面试复盘：解析（完全复刻 Python INTERVIEW_PARSE_PROMPT，占位符 {context}{raw_text}）"),
    ("interview/asr-correct", ASR_CORRECTION_PROMPT,
     "S8 面试复盘：ASR 纠错 + 删噪声 turn（复刻 ASR_CORRECTION_PROMPT，占位符 {dialogue}）"),
    ("interview/score-knowledge", INTERVIEW_SCORE_PROMPT,
     "S8 面试复盘：knowledge 评分（复刻 INTERVIEW_SCORE_PROMPT）"),
    ("interview/score-project", INTERVIEW_PROJECT_SCORE_PROMPT,
     "S8 面试复盘：project 评分（复刻 INTERVIEW_PROJECT_SCORE_PROMPT）"),
    ("interview/score-algorithm", INTERVIEW_ALGORITHM_SCORE_PROMPT,
     "S8 面试复盘：algorithm 评分（复刻 INTERVIEW_ALGORITHM_SCORE_PROMPT）"),
    ("interview/score-hr", INTERVIEW_HR_SCORE_PROMPT,
     "S8 面试复盘：hr 评分（复刻 INTERVIEW_HR_SCORE_PROMPT）"),
    ("interview/overall-analysis", INTERVIEW_OVERALL_ANALYSIS_PROMPT,
     "S8 面试复盘：总评（复刻 INTERVIEW_OVERALL_ANALYSIS_PROMPT，占位符 {company}{position}{scored_summary}）"),
]

# parser 里的两段内联 prompt（Python f-string），逐字搬运并参数化为 DB 占位符。
# 注意：这里直接写单花括号最终形态，故不参与 unescape_braces。
MERGE_PROJECT_TOPICS_PROMPT = (
    "以下是同一个项目「{proj_name}」下的多个面试话题，请判断哪些话题在语义上重复或高度相似，应该合并。\n\n"
    "话题列表：\n"
    "{topic_list}\n\n"
    "请返回合并方案。如果某些话题应合并，用数组表示（如 [1,3] 表示话题1和3合并）。不需要合并的单独成组。\n"
    "```json\n"
    "{\"merge_groups\": [[1, 3], [2], [4, 5, 6]]}\n"
    "```\n"
    "只返回 JSON，不要其他内容。如果都不需要合并，每个单独成组即可。"
)

MISSED_CHECK_PROMPT = (
    "请对比以下面试原文和已提取的问题列表，检查是否有遗漏的面试提问。\n\n"
    "## 面试原文\n"
    "{raw_text}\n\n"
    "## 已提取的问题（{question_count}个）\n"
    "{question_list}\n\n"
    "## 要求\n"
    "如果有遗漏的面试提问，按JSON格式返回遗漏的问题。如果没有遗漏，返回空数组。\n"
    "只返回被遗漏的面试官提问，不要重复已提取的。\n"
    "```json\n"
    "{\"missed\": [\"遗漏的问题1\", \"遗漏的问题2\"]}\n"
    "```"
)

# 这两段已是最终单花括号形态，标记为不再 unescape。
RAW_ITEMS = [
    ("interview/merge-project-topics", MERGE_PROJECT_TOPICS_PROMPT,
     "S8 面试复盘：同项目话题合并（复刻 parser 内联 prompt，占位符 {proj_name}{topic_list}）"),
    ("interview/missed-check", MISSED_CHECK_PROMPT,
     "S8 面试复盘：单段遗漏问题二次检查（复刻 parser 内联 prompt，占位符 {raw_text}{question_count}{question_list}）"),
]


def unescape_braces(s: str) -> str:
    # Python .format 字面量 {{ }} → Java 单花括号 { }
    return s.replace("{{", "{").replace("}}", "}")


def pick_tag(content: str) -> str:
    for cand in ("$PMT$", "$PROMPTX$", "$IVSEED$", "$Q9Z$"):
        if cand not in content:
            return cand
    raise RuntimeError("no safe dollar tag")


def main() -> None:
    out = []
    out.append("-- V23: 完全复刻 Python 面试 prompts（解析/纠错/分类型评分/总评）")
    out.append("-- 由 java-backend/scripts/gen_v23_interview_prompts.py 从 backend/prompts/interview_prompts.py 自动生成")
    out.append("-- 注意：Python .format 的 {{ }} 已还原为单花括号；真实占位符保持 {var} 形式")
    out.append("")
    for key, prompt, desc in ITEMS:
        content = unescape_braces(prompt).rstrip("\n")
        tag = pick_tag(content + desc + key)
        out.append(f"INSERT INTO prompt_template (key, content, description) VALUES")
        out.append(f"('{key}', {tag}{content}{tag}, {tag}{desc}{tag})")
        out.append("ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;")
        out.append("")

    # RAW_ITEMS：已是最终单花括号形态，不做 unescape。
    for key, prompt, desc in RAW_ITEMS:
        content = prompt.rstrip("\n")
        tag = pick_tag(content + desc + key)
        out.append(f"INSERT INTO prompt_template (key, content, description) VALUES")
        out.append(f"('{key}', {tag}{content}{tag}, {tag}{desc}{tag})")
        out.append("ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;")
        out.append("")

    target = ROOT / "java-backend/src/main/resources/db/migration/V23__seed_interview_prompts_full.sql"
    target.write_text("\n".join(out), encoding="utf-8")
    print(f"written: {target}")
    print(f"bytes: {target.stat().st_size}")


if __name__ == "__main__":
    main()
