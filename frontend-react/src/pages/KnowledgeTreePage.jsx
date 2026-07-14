import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { API_KNOWLEDGE, API_LEARN } from '../config'

// 统一 POST 小包装（全局 fetch 拦截器已注入 JWT）
async function postLearn(path, body) {
  const resp = await fetch(`${API_LEARN}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  return resp.json()
}

// 掌握度小圆环：SVG stroke-dasharray 画进度，未练习时显示空灰圈
function MasteryRing({ percent, color }) {
  const r = 7, c = 2 * Math.PI * r
  const p = Math.max(0, Math.min(100, percent || 0))
  const offset = c * (1 - p / 100)
  return (
    <svg className="mastery-ring" width="18" height="18" viewBox="0 0 18 18">
      <circle cx="9" cy="9" r={r} fill="none" stroke="#eee" strokeWidth="2.5" />
      {p > 0 && (
        <circle cx="9" cy="9" r={r} fill="none" stroke={color} strokeWidth="2.5"
          strokeDasharray={c} strokeDashoffset={offset} strokeLinecap="round"
          transform="rotate(-90 9 9)" />
      )}
    </svg>
  )
}

// 掌握度配色：≥80 绿 / ≥40 黄 / >0 红 / 0 灰
function masteryColor(p) {
  return p >= 80 ? '#52c41a' : p >= 40 ? '#faad14' : p > 0 ? '#ff4d4f' : '#e0e0e0'
}

// 问题行：📌真题徽标 + tier 徽标 + 内容 + 操作（改 tier / 答题 / 删除）
function QuestionRow({ node, kpId, depth, reload }) {
  const [busy, setBusy] = useState(false)
  const isInterview = node.source === 'interview'
  const isCore = node.tier === 'core'

  async function toggleTier() {
    if (busy) return
    setBusy(true)
    try {
      const next = isCore ? 'ext' : 'core'
      const r = await postLearn('/question-tier', { kp_id: Number(kpId), question_id: node.id, tier: next })
      if (r.code === 0) reload()
    } finally { setBusy(false) }
  }

  async function del() {
    if (busy) return
    if (!window.confirm('确定删除这道题？')) return
    setBusy(true)
    try {
      const r = await postLearn('/question-delete', { kp_id: Number(kpId), question_id: node.id })
      if (r.code === 0) reload()
    } finally { setBusy(false) }
  }

  return (
    <div className="tree-node">
      <div className="node-row q-row" style={{ paddingLeft: 16 + depth * 22 }}>
        <span className="bullet" />
        {isInterview && <span className="q-badge interview" title="面试真题">📌真题</span>}
        <button
          type="button"
          className={`learn-tier-badge ${isCore ? 'core' : 'ext'}`}
          disabled={busy}
          title="点击切换 高频/扩展"
          onClick={toggleTier}
        >
          {isCore ? '高频' : '扩展'}
        </button>
        <span className="q-text" title={node.name}>{node.name}</span>
        <div className="q-actions">
          <Link to={`/exam/${kpId}`} className="q-btn link" title="进入该知识点答题">答题</Link>
          <button type="button" className="q-btn danger" disabled={busy} onClick={del} title="删除该题">删除</button>
        </div>
      </div>
    </div>
  )
}

function TreeNode({ node, depth, kpId, reload }) {
  const [collapsed, setCollapsed] = useState(false)
  const children = node.children || []
  const hasKids = children.length > 0
  const type = node.node_type

  if (type === 'question') {
    return <QuestionRow node={node} kpId={kpId} depth={depth} reload={reload} />
  }

  const isCat = type === 'category'
  const isKp = type === 'knowledge_point'
  const childKpId = isKp ? node.id : kpId  // 知识点是问题的归属点，往下透传
  const weight = node.interview_weight || 0
  const examM = node.mastery_level || 0
  const selfM = node.self_mastery || 0

  return (
    <div className="tree-node">
      <div className={`node-row ${isCat ? 'cat' : ''} ${type === 'subtopic' ? 'subtopic' : ''}`}
        style={{ paddingLeft: 16 + depth * 22 }}>
        {hasKids ? (
          <span className={`toggle ${collapsed ? '' : 'open'}`} onClick={() => setCollapsed(c => !c)} />
        ) : (
          <span className="bullet" />
        )}
        <span className="node-name" onClick={() => hasKids && setCollapsed(c => !c)}>
          {node.name}
        </span>
        {isKp && node.has_interview_questions && (
          <span className="kp-interview-heart" title="有关联面试真题">♥</span>
        )}
        {isKp && (
          <div className="leaf-meta">
            <span className="stars" title={`重要度 ${weight}/5`}>
              {[1, 2, 3, 4, 5].map(i => (
                <span key={i} className={i <= weight ? 'star on' : 'star off'}>★</span>
              ))}
            </span>
            <span className="mastery-pair">
              <span className="mp-item" title={examM > 0 ? `答题掌握度 ${examM}%` : '尚未答题'}>
                <span className="mp-label">答题掌握度</span>
                <MasteryRing percent={examM} color={masteryColor(examM)} />
              </span>
              <span className="mp-item" title={selfM > 0 ? `自评掌握度 ${selfM}%` : '尚未自评'}>
                <span className="mp-label">自评掌握度</span>
                <MasteryRing percent={selfM} color={masteryColor(selfM)} />
              </span>
            </span>
            <Link to={`/learn/${node.id}`} className="leaf-action learn">
              <span className="icon">📖</span>学习
            </Link>
            <Link to={`/exam/${node.id}`} className="leaf-action exam">
              <span className="icon">✏️</span>答题
            </Link>
          </div>
        )}
      </div>
      {hasKids && !collapsed && children.map(ch => (
        <TreeNode key={ch.id} node={ch} depth={depth + 1} kpId={childKpId} reload={reload} />
      ))}
    </div>
  )
}

export default function KnowledgeTreePage() {
  const [tree, setTree] = useState([])
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState(0)

  const reload = useCallback(() => {
    return fetch(`${API_KNOWLEDGE}/tree-full`).then(r => r.json()).then(d => {
      if (d.code === 0) setTree(d.data)
    })
  }, [])

  useEffect(() => {
    reload().finally(() => setLoading(false))
  }, [reload])

  if (loading) return <div className="loading">加载中...</div>
  if (!tree.length) return <div className="empty">暂无知识树数据</div>

  return (
    <div className="knowledge-page">
      <div className="tree-tabs">
        {tree.map((cat, i) => (
          <button key={cat.id} className={`tree-tab ${i === tab ? 'active' : ''}`} onClick={() => setTab(i)}>
            {cat.name}
          </button>
        ))}
      </div>
      <div className="tree-card">
        {(tree[tab]?.children || []).map(node => (
          <TreeNode key={node.id} node={node} depth={0} kpId={null} reload={reload} />
        ))}
        {!(tree[tab]?.children?.length) && <div className="empty">暂无内容</div>}
      </div>
    </div>
  )
}
