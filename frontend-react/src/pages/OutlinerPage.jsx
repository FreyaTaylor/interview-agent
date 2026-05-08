/**
 * 幕布风格知识树编辑器
 * - 大纲式缩进显示
 * - 点击节点名即可编辑
 * - Enter 新增同级节点，Tab 缩进，Shift+Tab 反缩进
 * - 拖拽排序（同级内）
 * - 折叠/展开子节点
 * - 删除节点
 */
import { useState, useEffect, useRef, useCallback } from 'react'

const API = 'http://127.0.0.1:8000/api/admin'

export default function OutlinerPage() {
  // flatNodes: [{ id, parent_id, name, level, node_type, interview_weight, sort_order }]
  const [flatNodes, setFlatNodes] = useState([])
  const [loading, setLoading] = useState(true)
  const [collapsed, setCollapsed] = useState(new Set()) // 折叠的节点 id
  const [focusId, setFocusId] = useState(null) // 当前聚焦的节点
  const [dragId, setDragId] = useState(null)
  const [dragOverId, setDragOverId] = useState(null)
  const inputRefs = useRef({})
  const [saving, setSaving] = useState(false)

  // ---- 数据获取 ----
  const fetchTree = useCallback(async () => {
    try {
      const resp = await fetch(`${API}/tree-nodes`).then(r => r.json())
      if (resp.code === 0) {
        // 服务端返回扁平列表，按 parent_id 分组后递归排序得到大纲顺序
        const nodes = resp.data || []
        setFlatNodes(toOutlineOrder(nodes))
      }
    } catch (e) {
      console.error('获取知识树失败:', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchTree() }, [fetchTree])

  // ---- 工具函数 ----

  /** 把扁平列表按大纲顺序（DFS）排列 */
  function toOutlineOrder(nodes) {
    const byParent = {}
    for (const n of nodes) {
      const pid = n.parent_id ?? '__root__'
      if (!byParent[pid]) byParent[pid] = []
      byParent[pid].push(n)
    }
    // 每组内按 sort_order 排序
    for (const key in byParent) {
      byParent[key].sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0) || a.id - b.id)
    }
    const result = []
    function dfs(parentId) {
      const key = parentId ?? '__root__'
      const children = byParent[key] || []
      for (const c of children) {
        result.push(c)
        dfs(c.id)
      }
    }
    dfs(null)
    return result
  }

  /** 获取某节点的所有子节点 id（递归） */
  function getDescendantIds(nodeId) {
    const ids = new Set()
    function collect(pid) {
      for (const n of flatNodes) {
        if (n.parent_id === pid) {
          ids.add(n.id)
          collect(n.id)
        }
      }
    }
    collect(nodeId)
    return ids
  }

  /** 获取某节点的直接子节点 */
  function getChildren(nodeId) {
    return flatNodes.filter(n => n.parent_id === nodeId)
  }

  /** 获取某节点的同级节点（按当前大纲顺序） */
  function getSiblings(node) {
    return flatNodes.filter(n => n.parent_id === node.parent_id)
  }

  /** 是否可见（祖先都没被折叠） */
  function isVisible(node) {
    let pid = node.parent_id
    while (pid) {
      if (collapsed.has(pid)) return false
      const parent = flatNodes.find(n => n.id === pid)
      if (!parent) break
      pid = parent.parent_id
    }
    return true
  }

  /** 节点是否有子节点 */
  function hasChildren(nodeId) {
    return flatNodes.some(n => n.parent_id === nodeId)
  }

  // ---- 可见节点列表 ----
  const visibleNodes = flatNodes.filter(n => isVisible(n))

  // ---- 操作 ----

  /** 切换折叠 */
  function toggleCollapse(nodeId) {
    setCollapsed(prev => {
      const next = new Set(prev)
      if (next.has(nodeId)) next.delete(nodeId)
      else next.add(nodeId)
      return next
    })
  }

  /** 更新节点名称 */
  async function handleNameChange(nodeId, newName) {
    // 先乐观更新本地
    setFlatNodes(prev => prev.map(n => n.id === nodeId ? { ...n, name: newName } : n))
  }

  /** 失焦时保存 */
  async function handleBlur(nodeId) {
    const node = flatNodes.find(n => n.id === nodeId)
    if (!node || !node.name.trim()) return
    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/${nodeId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: node.name.trim() }),
      })
    } catch (e) {
      console.error('保存失败:', e)
    } finally {
      setSaving(false)
    }
  }

  /** 键盘事件 */
  async function handleKeyDown(e, node) {
    if (e.key === 'Enter' && !e.isComposing) {
      e.preventDefault()
      // 先保存当前节点
      await handleBlur(node.id)
      // 在当前节点下方新增同级节点
      const siblings = getSiblings(node)
      const idx = siblings.findIndex(n => n.id === node.id)
      const newSortOrder = (node.sort_order ?? 0) + 1

      try {
        const resp = await fetch(`${API}/tree-nodes`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            parent_id: node.parent_id,
            name: '',
          }),
        }).then(r => r.json())

        if (resp.code === 0) {
          const newId = resp.data.id
          // 更新 sort_order: 把后续同级节点的 sort_order 都+1
          const updates = []
          for (const s of siblings) {
            if (s.sort_order >= newSortOrder && s.id !== newId) {
              updates.push({ id: s.id, sort_order: s.sort_order + 1 })
            }
          }
          updates.push({ id: newId, sort_order: newSortOrder })
          if (updates.length) {
            await fetch(`${API}/tree-nodes/batch-sort`, {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ updates }),
            })
          }
          await fetchTree()
          setFocusId(newId)
        }
      } catch (err) {
        console.error('新增节点失败:', err)
      }
    }

    if (e.key === 'Tab' && !e.isComposing) {
      e.preventDefault()
      if (e.shiftKey) {
        // Shift+Tab: 反缩进 — 变为父节点的下一个同级
        await handleOutdent(node)
      } else {
        // Tab: 缩进 — 变为上一个同级的子节点
        await handleIndent(node)
      }
    }

    if (e.key === 'Backspace' && node.name === '') {
      e.preventDefault()
      await handleDelete(node)
    }

    if (e.key === 'ArrowUp' && e.altKey) {
      e.preventDefault()
      await handleMoveUp(node)
    }

    if (e.key === 'ArrowDown' && e.altKey) {
      e.preventDefault()
      await handleMoveDown(node)
    }
  }

  /** Tab — 缩进：把节点变成上一个同级节点的子节点 */
  async function handleIndent(node) {
    const siblings = getSiblings(node)
    const idx = siblings.findIndex(n => n.id === node.id)
    if (idx <= 0) return // 没有上一个兄弟
    const newParent = siblings[idx - 1]
    const newParentChildren = getChildren(newParent.id)
    const newSortOrder = newParentChildren.length

    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/${node.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: newParent.id }),
      })
      await fetch(`${API}/tree-nodes/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates: [{ id: node.id, sort_order: newSortOrder }] }),
      })
      await fetchTree()
      setFocusId(node.id)
      // 确保新父节点展开
      setCollapsed(prev => {
        const next = new Set(prev)
        next.delete(newParent.id)
        return next
      })
    } catch (e) {
      console.error('缩进失败:', e)
    } finally {
      setSaving(false)
    }
  }

  /** Shift+Tab — 反缩进 */
  async function handleOutdent(node) {
    if (!node.parent_id) return // 已经是顶级
    const parent = flatNodes.find(n => n.id === node.parent_id)
    if (!parent) return

    setSaving(true)
    try {
      // 找到 parent 在其同级中的位置
      const parentSiblings = flatNodes.filter(n => n.parent_id === parent.parent_id)
      const parentIdx = parentSiblings.findIndex(n => n.id === parent.id)
      const newSortOrder = (parent.sort_order ?? 0) + 1

      // 把 parent 后续同级的 sort_order +1
      const updates = []
      for (const s of parentSiblings) {
        if ((s.sort_order ?? 0) >= newSortOrder && s.id !== node.id) {
          updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
        }
      }
      updates.push({ id: node.id, sort_order: newSortOrder })

      await fetch(`${API}/tree-nodes/${node.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: parent.parent_id }),
      })
      await fetch(`${API}/tree-nodes/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchTree()
      setFocusId(node.id)
    } catch (e) {
      console.error('反缩进失败:', e)
    } finally {
      setSaving(false)
    }
  }

  /** 删除空节点 */
  async function handleDelete(node) {
    if (hasChildren(node.id)) return // 有子节点不删
    // 聚焦到上一个可见节点
    const visIdx = visibleNodes.findIndex(n => n.id === node.id)
    const prevNode = visIdx > 0 ? visibleNodes[visIdx - 1] : null

    try {
      await fetch(`${API}/tree-nodes/${node.id}`, { method: 'DELETE' })
      await fetchTree()
      if (prevNode) setFocusId(prevNode.id)
    } catch (e) {
      console.error('删除失败:', e)
    }
  }

  /** Alt+↑ 上移 */
  async function handleMoveUp(node) {
    const siblings = getSiblings(node)
    const idx = siblings.findIndex(n => n.id === node.id)
    if (idx <= 0) return
    const prev = siblings[idx - 1]
    const updates = [
      { id: node.id, sort_order: prev.sort_order ?? 0 },
      { id: prev.id, sort_order: node.sort_order ?? 0 },
    ]
    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchTree()
      setFocusId(node.id)
    } catch (e) {
      console.error('上移失败:', e)
    } finally {
      setSaving(false)
    }
  }

  /** Alt+↓ 下移 */
  async function handleMoveDown(node) {
    const siblings = getSiblings(node)
    const idx = siblings.findIndex(n => n.id === node.id)
    if (idx >= siblings.length - 1) return
    const next = siblings[idx + 1]
    const updates = [
      { id: node.id, sort_order: next.sort_order ?? 0 },
      { id: next.id, sort_order: node.sort_order ?? 0 },
    ]
    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchTree()
      setFocusId(node.id)
    } catch (e) {
      console.error('下移失败:', e)
    } finally {
      setSaving(false)
    }
  }

  // ---- 拖拽 ----
  function handleDragStart(e, node) {
    setDragId(node.id)
    e.dataTransfer.effectAllowed = 'move'
  }

  function handleDragOver(e, node) {
    e.preventDefault()
    if (node.id !== dragId) setDragOverId(node.id)
  }

  function handleDragLeave() {
    setDragOverId(null)
  }

  async function handleDrop(e, targetNode) {
    e.preventDefault()
    setDragOverId(null)
    if (!dragId || dragId === targetNode.id) { setDragId(null); return }

    const dragNode = flatNodes.find(n => n.id === dragId)
    if (!dragNode) { setDragId(null); return }

    // 不能拖到自己的后代上
    const descIds = getDescendantIds(dragId)
    if (descIds.has(targetNode.id)) { setDragId(null); return }

    // 拖放行为: 把 dragNode 移到 targetNode 下方（同父级）
    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/${dragNode.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: targetNode.parent_id }),
      })
      // 排在 targetNode 后面
      const newSortOrder = (targetNode.sort_order ?? 0) + 1
      const siblings = flatNodes.filter(n => n.parent_id === targetNode.parent_id)
      const updates = [{ id: dragNode.id, sort_order: newSortOrder }]
      for (const s of siblings) {
        if ((s.sort_order ?? 0) >= newSortOrder && s.id !== dragNode.id) {
          updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
        }
      }
      await fetch(`${API}/tree-nodes/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchTree()
    } catch (e) {
      console.error('拖拽移动失败:', e)
    } finally {
      setSaving(false)
      setDragId(null)
    }
  }

  // ---- 自动聚焦 ----
  useEffect(() => {
    if (focusId && inputRefs.current[focusId]) {
      const el = inputRefs.current[focusId]
      el.focus()
      // 光标移到末尾
      const len = el.value.length
      el.setSelectionRange(len, len)
      setFocusId(null)
    }
  }, [focusId, flatNodes])

  /** 新增顶级节点 */
  async function handleAddRoot() {
    try {
      const resp = await fetch(`${API}/tree-nodes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: null, name: '' }),
      }).then(r => r.json())
      if (resp.code === 0) {
        await fetchTree()
        setFocusId(resp.data.id)
      }
    } catch (e) {
      console.error('新增根节点失败:', e)
    }
  }

  // ---- 渲染 ----
  if (loading) return <div className="loading">加载中...</div>

  return (
    <div className="outliner-container">
      <div className="outliner-header">
        <h2 className="outliner-title">知识树大纲</h2>
        <div className="outliner-actions">
          {saving && <span className="outliner-saving">保存中...</span>}
          <button className="outliner-add-root" onClick={handleAddRoot}>+ 新增一级分类</button>
        </div>
      </div>
      <div className="outliner-help">
        <span>Enter 新增同级</span>
        <span>Tab 缩进</span>
        <span>Shift+Tab 反缩进</span>
        <span>Alt+↑↓ 移动</span>
        <span>Backspace 删除空行</span>
        <span>拖拽排序</span>
      </div>
      <div className="outliner-body">
        {visibleNodes.length === 0 && (
          <div className="outliner-empty">
            暂无知识树节点，点击上方按钮新增
          </div>
        )}
        {visibleNodes.map(node => {
          const depth = node.level - 1
          const hasKids = hasChildren(node.id)
          const isCollapsed = collapsed.has(node.id)
          const isDragging = dragId === node.id
          const isDragOver = dragOverId === node.id

          return (
            <div
              key={node.id}
              className={`outliner-item ${isDragging ? 'dragging' : ''} ${isDragOver ? 'drag-over' : ''}`}
              style={{ paddingLeft: 16 + depth * 24 }}
              draggable
              onDragStart={e => handleDragStart(e, node)}
              onDragOver={e => handleDragOver(e, node)}
              onDragLeave={handleDragLeave}
              onDrop={e => handleDrop(e, node)}
            >
              {/* 折叠/展开按钮 */}
              {hasKids ? (
                <span
                  className={`outliner-toggle ${isCollapsed ? '' : 'open'}`}
                  onClick={() => toggleCollapse(node.id)}
                />
              ) : (
                <span className="outliner-bullet" />
              )}

              {/* 可编辑节点名 */}
              <input
                ref={el => { if (el) inputRefs.current[node.id] = el }}
                className={`outliner-input level-${Math.min(depth, 3)}`}
                value={node.name}
                placeholder={depth === 0 ? '一级分类' : depth === 1 ? '二级分类' : '知识点'}
                onChange={e => handleNameChange(node.id, e.target.value)}
                onBlur={() => handleBlur(node.id)}
                onKeyDown={e => handleKeyDown(e, node)}
              />

              {/* 权重星星（叶子节点） */}
              {!hasKids && node.level >= 3 && (
                <span className="outliner-weight" title="面试权重">
                  {'★'.repeat(node.interview_weight || 3)}
                </span>
              )}

              {/* 删除按钮 */}
              <button
                className="outliner-delete"
                title="删除节点"
                onClick={async () => {
                  if (hasKids) {
                    if (!window.confirm(`确定删除「${node.name}」及其所有子节点？`)) return
                  }
                  await fetch(`${API}/tree-nodes/${node.id}`, { method: 'DELETE' })
                  await fetchTree()
                }}
              >×</button>
            </div>
          )
        })}
      </div>
    </div>
  )
}
