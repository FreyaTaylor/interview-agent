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
      <h2 style={{ marginBottom: 12 }}>📎 其他面试问题</h2>

      {/* 子 Tab 栏 */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '2px solid #eee', marginBottom: 16 }}>
        {allKeys.map(key => {
          const c = getConfig(key)
          return (
            <button key={key} onClick={() => setActiveTab(key)} style={{
              padding: '8px 16px', fontSize: 13, fontWeight: activeTab === key ? 600 : 400,
              color: activeTab === key ? c.color : '#888', background: 'none', border: 'none',
              borderBottom: activeTab === key ? `2px solid ${c.color}` : '2px solid transparent',
              marginBottom: -2, cursor: 'pointer', transition: 'all .2s',
            }}>
              {c.label} ({data[key].length})
            </button>
          )
        })}
      </div>

      {/* 内容区 */}
      {items.map((g, i) => {
        const isOpen = expanded[`${activeTab}_${i}`]
        const hasDetail = g.description || g.example || g.suggested_approach || g.feedback
        const lcUrl = g.leetcode_url || (g.leetcode_id ? `https://leetcode.cn/problems/` : null)
        return (
        <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: `3px solid ${cfg.color}`, padding: '10px 14px', marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: hasDetail ? 'pointer' : 'default' }} onClick={() => hasDetail && toggle(`${activeTab}_${i}`)}>
            {hasDetail && <span style={{ color: cfg.color, fontSize: 14 }}>{isOpen ? '▼' : '▶'}</span>}
            <span style={{ fontWeight: 600, flex: 1 }}>{g.title || g.question || '—'}</span>
            {g.count > 1 && <span style={{ fontSize: 11, color: '#fff', background: cfg.color, borderRadius: 10, padding: '1px 8px' }}>考过{g.count}次</span>}
            {g.leetcode_id && <span style={{ fontSize: 12, color: '#888' }}>#{g.leetcode_id}</span>}
            {lcUrl && (
              <a href={lcUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()}
                 style={{ fontSize: 12, color: cfg.color, padding: '2px 8px', border: `1px solid ${cfg.color}`, borderRadius: 4, textDecoration: 'none' }}>
                LeetCode
              </a>
            )}
          </div>
          {g.answer && <div style={{ fontSize: 13, color: '#666', marginTop: 4, paddingLeft: 12 }}>💬 {g.answer}</div>}
          {isOpen && (
            <div style={{ marginTop: 8, paddingLeft: 24, fontSize: 13 }}>
              {g.feedback && <div style={{ color: '#555', marginBottom: 6, padding: '4px 10px', background: '#fffbe6', borderLeft: `3px solid ${cfg.color}`, borderRadius: 4 }}>💬 {g.feedback}</div>}
              {g.description && <div style={{ marginBottom: 6 }}><b>📝 题目</b><div style={{ padding: '4px 10px', background: '#fafafa', borderRadius: 4, lineHeight: 1.7 }}>{g.description}</div></div>}
              {g.example && <div style={{ marginBottom: 6 }}><b>💡 示例</b><pre style={{ padding: '6px 10px', background: '#f5f5f5', borderRadius: 4, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap', margin: '4px 0', lineHeight: 1.6 }}>{g.example}</pre></div>}
              {g.suggested_approach && <div><b>📖 建议解法</b><div style={{ padding: '4px 10px', background: '#f6ffed', borderRadius: 4, lineHeight: 1.7 }}>{g.suggested_approach}</div></div>}
            </div>
          )}
        </div>
      )})}
    </div>
  )
}
