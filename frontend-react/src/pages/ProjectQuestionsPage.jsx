/**
 * 项目拷打页 — 独立 Tab 页面，展示面试中所有项目相关问题+评分
 * 从 sessionStorage 读取最近一次面试复盘结果
 */
import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

export default function ProjectQuestionsPage() {
  const [projects, setProjects] = useState([])
  const [company, setCompany] = useState('')
  const [expanded, setExpanded] = useState({})

  useEffect(() => {
    const stored = sessionStorage.getItem('interview_result')
    if (stored) {
      const data = JSON.parse(stored)
      setProjects(data.groups?.filter(g => g.type === 'project') || [])
      setCompany(data.company || '')
    }
  }, [])

  const toggle = k => setExpanded(p => ({ ...p, [k]: !p[k] }))
  const sc = s => s >= 80 ? '#52c41a' : s >= 60 ? '#faad14' : '#ff4d4f'

  if (!projects.length) return (
    <div style={{ textAlign: 'center', padding: '60px 20px', color: '#aaa' }}>
      <div style={{ fontSize: 40, marginBottom: 12 }}>🔨</div>
      <div style={{ fontSize: 16, marginBottom: 8 }}>暂无项目拷打记录</div>
      <div style={{ fontSize: 13 }}>先到 <Link to="/interview" style={{ color: '#1677ff' }}>面试复盘</Link> 上传面试记录</div>
    </div>
  )

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>🔨 项目拷打 {company && <span style={{ fontSize: 14, color: '#999', fontWeight: 400 }}>· {company}</span>}</h2>

      {projects.map((g, i) => {
        const sr = g.score_result
        const isOpen = expanded[i] !== false // 默认展开
        return (
          <div key={i} style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: 16, marginBottom: 14 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }} onClick={() => toggle(i)}>
              <span style={{ color: '#aaa', fontSize: 12 }}>{isOpen ? '▾' : '▸'}</span>
              <span style={{ fontSize: 16, fontWeight: 600, flex: 1 }}>{g.project_name || '项目'} · {g.topic || '拷打'}</span>
              <span style={{ color: '#888', fontSize: 13 }}>{g.questions?.length || 0} 个问题</span>
              {sr && <span style={{ color: sc(sr.total_score), fontWeight: 600 }}>{sr.total_score}分</span>}
            </div>

            {isOpen && (
              <div style={{ marginTop: 12 }}>
                {/* 原始对话（默认收起） */}
                {g.original_dialogue && (
                  <div style={{ marginBottom: 10 }}>
                    <div style={{ padding: '6px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6 }}
                         onClick={e => { e.stopPropagation(); toggle(`d${i}`) }}>
                      <span style={{ color: '#aaa', fontSize: 11 }}>{expanded[`d${i}`] ? '▾' : '▸'}</span>
                      <span style={{ fontSize: 12, color: '#aaa' }}>📝 原始对话</span>
                    </div>
                    {expanded[`d${i}`] && (
                      <div style={{ fontSize: 13, color: '#555', padding: '10px 14px', background: '#f9fafb', borderRadius: '0 0 6px 6px', whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                        {g.original_dialogue}
                      </div>
                    )}
                  </div>
                )}

                {/* 问题列表 */}
                <div style={{ marginBottom: 8 }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: '#333', marginBottom: 6 }}>面试问题：</div>
                  {g.questions?.map((q, j) => <div key={j} style={{ padding: '4px 0 4px 16px', fontSize: 13, color: '#555' }}>• {q}</div>)}
                </div>

                {/* 我的回答 */}
                {g.user_answer && (
                  <div style={{ fontSize: 13, color: '#666', padding: '10px 14px', background: '#f0e6ff', borderLeft: '3px solid #722ed1', borderRadius: 6, marginBottom: 8 }}>
                    💬 <b>我的回答：</b>{g.user_answer}
                  </div>
                )}

                {/* 评分 */}
                {sr && (
                  <div style={{ background: '#f9f0ff', borderLeft: '3px solid #722ed1', borderRadius: 6, padding: '12px 14px' }}>
                    <div style={{ marginBottom: 8 }}><b>表达质量: {sr.total_score}/100</b> — {sr.feedback}</div>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}><tbody>
                      {(sr.rubric_result || []).map((item, k) => (
                        <tr key={k} style={{ background: item.hit ? '#f0e6ff' : '#fff2f0', borderBottom: '1px solid #e0e0e0' }}>
                          <td style={{ padding: '4px 8px' }}>
                            {item.hit ? '✅' : '❌'} <b>{item.key_point}</b>（{item.score}分）
                            {item.matched_text && <span style={{ color: '#666', fontStyle: 'italic' }}> 「{item.matched_text}」</span>}
                          </td>
                        </tr>
                      ))}
                    </tbody></table>
                    {sr.recommended_answer && Array.isArray(sr.recommended_answer) && sr.recommended_answer.length > 0 && (
                      <div style={{ marginTop: 8, fontSize: 13 }}>
                        📖 <b>推荐表达：</b> {sr.recommended_answer.map((p, j) => <div key={j}>{j + 1}. {p}</div>)}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}
