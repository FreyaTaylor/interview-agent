import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [studyLoading, setStudyLoading] = useState(null)  // 正在学习的知识点 index
  const navigate = useNavigate()

  async function handleParse() {
    if (!text.trim() || loading) return
    setLoading(true)
    setResult(null)
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data)
      } else {
        alert(resp.message || '解析失败')
      }
    } catch (e) {
      alert('请求失败，请确保后端已启动')
    }
    setLoading(false)
  }

  async function startStudyWithAnswer(group, idx) {
    setStudyLoading(idx)
    try {
      const resp = await fetch(`${API}/study/start-with-answer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          knowledge_point_id: group.matched_node_id,
          user_answer: group.user_answer || '',
          interview_questions: group.questions || [],
        }),
      }).then(r => r.json())

      if (resp.code === 0) {
        // 把评分结果存到 sessionStorage，学习页读取
        sessionStorage.setItem('interview_study_result', JSON.stringify(resp.data))
        navigate(`/study/${group.matched_node_id}`)
      }
    } catch (e) {
      alert('请求失败')
    }
    setStudyLoading(null)
  }

  return (
    <div>
      {!result ? (
        /* ---- Step 1: 上传面试文本 ---- */
        <div className="interview-upload">
          <h2>📋 面试复盘</h2>
          <p style={{ color: '#888', marginBottom: 16 }}>
            粘贴面试记录文本 → 系统自动解析提问 → 聚类知识点 → 逐个复盘学习
          </p>

          <div className="form-row">
            <input className="form-input" placeholder="公司（可选）" value={company}
                   onChange={e => setCompany(e.target.value)} style={{ flex: 1 }} />
            <input className="form-input" placeholder="岗位（可选）" value={position}
                   onChange={e => setPosition(e.target.value)} style={{ flex: 1 }} />
          </div>

          <textarea
            className="form-textarea"
            placeholder={'粘贴面试记录文本...\n\n示例：\n面试官问了分布式锁怎么实现，我说了Redis SETNX，然后追问看门狗原理...\n后来问了TCP三次握手...\n最后手撕了一道LRU...'}
            value={text}
            onChange={e => setText(e.target.value)}
            rows={12}
          />

          <div style={{ marginTop: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
            <button className="parse-btn" onClick={handleParse} disabled={!text.trim() || loading}>
              {loading ? '🧠 解析中...' : '🔍 开始解析'}
            </button>
            <span style={{ color: '#aaa', fontSize: 13 }}>
              支持语音转写文本，系统容忍错别字
            </span>
          </div>
        </div>
      ) : (
        /* ---- Step 2: 解析结果 ---- */
        <div className="interview-result">
          <h2>📋 解析结果</h2>
          <p className="result-summary">{result.summary}</p>

          <div className="result-stats">
            <span className="stat-badge know">📖 知识点 {result.stats.knowledge} 个</span>
            <span className="stat-badge algo">💻 算法题 {result.stats.algorithm} 个</span>
            <span className="stat-badge hr">💬 HR题 {result.stats.hr} 个</span>
          </div>

          {/* 知识点组 */}
          {result.groups.filter(g => g.type === 'knowledge').map((g, i) => (
            <div className="result-group knowledge" key={i}>
              <div className="group-header">
                <span className="group-type">📖</span>
                <span className="group-title">
                  {g.knowledge_point}
                  {g.auto_created && <span style={{fontSize:11,color:'#fa8c16',marginLeft:6}}>新增</span>}
                </span>
                <span className="group-count">{g.questions.length} 个问题</span>
                <button className="study-btn" disabled={studyLoading === i}
                        onClick={() => startStudyWithAnswer(g, i)}>
                  {studyLoading === i ? '出题中...' : '开始学习'}
                </button>
              </div>
              <ul className="group-questions">
                {g.questions.map((q, j) => <li key={j}>{q}</li>)}
              </ul>
              {g.user_answer && (
                <div style={{fontSize:13,color:'#666',marginTop:6,padding:'6px 12px',background:'#f9f9fb',borderRadius:6}}>
                  💬 我的回答：{g.user_answer}
                </div>
              )}
            </div>
          ))}

          {/* 算法题 */}
          {result.groups.filter(g => g.type === 'algorithm').map((g, i) => (
            <div className="result-group algorithm" key={`a${i}`}>
              <div className="group-header">
                <span className="group-type">💻</span>
                <span className="group-title">{g.title}</span>
                {g.leetcode_id && (
                  <a href={`https://leetcode.cn/problems/`} target="_blank" rel="noreferrer"
                     className="lc-link">LeetCode #{g.leetcode_id}</a>
                )}
              </div>
            </div>
          ))}

          {/* HR题 */}
          {result.groups.filter(g => g.type === 'hr').map((g, i) => (
            <div className="result-group hr" key={`h${i}`}>
              <div className="group-header">
                <span className="group-type">💬</span>
                <span className="group-title">HR 题</span>
              </div>
              <ul className="group-questions">
                {g.questions.map((q, j) => <li key={j}>{q}</li>)}
              </ul>
            </div>
          ))}

          <button className="parse-btn" onClick={() => { setResult(null); setText('') }}
                  style={{ marginTop: 20 }}>
            📋 重新上传
          </button>
        </div>
      )}
    </div>
  )
}
