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
    CORE_PRINCIPLE = "core"         # 💡 核心原理


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
        type=SectionType.CORE_PRINCIPLE,
        emoji="💡", title="核心原理",
        required=True,
        format_rule="""根据知识点（面试知识方向）自动列出 3-8 个面试必考的具体子话题，每个用 #### 标题。
子话题 = 面试官会单独提问的最小知识单元。

例如：
- 锁机制 → #### synchronized原理、#### ReentrantLock与AQS、#### 锁升级过程、#### 读写锁、#### 死锁检测
- 线程池 → #### 核心参数详解、#### 工作流程、#### 拒绝策略、#### 线程工厂与命名、#### 动态调参
- 消息可靠性 → #### 发送确认机制、#### 事务消息、#### 持久化与刷盘、#### 消费确认与重投、#### 死信队列
- 事务与MVCC → #### 四种隔离级别、#### MVCC实现原理、#### ReadView机制、#### undo log与版本链

每个子话题的结构（严格遵守）：
1. 2-4 句话讲解，简洁专业，关键词用 **加粗**
2. 末尾必须附 1-2 个面试追问（用引用块格式），并给出简短答案：
   > 🎙 面试追问：xxx？
   > 答：一句话回答。

可包含简短代码示例（≤10行）和对比表格。

禁止生成「面试加分点」「加分项」模块，只用 `> 🎙 面试追问` 格式。""",
        max_length="根据知识方向复杂度自动决定，通常 3-8 个子话题",
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

## ❗❗ 领域约束
**必须严格按照「所属分类路径」确定知识点的技术领域！**
- 路径以 mysql 开头 → 讲 MySQL 相关内容，不要讲 Java
- 路径以 redis 开头 → 讲 Redis 相关内容
- 路径以 Java 开头 → 讲 Java 相关内容
- 即使知识点名称和其他领域有同名概念（如"线程池"），也必须讲当前路径对应技术的版本
- 例：mysql → 连接数与线程池 → 讲的是 MySQL 的线程池（thread_pool 插件、连接管理），不是 Java ThreadPoolExecutor

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
5. 总长度 800-1500 字
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
