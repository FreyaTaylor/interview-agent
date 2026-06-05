/**
 * 通用幕布风格大纲编辑器
 * - 大纲式缩进显示
 * - 点击节点名即可编辑
 * - Enter 新增同级节点，Tab 缩进，Shift+Tab 反缩进
 * - 拖拽排序（同级内）
 * - 折叠/展开子节点
 * - 删除节点
 *
 * Props:
 *   apiPrefix  — API 路径前缀，如 "tree-nodes" 或 "project-nodes"
 *   storageKey — localStorage 折叠状态的 key
 *   showWeight — 是否显示面试权重下拉（知识树用）
 *   showOptimize — 是否显示 LLM 优化按钮（知识树用）
 *   placeholders — 各层级 placeholder 文本，如 ['项目名', '话题', '问题']
 *   emptyText — 空状态提示文本
 *   headerSlot — 头部插槽（放按钮等）
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import { API_ADMIN } from '../config'

export default function OutlinerEditor({
  apiPrefix = 'tree-nodes',
  storageKey = 'outliner_collapsed',
  showWeight = false,
  showOptimize = false,
  placeholders = ['一级分类', '二级分类', '知识点'],
  emptyText = '暂无节点，点击上方按钮新增',
  headerSlot = null,
}) {
  const [flatNodes, setFlatNodes] = useState([])
  const [loading, setLoading] = useState(true)
  const [collapsed, setCollapsed] = useState(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      return saved ? new Set(JSON.parse(saved)) : new Set()
    } catch { return new Set() }
  })
  const [focusId, setFocusId] = useState(null)
  const [dragId, setDragId] = useState(null)
  const [dragOverId, setDragOverId] = useState(null)
  // 拖拽悬停位置：'before' | 'after' | 'child'
  //   before/after = 与 target 同级，插到前/后
  //   child       = 成为 target 的子节点（追加到末尾）
  const [dragOverZone, setDragOverZone] = useState(null)
  const inputRefs = useRef({})
  const emptyDragImg = useRef(null)  // 幕布风格：拖拽时隐藏文字本体
  // 撤销栈：每次会改变结构的操作前 push 一份 {label, undo: async () => ...}
  const undoStack = useRef([])
  const [saving, setSaving] = useState(false)
  const [optimizingId, setOptimizingId] = useState(null)
  // 来自 URL 的"定位"参数：?node=<id> — 加载完数据后展开祖先 + 滚动 + 高亮
  const [searchParams, setSearchParams] = useSearchParams()
  const [highlightId, setHighlightId] = useState(null)
  const locatedRef = useRef(false)  // 防止 fetchData 多次触发定位

  const apiUrl = `${API_ADMIN}/${apiPrefix}`

  // ---- 数据获取 ----
  const fetchData = useCallback(async () => {
    try {
      const resp = await fetch(`${apiUrl}/list`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }).then(r => r.json())
      if (resp.code === 0) {
        setFlatNodes(toOutlineOrder(resp.data || []))
      }
    } catch (e) {
      console.error('获取节点失败:', e)
    } finally {
      setLoading(false)
    }
  }, [apiUrl])

  useEffect(() => { fetchData() }, [fetchData])

  // ---- 工具函数 ----

  function toOutlineOrder(nodes) {
    const byParent = {}
    for (const n of nodes) {
      const pid = n.parent_id ?? '__root__'
      if (!byParent[pid]) byParent[pid] = []
      byParent[pid].push(n)
    }
    for (const key in byParent) {
      byParent[key].sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0) || a.id - b.id)
    }
    const result = []
    function dfs(parentId) {
      const key = parentId ?? '__root__'
      for (const c of (byParent[key] || [])) {
        result.push(c)
        dfs(c.id)
      }
    }
    dfs(null)
    return result
  }

  function getDescendantIds(nodeId) {
    const ids = new Set()
    function collect(pid) {
      for (const n of flatNodes) {
        if (n.parent_id === pid) { ids.add(n.id); collect(n.id) }
      }
    }
    collect(nodeId)
    return ids
  }

  function getChildren(nodeId) { return flatNodes.filter(n => n.parent_id === nodeId) }
  function getSiblings(node) { return flatNodes.filter(n => n.parent_id === node.parent_id) }
  function hasChildren(nodeId) { return flatNodes.some(n => n.parent_id === nodeId) }

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

  const visibleNodes = flatNodes.filter(n => isVisible(n))
  // 拖拽中：被拖节点的所有子孙 id（用于把整棵子树一并高亮）
  const draggingSubtreeIds = dragId != null ? getDescendantIds(dragId) : new Set()

  // ---- 折叠 ----
  function toggleCollapse(nodeId) {
    setCollapsed(prev => {
      const next = new Set(prev)
      if (next.has(nodeId)) next.delete(nodeId)
      else next.add(nodeId)
      localStorage.setItem(storageKey, JSON.stringify([...next]))
      return next
    })
  }

  // ---- 编辑 ----
  function handleNameChange(nodeId, newName) {
    setFlatNodes(prev => prev.map(n => n.id === nodeId ? { ...n, name: newName } : n))
  }

  async function handleBlur(nodeId) {
    const node = flatNodes.find(n => n.id === nodeId)
    if (!node || !node.name.trim()) return
    setSaving(true)
    try {
      await fetch(`${apiUrl}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: nodeId, name: node.name.trim() }),
      })
    } catch (e) { console.error('保存失败:', e) }
    finally { setSaving(false) }
  }

  // ---- 键盘事件 ----
  async function handleKeyDown(e, node) {
    if (e.key === 'Enter' && !e.isComposing) {
      e.preventDefault()
      await handleBlur(node.id)
      const siblings = getSiblings(node)
      const newSortOrder = (node.sort_order ?? 0) + 1
      try {
        const resp = await fetch(`${apiUrl}/create`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ parent_id: node.parent_id, name: '' }),
        }).then(r => r.json())
        if (resp.code === 0) {
          const newId = resp.data.id
          const updates = []
          for (const s of siblings) {
            if ((s.sort_order ?? 0) >= newSortOrder && s.id !== newId) {
              updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
            }
          }
          updates.push({ id: newId, sort_order: newSortOrder })
          if (updates.length) {
            await fetch(`${apiUrl}/batch-sort`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ updates }),
            })
          }
          await fetchData()
          setFocusId(newId)
        }
      } catch (err) { console.error('新增节点失败:', err) }
    }

    if (e.key === 'Tab' && !e.isComposing) {
      e.preventDefault()
      if (e.shiftKey) await handleOutdent(node)
      else await handleIndent(node)
    }

    if (e.key === 'Backspace' && node.name === '') {
      e.preventDefault()
      await handleDelete(node)
    }
  }

  async function handleIndent(node) {
    const siblings = getSiblings(node)
    const idx = siblings.findIndex(n => n.id === node.id)
    if (idx <= 0) return
    const newParent = siblings[idx - 1]
    const newParentChildren = getChildren(newParent.id)
    setSaving(true)
    try {
      await fetch(`${apiUrl}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: node.id, parent_id: newParent.id, moving_parent: true }),
      })
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates: [{ id: node.id, sort_order: newParentChildren.length }] }),
      })
      await fetchData()
      setFocusId(node.id)
      setCollapsed(prev => {
        const next = new Set(prev)
        next.delete(newParent.id)
        localStorage.setItem(storageKey, JSON.stringify([...next]))
        return next
      })
    } catch (e) { console.error('缩进失败:', e) }
    finally { setSaving(false) }
  }

  async function handleOutdent(node) {
    if (!node.parent_id) return
    const parent = flatNodes.find(n => n.id === node.parent_id)
    if (!parent) return
    setSaving(true)
    try {
      const parentSiblings = flatNodes.filter(n => n.parent_id === parent.parent_id)
      const newSortOrder = (parent.sort_order ?? 0) + 1
      const updates = []
      for (const s of parentSiblings) {
        if ((s.sort_order ?? 0) >= newSortOrder && s.id !== node.id) {
          updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
        }
      }
      updates.push({ id: node.id, sort_order: newSortOrder })
      await fetch(`${apiUrl}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: node.id, parent_id: parent.parent_id, moving_parent: true }),
      })
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchData()
      setFocusId(node.id)
    } catch (e) { console.error('反缩进失败:', e) }
    finally { setSaving(false) }
  }

  async function handleDelete(node) {
    if (hasChildren(node.id)) return
    const visIdx = visibleNodes.findIndex(n => n.id === node.id)
    const prevNode = visIdx > 0 ? visibleNodes[visIdx - 1] : null
    try {
      await fetch(`${apiUrl}/delete`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: node.id }),
      })
      await fetchData()
      if (prevNode) setFocusId(prevNode.id)
    } catch (e) { console.error('删除失败:', e) }
  }

  // ---- 撤销栈 ----
  // 每次结构性改动（drag / 删除 / 缩进等）push 一个 { label, undo: async () => ... }
  // Ctrl/Cmd+Z 触发 pop 并执行
  function pushUndo(entry) {
    undoStack.current.push(entry)
    if (undoStack.current.length > 30) undoStack.current.shift()
  }
  async function popUndo() {
    const entry = undoStack.current.pop()
    if (!entry) return
    console.log('[Undo]', entry.label)
    setSaving(true)
    try {
      await entry.undo()
    } catch (err) {
      console.error('[Undo] 失败:', err)
      alert('撤销失败：' + (err.message || err))
    } finally {
      setSaving(false)
    }
  }
  useEffect(() => {
    function onKey(e) {
      // Cmd+Z (mac) / Ctrl+Z (win)；忽略 Shift+Z（留给 redo 后续）
      const isUndo = (e.key === 'z' || e.key === 'Z')
        && (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey
      if (!isUndo) return
      // 在输入框内时，让浏览器处理 input 自身的 undo
      const t = e.target
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
      e.preventDefault()
      popUndo()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // ---- 拖拽 ----
  // 判定拖拽落点（参考幕布/Notion 三段式）：
  //   Y < height*1/3   → before  插到 target 同级上方
  //   Y > height*2/3   → after   插到 target 同级下方
  //   else（中段）     → child   插为 target 末位子
  //   特例：X 在 indent+8 之前（toggle/bullet 列）一律按 after，
  //         避免在窄列里误判为 child
  function getDropZone(e, rowEl) {
    const rect = rowEl.getBoundingClientRect()
    const y = e.clientY - rect.top
    const x = e.clientX - rect.left
    const indent = parseInt(rowEl.dataset.indent || '16', 10)
    const onBullet = x < indent + 8
    if (y < rect.height / 3) return 'before'
    if (y > rect.height * 2 / 3) return 'after'
    return onBullet ? 'after' : 'child'
  }

  function handleDragStart(e, node) {
    setDragId(node.id)
    e.dataTransfer.effectAllowed = 'move'
    // 幕布风格：不拖动文字本体，隐藏原生拖拽幽灵图
    // 用 1x1 透明图作为 drag image（设为 null 在部分浏览器不生效）
    if (!emptyDragImg.current) {
      const img = new Image()
      img.src = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='
      emptyDragImg.current = img
    }
    try { e.dataTransfer.setDragImage(emptyDragImg.current, 0, 0) } catch { /* noop */ }
  }
  function handleDragOver(e, node) {
    e.preventDefault()
    if (node.id === dragId) {
      e.dataTransfer.dropEffect = 'none'
      return
    }
    const zone = getDropZone(e, e.currentTarget)
    // 统一用 move（避免 copy 的绿色 +号 + 光标切换延迟）
    e.dataTransfer.dropEffect = 'move'
    if (node.id !== dragOverId) setDragOverId(node.id)
    if (zone !== dragOverZone) setDragOverZone(zone)
  }
  function handleDragLeave() {
    setDragOverId(null)
    setDragOverZone(null)
  }

  async function handleDrop(e, targetNode) {
    e.preventDefault()
    const zone = dragOverZone || getDropZone(e, e.currentTarget)
    setDragOverId(null)
    setDragOverZone(null)
    const draggingId = dragId
    setDragId(null)

    // Step 1: 自检
    if (!draggingId || draggingId === targetNode.id) return
    const dragNode = flatNodes.find(n => n.id === draggingId)
    if (!dragNode) return
    // 防环：不能拖到自己的子孙下
    if (getDescendantIds(draggingId).has(targetNode.id)) {
      alert('不能把节点拖到自己的子节点下')
      return
    }

    // Step 2: 根据 zone 计算新的 parent + sort_order，以及需要让位的兄弟
    let newParentId, newSortOrder
    const updates = []  // [{ id, sort_order }]
    if (zone === 'child') {
      // 作为 target 的最后一个子节点
      newParentId = targetNode.id
      const targetKids = flatNodes
        .filter(n => n.parent_id === targetNode.id && n.id !== draggingId)
      newSortOrder = targetKids.length
      // 子节点追加到末尾，不需要给现有 kids 让位
    } else {
      // before / after：与 target 同级
      newParentId = targetNode.parent_id
      const baseOrder = targetNode.sort_order ?? 0
      newSortOrder = zone === 'before' ? baseOrder : baseOrder + 1
      // 该父下所有兄弟（排除被拖节点本身），sort_order >= newSortOrder 的统一 +1 让位
      const siblings = flatNodes.filter(
        n => n.parent_id === newParentId && n.id !== draggingId
      )
      for (const s of siblings) {
        if ((s.sort_order ?? 0) >= newSortOrder) {
          updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
        }
      }
    }

    console.log('[Drag] drop', { drag: dragNode.name, target: targetNode.name, zone, newParentId, newSortOrder })

    // 若目标 parent 未变，且新位置和原位置一致 → 无操作
    if (newParentId === dragNode.parent_id && newSortOrder === (dragNode.sort_order ?? 0)) {
      return
    }

    // ---- 撤销快照：当前所有兄弟（旧父 + 新父）的位置 + 被拖节点的原 parent_id ----
    const oldParentId = dragNode.parent_id
    const oldSortOrder = dragNode.sort_order ?? 0
    const affectedParentIds = new Set([oldParentId, newParentId])
    const snapshot = flatNodes
      .filter(n => affectedParentIds.has(n.parent_id))
      .map(n => ({ id: n.id, parent_id: n.parent_id, sort_order: n.sort_order ?? 0 }))

    // Step 3: PUT 改父亲（关键：moving_parent=true，否则后端忽略 parent_id）
    setSaving(true)
    try {
      const movingParent = newParentId !== dragNode.parent_id
      const moveResp = await fetch(`${apiUrl}/update`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: dragNode.id,
          parent_id: newParentId,
          moving_parent: movingParent,
        }),
      }).then(r => r.json()).catch(() => ({ code: -1, message: '网络错误' }))
      if (moveResp.code !== 0) {
        alert(`拖动失败：${moveResp.message || '未知错误'}`)
        return
      }

      // Step 4: 批量排序（dragNode 自身 + 让位的兄弟）
      updates.push({ id: dragNode.id, sort_order: newSortOrder })
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })

      // 推入撤销栈
      pushUndo({
        label: `移动「${dragNode.name}」`,
        undo: async () => {
          // 先把 parent 改回去（若变过）
          if (movingParent) {
            await fetch(`${apiUrl}/update`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ id: dragNode.id, parent_id: oldParentId, moving_parent: true }),
            })
          }
          // 还原所有受影响节点的 sort_order
          await fetch(`${apiUrl}/batch-sort`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              updates: snapshot.map(s => ({ id: s.id, sort_order: s.sort_order })),
            }),
          })
          await fetchData()
        },
      })

      // Step 5: 若 drop 'child' 模式且 target 处于折叠，把它展开以便看到结果
      if (zone === 'child' && collapsed.has(targetNode.id)) {
        setCollapsed(prev => {
          const next = new Set(prev)
          next.delete(targetNode.id)
          try { localStorage.setItem(storageKey, JSON.stringify([...next])) } catch {}
          return next
        })
      }

      await fetchData()
    } catch (err) {
      console.error('拖拽移动失败:', err)
      alert(`拖动失败：${err.message || err}`)
    } finally {
      setSaving(false)
    }
  }

  // ---- 新增根节点 ----
  async function handleAddRoot() {
    try {
      const resp = await fetch(`${apiUrl}/create`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: null, name: '' }),
      }).then(r => r.json())
      if (resp.code === 0) {
        await fetchData()
        setFocusId(resp.data.id)
      }
    } catch (e) { console.error('新增根节点失败:', e) }
  }

  // ---- LLM 优化（知识树专用） ----
  async function handleOptimize(rootNode) {
    if (optimizingId) return
    if (!window.confirm(`LLM 将对「${rootNode.name}」进行全面优化（去重合并、结构调整、查漏补缺、语言精简），确定继续？`)) return
    setOptimizingId(rootNode.id)
    try {
      const resp = await fetch(`${API_ADMIN}/trees/${rootNode.id}/optimize`, {
        method: 'POST',
      }).then(r => r.json())
      if (resp.code === 0) {
        await fetchData()
        alert(`优化完成：共 ${resp.data.leaf_count || 0} 个知识点`)
      } else { alert(resp.message || '优化失败') }
    } catch (e) { alert('优化失败: ' + e.message) }
    finally { setOptimizingId(null) }
  }

  // ---- 自动聚焦 ----
  useEffect(() => {
    if (focusId && inputRefs.current[focusId]) {
      const el = inputRefs.current[focusId]
      el.focus()
      const len = el.value.length
      el.setSelectionRange(len, len)
      setFocusId(null)
    }
  }, [focusId, flatNodes])

  // ---- URL ?node=<id> 定位：展开祖先 + 滚动到视野 + 短暂高亮 ----
  useEffect(() => {
    const nodeParam = searchParams.get('node')
    if (!nodeParam || locatedRef.current || flatNodes.length === 0) return
    const targetId = Number(nodeParam)
    const target = flatNodes.find(n => n.id === targetId)
    if (!target) return  // 节点可能在另一个 tab（项目树）；这里就不处理
    locatedRef.current = true

    // 展开所有祖先（从 collapsed 集合移除）
    const ancestors = new Set()
    let pid = target.parent_id
    while (pid) {
      ancestors.add(pid)
      const p = flatNodes.find(n => n.id === pid)
      if (!p) break
      pid = p.parent_id
    }
    if (ancestors.size > 0) {
      setCollapsed(prev => {
        const next = new Set(prev)
        ancestors.forEach(id => next.delete(id))
        try { localStorage.setItem(storageKey, JSON.stringify([...next])) } catch {}
        return next
      })
    }

    // 等 DOM 更新后滚动 + 高亮
    setTimeout(() => {
      const el = inputRefs.current[targetId]
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        setHighlightId(targetId)
        setTimeout(() => setHighlightId(null), 2500)
      }
      // 清掉 URL 参数，避免下次进入还重定位
      const next = new URLSearchParams(searchParams)
      next.delete('node')
      setSearchParams(next, { replace: true })
    }, 100)
  }, [flatNodes, searchParams, setSearchParams, storageKey])

  // ---- 渲染 ----
  if (loading) return <div className="loading">加载中...</div>

  return (
    <>
      <div className="outliner-header">
        <div className="outliner-actions">
          {saving && <span className="outliner-saving">保存中...</span>}
          {headerSlot ? headerSlot({ handleAddRoot, fetchData }) : (
            <button className="outliner-add-root" onClick={handleAddRoot}>+ 新增根节点</button>
          )}
        </div>
        <div className="outliner-help">
          <span>Enter 新增同级</span>
          <span>Tab 缩进</span>
          <span>Shift+Tab 反缩进</span>
          <span>Backspace 删除空行</span>
          <span>拖拽：上 1/3 = 插到上方，下 1/3 = 插到下方，中段 = 作为子节点</span>
          <span>Ctrl/Cmd+Z 撤销上一步</span>
        </div>
      </div>

      <div className="outliner-body">
        {visibleNodes.map(node => {
          const depth = node.level - 1
          const hasKids = hasChildren(node.id)
          const isCollapsed = collapsed.has(node.id)
          const isDragging = dragId === node.id
          const isDraggingDescendant = dragId != null && draggingSubtreeIds.has(node.id)
          const isDragOver = dragOverId === node.id
          const dragOverClass = isDragOver ? `drag-over drag-over-${dragOverZone}` : ''
          const placeholder = placeholders[Math.min(depth, placeholders.length - 1)] || ''

          const indentPx = 16 + depth * 24
          const draggingClass = isDragging ? 'dragging dragging-root' : (isDraggingDescendant ? 'dragging' : '')
          return (
            <div key={node.id}
              className={`outliner-item ${draggingClass} ${dragOverClass}`}
              style={{ paddingLeft: indentPx, '--row-indent': indentPx + 'px' }}
              data-indent={indentPx}
              draggable
              onDragStart={e => handleDragStart(e, node)}
              onDragOver={e => handleDragOver(e, node)}
              onDragLeave={handleDragLeave}
              onDrop={e => handleDrop(e, node)}
            >
              {hasKids ? (
                <span className={`outliner-toggle ${isCollapsed ? '' : 'open'}`}
                  onClick={() => toggleCollapse(node.id)} />
              ) : (
                <span className="outliner-bullet" />
              )}

              <input
                ref={el => { if (el) inputRefs.current[node.id] = el }}
                className={`outliner-input level-${Math.min(depth, 3)} ${highlightId === node.id ? 'located-flash' : ''}`}
                value={node.name}
                placeholder={placeholder}
                draggable={false}
                onDragStart={e => e.preventDefault()}
                onChange={e => handleNameChange(node.id, e.target.value)}
                onBlur={() => handleBlur(node.id)}
                onKeyDown={e => handleKeyDown(e, node)}
              />

              {/* 面试权重（知识树叶子节点） */}
              {showWeight && !hasKids && node.level >= 3 && (
                <select className="outliner-weight-select"
                  value={node.interview_weight ?? 3} title="面试权重"
                  onChange={async (e) => {
                    const w = Number(e.target.value)
                    setFlatNodes(prev => prev.map(n => n.id === node.id ? { ...n, interview_weight: w } : n))
                    await fetch(`${apiUrl}/update`, {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ id: node.id, interview_weight: w }),
                    })
                  }}
                >
                  {[1,2,3,4,5].map(v => <option key={v} value={v}>{'★'.repeat(v)}</option>)}
                </select>
              )}

              {/* LLM 优化按钮（知识树根节点） */}
              {showOptimize && node.parent_id === null && (
                <button className="outliner-optimize" title="LLM 查漏补缺"
                  disabled={optimizingId === node.id}
                  onClick={() => handleOptimize(node)}
                >{optimizingId === node.id ? '⏳' : '✨'}</button>
              )}

              {/* 删除 */}
              <button className="outliner-delete" title="删除节点"
                onClick={async () => {
                  if (hasKids) {
                    if (!window.confirm(`确定删除「${node.name}」及其所有子节点？`)) return
                  }
                  await fetch(`${apiUrl}/delete`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ id: node.id }),
                  })
                  await fetchData()
                }}
              >×</button>
            </div>
          )
        })}
      </div>
    </>
  )
}
