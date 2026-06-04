/**
 * 管理页 — 用户画像 + 知识树初始化
 * SSE 流式接收初始化进度
 */
import { useState, useEffect, useRef } from 'react'
import { API_ADMIN } from '../config'

// 递归编辑节点组件
function EditNode({ node, allNodes, depth, editingId, editName, addParentId, addName,
  setEditingId, setEditName, setAddParentId, setAddName, onUpdate, onDelete, onAdd }) {
  const children = allNodes.filter(n => n.parent_id === node.id)
  const hasChildren = children.length > 0
  const isLeaf = !hasChildren && depth >= 2
  const indent = depth * 20
  const fontSize = depth === 0 ? 14 : 13
  const fontWeight = depth === 0 ? 700 : depth === 1 ? 600 : 400
  const color = depth === 0 ? '#333' : depth === 1 ? '#555' : '#666'

  return (
    <div style={{ marginLeft: indent }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: depth === 0 ? '8px 0' : '4px 0',
        borderBottom: depth === 0 ? '1px solid #eee' : 'none' }}>
        {editingId === node.id ? (
          <>
            <input value={editName} onChange={e => setEditName(e.target.value)} autoFocus
              style={{ flex: 1, padding: '3px 6px', border: '1px solid #1677ff', borderRadius: 4, fontSize, fontFamily: 'inherit' }}
              onKeyDown={e => { if (e.key === 'Enter') onUpdate(node.id); if (e.key === 'Escape') setEditingId(null) }} />
            <button onClick={() => onUpdate(node.id)} style={{ fontSize: 11, color: '#1677ff', background: 'none', border: 'none', cursor: 'pointer' }}>✓</button>
            <button onClick={() => setEditingId(null)} style={{ fontSize: 11, color: '#999', background: 'none', border: 'none', cursor: 'pointer' }}>✕</button>
          </>
        ) : (
          <>
            <span style={{ flex: 1, fontWeight, fontSize, color, cursor: 'pointer' }}
              onClick={() => { setEditingId(node.id); setEditName(node.name) }}>
              {depth > 0 && !isLeaf ? '▸ ' : depth > 0 ? '• ' : ''}{node.name}
            </span>
            <button onClick={() => { setAddParentId(node.id); setAddName('') }} style={{ fontSize: 11, color: '#52c41a', background: 'none', border: 'none', cursor: 'pointer' }}>+</button>
            <button onClick={() => onDelete(node.id, node.name)} style={{ fontSize: 11, color: '#ff4d4f', background: 'none', border: 'none', cursor: 'pointer' }}>删</button>
          </>
        )}
      </div>
      {addParentId === node.id && (
        <div style={{ display: 'flex', gap: 6, marginLeft: 20, marginBottom: 4, marginTop: 4 }}>
          <input value={addName} onChange={e => setAddName(e.target.value)} placeholder="新节点名称" autoFocus
            style={{ flex: 1, padding: '3px 6px', border: '1px solid #ddd', borderRadius: 4, fontSize: 12, fontFamily: 'inherit' }}
            onKeyDown={e => { if (e.key === 'Enter') onAdd(node.id); if (e.key === 'Escape') setAddParentId(null) }} />
          <button onClick={() => onAdd(node.id)} style={{ fontSize: 11, color: '#1677ff', background: 'none', border: 'none', cursor: 'pointer' }}>✓</button>
          <button onClick={() => setAddParentId(null)} style={{ fontSize: 11, color: '#999', background: 'none', border: 'none', cursor: 'pointer' }}>✕</button>
        </div>
      )}
      {children.map(child => (
        <EditNode key={child.id} node={child} allNodes={allNodes} depth={depth + 1}
          editingId={editingId} editName={editName} addParentId={addParentId} addName={addName}
          setEditingId={setEditingId} setEditName={setEditName} setAddParentId={setAddParentId} setAddName={setAddName}
          onUpdate={onUpdate} onDelete={onDelete} onAdd={onAdd} />
      ))}
    </div>
  )
}

const DEFAULT_PROFILE = `3年Java后端开发，目前在一家中型互联网公司做电商业务。

技术栈：
- 主力：Java/Spring Boot/MyBatis
- 中间件：Redis、Kafka、Elasticsearch
- 数据库：MySQL、一点点 MongoDB
- 基础设施：Docker、K8s 基础使用

项目经验：
- 电商订单系统（超时取消、库存扣减、分布式事务）
- 消息推送平台（百万级推送、消息去重、延迟队列）

薄弱方向：JVM 调优（基本没实操过）、分布式理论（Raft/Paxos 只看过没深入）、系统设计（没做过）
擅长方向：Redis 用得比较多、MySQL 调优有经验

计划 1 个月内开始面试。`

export default function AdminPage() {
  const [profile, setProfile] = useState('')
  const [profileSaved, setProfileSaved] = useState(false)
  const [stats, setStats] = useState(null)
  const [initing, setIniting] = useState(false)
  const [logs, setLogs] = useState([])
  const [done, setDone] = useState(false)
  const logsEndRef = useRef(null)
  // 面板折叠状态（默认全部收起）
  const [openSections, setOpenSections] = useState({})
  const toggleSection = (key) => setOpenSections(prev => ({ ...prev, [key]: !prev[key] }))
  // 知识树编辑
  const [treeNodes, setTreeNodes] = useState([])
  const [editingId, setEditingId] = useState(null)
  const [editName, setEditName] = useState('')
  const [addParentId, setAddParentId] = useState(null)
  const [addName, setAddName] = useState('')

  // 加载画像 + 统计 + 知识树
  useEffect(() => {
    fetch(`${API_ADMIN}/profile`).then(r => r.json()).then(resp => {
      if (resp.code === 0) setProfile(resp.data.profile_text || '')
    }).catch(() => {})
    fetchStats()
    fetchTree()
  }, [])

  function fetchStats() {
    fetch(`${API_ADMIN}/tree-stats`).then(r => r.json()).then(resp => {
      if (resp.code === 0) setStats(resp.data)
    }).catch(() => {})
  }

  function fetchTree() {
    fetch(`${API_ADMIN}/tree-nodes`).then(r => r.json()).then(resp => {
      if (resp.code === 0) setTreeNodes(resp.data || [])
    }).catch(() => {})
  }

  // 滚动到最新日志
  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  async function handleSaveProfile() {
    const resp = await fetch(`${API_ADMIN}/profile`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile_text: profile }),
    }).then(r => r.json())
    if (resp.code === 0) {
      setProfileSaved(true)
      setTimeout(() => setProfileSaved(false), 2000)
    }
  }

  async function handleInit() {
    const text = profile.trim()
    if (!text || initing) return

    if (stats?.initialized) {
      if (!window.confirm('⚠️ 重新初始化会清空现有知识树和掌握度数据，确定继续？')) return
    }

    setIniting(true)
    setLogs([])
    setDone(false)

    try {
      // 用 POST 发起请求，手动读取 SSE 流
      const resp = await fetch(`${API_ADMIN}/init-tree`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profile_text: text }),
      })

      if (!resp.ok) {
        setLogs([{ step: 'error', message: `请求失败: HTTP ${resp.status}` }])
        setDone(true)
        setIniting(false)
        return
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      const processLine = (line) => {
        if (!line.startsWith('data: ')) return
        try {
          const data = JSON.parse(line.slice(6))
          console.log('SSE:', data)
          setLogs(prev => [...prev, data])
          if (data.step === 'done' || data.step === 'error') {
            setDone(true)
            fetchStats()
            fetchTree()
          }
        } catch (parseErr) {
          console.error('SSE parse error:', parseErr, line)
        }
      }

      while (true) {
        const { done: streamDone, value } = await reader.read()
        if (streamDone) break
        buffer += decoder.decode(value, { stream: true })
        // SSE 消息以 \n\n 分隔
        while (buffer.includes('\n\n')) {
          const idx = buffer.indexOf('\n\n')
          const chunk = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          chunk.split('\n').forEach(processLine)
        }
      }
      // 处理剩余 buffer
      if (buffer.trim()) {
        buffer.split('\n').forEach(processLine)
      }
    } catch (e) {
      console.error('SSE fetch error:', e)
      setLogs(prev => [...prev, { step: 'error', message: `请求失败: ${e.message}` }])
      setDone(true)
    }
    setIniting(false)
  }

  // ---- 知识树编辑 ----
  async function handleAddNode(parentId) {
    if (!addName.trim()) return
    await fetch(`${API_ADMIN}/tree-nodes`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parent_id: parentId, name: addName.trim() }),
    })
    setAddName(''); setAddParentId(null); fetchTree(); fetchStats()
  }

  async function handleUpdateNode(nodeId) {
    if (!editName.trim()) return
    await fetch(`${API_ADMIN}/tree-nodes/${nodeId}/update`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: editName.trim() }),
    })
    setEditingId(null); setEditName(''); fetchTree()
  }

  async function handleDeleteNode(nodeId, name) {
    if (!window.confirm(`确定删除「${name}」及其所有子节点？`)) return
    await fetch(`${API_ADMIN}/tree-nodes/${nodeId}/delete`, { method: 'POST' })
    fetchTree(); fetchStats()
  }

  const lastLog = logs[logs.length - 1]
  const progress = lastLog?.completed && lastLog?.total
    ? Math.round((lastLog.completed / lastLog.total) * 100)
    : null

  return (
    <div>
      {/* ---- 用户画像 ---- */}
      <div className="tree-card" style={{ padding: '20px 24px', marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
          onClick={() => toggleSection('profile')}>
          <h3 style={{ margin: 0, fontSize: 16 }}>
            <span style={{ display: 'inline-block', transition: 'transform 0.2s', transform: openSections.profile ? 'rotate(90deg)' : 'rotate(0deg)', marginRight: 6 }}>▸</span>
            👤 用户画像
          </h3>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {profileSaved && <span style={{ fontSize: 13, color: '#52c41a' }}>✓ 已保存</span>}
          </div>
        </div>
        {openSections.profile && (<div style={{ marginTop: 12 }}>
        <p style={{ fontSize: 13, color: '#888', margin: '0 0 8px' }}>
          描述你的背景和目标（自由填写），Agent 会据此个性化生成知识树
        </p>
        <textarea
          className="form-textarea"
          rows={10}
          value={profile}
          onChange={e => setProfile(e.target.value)}
          placeholder={DEFAULT_PROFILE}
          style={{ fontSize: 13 }}
        />
        <div style={{ marginTop: 8, textAlign: 'right' }}>
          <button className="parse-btn" onClick={handleSaveProfile} disabled={!profile.trim()}>保存</button>
        </div>
        </div>)}
      </div>

      {/* ---- 知识树初始化 ---- */}
      <div className="tree-card" style={{ padding: '20px 24px', marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
          onClick={() => toggleSection('init')}>
          <h3 style={{ margin: 0, fontSize: 16 }}>
            <span style={{ display: 'inline-block', transition: 'transform 0.2s', transform: openSections.init ? 'rotate(90deg)' : 'rotate(0deg)', marginRight: 6 }}>▸</span>
            🌳 知识树初始化
          </h3>
          {stats?.initialized && !openSections.init && (
            <span style={{ fontSize: 12, color: '#52c41a' }}>✅ {stats.leaf_count} 个知识点</span>
          )}
        </div>
        {openSections.init && (<div style={{ marginTop: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
          <button className="parse-btn" onClick={handleInit} disabled={initing || !profile.trim()}>
            {initing ? '⏳ 初始化中...' : stats?.initialized ? '🔄 重新生成' : '🚀 LLM生成'}
          </button>
          </div>

        {/* 统计信息 */}
        {stats && (
          <div style={{ fontSize: 13, color: '#666', marginBottom: 12, padding: '8px 12px', background: '#f9fafb', borderRadius: 6 }}>
            {stats.initialized
              ? <span>✅ 已初始化 — {stats.leaf_count} 个知识点，{stats.category_count} 个一级分类</span>
              : <span>⬜ 尚未初始化，请填写画像后点击「开始初始化」</span>
            }
          </div>
        )}

        {/* 加载中提示 */}
        {initing && logs.length === 0 && (
          <div style={{ fontSize: 13, color: '#888', padding: '12px 0' }}>⏳ 正在初始化，请稍候...</div>
        )}

        {/* 进度条 */}
        {initing && progress !== null && (
          <div style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#888', marginBottom: 4 }}>
              <span>展开叶子知识点</span>
              <span>{lastLog.completed}/{lastLog.total} ({progress}%)</span>
            </div>
            <div style={{ height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${progress}%`, background: '#1677ff', borderRadius: 3, transition: 'width 0.3s' }} />
            </div>
          </div>
        )}

        {/* 日志 */}
        {logs.length > 0 && (
          <div style={{
            maxHeight: 300, overflowY: 'auto', padding: '12px 16px',
            background: '#1e1e1e', borderRadius: 8, fontSize: 12, fontFamily: 'monospace',
            lineHeight: 1.8,
          }}>
            {logs.map((log, i) => (
              <div key={i} style={{ color: log.step === 'error' ? '#ff4d4f' : log.step === 'done' ? '#52c41a' : '#d4d4d4' }}>
                {log.step === 'done' ? '✅ ' : log.step === 'error' ? '❌ ' : log.step === 'expanding' ? '📦 ' : '⏳ '}
                {log.message}
              </div>
            ))}
            <div ref={logsEndRef} />
          </div>
        )}

        {/* 完成提示 */}
        {done && lastLog?.step === 'done' && (
          <div style={{ marginTop: 12, padding: '10px 16px', background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 8, fontSize: 13 }}>
            🎉 初始化完成！共生成 <b>{lastLog.leaf_count}</b> 个知识点。
            <a href="/" style={{ marginLeft: 8, color: '#1677ff' }}>去知识树查看 →</a>
          </div>
        )}
        </div>)}
      </div>

      {/* ---- 知识树编辑 ---- */}
      {treeNodes.length > 0 && (
        <div className="tree-card" style={{ padding: '20px 24px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }}
            onClick={() => toggleSection('edit')}>
            <h3 style={{ margin: 0, fontSize: 16 }}>
              <span style={{ display: 'inline-block', transition: 'transform 0.2s', transform: openSections.edit ? 'rotate(90deg)' : 'rotate(0deg)', marginRight: 6 }}>▸</span>
              ✏️ 知识树编辑
            </h3>
            {!openSections.edit && (
              <span style={{ fontSize: 12, color: '#888' }}>{treeNodes.length} 个节点</span>
            )}
          </div>
          {openSections.edit && (<div style={{ marginTop: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
            <button className="parse-btn" style={{ fontSize: 12, padding: '4px 12px' }}
              onClick={() => { setAddParentId(null); setAddName('') }}>
              ＋ 添加一级分类
            </button>
          </div>

          {/* 添加一级分类 */}
          {addParentId === null && addName !== null && (
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input value={addName} onChange={e => setAddName(e.target.value)} placeholder="新一级分类名称"
                style={{ flex: 1, padding: '6px 10px', border: '1px solid #ddd', borderRadius: 6, fontSize: 13, fontFamily: 'inherit' }}
                onKeyDown={e => e.key === 'Enter' && handleAddNode(null)} />
              <button onClick={() => handleAddNode(null)} style={{ padding: '6px 12px', borderRadius: 6, border: 'none', background: '#1677ff', color: '#fff', fontSize: 12, cursor: 'pointer' }}>添加</button>
              <button onClick={() => setAddName(null)} style={{ padding: '6px 12px', borderRadius: 6, border: '1px solid #ddd', background: '#fff', fontSize: 12, cursor: 'pointer' }}>取消</button>
            </div>
          )}

          {/* 树结构（递归渲染） */}
          {treeNodes.filter(n => n.level === 1).map(node => (
            <EditNode key={node.id} node={node} allNodes={treeNodes} depth={0}
              editingId={editingId} editName={editName} addParentId={addParentId} addName={addName}
              setEditingId={setEditingId} setEditName={setEditName} setAddParentId={setAddParentId} setAddName={setAddName}
              onUpdate={handleUpdateNode} onDelete={handleDeleteNode} onAdd={handleAddNode} />
          ))}
          </div>)}
        </div>
      )}
    </div>
  )
}
