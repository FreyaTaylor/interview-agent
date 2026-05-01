import { useState } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [expanded, setExpanded] = useState({})  // index → bool

  async function handleParse() {
    if (!text.trim() || loading) return
    setLoading(true)
    setResult(null)
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data)
        // 默认全部收起，用户点击展开
        setExpanded({})
      } else {
        alert(resp.message || '解析失败')
      }
    } catch {
      alert('请求失败，请确保后端已启动')
    }
    setLoading(false)
  }

  function toggle(i) {
    setExpanded(prev => ({ ...prev, [i]: !prev[i] }))
  }

  function scoreColor(s) {
    if (s >= 80) return '#52c41a'
    if (s >= 60) return '#faad14'
    return '#ff4d4f'
  }

  if (!result) {
    return (
      <div className="interview-upload">
        <h2>📋 面试复盘</h2>
        <p style={{ color: '#888', marginBottom: 16 }}>
          粘贴面试记录 → 自动解析提问 → 聚类知识点 → 逐个评分 → 给出通过率
        </p>
        <div className="form-row">
          <input className="form-input" placeholder="公司（可选）" value={company}
                 onChange={e => setCompany(e.target.value)} style={{ flex: 1 }} />
          <input className="form-input" placeholder="岗位（可选）" value={position}
                 onChange={e => setPosition(e.target.value)} style={{ flex: 1 }} />
        </div>
        <textarea className="form-textarea" rows={12} value={text} onChange={e => setText(e.target.value)}
          placeholder={'粘贴面试记录...\n\n示例：\n面试官问了分布式锁怎么实现，我说了SETNX加过期时间，追问看门狗原理我没答上来。\n然后问了HashMap原理，我说了数组加链表加红黑树。\n手撕了LRU。问了为什么离职。'} />
        <div style={{ marginTop: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
          <button className="parse-btn" onClick={handleParse} disabled={!text.trim() || loading}>
            {loading ? '🧠 解析+评分中（约10秒）...' : '🔍 开始解析'}
          </button>
          <span style={{ color: '#aaa', fontSize: 13 }}>解析+评分一步完成</span>
        </div>
      </div>
    )
  }

  // 计算统计
  const knowledgeGroups = result.groups.filter(g => g.type === 'knowledge')
  const scoredGroups = knowledgeGroups.filter(g => g.score_result)

  return (
    <div className="interview-result">
      {/* ---- 汇总卡片 ---- */}
      <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', padding: 20, marginBottom: 20 }}>
        <h2 style={{ marginBottom: 12 }}>📋 面试复盘 {company && `· ${company}`} {position && position}</h2>
        <p style={{ color: '#666', marginBottom: 16 }}>{result.summary}</p>
        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="stat-badge know">📖 知识点 {result.stats.knowledge} 个</span>
          <span className="stat-badge algo">💻 算法题 {result.stats.algorithm} 个</span>
          <span className="stat-badge hr">💬 HR题 {result.stats.hr} 个</span>
          <span style={{ marginLeft: 'auto', fontSize: 15 }}>
            平均分 <b style={{ color: scoreColor(result.avg_score), fontSize: 22 }}>{result.avg_score}</b>/100
          </span>
          <span style={{
            padding: '4px 12px', borderRadius: 20, fontSize: 13, fontWeight: 500,
            background: result.avg_score >= 70 ? '#f6ffed' : result.avg_score >= 50 ? '#fffbe6' : '#fff2f0',
            color: result.avg_score >= 70 ? '#52c41a' : result.avg_score >= 50 ? '#faad14' : '#ff4d4f',
          }}>
            通过概率: {result.pass_estimate}
          </span>
        </div>
      </div>

      {/* ---- 知识点卡片 ---- */}
      {result.groups.map((g, i) => {
        if (g.type !== 'knowledge') return null
        const sr = g.score_result
        const isOpen = expanded[i]

        return (
          <div className="result-group knowledge" key={i} style={{ cursor: 'pointer' }}>
            <div className="group-header" onClick={() => toggle(i)}>
              <span style={{ color: '#aaa', fontSize: 12, marginRight: 4 }}>{isOpen ? '▾' : '▸'}</span>
              <span className="group-type">📖</span>
              <span className="group-title">
                {g.knowledge_point}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
              </span>
              {sr && (
                <span style={{ color: scoreColor(sr.total_score), fontWeight: 600, fontSize: 15 }}>
                  {sr.total_score}分
                </span>
              )}
              <Link to={`/study/${g.matched_node_id}`} className="study-btn"
                    onClick={e => e.stopPropagation()}>
                深入学习
              </Link>
            </div>

            {isOpen && (
              <div style={{ marginTop: 8 }}>
                {/* 原始对话片段（校验用） */}
                {g.original_dialogue && (
                  <div style={{
                    fontSize: 13, color: '#555', margin: '0 0 10px 0', padding: '10px 14px',
                    background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6,
                    whiteSpace: 'pre-wrap', lineHeight: 1.7,
                  }}>
                    <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4 }}>📝 原始对话</div>
                    {g.original_dialogue}
                  </div>
                )}
                <ul className="group-questions">
                  {g.questions.map((q, j) => <li key={j}>{q}</li>)}
                </ul>

                {/* 我的回答 */}
                {g.user_answer && (
                  <div style={{ fontSize: 13, color: '#666', margin: '8px 0', padding: '8px 14px', background: '#fff8e1', borderLeft: '3px solid #fa8c16', borderRadius: 6 }}>
                    💬 我的回答：{g.user_answer}
                  </div>
                )}

                {/* 评分结果 */}
                {sr && (
                  <div style={{ background: '#f6ffed', borderLeft: '3px solid #52c41a', borderRadius: 6, padding: '12px 14px', margin: '8px 0' }}>
                    <div style={{ marginBottom: 8 }}>
                      <b>得分: {sr.total_score}/100</b> — {sr.feedback}
                    </div>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                      <tbody>
                        {(sr.rubric_result || []).map((item, k) => (
                          <tr key={k} style={{ background: item.hit ? '#e8f5e9' : '#ffebee', borderBottom: '1px solid #e0e0e0' }}>
                            <td style={{ padding: '4px 8px' }}>
                              {item.hit ? '✅' : '❌'} <b>{item.key_point}</b>（{item.score}分）
                              {item.matched_text && (
                                <span style={{ color: '#666', fontStyle: 'italic' }}> 「{item.matched_text}」</span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {sr.recommended_answer && Array.isArray(sr.recommended_answer) && sr.recommended_answer.length > 0 && (
                      <div style={{ marginTop: 8, fontSize: 13 }}>
                        📖 <b>推荐回答</b>:
                        {sr.recommended_answer.map((p, j) => <div key={j}>{j + 1}. {p}</div>)}
                      </div>
                    )}
                  </div>
                )}

                {!sr && g.user_answer && (
                  <div style={{ color: '#aaa', fontSize: 13, padding: 8 }}>评分未完成</div>
                )}
              </div>
            )}
          </div>
        )
      })}

      {/* ---- 算法题 ---- */}
      {result.groups.filter(g => g.type === 'algorithm').map((g, i) => (
        <div className="result-group algorithm" key={`a${i}`}>
          <div className="group-header">
            <span className="group-type">💻</span>
            <span className="group-title">{g.title}</span>
            {g.leetcode_id && (
              <a href={`https://leetcode.cn/problems/`} target="_blank" rel="noreferrer" className="lc-link">
                LeetCode #{g.leetcode_id}
              </a>
            )}
          </div>
          {g.original_dialogue && (
            <div style={{ fontSize: 13, color: '#555', padding: '6px 14px', background: '#f9fafb', borderRadius: 4, margin: '6px 0', whiteSpace: 'pre-wrap' }}>
              {g.original_dialogue}
            </div>
          )}
        </div>
      ))}

      {/* ---- HR题 ---- */}
      {result.groups.filter(g => g.type === 'hr').map((g, i) => (
        <div className="result-group hr" key={`h${i}`}>
          <div className="group-header">
            <span className="group-type">💬</span>
            <span className="group-title">HR 题</span>
          </div>
          <ul className="group-questions">
            {g.questions.map((q, j) => <li key={j}>{q}</li>)}
          </ul>
          {g.original_dialogue && (
            <div style={{ fontSize: 13, color: '#555', padding: '6px 14px', background: '#f9fafb', borderRadius: 4, margin: '6px 0', whiteSpace: 'pre-wrap' }}>
              {g.original_dialogue}
            </div>
          )}
        </div>
      ))}

      <button className="parse-btn" onClick={() => { setResult(null); setText('') }} style={{ marginTop: 20 }}>
        📋 重新上传
      </button>
    </div>
  )
}
