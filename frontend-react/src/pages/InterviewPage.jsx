import { useState } from 'react'

const API = 'http://127.0.0.1:8000/api'

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [expanded, setExpanded] = useState({})
  const [activeTab, setActiveTab] = useState('review')

  async function handleParse() {
    if (!text.trim() || loading) return
    setLoading(true); setResult(null)
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data); setExpanded({}); setActiveTab('review')
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
  const scoredGroups = result.groups.filter(g => g.type === 'knowledge' || g.type === 'project')
  const projectGroups = result.groups.filter(g => g.type === 'project')
  const algorithmGroups = result.groups.filter(g => g.type === 'algorithm')
  const hrGroups = result.groups.filter(g => g.type === 'hr')
  const otherGroups = result.groups.filter(g => g.type === 'other')
  const otherCount = algorithmGroups.length + hrGroups.length + otherGroups.length

  const tabs = [
    { key: 'review', label: '面试复盘', count: scoredGroups.length },
    { key: 'project', label: '🔨 项目拷打', count: projectGroups.length },
    { key: 'other', label: '📎 其他问题', count: otherCount },
  ]

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

      {/* ---- 原始文本（默认收起） ---- */}
      {result.missed_count > 0 && (
        <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '10px 16px', marginBottom: 12, fontSize: 13, color: '#ff4d4f' }}>
          ⚠️ 二次检查发现 {result.missed_count} 个可能遗漏的问题，已补充到"其他问题"中，请检查
        </div>
      )}
      <div style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', marginBottom: 16, overflow: 'hidden' }}>
        <div style={{ padding: '10px 16px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, borderBottom: expanded['raw'] ? '1px solid #eee' : 'none' }}
             onClick={() => toggle('raw')}>
          <span style={{ color: '#aaa', fontSize: 12 }}>{expanded['raw'] ? '▾' : '▸'}</span>
          <span style={{ fontSize: 13, color: '#888' }}>📄 查看原始文本</span>
        </div>
        {expanded['raw'] && (
          <div style={{ padding: '12px 16px', fontSize: 13, color: '#555', whiteSpace: 'pre-wrap', lineHeight: 1.8, maxHeight: 400, overflow: 'auto', background: '#fafbfc' }}>
            {text}
          </div>
        )}
      </div>

      {/* ---- Tab 栏 ---- */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '2px solid #eee', marginBottom: 16 }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
            padding: '10px 20px', fontSize: 14, fontWeight: activeTab === t.key ? 600 : 400,
            color: activeTab === t.key ? '#1677ff' : '#888', background: 'none', border: 'none',
            borderBottom: activeTab === t.key ? '2px solid #1677ff' : '2px solid transparent',
            marginBottom: -2, cursor: 'pointer', transition: 'all .2s',
          }}>
            {t.label}{t.count > 0 ? ` (${t.count})` : ''}
          </button>
        ))}
      </div>

      {/* ---- Tab: 面试复盘 — 所有评分项混合展示 ---- */}
      {activeTab === 'review' && scoredGroups.map((g, i) => {
        const sr = g.score_result; const isOpen = expanded[`r${i}`]
        const icon = g.type === 'project' ? '🔨' : '📖'
        const title = g.type === 'project' ? `${g.project_name || '项目'} · ${g.topic || '拷打'}` : g.knowledge_point
        return (
          <div className="result-group" key={`r${i}`} style={{ borderLeft: g.type === 'project' ? '3px solid #722ed1' : undefined }}>
            <div className="group-header" style={{ cursor: 'pointer' }} onClick={() => toggle(`r${i}`)}>
              <span style={{ color: '#aaa', fontSize: 12, marginRight: 4 }}>{isOpen ? '▾' : '▸'}</span>
              <span className="group-type">{icon}</span>
              <span className="group-title">
                {title}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
              </span>
              {sr && <span style={{ color: sc(sr.total_score), fontWeight: 600, fontSize: 15 }}>{sr.total_score}分</span>}
              <button className="study-btn" onClick={e => { e.stopPropagation(); toggle(`r${i}`) }}>{isOpen ? '收起' : '展开'}</button>
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
                  <div style={{ fontSize: 13, color: '#666', padding: '8px 14px', background: g.type === 'project' ? '#f0e6ff' : '#fff8e1', borderLeft: `3px solid ${g.type === 'project' ? '#722ed1' : '#fa8c16'}`, borderRadius: 6, marginBottom: 8 }}>
                    💬 我的回答：{g.user_answer}
                  </div>
                )}
                {sr && (
                  <div style={{ background: g.type === 'project' ? '#f9f0ff' : '#f6ffed', borderLeft: `3px solid ${g.type === 'project' ? '#722ed1' : '#52c41a'}`, borderRadius: 6, padding: '12px 14px' }}>
                    <div style={{ marginBottom: 8 }}><b>{g.type === 'project' ? '表达质量' : '得分'}: {sr.total_score}/100</b> — {sr.feedback}</div>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}><tbody>
                      {(sr.rubric_result || []).map((item, k) => (
                        <tr key={k} style={{ background: item.hit ? (g.type === 'project' ? '#f0e6ff' : '#e8f5e9') : '#ffebee', borderBottom: '1px solid #e0e0e0' }}>
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

      {/* ---- Tab: 项目拷打 ---- */}
      {activeTab === 'project' && (projectGroups.length === 0
        ? <div className="empty">暂无项目拷打记录</div>
        : projectGroups.map((g, i) => {
          const sr = g.score_result; const isOpen = expanded[`p${i}`] !== false
          return (
            <div key={`p${i}`} style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: 16, marginBottom: 14 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }} onClick={() => toggle(`p${i}`)}>
                <span style={{ color: '#aaa', fontSize: 12 }}>{isOpen ? '▾' : '▸'}</span>
                <span style={{ fontSize: 16, fontWeight: 600, flex: 1 }}>{g.project_name || '项目'} · {g.topic || '拷打'}</span>
                <span style={{ color: '#888', fontSize: 13 }}>{g.questions?.length || 0} 个问题</span>
                {sr && <span style={{ color: sc(sr.total_score), fontWeight: 600 }}>{sr.total_score}分</span>}
              </div>
              {isOpen && (
                <div style={{ marginTop: 12 }}>
                  {g.original_dialogue && (
                    <div style={{ fontSize: 13, color: '#555', padding: '10px 14px', background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6, marginBottom: 10, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                      <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4 }}>📝 原始对话</div>
                      {g.original_dialogue}
                    </div>
                  )}
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 13, fontWeight: 500, color: '#333', marginBottom: 6 }}>面试问题：</div>
                    {g.questions?.map((q, j) => <div key={j} style={{ padding: '4px 0 4px 16px', fontSize: 13, color: '#555' }}>• {q}</div>)}
                  </div>
                  {g.user_answer && (
                    <div style={{ fontSize: 13, color: '#666', padding: '10px 14px', background: '#f0e6ff', borderLeft: '3px solid #722ed1', borderRadius: 6, marginBottom: 8 }}>
                      💬 <b>我的回答：</b>{g.user_answer}
                    </div>
                  )}
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
        })
      )}

      {/* ---- Tab: 其他问题 ---- */}
      {activeTab === 'other' && (otherCount === 0
        ? <div className="empty">暂无其他面试问题</div>
        : <>
          {algorithmGroups.length > 0 && (
            <div style={{ marginBottom: 20 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8, color: '#fa8c16' }}>💻 算法题</h3>
              {algorithmGroups.map((g, i) => (
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
                  {g.original_dialogue && <div style={{ fontSize: 13, color: '#888', marginTop: 6, whiteSpace: 'pre-wrap' }}>{g.original_dialogue}</div>}
                </div>
              ))}
            </div>
          )}
          {hrGroups.length > 0 && (
            <div style={{ marginBottom: 20 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8, color: '#52c41a' }}>💬 HR / 行为题</h3>
              {hrGroups.map((g, i) => (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #52c41a', padding: 14, marginBottom: 10 }}>
                  {g.questions?.map((q, j) => <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#333' }}>• {q}</div>)}
                  {g.user_answer && (
                    <div style={{ fontSize: 13, color: '#666', marginTop: 6, padding: '6px 12px', background: '#f6ffed', borderRadius: 4 }}>💬 {g.user_answer}</div>
                  )}
                  {g.original_dialogue && <div style={{ fontSize: 12, color: '#aaa', marginTop: 4, whiteSpace: 'pre-wrap' }}>{g.original_dialogue}</div>}
                </div>
              ))}
            </div>
          )}
          {otherGroups.length > 0 && (
            <div style={{ marginBottom: 20 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8, color: '#999' }}>❓ 其他问题</h3>
              {otherGroups.map((g, i) => (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #999', padding: 14, marginBottom: 10 }}>
                  {g.questions?.map((q, j) => <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#555' }}>• {q}</div>)}
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <button className="parse-btn" onClick={() => { setResult(null); setText('') }} style={{ marginTop: 20 }}>📋 重新上传</button>
    </div>
  )
}
