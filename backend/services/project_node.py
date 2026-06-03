"""
项目节点服务 — 项目树节点的 CRUD + LLM 解析

职责：
  1. 获取全部项目节点列表（编辑用）
  2. 创建节点（自动推断 level / node_type）
  3. 更新节点（改名、移动父节点、改排序）
  4. 批量更新排序
  5. 递归删除节点（含子孙）
  6. 从文本描述创建项目树（LLM 解析 + 去重）
"""
import logging

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.project_node import ProjectNode
from backend.models.project import Project
from backend.models.interview import InterviewProjectQuestion
from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.project_prompts import (
    PROJECT_PARSE_TEXT_PROMPT, PROJECT_DUPLICATE_CHECK_PROMPT,
)

logger = logging.getLogger(__name__)


async def _fill_project_leaf_embeddings(db: AsyncSession, root_id: int) -> int:
    """为指定项目根下所有缺 embedding 的节点（包含叶子 + 非叶 category）回填 embedding。

    用于：project_node 创建 / 编辑完后调用，避免后续面试项目题匹配时
    因 embedding 为空被全部过滤 → 每条答卷都被当作新题走创建分支。
    非叶节点也生成 embedding，供 matcher 在题目匹配不上叶子时 fallback 到其对应 category，
    在该 category 下新增子节点。

    embedding 文本采用「父路径 / 节点名」格式，区分不同上下文下的重名节点。

    返回成功回填的数量；失败不报错，只记 warning。
    """
    all_nodes = {
        n.id: n for n in (await db.execute(select(ProjectNode))).scalars().all()
    }

    def _under_root(node: ProjectNode) -> bool:
        if node.id == root_id:
            return False  # root 自身不嵌
        cur = node
        while cur is not None and cur.parent_id is not None:
            if cur.parent_id == root_id or cur.id == root_id:
                return True
            cur = all_nodes.get(cur.parent_id)
        return False

    def _build_text(node: ProjectNode) -> str:
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
            logger.warning(f"项目节点回填 embedding 失败 node={node.id} name={node.name}: {e}")
    logger.info(f"项目树 root={root_id} 回填 embedding 成功={ok}/{len(targets)}")
    return ok


# ========== 查询 ==========

async def get_all_nodes(db: AsyncSession) -> list[dict]:
    """获取全部项目节点，按 level → sort_order → id 排序"""
    result = await db.execute(
        select(ProjectNode).order_by(
            ProjectNode.level, ProjectNode.sort_order, ProjectNode.id,
        )
    )
    return [{
        "id": n.id,
        "parent_id": n.parent_id,
        "name": n.name,
        "level": n.level,
        "node_type": n.node_type,
        "sort_order": n.sort_order,
    } for n in result.scalars().all()]


# ========== 创建 ==========

async def create_node(
    db: AsyncSession,
    parent_id: int | None,
    name: str,
) -> dict:
    """
    新增项目节点。
    - 无 parent_id → level=1（项目根节点）
    - 有 parent_id → level = 父节点 level + 1，level >= 3 为 leaf
    """
    if parent_id:
        parent = await db.get(ProjectNode, parent_id)
        if not parent:
            raise ValueError("父节点不存在")
        level = parent.level + 1
        node_type = "leaf" if level >= 3 else "category"
    else:
        level = 1
        node_type = "category"

    node = ProjectNode(
        parent_id=parent_id,
        name=name.strip(),
        level=level,
        node_type=node_type,
    )
    db.add(node)
    await db.flush()

    # 新增节点同步生成 embedding（叶子供题匹配，非叶 category 供 fallback）
    # 用「父路径 / 节点名」作为输入，避免不同上下文重名拿到同一向量
    try:
        parts: list[str] = [node.name]
        cur_pid = node.parent_id
        while cur_pid is not None:
            p = await db.get(ProjectNode, cur_pid)
            if p is None:
                break
            parts.append(p.name or "")
            cur_pid = p.parent_id
        text = " / ".join(reversed([s for s in parts if s]))
        vec = await get_embedding(text)
        if vec is not None:
            node.embedding = vec
    except Exception as e:
        logger.warning(f"新建项目节点生成 embedding 失败 node={node.id}: {e}")

    await db.commit()
    return {"id": node.id, "name": node.name, "level": level}


# ========== 更新 ==========

async def update_node(
    db: AsyncSession,
    node_id: int,
    *,
    name: str | None = None,
    parent_id: int | None = None,
    sort_order: int | None = None,
    move_parent: bool = False,
) -> dict:
    """
    更新项目节点。
    move_parent=True 时重新计算 level 和 node_type。
    """
    node = await db.get(ProjectNode, node_id)
    if not node:
        raise ValueError("节点不存在")

    if name is not None:
        node.name = name.strip()
    if sort_order is not None:
        node.sort_order = sort_order

    if move_parent:
        node.parent_id = parent_id
        if parent_id:
            parent = await db.get(ProjectNode, parent_id)
            if parent:
                node.level = parent.level + 1
        else:
            node.level = 1
        node.node_type = "leaf" if node.level >= 3 else "category"

    await db.commit()
    return {"id": node.id, "name": node.name}


async def batch_update_sort(
    db: AsyncSession,
    updates: list[dict],
) -> int:
    """批量更新节点排序"""
    count = 0
    for item in updates:
        node = await db.get(ProjectNode, item["id"])
        if node:
            node.sort_order = item["sort_order"]
            count += 1
    await db.commit()
    return count


# ========== 删除 ==========

async def delete_node_recursive(db: AsyncSession, node_id: int) -> int:
    """
    递归删除节点及其所有子孙。
    删除后，如果父节点不再有子节点，将其 node_type 改为 leaf。
    先解除外部引用：interview_project_question.project_node_id 置空（事实数据保留）。
    """
    node = await db.get(ProjectNode, node_id)
    if not node:
        raise ValueError("节点不存在")

    # 1. 收集所有待删 node_id（含自己 + 全部子孙）
    to_delete_ids: list[int] = []

    async def _collect(pid: int):
        to_delete_ids.append(pid)
        children = await db.execute(
            select(ProjectNode).where(ProjectNode.parent_id == pid)
        )
        for child in children.scalars().all():
            await _collect(child.id)

    await _collect(node.id)

    # 2. 先解除 interview_project_question 的外键引用（置空，不删事实数据）
    await db.execute(
        update(InterviewProjectQuestion)
        .where(InterviewProjectQuestion.project_node_id.in_(to_delete_ids))
        .values(project_node_id=None)
    )

    # 3. 自底向上删除节点（叶子先删，避免自引用 FK 冲突）
    async def _delete(pid: int):
        children = await db.execute(
            select(ProjectNode).where(ProjectNode.parent_id == pid)
        )
        for child in children.scalars().all():
            await _delete(child.id)
        sub = await db.get(ProjectNode, pid)
        if sub:
            await db.delete(sub)

    parent_id = node.parent_id
    await _delete(node.id)

    # 4. 父节点如果没有剩余子节点，变成 leaf
    if parent_id:
        remaining = await db.execute(
            select(ProjectNode).where(ProjectNode.parent_id == parent_id).limit(1)
        )
        if not remaining.scalar_one_or_none():
            parent = await db.get(ProjectNode, parent_id)
            if parent:
                parent.node_type = "leaf"

    await db.commit()
    return node_id


# ========== LLM 解析创建 ==========

async def _check_project_duplicate_by_name(name: str, db: AsyncSession) -> None:
    """
    创建项目前检查名称是否与已有项目根节点重复（精确 + LLM 语义）。
    重复则 raise ValueError。
    """
    name = name.strip()
    if not name:
        return

    roots = (await db.execute(
        select(ProjectNode).where(ProjectNode.parent_id.is_(None))
    )).scalars().all()
    if not roots:
        return

    # 精确匹配
    for r in roots:
        if (r.name or "").strip().lower() == name.lower():
            raise ValueError(f"已存在同名项目「{r.name}」，请删除后重新创建")

    # LLM 语义匹配
    existing_names = [(r.name or "").strip() for r in roots if (r.name or "").strip()]
    if not existing_names:
        return

    try:
        llm = get_llm(temperature=0.0, max_tokens=256)
        names_text = "\n".join(f"- {n}" for n in existing_names)
        prompt = PROJECT_DUPLICATE_CHECK_PROMPT.format(new_name=name, existing_names=names_text)
        resp = await llm.ainvoke(prompt)
        data = parse_llm_json(resp.content)
        if data.get("duplicate") and data.get("matched_name"):
            matched = data["matched_name"].strip()
            for r in roots:
                if (r.name or "").strip().lower() == matched.lower():
                    raise ValueError(f"与已有项目「{r.name}」语义重复，请删除后重新创建")
    except ValueError:
        raise
    except Exception as e:
        logger.warning(f"项目语义去重检测失败（跳过）: {e}")


async def _save_project_tree_to_db(
    db: AsyncSession,
    tree_data: dict,
    parent_id: int | None = None,
    base_level: int = 1,
) -> tuple[int, int]:
    """
    递归写入项目树到 project_node 表。
    返回 (root_node_id, leaf_count)
    """
    name = (tree_data.get("name") or "").strip()
    children = tree_data.get("children", [])
    has_kids = bool(children)
    node_type = "category" if has_kids else "leaf"

    node = ProjectNode(
        parent_id=parent_id,
        name=name,
        level=base_level,
        node_type=node_type,
        sort_order=tree_data.get("sort_order", 0),
    )
    db.add(node)
    await db.flush()

    total_leaves = 0
    for i, child in enumerate(children):
        child["sort_order"] = i
        _, leaves = await _save_project_tree_to_db(db, child, parent_id=node.id, base_level=base_level + 1)
        total_leaves += leaves

    if not has_kids:
        total_leaves = 1

    return node.id, total_leaves


async def create_project_from_text(text: str, db: AsyncSession) -> dict:
    """解析用户的项目描述文本，LLM 拆解为 项目→话题→问题 三层树，写入 DB。

    同时在 `project` 表插入元数据行并通过 root_node_id 关联到树根 ——
    这是用户视角"项目"的**唯一创建入口**（拷打页只编辑/查看，不创建）。

    Step 1: LLM 解析（重试 3 次）
    Step 2: 项目名去重检查（精确 + LLM 语义）
    Step 3: 写入 project_node 三层树，拿到 root_id
    Step 4: 同步插入 project 元数据行，关联 root_id
    Step 5: 提交事务
    """
    # ===== Step 1: LLM 解析 =====
    llm = get_llm(temperature=0.2, max_tokens=4096)
    prompt = PROJECT_PARSE_TEXT_PROMPT.format(text=text)

    tree_data = None
    for attempt in range(3):
        try:
            resp = await llm.ainvoke(prompt)
            tree_data = parse_llm_json(resp.content)
            break
        except Exception as e:
            logger.warning(f"项目解析第{attempt+1}次失败: {e}")
    if tree_data is None:
        raise RuntimeError("项目解析失败，请重试")

    # ===== Step 2: 重名去重 =====
    project_name = (tree_data.get("name") or "").strip()
    if project_name:
        await _check_project_duplicate_by_name(project_name, db)

    # ===== Step 3: 写题库树 =====
    root_id, leaf_count = await _save_project_tree_to_db(db, tree_data)

    # ===== Step 4: 同步建 project 元数据行（原始 text 作为初始 description）=====
    project_row = Project(
        name=project_name or f"项目#{root_id}",
        description=text.strip() or None,
        root_node_id=root_id,
    )
    db.add(project_row)
    await db.flush()

    # 为新建项目树的 level=3 leaf 回填 embedding，避免后续面试匹配漏题
    await _fill_project_leaf_embeddings(db, root_id)

    # ===== Step 5: 提交 =====
    await db.commit()
    return {
        "root_id": root_id,
        "project_id": project_row.id,
        "name": project_name,
        "leaf_count": leaf_count,
    }
