import { useState } from 'react'
import { Link } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api'

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [expanded, setExpanded] = useState({})

  async function handleParse() {
    if (!text.trim() || loading) return
    setLoading(true); setResult(null)
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data); setExpanded({})
        // 存到 sessionStorage 供子页面读取
        sessionStorage.setItem('interview_result', JSON.stringify({ ...resp.data, company, position }))
      }
      else alert(resp.message || '解析失败')
    } catch { alert('请求失败') }
    setLoading(false)
  }

  const toggle = i => setExpanded(p => ({ ...p, [i]: !p[i] }))
  const sc = s => s >= 80 ? '#52c41a' : s >= 60 ? '#faad14' : '#ff4d4f'

  // ---- 输入页 ----
  if (!result) return (
    <div className="interview-upload">
      <h2>📋 面试复盘</h2>
      <p style={{ color: '#888', marginBottom: 16 }}>粘贴面试记录 → 自动解析+评分 → 查看薄弱点</p>
      <div className="form-row">
        <input className="form-input" placeholder="公司（可选）" value={company} onChange={e => setCompany(e.target.value)} style={{ flex: 1 }} />
        <input className="form-input" placeholder="岗位（可选）" value={position} onChange={e => setPosition(e.target.value)} style={{ flex: 1 }} />
      </div>
      <textarea className="form-textarea" rows={12} value={text} onChange={e => setText(e.target.value)}
        placeholder={'粘贴面试记录...\n\n示例：\n面试官问了分布式锁怎么实现，我说了SETNX加过期时间，追问看门狗我没答上来。\n然后聊了我的订单系统项目，问超时取消怎么做的。\n手撕了LRU。问了离职原因。\n中间他接了个电话等了一会。'} />
      <div style={{ marginTop: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
        <button className="parse-btn" onClick={handleParse} disabled={!text.trim() || loading}>
          {loading ? '🧠 解析+评分中...' : '🔍 开始解析'}
        </button>
      </div>
    </div>
  )

  // ---- 结果页 ----
  const knowledgeGroups = result.groups.filter(g => g.type === 'knowledge')
  const projectGroups = result.groups.filter(g => g.type === 'project')
  const algorithmGroups = result.groups.filter(g => g.type === 'algorithm')
  const hrGroups = result.groups.filter(g => g.type === 'hr')
  const otherGroups = result.groups.filter(g => g.type === 'other')

  return (
    <div className="interview-result">
      {/* ---- 汇总 ---- */}
      <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', padding: 20, marginBottom: 20 }}>
        <h2 style={{ marginBottom: 8 }}>📋 面试复盘 {company && `· ${company}`} {position}</h2>
        <p style={{ color: '#666', marginBottom: 12 }}>{result.summary}</p>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="stat-badge know">📖 知识点 {result.stats.knowledge}</span>
          {result.stats.project > 0 && <span className="stat-badge" style={{ background: '#f0e6ff', color: '#722ed1' }}>🔨 项目 {result.stats.project}</span>}
          <span className="stat-badge algo">💻 算法 {result.stats.algorithm}</span>
          <span className="stat-badge hr">💬 HR {result.stats.hr}</span>
          <span style={{ marginLeft: 'auto', fontSize: 15 }}>
            平均分 <b style={{ color: sc(result.avg_score), fontSize: 22 }}>{result.avg_score}</b>/100
          </span>
          <span style={{
            padding: '4px 12px', borderRadius: 20, fontSize: 13, fontWeight: 500,
            background: result.avg_score >= 70 ? '#f6ffed' : result.avg_score >= 50 ? '#fffbe6' : '#fff2f0',
            color: sc(result.avg_score),
          }}>通过概率: {result.pass_estimate}</span>
        </div>
      </div>

      {/* ---- 原始文本（可折叠） ---- */}
      {result.missed_count > 0 && (
        <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '10px 16px', marginBottom: 12, fontSize: 13, color: '#ff4d4f' }}>
          ⚠️ 二次检查发现 {result.missed_count} 个可能遗漏的问题，已补充到"其他问题"中，请检查
        </div>
      )}
      <div style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', marginBottom: 16, overflow: 'hidden' }}>
        <div style={{ padding: '10px 16px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, borderBottom: expanded['raw'] ? '1px solid #eee' : 'none' }}
             onClick={() => toggle('raw')}>
          <span style={{ color: '#aaa', fontSize: 12 }}>{expanded['raw'] ? '▾' : '▸'}</span>
          <span style={{ fontSize: 13, color: '#888' }}>📄 查看原始文本（校验解析是否完整）</span>
        </div>
        {expanded['raw'] && (
          <div style={{ padding: '12px 16px', fontSize: 13, color: '#555', whiteSpace: 'pre-wrap', lineHeight: 1.8, maxHeight: 400, overflow: 'auto', background: '#fafbfc' }}>
            {text}
          </div>
        )}
      </div>

      {/* ---- 知识点（可展开评分，不跳转） ---- */}
      {knowledgeGroups.length > 0 && <h3 style={{ margin: '16px 0 8px', fontSize: 15 }}>📖 技术知识点</h3>}
      {result.groups.map((g, i) => {
        if (g.type !== 'knowledge') return null
        const sr = g.score_result; const isOpen = expanded[i]
        return (
          <div className="result-group knowledge" key={i}>
            <div className="group-header" style={{ cursor: 'pointer' }} onClick={() => toggle(i)}>
              <span style={{ color: '#aaa', fontSize: 12, marginRight: 4 }}>{isOpen ? '▾' : '▸'}</span>
              <span className="group-type">📖</span>
              <span className="group-title">
                {g.knowledge_point}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
              </span>
              {sr && <span style={{ color: sc(sr.total_score), fontWeight: 600, fontSize: 15 }}>{sr.total_score}分</span>}
              <button className="study-btn" onClick={e => { e.stopPropagation(); toggle(i) }}>{isOpen ? '收起' : '展开'}</button>
            </div>
            {isOpen && (
              <div style={{ marginTop: 8 }}>
                {g.original_dialogue && (
                  <div style={{ fontSize: 13, color: '#555', padding: '10px 14px', background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6, marginBottom: 10, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                    <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4 }}>📝 原始对话</div>
                    {g.original_dialogue}
                  </div>
                )}
                {g.user_answer && (
                  <div style={{ fontSize: 13, color: '#666', padding: '8px 14px', background: '#fff8e1', borderLeft: '3px solid #fa8c16', borderRadius: 6, marginBottom: 8 }}>
                    💬 我的回答：{g.user_answer}
                  </div>
                )}
                {sr && (
                  <div style={{ background: '#f6ffed', borderLeft: '3px solid #52c41a', borderRadius: 6, padding: '12px 14px' }}>
                    <div style={{ marginBottom: 8 }}><b>得分: {sr.total_score}/100</b> — {sr.feedback}</div>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}><tbody>
                      {(sr.rubric_result || []).map((item, k) => (
                        <tr key={k} style={{ background: item.hit ? '#e8f5e9' : '#ffebee', borderBottom: '1px solid #e0e0e0' }}>
                          <td style={{ padding: '4px 8px' }}>
                            {item.hit ? '✅' : '❌'} <b>{item.key_point}</b>（{item.score}分）
                            {item.matched_text && <span style={{ color: '#666', fontStyle: 'italic' }}> 「{item.matched_text}」</span>}
                          </td>
                        </tr>
                      ))}
                    </tbody></table>
                    {sr.recommended_answer && Array.isArray(sr.recommended_answer) && sr.recommended_answer.length > 0 && (
                      <div style={{ marginTop: 8, fontSize: 13 }}>
                        📖 <b>推荐回答</b>: {sr.recommended_answer.map((p, j) => <div key={j}>{j + 1}. {p}</div>)}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        )
      })}

      {/* ---- 项目拷打 + 其他问题 跳转卡片 ---- */}
      <div style={{ display: 'flex', gap: 14, marginTop: 20 }}>
        {projectGroups.length > 0 && (
          <Link to="/interview/projects" style={{ flex: 1, textDecoration: 'none' }}>
            <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: '16px 20px' }}>
              <div style={{ fontSize: 16, fontWeight: 600, color: '#722ed1', marginBottom: 4 }}>🔨 项目拷打</div>
              <div style={{ fontSize: 13, color: '#888' }}>{projectGroups.length} 个项目 · {projectGroups.reduce((s, g) => s + (g.questions?.length || 0), 0)} 个问题</div>
              <div style={{ fontSize: 12, color: '#aaa', marginTop: 4 }}>点击查看详情 + 评分 →</div>
            </div>
          </Link>
        )}
        {(algorithmGroups.length > 0 || hrGroups.length > 0 || otherGroups.length > 0) && (
          <Link to="/interview/others" style={{ flex: 1, textDecoration: 'none' }}>
            <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', borderLeft: '4px solid #999', padding: '16px 20px' }}>
              <div style={{ fontSize: 16, fontWeight: 600, color: '#555', marginBottom: 4 }}>📎 其他问题</div>
              <div style={{ fontSize: 13, color: '#888' }}>
                {algorithmGroups.length > 0 && \`💻算法${algorithmGroups.length} \`}
                {hrGroups.length > 0 && \`💬HR${hrGroups.length} \`}
                {otherGroups.length > 0 && \`❓其他${otherGroups.length}\`}
              </div>
              <div style={{ fontSize: 12, color: '#aaa', marginTop: 4 }}>点击查看详情 →</div>
            </div>
          </Link>
        )}
      </div>

      <button className="parse-btn" onClick={() => { setResult(null); setText('') }} style={{ marginTop: 20 }}>📋 重新上传</button>
    </div>
  )
}
