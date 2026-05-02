/**
 * 面试其他问题页 — 独立 Tab 页面，累积所有面试的非知识点/非项目问题
 * 从后端 DB 读取（跨会话持久化，去重）
 * 子 Tab 动态生成，根据数据自动显示
 */
import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

// 分类配置：可自由扩展，新增分类只需加一行
const CATEGORY_CONFIG = {
  algorithm: { label: '💻 算法题', color: '#fa8c16' },
  hr: { label: '💬 HR / 行为题', color: '#52c41a' },
  system_design: { label: '🏗️ 系统设计', color: '#1677ff' },
  scenario: { label: '🎯 场景题', color: '#722ed1' },
  other: { label: '❓ 其他', color: '#999' },
}

export default function OtherQuestionsPage() {
  const [data, setData] = useState({})
  const [activeTab, setActiveTab] = useState('')
  const [expanded, setExpanded] = useState({})

  const toggle = k => setExpanded(p => ({ ...p, [k]: !p[k] }))

  useEffect(() => {
    fetch(`${API}/interview/other-questions`)
      .then(r => r.json())
      .then(resp => {
        if (resp.code === 0 && resp.data) {
          setData(resp.data)
          const firstKey = Object.keys(resp.data).find(k => resp.data[k]?.length > 0)
          if (firstKey) setActiveTab(firstKey)
        }
      })
      .catch(() => {})
  }, [])

  const allKeys = Object.keys(data).filter(k => data[k]?.length > 0)
  const hasContent = allKeys.length > 0

  if (!hasContent) return (
    <div style={{ textAlign: 'center', padding: '60px 20px', color: '#aaa' }}>
      <div style={{ fontSize: 40, marginBottom: 12 }}>📎</div>
      <div style={{ fontSize: 16, marginBottom: 8 }}>暂无其他面试问题</div>
      <div style={{ fontSize: 13 }}>先到 <Link to="/interview" style={{ color: '#1677ff' }}>面试复盘</Link> 上传面试记录</div>
    </div>
  )

  const getConfig = (key) => CATEGORY_CONFIG[key] || { label: `📋 ${key}`, color: '#666' }
  const items = data[activeTab] || []
  const cfg = getConfig(activeTab)

  return (
    <div>
      {/* 子 Tab 栏 */}
      <div className="tree-tabs" style={{ marginBottom: 16 }}>
        {allKeys.map(key => {
          const c = getConfig(key)
          return (
            <button key={key} className={`tree-tab ${activeTab === key ? 'active' : ''}`}
              onClick={() => setActiveTab(key)}>
              {c.label} ({data[key].length})
            </button>
          )
        })}
      </div>

      {/* 内容区 — 幕布风格 */}
      <div className="tree-card">
      {items.map((g, i) => {
        const isOpen = expanded[`${activeTab}_${i}`]
        const hasDetail = g.description || g.example || g.suggested_approach || g.feedback || g.answer
        const lcUrl = g.leetcode_url || null
        return (
        <div key={i} className="tree-node">
          <div className="node-row" style={{ paddingLeft: 16, cursor: hasDetail ? 'pointer' : 'default' }} onClick={() => hasDetail && toggle(`${activeTab}_${i}`)}>
            {hasDetail ? <span className={`toggle ${isOpen ? 'open' : ''}`} /> : <span className="bullet" />}
            <span className="node-name">{g.title || g.question || '—'}</span>
            {g.count > 1 && <span style={{ fontSize: 11, color: '#fff', background: cfg.color, borderRadius: 10, padding: '1px 6px', marginLeft: 6 }}>×{g.count}</span>}
            {lcUrl ? <a href={lcUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} className="lc-tag">LeetCode{g.leetcode_id ? ` #${g.leetcode_id}` : ''}</a> : g.leetcode_id ? <span className="lc-tag" style={{ cursor: 'default' }}>LeetCode #{g.leetcode_id}</span> : null}
          </div>
          {isOpen && (
            <div style={{ paddingLeft: 38 }}>
              {g.answer && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#666' }}>💬 {g.answer}</span></div></div>}
              {g.feedback && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📊 {g.feedback}</span></div></div>}
              {g.description && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📝 {g.description}</span></div></div>}
              {g.example && <div style={{ marginLeft: 16, padding: '4px 10px', background: '#f5f5f5', borderRadius: 4, fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.example}</div>}
              {g.suggested_approach && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📖 {g.suggested_approach}</span></div></div>}
            </div>
          )}
        </div>
      )})}
      </div>
    </div>
  )
}
