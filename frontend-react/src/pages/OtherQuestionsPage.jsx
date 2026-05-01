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
      {items.map((g, i) => (
        <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: `3px solid ${cfg.color}`, padding: '10px 14px', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 600, flex: 1, minWidth: 150 }}>{g.title || g.question || '—'}</span>
          {g.count > 1 && <span style={{ fontSize: 11, color: '#fff', background: cfg.color, borderRadius: 10, padding: '1px 8px' }}>考过{g.count}次</span>}
          {g.leetcode_id && (
            <a href={`https://leetcode.cn/problems/`} target="_blank" rel="noreferrer"
               style={{ fontSize: 12, color: cfg.color, padding: '2px 8px', border: `1px solid ${cfg.color}`, borderRadius: 4, textDecoration: 'none' }}>
              LeetCode
            </a>
          )}
          {g.answer && <div style={{ width: '100%', fontSize: 13, color: '#666', marginTop: 2, paddingLeft: 12 }}>💬 {g.answer}</div>}
        </div>
      ))}
    </div>
  )
}
