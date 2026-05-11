"""
知识讲解生成 Skill — Agent 可调用的结构化内容生成能力

Skill 定义：
- name: generate_knowledge_content
- description: 为知识点生成结构化讲解文章
- input_schema: { knowledge_point, category_path }
- output_schema: { sections: [...], raw_markdown }
- required_sections: 概述、必须掌握、核心原理、常见误区
- optional_sections: 代码示例、关键细节、对比表格
"""
import logging
from dataclasses import dataclass
from enum import Enum

from backend.services.llm import get_llm

logger = logging.getLogger(__name__)


class SectionType(str, Enum):
    """内容模块类型"""
    OVERVIEW = "overview"           # 📌 一句话概述
    MUST_KNOW = "must_know"         # 🔑 必须掌握
    CORE_PRINCIPLE = "core"         # 💡 核心原理
    MISCONCEPTIONS = "misconception"  # ⚠️ 常见误区
    CODE_EXAMPLE = "code"           # 💻 代码示例
    KEY_DETAILS = "details"         # 🔍 关键细节
    COMPARISON = "comparison"       # 📊 对比表格


@dataclass
class SectionSpec:
    """模块规格定义"""
    type: SectionType
    emoji: str
    title: str
    required: bool
    format_rule: str
    max_length: str  # 描述性限制


# Skill 的模块规格注册表
SECTION_REGISTRY: list[SectionSpec] = [
    SectionSpec(
        type=SectionType.OVERVIEW,
        emoji="📌", title="一句话概述",
        required=True,
        format_rule="用引用块格式：`> 一句话说清是什么、解决什么问题`",
        max_length="1 句话",
    ),
    SectionSpec(
        type=SectionType.MUST_KNOW,
        emoji="🔑", title="必须掌握",
        required=True,
        format_rule="3-5 个要点，每个格式：`- ✅ **关键词**：一句话（≤20字）`",
        max_length="3-5 条",
    ),
    SectionSpec(
        type=SectionType.CORE_PRINCIPLE,
        emoji="💡", title="核心原理",
        required=True,
        format_rule="2-3 段。第1段：是什么/怎么工作。第2段：为什么这样设计。第3段（可选）：对比。每段≤3句",
        max_length="2-3 段，每段≤3句",
    ),
    SectionSpec(
        type=SectionType.MISCONCEPTIONS,
        emoji="⚠️", title="常见误区",
        required=True,
        format_rule="2-3 对，每对格式：\n`- ❌ 错误理解：xxx`\n`- ✅ 正确理解：xxx`",
        max_length="2-3 对",
    ),
    SectionSpec(
        type=SectionType.CODE_EXAMPLE,
        emoji="💻", title="代码示例",
        required=False,
        format_rule="≤15 行代码 + 1-2 句说明。仅在知识点涉及代码时输出",
        max_length="15 行代码",
    ),
    SectionSpec(
        type=SectionType.KEY_DETAILS,
        emoji="🔍", title="关键细节",
        required=False,
        format_rule="3-5 个列表项，面试官常追问的细节。仅在高频追问点多时输出",
        max_length="3-5 条",
    ),
    SectionSpec(
        type=SectionType.COMPARISON,
        emoji="📊", title="对比表格",
        required=False,
        format_rule="Markdown 表格对比。仅在有明确可比对象时输出（如 RDB vs AOF）",
        max_length="3-5 行表格",
    ),
]


def build_skill_prompt(knowledge_point: str, category_path: str) -> str:
    """
    根据 Skill 的 Section 注册表动态构建 Prompt。
    这是 Skill 的核心：模块定义和输出规范从注册表生成，
    而非硬编码在 prompt 字符串里。
    """
    required_sections = [s for s in SECTION_REGISTRY if s.required]
    optional_sections = [s for s in SECTION_REGISTRY if not s.required]

    # 构建必选模块描述
    required_desc = ""
    for s in required_sections:
        required_desc += f"\n#### {s.emoji} {s.title}\n{s.format_rule}\n"

    # 构建可选模块描述
    optional_desc = ""
    for s in optional_sections:
        optional_desc += f"\n#### {s.emoji} {s.title}\n{s.format_rule}\n"

    prompt = f"""你是一位资深技术面试辅导专家。请为以下知识点生成一篇结构化讲解文章。

## 知识点
{knowledge_point}

## 所属分类路径
{category_path}

## ⚠️ 内容模板（严格遵守）

**【必选模块】以下 {len(required_sections)} 个模块必须全部输出：**
{required_desc}
**【可选模块】根据知识点特性决定是否输出（不适用则跳过）：**
{optional_desc}
### 风格规范
1. 标题统一用 `###` + emoji（如 `### 📌 一句话概述`）
2. 全文不用 `#` 和 `##`
3. 关键词用 `**加粗**`，要点用 ✅/❌ 标注
4. 每段最多 3 句话，拒绝大段文字
5. 总长度 500-800 字
6. 不要输出"总结""小结"等收尾内容
7. 不要自创模块，只用上面定义的模块

直接输出 Markdown，不要包裹在 JSON 或代码块中。"""
    return prompt


async def execute_content_skill(knowledge_point: str, category_path: str) -> str:
    """
    执行知识讲解生成 Skill。
    返回: Markdown 字符串
    """
    prompt = build_skill_prompt(knowledge_point, category_path)
    llm = get_llm(temperature=0.3, max_tokens=4096)

    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            content = resp.content.strip()
            if content:
                # 验证必选模块是否存在
                missing = validate_sections(content)
                if missing:
                    logger.warning(f"内容缺少必选模块: {missing}，第{attempt+1}次重试")
                    continue
                return content
        except Exception as e:
            logger.warning(f"Skill 执行第{attempt+1}次失败: {e}")
    raise RuntimeError("知识讲解生成失败，请重试")


def validate_sections(content: str) -> list[str]:
    """验证 Markdown 内容是否包含所有必选模块"""
    missing = []
    for s in SECTION_REGISTRY:
        if s.required and s.title not in content:
            missing.append(f"{s.emoji} {s.title}")
    return missing
