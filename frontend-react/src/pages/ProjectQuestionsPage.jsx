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
    <div className="tree-card">
      {(() => {
        const byProject = {}
        projects.forEach((g, i) => {
          const name = g.project_name || '未命名项目'
          if (!byProject[name]) byProject[name] = []
          byProject[name].push({ ...g, _idx: i })
        })
        return Object.entries(byProject).map(([projName, topics]) => (
          <div key={projName} className="tree-node">
            {/* 项目名（一级） */}
            <div className="node-row cat" style={{ paddingLeft: 16, cursor: 'pointer' }} onClick={() => toggle(`proj_${projName}`)}>
              <span className={`toggle ${expanded[`proj_${projName}`] === false ? '' : 'open'}`} />
              <span className="node-name">{projName}</span>
              <span style={{ fontSize: 12, color: '#999', marginLeft: 8 }}>{topics.length} 个话题</span>
            </div>
            {expanded[`proj_${projName}`] !== false && topics.map((g) => {
              const i = g._idx; const sr = g.score_result
              const isOpen = expanded[i]
              return (
                <div key={i} className="tree-node">
                  {/* 话题（二级） */}
                  <div className="node-row" style={{ paddingLeft: 38, cursor: 'pointer' }} onClick={() => toggle(i)}>
                    {(g.questions?.length > 0 || sr) ? (
                      <span className={`toggle ${isOpen ? 'open' : ''}`} />
                    ) : (
                      <span className="bullet" />
                    )}
                    <span className="node-name">{g.topic || '拷打'}</span>
                    <span style={{ fontSize: 12, color: '#aaa', marginLeft: 8 }}>{g.questions?.length || 0} 问</span>
                  </div>
                  {isOpen && (
                    <div>
                      {/* 问题（三级） */}
                      {g.questions?.map((q, j) => (
                        <div key={j} className="tree-node">
                          <div className="node-row" style={{ paddingLeft: 60 }}>
                            <span className="bullet" />
                            <span style={{ fontSize: 13, color: '#555' }}>{q}</span>
                          </div>
                        </div>
                      ))}
                      {/* 建议回答 */}
                      {sr?.suggested_answer?.length > 0 && (
                        <div style={{ marginLeft: 60, marginTop: 4, marginBottom: 8, padding: '8px 12px', background: '#f9f0ff', borderRadius: 6, fontSize: 13 }}>
                          <b style={{ color: '#722ed1' }}>📖 建议回答</b>
                          {sr.suggested_answer.map((p, j) => <div key={j} style={{ color: '#555', padding: '2px 0' }}>{j + 1}. {p}</div>)}
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
