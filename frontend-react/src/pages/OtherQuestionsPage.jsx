/**
 * 面试其他问题页 — HR题、算法题、反问、其他问题
 * 只记录问题，不评分
 */
import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

export default function OtherQuestionsPage() {
  const [groups, setGroups] = useState({ algorithm: [], hr: [], other: [] })
  const [company, setCompany] = useState('')

  useEffect(() => {
    const stored = sessionStorage.getItem('interview_result')
    if (stored) {
      const data = JSON.parse(stored)
      const all = data.groups || []
      setGroups({
        algorithm: all.filter(g => g.type === 'algorithm'),
        hr: all.filter(g => g.type === 'hr'),
        other: all.filter(g => g.type === 'other'),
      })
      setCompany(data.company || '')
    }
  }, [])

  const hasContent = groups.algorithm.length + groups.hr.length + groups.other.length > 0

  if (!hasContent) return (
    <div>
      <Link to="/interview" style={{ color: '#1677ff', fontSize: 14 }}>← 返回面试复盘</Link>
      <div className="empty">暂无其他面试问题</div>
    </div>
  )

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Link to="/interview" style={{ color: '#1677ff', fontSize: 14 }}>← 返回</Link>
        <h2 style={{ fontSize: 18 }}>📎 面试其他问题 {company && `· ${company}`}</h2>
      </div>

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
                <div style={{ fontSize: 13, color: '#888', marginTop: 6, whiteSpace: 'pre-wrap' }}>{g.original_dialogue}</div>
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
              {g.questions?.map((q, j) => (
                <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#333' }}>• {q}</div>
              ))}
              {g.user_answer && (
                <div style={{ fontSize: 13, color: '#666', marginTop: 6, padding: '6px 12px', background: '#f6ffed', borderRadius: 4 }}>
                  💬 {g.user_answer}
                </div>
              )}
              {g.original_dialogue && (
                <div style={{ fontSize: 12, color: '#aaa', marginTop: 4, whiteSpace: 'pre-wrap' }}>{g.original_dialogue}</div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 其他问题 */}
      {groups.other.length > 0 && (
        <div style={{ marginBottom: 20 }}>
          <h3 style={{ fontSize: 15, marginBottom: 8, color: '#999' }}>❓ 其他问题</h3>
          {groups.other.map((g, i) => (
            <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #999', padding: 14, marginBottom: 10 }}>
              {g.questions?.map((q, j) => (
                <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#555' }}>• {q}</div>
              ))}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
