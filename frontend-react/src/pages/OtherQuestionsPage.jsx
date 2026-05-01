/**
 * 面试其他问题页 — 独立 Tab 页面，累积所有面试的算法题/HR题
 * 从后端 DB 读取（跨会话持久化）
 */
import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

export default function OtherQuestionsPage() {
  const [groups, setGroups] = useState({ algorithm: [], hr: [] })
  const [expanded, setExpanded] = useState({})

  useEffect(() => {
    fetch(`${API}/interview/other-questions`)
      .then(r => r.json())
      .then(resp => { if (resp.code === 0) setGroups(resp.data || { algorithm: [], hr: [] }) })
      .catch(() => {})
  }, [])

  const toggle = k => setExpanded(p => ({ ...p, [k]: !p[k] }))
  const hasContent = groups.algorithm.length + groups.hr.length > 0

  if (!hasContent) return (
    <div style={{ textAlign: 'center', padding: '60px 20px', color: '#aaa' }}>
      <div style={{ fontSize: 40, marginBottom: 12 }}>📎</div>
      <div style={{ fontSize: 16, marginBottom: 8 }}>暂无其他面试问题</div>
      <div style={{ fontSize: 13 }}>先到 <Link to="/interview" style={{ color: '#1677ff' }}>面试复盘</Link> 上传面试记录</div>
    </div>
  )

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>📎 其他面试问题</h2>

      {/* 算法题 */}
      {groups.algorithm.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <h3 style={{ fontSize: 15, marginBottom: 8, color: '#fa8c16' }}>💻 算法题</h3>
          {groups.algorithm.map((g, i) => (
            <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #fa8c16', padding: 14, marginBottom: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontWeight: 600, flex: 1 }}>{g.title}</span>
                {g.leetcode_id && (
                  <a href={`https://leetcode.cn/problems/`} target="_blank" rel="noreferrer"
                     style={{ fontSize: 12, color: '#fa8c16', padding: '2px 8px', border: '1px solid #fa8c16', borderRadius: 4, textDecoration: 'none' }}>
                    LeetCode #{g.leetcode_id}
                  </a>
                )}
              </div>
              {g.original_dialogue && (
                <div style={{ marginTop: 6 }}>
                  <div style={{ padding: '4px 0', cursor: 'pointer', fontSize: 12, color: '#aaa' }}
                       onClick={() => toggle(`a${i}`)}>
                    {expanded[`a${i}`] ? '▾' : '▸'} 原始对话
                  </div>
                  {expanded[`a${i}`] && (
                    <div style={{ fontSize: 13, color: '#888', whiteSpace: 'pre-wrap', padding: '6px 12px', background: '#fffbf0', borderRadius: 4 }}>{g.original_dialogue}</div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* HR题 */}
      {groups.hr.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <h3 style={{ fontSize: 15, marginBottom: 8, color: '#52c41a' }}>💬 HR / 行为题</h3>
          {groups.hr.map((g, i) => (
            <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #52c41a', padding: 14, marginBottom: 10 }}>
              {g.questions?.map((q, j) => <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#333' }}>• {q}</div>)}
              {g.user_answer && (
                <div style={{ fontSize: 13, color: '#666', marginTop: 6, padding: '6px 12px', background: '#f6ffed', borderRadius: 4 }}>💬 {g.user_answer}</div>
              )}
              {g.original_dialogue && (
                <div style={{ marginTop: 6 }}>
                  <div style={{ padding: '4px 0', cursor: 'pointer', fontSize: 12, color: '#aaa' }}
                       onClick={() => toggle(`h${i}`)}>
                    {expanded[`h${i}`] ? '▾' : '▸'} 原始对话
                  </div>
                  {expanded[`h${i}`] && (
                    <div style={{ fontSize: 12, color: '#aaa', whiteSpace: 'pre-wrap', padding: '6px 12px', background: '#f6ffed', borderRadius: 4 }}>{g.original_dialogue}</div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 其他 */}
      {groups.other.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <h3 style={{ fontSize: 15, marginBottom: 8, color: '#999' }}>❓ 其他问题</h3>
          {groups.other.map((g, i) => (
            <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #999', padding: 14, marginBottom: 10 }}>
              {g.questions?.map((q, j) => <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#555' }}>• {q}</div>)}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
