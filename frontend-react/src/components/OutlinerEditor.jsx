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
  const inputRefs = useRef({})
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
      const resp = await fetch(apiUrl).then(r => r.json())
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
      await fetch(`${apiUrl}/${nodeId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: node.name.trim() }),
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
        const resp = await fetch(apiUrl, {
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
              method: 'PUT',
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
      await fetch(`${apiUrl}/${node.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: newParent.id }),
      })
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'PUT',
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
      await fetch(`${apiUrl}/${node.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: parent.parent_id }),
      })
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'PUT',
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
      await fetch(`${apiUrl}/${node.id}`, { method: 'DELETE' })
      await fetchData()
      if (prevNode) setFocusId(prevNode.id)
    } catch (e) { console.error('删除失败:', e) }
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
  function handleDragLeave() { setDragOverId(null) }

  async function handleDrop(e, targetNode) {
    e.preventDefault()
    setDragOverId(null)
    if (!dragId || dragId === targetNode.id) { setDragId(null); return }
    const dragNode = flatNodes.find(n => n.id === dragId)
    if (!dragNode) { setDragId(null); return }
    if (getDescendantIds(dragId).has(targetNode.id)) { setDragId(null); return }

    setSaving(true)
    try {
      const moveResp = await fetch(`${apiUrl}/${dragNode.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: targetNode.parent_id }),
      }).then(r => r.json()).catch(() => ({ code: -1, message: '网络错误' }))
      if (moveResp.code !== 0) {
        alert(`拖动失败：${moveResp.message || '未知错误'}`)
        return
      }
      const newSortOrder = targetNode.sort_order ?? 0
      const siblings = flatNodes.filter(n => n.parent_id === targetNode.parent_id)
      const updates = [{ id: dragNode.id, sort_order: newSortOrder }]
      for (const s of siblings) {
        if ((s.sort_order ?? 0) >= newSortOrder && s.id !== dragNode.id) {
          updates.push({ id: s.id, sort_order: (s.sort_order ?? 0) + 1 })
        }
      }
      await fetch(`${apiUrl}/batch-sort`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ updates }),
      })
      await fetchData()
    } catch (e) { console.error('拖拽移动失败:', e); alert(`拖动失败：${e.message || e}`) }
    finally { setSaving(false); setDragId(null) }
  }

  // ---- 新增根节点 ----
  async function handleAddRoot() {
    try {
      const resp = await fetch(apiUrl, {
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
          <span>拖拽排序</span>
        </div>
      </div>

      <div className="outliner-body">
        {visibleNodes.map(node => {
          const depth = node.level - 1
          const hasKids = hasChildren(node.id)
          const isCollapsed = collapsed.has(node.id)
          const isDragging = dragId === node.id
          const isDragOver = dragOverId === node.id
          const placeholder = placeholders[Math.min(depth, placeholders.length - 1)] || ''

          return (
            <div key={node.id}
              className={`outliner-item ${isDragging ? 'dragging' : ''} ${isDragOver ? 'drag-over' : ''}`}
              style={{ paddingLeft: 16 + depth * 24 }}
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
                    await fetch(`${apiUrl}/${node.id}`, {
                      method: 'PUT',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ interview_weight: w }),
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
                  await fetch(`${apiUrl}/${node.id}`, { method: 'DELETE' })
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
