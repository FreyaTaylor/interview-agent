import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api/knowledge'

function TreeNode({ node, depth }) {
  const [collapsed, setCollapsed] = useState(false)
  const children = node.children || []
  const hasKids = children.length > 0
  const isCat = depth === 0

  const stars = '★'.repeat(node.interview_weight) + '☆'.repeat(5 - node.interview_weight)
  const m = node.mastery_level
  const color = m >= 80 ? '#52c41a' : m >= 40 ? '#faad14' : m > 0 ? '#ff4d4f' : '#e0e0e0'

  return (
    <div className="tree-node">
      <div className={`node-row ${isCat ? 'cat' : ''}`} style={{ paddingLeft: 16 + depth * 22 }}>
        {hasKids ? (
          <span className={`toggle ${collapsed ? '' : 'open'}`} onClick={() => setCollapsed(c => !c)} />
        ) : (
          <span className="bullet" />
        )}
        <span className="node-name" onClick={() => hasKids && setCollapsed(c => !c)}>
          {node.name}
        </span>
        {!hasKids && (
          <div className="leaf-meta">
            <span className="stars">{stars}</span>
            <div className="bar"><div className="bar-fill" style={{ width: `${m}%`, background: color }} /></div>
            <span className="pct">{m > 0 ? `${m}%` : '—'}</span>
            <Link to={`/study/${node.id}`} className="study-btn">
              {node.study_count > 0 ? '复习' : '学习'}
            </Link>
          </div>
        )}
      </div>
      {hasKids && !collapsed && children.map(ch => <TreeNode key={ch.id} node={ch} depth={depth + 1} />)}
    </div>
  )
}

export default function KnowledgeTreePage() {
  const [tree, setTree] = useState([])
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState(0)

  useEffect(() => {
    fetch(`${API}/tree`).then(r => r.json()).then(d => {
      if (d.code === 0) setTree(d.data)
    }).finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="loading">加载中...</div>
  if (!tree.length) return <div className="empty">暂无知识树数据</div>

  return (
    <>
      <div className="tree-tabs">
        {tree.map((cat, i) => (
          <button key={cat.id} className={`tree-tab ${i === tab ? 'active' : ''}`} onClick={() => setTab(i)}>
            {cat.name}
          </button>
        ))}
      </div>
      <div className="tree-card">
        {(tree[tab]?.children || []).map(node => <TreeNode key={node.id} node={node} depth={0} />)}
        {!(tree[tab]?.children?.length) && <div className="empty">暂无内容</div>}
      </div>
    </>
  )
}
