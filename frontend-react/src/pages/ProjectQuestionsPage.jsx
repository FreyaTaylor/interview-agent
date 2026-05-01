/**
 * 项目拷打页 — 独立 Tab 页面，累积所有面试的项目问题
 * 从后端 DB 读取（跨会话持久化，每次复盘自动 merge）
 * 只展示：问题树状分类 + 推荐回答
 */
import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

export default function ProjectQuestionsPage() {
  const [projects, setProjects] = useState([])
  const [expanded, setExpanded] = useState({})

  useEffect(() => {
    fetch(`${API}/interview/project-questions`)
      .then(r => r.json())
      .then(resp => { if (resp.code === 0) setProjects(resp.data || []) })
      .catch(() => {})
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
      <h2 style={{ marginBottom: 16 }}>🔨 项目拷打</h2>

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
              const isOpen = expanded[i]
              return (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: 14, marginTop: 10, marginLeft: 20 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', padding: '4px 0' }} onClick={() => toggle(i)}>
                    <span style={{ color: '#722ed1', fontSize: 14, width: 20, textAlign: 'center' }}>{isOpen ? '▼' : '▶'}</span>
                    <span style={{ fontSize: 15, fontWeight: 600, flex: 1 }}>{g.topic || '拷打'}</span>
                    <span style={{ color: '#888', fontSize: 13 }}>{g.questions?.length || 0} 个问题</span>
                  </div>
                  {isOpen && (
                    <div style={{ marginTop: 10, paddingLeft: 28 }}>
                      {/* 问题列表 */}
                      <div style={{ marginBottom: 8 }}>
                        {g.questions?.map((q, j) => <div key={j} style={{ padding: '4px 0 4px 12px', fontSize: 13, color: '#555' }}>• {q}</div>)}
                      </div>
                      {/* 推荐回答 */}
                      {sr?.suggested_answer?.length > 0 && (
                        <div style={{ background: '#f9f0ff', border: '1px solid #f0e6ff', borderRadius: 6, padding: '10px 14px', marginTop: 8 }}>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#722ed1', marginBottom: 6 }}>📖 建议回答</div>
                          {sr.suggested_answer.map((p, j) => <div key={j} style={{ fontSize: 13, color: '#555', padding: '2px 0' }}>{j + 1}. {p}</div>)}
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
