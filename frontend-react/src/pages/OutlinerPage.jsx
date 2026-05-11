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

  // ---- 新建知识树对话框 ----
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [createTab, setCreateTab] = useState('generate') // 'generate' | 'text' | 'image' | 'mm'
  const [createLoading, setCreateLoading] = useState(false)
  const [createText, setCreateText] = useState('')
  const [genName, setGenName] = useState('')
  const [genRequirements, setGenRequirements] = useState('')
  const [imageFile, setImageFile] = useState(null)
  const [mmFile, setMmFile] = useState(null)
  const fileInputRef = useRef(null)
  const mmFileInputRef = useRef(null)

  // ---- 重复冲突对话框 ----
  const [conflict, setConflict] = useState(null) // { newId, newName, oldId, oldName, isGenerate }

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

    // 拖放行为: 把 dragNode 移到 targetNode 上方（同父级）
    setSaving(true)
    try {
      await fetch(`${API}/tree-nodes/${dragNode.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parent_id: targetNode.parent_id }),
      })
      // 排在 targetNode 前面
      const newSortOrder = targetNode.sort_order ?? 0
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

  /** LLM 优化知识树（查漏补缺） */
  const [optimizingId, setOptimizingId] = useState(null)
  async function handleOptimize(rootNode) {
    if (optimizingId) return
    if (!window.confirm(`LLM 将对「${rootNode.name}」进行全面优化（去重合并、结构调整、查漏补缺、语言精简），确定继续？`)) return
    setOptimizingId(rootNode.id)
    try {
      const resp = await fetch(`${API}/trees/${rootNode.id}/optimize`, {
        method: 'POST',
      }).then(r => r.json())
      if (resp.code === 0) {
        await fetchTree()
        alert(`优化完成：共 ${resp.data.leaf_count || 0} 个知识点`)
      } else {
        alert(resp.message || '优化失败')
      }
    } catch (e) {
      console.error('优化失败:', e)
      alert('优化失败: ' + e.message)
    } finally {
      setOptimizingId(null)
    }
  }

  // ---- 渲染 ----
  if (loading) return <div className="loading">加载中...</div>

  /** 创建知识树 */
  async function handleCreate() {
    setCreateLoading(true)
    try {
      let resp
      if (createTab === 'text') {
        if (!createText.trim()) return
        resp = await fetch(`${API}/trees/from-text`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: createText.trim() }),
        }).then(r => r.json())
      } else if (createTab === 'generate') {
        if (!genName.trim()) return
        resp = await fetch(`${API}/trees/from-generate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tree_name: genName.trim(), requirements: genRequirements.trim() || genName.trim() }),
        }).then(r => r.json())
      } else if (createTab === 'image') {
        if (!imageFile) return
        const formData = new FormData()
        formData.append('file', imageFile)
        resp = await fetch(`${API}/trees/from-image`, {
          method: 'POST',
          body: formData,
        }).then(r => r.json())
      } else if (createTab === 'mm') {
        if (!mmFile) return
        const formData = new FormData()
        formData.append('file', mmFile)
        resp = await fetch(`${API}/trees/from-mm`, {
          method: 'POST',
          body: formData,
        }).then(r => r.json())
      }
      if (resp?.code === 0) {
        setShowCreateDialog(false)
        setCreateText('')
        setGenName('')
        setGenRequirements('')
        setImageFile(null)
        setMmFile(null)

        const newId = resp.data.root_id
        const newName = resp.data.name || ''

        // 后端语义检测是否与已有根节点重复
        let duplicate = null
        try {
          const checkResp = await fetch(`${API}/trees/${newId}/check-duplicate`).then(r => r.json())
          if (checkResp.code === 0 && checkResp.data?.duplicate_id) {
            duplicate = { id: checkResp.data.duplicate_id, name: checkResp.data.duplicate_name }
          }
        } catch (e) {
          console.error('重复检测失败:', e)
        }

        await fetchTree()

        if (duplicate) {
          setConflict({
            newId,
            newName,
            oldId: duplicate.id,
            oldName: duplicate.name,
            isGenerate: createTab === 'generate',
          })
        }
      } else {
        alert(resp?.message || '创建失败')
      }
    } catch (e) {
      console.error('创建知识树失败:', e)
      alert('创建失败: ' + e.message)
    } finally {
      setCreateLoading(false)
    }
  }

  /** 冲突处理：删除旧树，保留新树 */
  async function handleConflictReplace() {
    if (!conflict) return
    await fetch(`${API}/tree-nodes/${conflict.oldId}`, { method: 'DELETE' })
    setConflict(null)
    await fetchTree()
  }

  /** 冲突处理：取消新增，删除新树 */
  async function handleConflictCancel() {
    if (!conflict) return
    await fetch(`${API}/tree-nodes/${conflict.newId}`, { method: 'DELETE' })
    setConflict(null)
    await fetchTree()
  }

  /** 冲突处理：合并新树到旧树 */
  async function handleConflictMerge() {
    if (!conflict) return
    try {
      const resp = await fetch(`${API}/trees/merge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source_id: conflict.newId, target_id: conflict.oldId }),
      }).then(r => r.json())
      if (resp.code !== 0) {
        alert(resp.message || '合并失败')
      }
    } catch (e) {
      alert('合并失败: ' + e.message)
    }
    setConflict(null)
    await fetchTree()
  }

  return (
    <div className="outliner-container">
      <div className="outliner-header">
        <h2 className="outliner-title">知识树大纲</h2>
        <div className="outliner-actions">
          {saving && <span className="outliner-saving">保存中...</span>}
          <button className="outliner-add-root" onClick={() => setShowCreateDialog(true)}>+ 新建知识树</button>
        </div>
      </div>

      {/* ---- 新建知识树对话框 ---- */}
      {showCreateDialog && (
        <div className="outliner-dialog-overlay" onClick={() => !createLoading && setShowCreateDialog(false)}>
          <div className="outliner-dialog" onClick={e => e.stopPropagation()}>
            <div className="outliner-dialog-header">
              <h3>新建知识树</h3>
              <button className="outliner-dialog-close" onClick={() => !createLoading && setShowCreateDialog(false)}>×</button>
            </div>
            <div className="outliner-dialog-tabs">
              {[
                { key: 'generate', label: '🤖 LLM生成' },
                { key: 'text', label: '📄 文本导入' },
                { key: 'image', label: '📷 截图解析' },
                { key: 'mm', label: '📁 文件导入' },
              ].map(t => (
                <button key={t.key} className={`outliner-dialog-tab ${createTab === t.key ? 'active' : ''}`}
                  onClick={() => setCreateTab(t.key)} disabled={createLoading}>{t.label}</button>
              ))}
            </div>
            <div className="outliner-dialog-body">
              {createTab === 'text' && (
                <>
                  <p className="outliner-dialog-hint">粘贴文本或 Markdown 大纲，LLM 自动解析为知识树结构</p>
                  <textarea className="outliner-dialog-textarea" rows={10}
                    placeholder={"例如:\n# Java 基础\n## 集合框架\n- ArrayList vs LinkedList\n- HashMap 原理\n## 并发编程\n- synchronized\n- volatile"}
                    value={createText} onChange={e => setCreateText(e.target.value)} disabled={createLoading} />
                </>
              )}
              {createTab === 'generate' && (
                <>
                  <p className="outliner-dialog-hint">输入树名称和需求描述，LLM 一次性生成完整知识树</p>
                  <input className="outliner-dialog-input" placeholder="知识树名称，如：Java 面试知识点"
                    value={genName} onChange={e => setGenName(e.target.value)} disabled={createLoading} />
                  <textarea className="outliner-dialog-textarea" rows={6}
                    placeholder={"需求描述，例如:\n3年Java后端开发，准备面试大厂。\n重点覆盖：Spring、MySQL、Redis、分布式系统\n可以少一点：设计模式、计算机网络"}
                    value={genRequirements} onChange={e => setGenRequirements(e.target.value)} disabled={createLoading} />
                </>
              )}
              {createTab === 'image' && (
                <>
                  <p className="outliner-dialog-hint">上传知识树截图（思维导图、大纲等），LLM 自动识别并解析</p>
                  <div className="outliner-dialog-upload"
                    onClick={() => fileInputRef.current?.click()}
                    onDragOver={e => e.preventDefault()}
                    onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f?.type.startsWith('image/')) setImageFile(f) }}
                  >
                    {imageFile ? (
                      <span>✅ {imageFile.name} ({(imageFile.size / 1024).toFixed(0)} KB)</span>
                    ) : (
                      <span>点击选择或拖拽图片到这里</span>
                    )}
                  </div>
                  <input ref={fileInputRef} type="file" accept="image/*" style={{ display: 'none' }}
                    onChange={e => { if (e.target.files[0]) setImageFile(e.target.files[0]) }} />
                </>
              )}
              {createTab === 'mm' && (
                <>
                  <p className="outliner-dialog-hint">上传 .mm 文件（幕布/FreeMind/XMind 等导出），直接解析为知识树（无需 LLM）</p>
                  <div className="outliner-dialog-upload"
                    onClick={() => mmFileInputRef.current?.click()}
                    onDragOver={e => e.preventDefault()}
                    onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f?.name.endsWith('.mm')) setMmFile(f) }}
                  >
                    {mmFile ? (
                      <span>✅ {mmFile.name} ({(mmFile.size / 1024).toFixed(0)} KB)</span>
                    ) : (
                      <span>点击选择或拖拽 .mm 文件到这里</span>
                    )}
                  </div>
                  <input ref={mmFileInputRef} type="file" accept=".mm" style={{ display: 'none' }}
                    onChange={e => { if (e.target.files[0]) setMmFile(e.target.files[0]) }} />
                </>
              )}
            </div>
            <div className="outliner-dialog-footer">
              <button className="outliner-dialog-cancel" onClick={() => setShowCreateDialog(false)} disabled={createLoading}>取消</button>
              <button className="outliner-dialog-submit" onClick={handleCreate} disabled={createLoading}>
                {createLoading ? '生成中...' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ---- 重复冲突对话框 ---- */}
      {conflict && (
        <div className="outliner-dialog-overlay">
          <div className="outliner-dialog" style={{ width: 440 }}>
            <div className="outliner-dialog-header">
              <h3>检测到同名知识树</h3>
              <button className="outliner-dialog-close" onClick={handleConflictCancel}>×</button>
            </div>
            <div className="outliner-dialog-body">
              <p style={{ fontSize: 14, color: '#333', lineHeight: 1.8, margin: '0 0 16px' }}>
                已存在名为「<b>{conflict.oldName}</b>」的知识树，<br />
                新导入的「<b>{conflict.newName}</b>」与其重名，请选择处理方式：
              </p>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <button className="outliner-conflict-btn replace" onClick={handleConflictReplace}>
                  🗑️ 删除旧树，保留新树
                </button>
                <button className="outliner-conflict-btn cancel" onClick={handleConflictCancel}>
                  ↩️ 取消新增，保留旧树
                </button>
                {!conflict.isGenerate && (
                  <button className="outliner-conflict-btn merge" onClick={handleConflictMerge}>
                    🔀 合并到旧树（同名节点合并，新节点追加）
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      <div className="outliner-help">
        <span>Enter 新增同级</span>
        <span>Tab 缩进</span>
        <span>Shift+Tab 反缩进</span>
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

              {/* 面试权重下拉（叶子节点） */}
              {!hasKids && node.level >= 3 && (
                <select
                  className="outliner-weight-select"
                  value={node.interview_weight ?? 3}
                  title="面试权重"
                  onChange={async (e) => {
                    const w = Number(e.target.value)
                    setFlatNodes(prev => prev.map(n => n.id === node.id ? { ...n, interview_weight: w } : n))
                    await fetch(`${API}/tree-nodes/${node.id}`, {
                      method: 'PUT',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ interview_weight: w }),
                    })
                  }}
                >
                  {[1,2,3,4,5].map(v => (
                    <option key={v} value={v}>{'★'.repeat(v)}</option>
                  ))}
                </select>
              )}

              {/* LLM 优化按钮（根节点） */}
              {node.parent_id === null && (
                <button
                  className="outliner-optimize"
                  title="LLM 查漏补缺"
                  disabled={optimizingId === node.id}
                  onClick={() => handleOptimize(node)}
                >{optimizingId === node.id ? '⏳' : '✨'}</button>
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
