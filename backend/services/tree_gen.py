"""
知识树生成服务
- 多树创建（文本解析 / LLM生成 / 截图解析 / .mm文件导入）
- 树操作（优化 / 合并）
- 通用树写入
"""
import logging
import xml.etree.ElementTree as ET

from langchain_core.messages import HumanMessage
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import settings
from backend.models.knowledge import KnowledgeNode
from backend.models.user import User
from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.tree_prompts import (
    TREE_PARSE_TEXT_PROMPT, TREE_GENERATE_PROMPT, TREE_PARSE_IMAGE_PROMPT,
    TREE_OPTIMIZE_PROMPT, TREE_DUPLICATE_CHECK_PROMPT, TREE_MERGE_PROMPT,
)

logger = logging.getLogger(__name__)


# ============================================================
# 内部工具
# ============================================================

async def _get_profile_text(db: AsyncSession, user_id: int = 1) -> str:
    """获取用户画像文本"""
    user = await db.get(User, user_id)
    return (user.profile_text or "").strip() if user else ""


async def _check_duplicate_by_name(name: str, db: AsyncSession) -> None:
    """
    创建树之前检查名称是否与已有根节点重复（精确 + LLM 语义）。
    重复则 raise ValueError。
    """
    name = name.strip()
    if not name:
        return

    roots = (await db.execute(
        select(KnowledgeNode).where(KnowledgeNode.parent_id.is_(None))
    )).scalars().all()
    if not roots:
        return

    # 精确匹配
    for r in roots:
        if (r.name or "").strip().lower() == name.lower():
            raise ValueError(f"已存在同名知识树「{r.name}」，请更换名称或合并")

    # LLM 语义匹配
    existing_names = [(r.name or "").strip() for r in roots if (r.name or "").strip()]
    if not existing_names:
        return

    try:
        llm = get_llm(temperature=0.0, max_tokens=256)
        names_text = "\n".join(f"- {n}" for n in existing_names)
        prompt = TREE_DUPLICATE_CHECK_PROMPT.format(new_name=name, existing_names=names_text)
        resp = await llm.ainvoke(prompt)
        data = parse_llm_json(resp.content)
        if data.get("duplicate") and data.get("matched_name"):
            matched = data["matched_name"].strip()
            for r in roots:
                if (r.name or "").strip().lower() == matched.lower():
                    raise ValueError(f"与已有知识树「{r.name}」语义重复，请更换名称或合并")
    except ValueError:
        raise  # 重复错误向上抛
    except Exception as e:
        logger.warning(f"创建前语义去重检测失败（跳过）: {e}")


async def _fill_leaf_embeddings(db: AsyncSession, root_id: int) -> int:
    """为指定根下所有缺 embedding 的节点（叶子 + 非叶 category）回填 embedding。

    用途：tree_gen / optimize / merge 写完树后调用，避免后续面试匹配时
    因 embedding 为空而漏匹配（之前需手动跑 backfill_node_embeddings.py）。

    设计要点：
    - 不再按 level==3 过滤——知识树可能是 3 层或 4 层（甚至更深）；
      统一按「embedding IS NULL」过滤所有节点。
    - 非叶节点也生成 embedding，便于：题目匹配不上叶子时 fallback 到 category
      （在该 category 下新增子节点），避免一直在树外漂着。
    - embedding 文本采用「父路径 / 节点名」，区分不同上下文下的同名节点
      （如 MySQL/事务 vs 分布式系统/事务）。

    返回成功回填的数量；失败不报错，只记 warning。
    """
    all_nodes = {
        n.id: n for n in (await db.execute(select(KnowledgeNode))).scalars().all()
    }

    def _under_root(node: KnowledgeNode) -> bool:
        if node.id == root_id:
            return False  # root 自身不嵌
        cur = node
        while cur is not None and cur.parent_id is not None:
            if cur.parent_id == root_id or cur.id == root_id:
                return True
            cur = all_nodes.get(cur.parent_id)
        return False

    def _build_text(node: KnowledgeNode) -> str:
        # 父路径 + 节点名，与 backfill_node_embeddings 脚本保持一致
        parts: list[str] = [node.name or ""]
        pid = node.parent_id
        while pid is not None:
            p = all_nodes.get(pid)
            if p is None:
                break
            parts.append(p.name or "")
            pid = p.parent_id
        return " / ".join(reversed([s for s in parts if s]))

    targets = [
        n for n in all_nodes.values()
        if n.embedding is None and _under_root(n)
    ]
    if not targets:
        return 0

    ok = 0
    for node in targets:
        try:
            vec = await get_embedding(_build_text(node))
            if vec is None:
                continue
            node.embedding = vec
            ok += 1
        except Exception as e:
            logger.warning(f"回填 embedding 失败 node={node.id} name={node.name}: {e}")
    logger.info(f"知识树 root={root_id} 回填 embedding 成功={ok}/{len(targets)}")
    return ok


# ============================================================
# 通用：将嵌套 JSON 树写入 DB
# ============================================================

async def save_tree_to_db(
    db: AsyncSession,
    tree_data: dict,
    parent_id: int | None = None,
    base_level: int = 1,
) -> tuple[int, int]:
    """
    递归写入嵌套树结构到 DB。
    tree_data: {"name": "...", "children": [...], "interview_weight": 3}
    返回: (root_node_id, leaf_count)
    """
    name = tree_data.get("name", "").strip()
    children = tree_data.get("children", [])

    # 去重：如果只有一个子节点且名称和当前节点相同，跳过这层包装
    if len(children) == 1 and children[0].get("name", "").strip() == name and children[0].get("children"):
        children = children[0]["children"]

    has_kids = bool(children)
    node_type = "category" if has_kids else "leaf"

    node = KnowledgeNode(
        parent_id=parent_id,
        name=name,
        level=base_level,
        node_type=node_type,
        interview_weight=tree_data.get("interview_weight", 3),
        sort_order=tree_data.get("sort_order", 0),
    )
    db.add(node)
    await db.flush()

    total_leaves = 0
    for i, child in enumerate(children):
        child["sort_order"] = i
        _, leaves = await save_tree_to_db(db, child, parent_id=node.id, base_level=base_level + 1)
        total_leaves += leaves

    if not has_kids:
        total_leaves = 1

    return node.id, total_leaves


# ============================================================
# 方式1: 文本/Markdown → LLM 解析 → 落库
# ============================================================

async def create_tree_from_text(text: str, db: AsyncSession) -> dict:
    """解析文本/Markdown 为知识树并写入 DB"""
    llm = get_llm(temperature=0.1, max_tokens=8192)
    prompt = TREE_PARSE_TEXT_PROMPT.format(text=text)

    tree_data = None
    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            tree_data = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"文本解析第{attempt+1}次失败: {e}")
    if tree_data is None:
        raise RuntimeError("文本解析失败，请重试")

    # 创建前检查重复
    tree_name = tree_data.get("name", "").strip()
    if tree_name:
        await _check_duplicate_by_name(tree_name, db)

    root_id, leaf_count = await save_tree_to_db(db, tree_data)
    await _fill_leaf_embeddings(db, root_id)
    await db.commit()
    return {"root_id": root_id, "name": tree_data.get("name", ""), "leaf_count": leaf_count}


# ============================================================
# 方式2: 需求描述 → LLM 一次生成完整树 → 落库
# ============================================================

async def create_tree_from_generate(tree_name: str, requirements: str, db: AsyncSession) -> dict:
    """根据需求描述，LLM 一次生成完整知识树。带入用户画像，requirements 为空时默认用 tree_name"""
    # 检查重复
    await _check_duplicate_by_name(tree_name, db)

    # requirements 为空则用 tree_name
    if not requirements.strip():
        requirements = tree_name

    # 读取用户画像
    profile_text = await _get_profile_text(db)

    llm = get_llm(temperature=0.3, max_tokens=8192)
    prompt = TREE_GENERATE_PROMPT.format(
        tree_name=tree_name,
        requirements=requirements,
        profile_text=profile_text or "（未设置用户画像）",
    )

    tree_data = None
    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            tree_data = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"树生成第{attempt+1}次失败: {e}")
    if tree_data is None:
        raise RuntimeError("知识树生成失败，请重试")

    # LLM 返回的是 {"children": [...]}, 补上 name 作为根节点
    tree_data["name"] = tree_name
    root_id, leaf_count = await save_tree_to_db(db, tree_data)
    await _fill_leaf_embeddings(db, root_id)
    await db.commit()
    return {"root_id": root_id, "name": tree_name, "leaf_count": leaf_count}


# ============================================================
# 方式3: 截图 → LLM Vision 解析 → 落库
# ============================================================

async def create_tree_from_image(image_base64: str, media_type: str, db: AsyncSession) -> dict:
    """解析截图为知识树并写入 DB，使用 DashScope qwen-vl 多模态"""
    from langchain_openai import ChatOpenAI

    vlm = ChatOpenAI(
        model="qwen-vl-max",
        api_key=settings.DASHSCOPE_API_KEY,
        base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        temperature=0.1,
    )

    message = HumanMessage(content=[
        {"type": "image_url", "image_url": {"url": f"data:{media_type};base64,{image_base64}"}},
        {"type": "text", "text": TREE_PARSE_IMAGE_PROMPT},
    ])

    tree_data = None
    for attempt in range(3):
        try:
            resp = await vlm.ainvoke([message])
            tree_data = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"截图解析第{attempt+1}次失败: {e}")
    if tree_data is None:
        raise RuntimeError("截图解析失败，请重试")

    # 创建前检查重复
    tree_name = tree_data.get("name", "").strip()
    if tree_name:
        await _check_duplicate_by_name(tree_name, db)

    root_id, leaf_count = await save_tree_to_db(db, tree_data)
    await _fill_leaf_embeddings(db, root_id)
    await db.commit()
    return {"root_id": root_id, "name": tree_data.get("name", ""), "leaf_count": leaf_count}


# ============================================================
# 方式4: .mm 文件（FreeMind/幕布导出）→ 解析 XML → 落库
# ============================================================

def _parse_mm_node(element: ET.Element) -> dict:
    """递归解析 .mm XML 节点为嵌套 dict"""
    text = (element.get("TEXT") or "").strip()
    children = []
    for child in element.findall("node"):
        parsed = _parse_mm_node(child)
        if parsed["name"] or parsed.get("children"):
            children.append(parsed)
    result = {"name": text, "interview_weight": 3}
    if children:
        result["children"] = children
    return result


async def create_tree_from_mm(content: bytes, db: AsyncSession) -> dict:
    """解析 .mm 文件（FreeMind/幕布 XML 格式）并写入 DB"""
    try:
        root_el = ET.fromstring(content)
    except ET.ParseError as e:
        raise RuntimeError(f".mm 文件解析失败: {e}")

    root_node_el = root_el if root_el.tag == "node" else root_el.find("node")
    if root_node_el is None:
        raise RuntimeError(".mm 文件中未找到有效节点")

    tree_data = _parse_mm_node(root_node_el)
    if not tree_data.get("name"):
        tree_data["name"] = "导入的知识树"

    # 创建前检查重复
    await _check_duplicate_by_name(tree_data["name"], db)

    root_id, leaf_count = await save_tree_to_db(db, tree_data)
    await _fill_leaf_embeddings(db, root_id)
    await db.commit()
    return {"root_id": root_id, "name": tree_data.get("name", ""), "leaf_count": leaf_count}


# ============================================================
# LLM 优化知识树（查漏补缺）
# ============================================================

async def optimize_tree(root_id: int, db: AsyncSession) -> dict:
    """LLM 全面优化知识树：去重合并、结构调整、查漏补缺、语言精简"""
    import json

    root = await db.get(KnowledgeNode, root_id)
    if not root:
        raise RuntimeError("根节点不存在")

    all_nodes = (await db.execute(
        select(KnowledgeNode).order_by(KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id)
    )).scalars().all()

    def get_descendants(pid):
        result = []
        for n in all_nodes:
            if n.parent_id == pid:
                children = get_descendants(n.id)
                node_dict = {"name": n.name}
                if children:
                    node_dict["children"] = children
                else:
                    node_dict["interview_weight"] = n.interview_weight or 3
                result.append(node_dict)
        return result

    tree_json = json.dumps(
        {"name": root.name, "children": get_descendants(root.id)},
        ensure_ascii=False, indent=2
    )

    llm = get_llm(temperature=0.3, max_tokens=8192)
    prompt = TREE_OPTIMIZE_PROMPT.format(tree_name=root.name, tree_json=tree_json)

    optimized = None
    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            optimized = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"优化第{attempt+1}次失败: {e}")
    if optimized is None:
        raise RuntimeError("LLM 优化失败，请重试")

    async def _delete_descendants(pid):
        children = (await db.execute(
            select(KnowledgeNode).where(KnowledgeNode.parent_id == pid)
        )).scalars().all()
        for c in children:
            await _delete_descendants(c.id)
            await db.delete(c)

    await _delete_descendants(root_id)
    root.name = optimized.get("name", root.name)

    children = optimized.get("children", [])
    leaf_count = 0
    for i, child in enumerate(children):
        child["sort_order"] = i
        _, leaves = await save_tree_to_db(db, child, parent_id=root_id, base_level=root.level + 1)
        leaf_count += leaves

    await _fill_leaf_embeddings(db, root_id)
    await db.commit()
    return {"root_id": root_id, "leaf_count": leaf_count}


# ============================================================
# 合并知识树
# ============================================================

async def merge_trees(source_id: int, target_id: int, db: AsyncSession) -> dict:
    """LLM 语义合并：将 source 树合并到 target 树"""
    import json

    source = await db.get(KnowledgeNode, source_id)
    target = await db.get(KnowledgeNode, target_id)
    if not source or not target:
        raise RuntimeError("源或目标节点不存在")

    all_nodes = (await db.execute(
        select(KnowledgeNode).order_by(KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id)
    )).scalars().all()

    def _to_dict(root_id):
        root = next((n for n in all_nodes if n.id == root_id), None)
        if not root:
            return {}

        def _children(pid):
            result = []
            for n in all_nodes:
                if n.parent_id == pid:
                    node = {"name": n.name}
                    kids = _children(n.id)
                    if kids:
                        node["children"] = kids
                    else:
                        node["interview_weight"] = n.interview_weight or 3
                    result.append(node)
            return result

        return {"name": root.name, "children": _children(root_id)}

    old_tree = _to_dict(target_id)
    new_tree = _to_dict(source_id)

    llm = get_llm(temperature=0.1, max_tokens=8192)
    prompt = TREE_MERGE_PROMPT.format(
        old_tree=json.dumps(old_tree, ensure_ascii=False, indent=2),
        new_tree=json.dumps(new_tree, ensure_ascii=False, indent=2),
    )

    merged_data = None
    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            merged_data = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"合并第{attempt+1}次失败: {e}")
    if merged_data is None:
        raise RuntimeError("LLM 合并失败，请重试")

    async def _delete_descendants(pid):
        children = (await db.execute(
            select(KnowledgeNode).where(KnowledgeNode.parent_id == pid)
        )).scalars().all()
        for c in children:
            await _delete_descendants(c.id)
            await db.delete(c)

    await _delete_descendants(target_id)
    target.name = merged_data.get("name", target.name)

    children = merged_data.get("children", [])
    leaf_count = 0
    for i, child in enumerate(children):
        child["sort_order"] = i
        _, leaves = await save_tree_to_db(db, child, parent_id=target_id, base_level=target.level + 1)
        leaf_count += leaves

    await _delete_descendants(source_id)
    await db.delete(source)

    await _fill_leaf_embeddings(db, target_id)
    await db.commit()
    return {"target_id": target_id, "leaf_count": leaf_count}
