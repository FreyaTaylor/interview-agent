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

      {(() => {
        const byProject = {}
        projects.forEach((g, i) => {
          const name = g.project_name || '未命名项目'
          if (!byProject[name]) byProject[name] = []
          byProject[name].push({ ...g, _idx: i })
        })
        return Object.entries(byProject).map(([projName, topics]) => (
          <div key={projName} style={{ marginBottom: 20 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 0', cursor: 'pointer', borderBottom: '2px solid #f0e6ff' }}
                 onClick={() => toggle(`proj_${projName}`)}>
              <span style={{ fontSize: 16, color: '#722ed1' }}>{expanded[`proj_${projName}`] === false ? '▶' : '▼'}</span>
              <span style={{ fontSize: 17, fontWeight: 700, color: '#333' }}>📁 {projName}</span>
              <span style={{ fontSize: 13, color: '#999' }}>{topics.length} 个话题 · {topics.reduce((s, t) => s + (t.questions?.length || 0), 0)} 个问题</span>
            </div>
            {expanded[`proj_${projName}`] !== false && topics.map((g) => {
              const i = g._idx; const sr = g.score_result
              const isOpen = expanded[i] !== false
              const stars = sr ? '⭐'.repeat(Math.min(sr.rating || 3, 5)) : ''
              return (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: 14, marginTop: 10, marginLeft: 20 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', padding: '4px 0' }} onClick={() => toggle(i)}>
                    <span style={{ color: '#722ed1', fontSize: 14, width: 20, textAlign: 'center' }}>{isOpen ? '▼' : '▶'}</span>
                    <span style={{ fontSize: 15, fontWeight: 600, flex: 1 }}>{g.topic || '拷打'}</span>
                    <span style={{ color: '#888', fontSize: 13 }}>{g.questions?.length || 0} 个问题</span>
                    {sr && <span style={{ fontSize: 14 }}>{stars}</span>}
                  </div>
                  {isOpen && (
                    <div style={{ marginTop: 10, paddingLeft: 28 }}>
                      {g.original_dialogue && (
                        <div style={{ marginBottom: 10 }}>
                          <div style={{ padding: '6px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6 }}
                               onClick={e => { e.stopPropagation(); toggle(`d${i}`) }}>
                            <span style={{ color: '#aaa', fontSize: 12 }}>{expanded[`d${i}`] ? '▼' : '▶'}</span>
                            <span style={{ fontSize: 12, color: '#aaa' }}>📝 原始对话</span>
                          </div>
                          {expanded[`d${i}`] && (
                            <div style={{ fontSize: 13, color: '#555', padding: '10px 14px', background: '#f9fafb', borderRadius: '0 0 6px 6px', whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>{g.original_dialogue}</div>
                          )}
                        </div>
                      )}
                      <div style={{ marginBottom: 8 }}>
                        {g.questions?.map((q, j) => <div key={j} style={{ padding: '4px 0 4px 12px', fontSize: 13, color: '#555' }}>• {q}</div>)}
                      </div>
                      {g.user_answer && (
                        <div style={{ fontSize: 13, color: '#666', padding: '10px 14px', background: '#f0e6ff', borderLeft: '3px solid #722ed1', borderRadius: 6, marginBottom: 8 }}>
                          💬 <b>我的回答：</b>{g.user_answer}
                        </div>
                      )}
                      {sr && (
                        <div style={{ background: '#f9f0ff', borderRadius: 8, padding: '14px 16px', marginTop: 8 }}>
                          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>
                            {stars} <span style={{ color: '#722ed1' }}>{sr.rating_label}</span>
                          </div>
                          <div style={{ fontSize: 13, color: '#555', lineHeight: 1.8, marginBottom: 12, padding: '8px 12px', background: '#fff', borderRadius: 6, border: '1px solid #f0e6ff' }}>
                            💬 {sr.impression}
                          </div>
                          {sr.highlights?.length > 0 && (
                            <div style={{ marginBottom: 10 }}>
                              <div style={{ fontSize: 13, fontWeight: 500, color: '#52c41a', marginBottom: 4 }}>✅ 亮点</div>
                              {sr.highlights.map((h, k) => <div key={k} style={{ fontSize: 13, color: '#555', padding: '2px 0 2px 16px' }}>• {h}</div>)}
                            </div>
                          )}
                          {sr.improvements?.length > 0 && (
                            <div style={{ marginBottom: 10 }}>
                              <div style={{ fontSize: 13, fontWeight: 500, color: '#fa8c16', marginBottom: 4 }}>💡 改进建议</div>
                              {sr.improvements.map((h, k) => <div key={k} style={{ fontSize: 13, color: '#555', padding: '2px 0 2px 16px' }}>• {h}</div>)}
                            </div>
                          )}
                          {sr.follow_up_risks?.length > 0 && (
                            <div style={{ marginBottom: 10 }}>
                              <div style={{ fontSize: 13, fontWeight: 500, color: '#ff4d4f', marginBottom: 4 }}>⚠️ 面试官可能继续追问</div>
                              {sr.follow_up_risks.map((h, k) => <div key={k} style={{ fontSize: 13, color: '#555', padding: '2px 0 2px 16px' }}>• {h}</div>)}
                            </div>
                          )}
                          {sr.suggested_answer?.length > 0 && (
                            <div style={{ background: '#fff', border: '1px solid #f0e6ff', borderRadius: 6, padding: '10px 14px' }}>
                              <div style={{ fontSize: 13, fontWeight: 500, color: '#722ed1', marginBottom: 6 }}>📖 建议下次这样回答</div>
                              {sr.suggested_answer.map((p, j) => <div key={j} style={{ fontSize: 13, color: '#555', padding: '2px 0' }}>{j + 1}. {p}</div>)}
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
        ))
      })()}
    </div>
  )
}
