import { useState } from 'react'

const API = 'http://127.0.0.1:8000/api'

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState(() => sessionStorage.getItem('iv_company') || '')
  const [position, setPosition] = useState(() => sessionStorage.getItem('iv_position') || '')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(() => {
    const s = sessionStorage.getItem('iv_result')
    return s ? JSON.parse(s) : null
  })
  const [expanded, setExpanded] = useState({})
  const [activeTab, setActiveTab] = useState(() => sessionStorage.getItem('iv_tab') || 'knowledge')

  const [error, setError] = useState('')

  // 将新解析的 project/other 数据 merge 进 localStorage（跨会话持久化）
  function mergeToLocalStorage(data) {
    // ---- 项目拷打 merge ----
    const newProjects = data.groups?.filter(g => g.type === 'project') || []
    if (newProjects.length > 0) {
      const stored = JSON.parse(localStorage.getItem('project_questions') || '[]')
      const merged = [...stored]
      for (const np of newProjects) {
        const name = (np.project_name || '').toLowerCase().trim()
        const topic = (np.topic || '').toLowerCase().trim()
        // 按 project_name + topic 语义匹配（简单字符串匹配，LLM 合并已在后端做过）
        const existing = merged.find(m =>
          (m.project_name || '').toLowerCase().trim() === name &&
          (m.topic || '').toLowerCase().trim() === topic
        )
        if (existing) {
          // 合并 questions 去重
          const qSet = new Set(existing.questions || [])
          for (const q of (np.questions || [])) qSet.add(q)
          existing.questions = [...qSet]
          // 更新评分（用最新的）
          if (np.score_result) existing.score_result = np.score_result
          if (np.user_answer) existing.user_answer = np.user_answer
          if (np.original_dialogue) existing.original_dialogue = np.original_dialogue
        } else {
          merged.push(np)
        }
      }
      localStorage.setItem('project_questions', JSON.stringify(merged))
    }

    // ---- 其他问题 merge ----
    const newOthers = data.groups?.filter(g => ['algorithm', 'hr', 'other'].includes(g.type)) || []
    if (newOthers.length > 0) {
      const stored = JSON.parse(localStorage.getItem('other_questions') || '[]')
      const merged = [...stored]
      for (const no of newOthers) {
        // 算法题按 title 去重，HR/other 按 questions 去重
        if (no.type === 'algorithm') {
          const t = (no.title || '').toLowerCase().trim()
          if (!merged.some(m => m.type === 'algorithm' && (m.title || '').toLowerCase().trim() === t)) {
            merged.push(no)
          }
        } else {
          // HR/other: 合并 questions 到同类型的第一个组
          const existing = merged.find(m => m.type === no.type)
          if (existing) {
            const qSet = new Set(existing.questions || [])
            for (const q of (no.questions || [])) qSet.add(q)
            existing.questions = [...qSet]
          } else {
            merged.push(no)
          }
        }
      }
      localStorage.setItem('other_questions', JSON.stringify(merged))
    }
  }

  async function handleParse() {
    if (!text.trim() || loading) return
    setLoading(true); setResult(null); setError('')
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data); setExpanded({}); setActiveTab('knowledge')
        sessionStorage.setItem('iv_result', JSON.stringify(resp.data))
        sessionStorage.setItem('iv_company', company)
        sessionStorage.setItem('iv_position', position)
        sessionStorage.setItem('iv_tab', 'knowledge')
        sessionStorage.setItem('interview_result', JSON.stringify({ ...resp.data, company, position }))
        mergeToLocalStorage(resp.data)
      }
      else setError(resp.message || resp.detail || '解析失败，请重试')
    } catch (e) { setError(`请求失败: ${e.message || '网络错误'}`) }
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
          {loading ? '🧠 解析+评分中（长文本约需1-2分钟）...' : '🔍 开始解析'}
        </button>
      </div>
      {error && (
        <div style={{ marginTop: 12, padding: '10px 16px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, fontSize: 13, color: '#ff4d4f' }}>
          ❌ {error}
          <button onClick={() => { setError(''); handleParse() }} style={{ marginLeft: 12, fontSize: 12, color: '#1677ff', background: 'none', border: 'none', cursor: 'pointer', textDecoration: 'underline' }}>
            重试
          </button>
        </div>
      )}
    </div>
  )

  // ---- 结果页 ----
  const knowledgeGroups = result.groups.filter(g => g.type === 'knowledge')
  const projectGroups = result.groups.filter(g => g.type === 'project')
  const algorithmGroups = result.groups.filter(g => g.type === 'algorithm')
  const hrGroups = result.groups.filter(g => g.type === 'hr')
  const otherGroups = result.groups.filter(g => g.type === 'other')
  const otherCount = algorithmGroups.length + hrGroups.length + otherGroups.length

  const tabs = [
    { key: 'knowledge', label: '📖 知识点', count: knowledgeGroups.length },
    { key: 'project', label: '🔨 项目拷打', count: projectGroups.length },
    { key: 'other', label: '📎 其他问题', count: otherCount },
  ]

  const oa = result.overall_analysis

  return (
    <div className="interview-result">
      {/* ---- 整体分析（精简版） ---- */}
      <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', padding: '16px 20px', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <h2 style={{ margin: 0, flex: 1 }}>📋 面试复盘 {company && `· ${company}`} {position}</h2>
          <span style={{ fontSize: 15 }}>知识点 <b style={{ color: sc(result.avg_score), fontSize: 20 }}>{result.avg_score}</b>/100</span>
          {oa && <span style={{ fontSize: 14 }}>{'⭐'.repeat(Math.min(oa.overall_rating || 3, 5))} <b style={{ color: '#722ed1' }}>{oa.overall_label}</b></span>}
        </div>
        {oa && (
          <>
            <div style={{ fontSize: 13, color: '#555', lineHeight: 1.8, marginBottom: 8 }}>{oa.comment}</div>
            <div style={{ display: 'flex', gap: 16, fontSize: 13 }}>
              {oa.top3_improvements?.length > 0 && (
                <div style={{ flex: 1 }}>
                  <b style={{ color: '#ff4d4f' }}>🎯 改进</b>
                  {oa.top3_improvements.map((s, i) => <div key={i} style={{ color: '#666', paddingLeft: 12 }}>{i + 1}. {s}</div>)}
                </div>
              )}
              {oa.prediction && <div style={{ color: '#888', alignSelf: 'flex-end' }}>🔮 {oa.prediction}</div>}
            </div>
          </>
        )}
        {!oa && <p style={{ color: '#666', margin: 0 }}>{result.summary}</p>}
      </div>

      {result.missed_count > 0 && (
        <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '10px 16px', marginBottom: 12, fontSize: 13, color: '#ff4d4f' }}>
          ⚠️ 二次检查发现 {result.missed_count} 个可能遗漏的问题，已补充到"其他问题"中，请检查
        </div>
      )}

      {/* ---- Tab 栏 ---- */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '2px solid #eee', marginBottom: 16 }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => { setActiveTab(t.key); sessionStorage.setItem('iv_tab', t.key) }} style={{
            padding: '10px 20px', fontSize: 14, fontWeight: activeTab === t.key ? 600 : 400,
            color: activeTab === t.key ? '#1677ff' : '#888', background: 'none', border: 'none',
            borderBottom: activeTab === t.key ? '2px solid #1677ff' : '2px solid transparent',
            marginBottom: -2, cursor: 'pointer', transition: 'all .2s',
          }}>
            {t.label}{t.count > 0 ? ` (${t.count})` : ''}
          </button>
        ))}
      </div>

      {/* ---- Tab: 知识点 ---- */}
      {activeTab === 'knowledge' && (knowledgeGroups.length === 0
        ? <div className="empty">暂无知识点问题</div>
        : knowledgeGroups.map((g, i) => {
        const sr = g.score_result; const isOpen = expanded[`r${i}`]
        return (
          <div className="result-group knowledge" key={`r${i}`}>
            <div className="group-header" style={{ cursor: 'pointer' }} onClick={() => toggle(`r${i}`)}>
              <span style={{ color: '#aaa', fontSize: 12, marginRight: 4 }}>{isOpen ? '▾' : '▸'}</span>
              <span className="group-type">📖</span>
              <span className="group-title">
                {g.knowledge_point}
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
      }))}

      {/* ---- Tab: 项目拷打（按项目名分组树状结构） ---- */}
      {activeTab === 'project' && (projectGroups.length === 0
        ? <div className="empty">暂无项目拷打记录</div>
        : (() => {
          // 按 project_name 分组
          const byProject = {}
          projectGroups.forEach((g, i) => {
            const name = g.project_name || '未命名项目'
            if (!byProject[name]) byProject[name] = []
            byProject[name].push({ ...g, _idx: i })
          })
          return Object.entries(byProject).map(([projName, topics]) => (
            <div key={projName} style={{ marginBottom: 20 }}>
              {/* 项目名（一级） */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 0', cursor: 'pointer', borderBottom: '2px solid #f0e6ff' }}
                   onClick={() => toggle(`proj_${projName}`)}>
                <span style={{ fontSize: 16, color: '#722ed1' }}>{expanded[`proj_${projName}`] === false ? '▶' : '▼'}</span>
                <span style={{ fontSize: 17, fontWeight: 700, color: '#333' }}>📁 {projName}</span>
                <span style={{ fontSize: 13, color: '#999' }}>{topics.length} 个话题 · {topics.reduce((s, t) => s + (t.questions?.length || 0), 0)} 个问题</span>
              </div>
              {expanded[`proj_${projName}`] !== false && topics.map((g) => {
                const i = g._idx; const sr = g.score_result; const isOpen = expanded[`p${i}`]
                const stars = sr ? '⭐'.repeat(Math.min(sr.rating || 3, 5)) : ''
                return (
                  <div key={`p${i}`} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '4px solid #722ed1', padding: 14, marginTop: 10, marginLeft: 20 }}>
                    {/* 话题标题（二级） */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', padding: '4px 0' }} onClick={() => toggle(`p${i}`)}>
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
        })()
      )}

      {/* ---- Tab: 其他问题 ---- */}
      {activeTab === 'other' && (otherCount === 0
        ? <div className="empty">暂无其他面试问题</div>
        : <>
          {algorithmGroups.length > 0 && (
            <div style={{ marginBottom: 20 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8, color: '#fa8c16' }}>💻 算法题</h3>
              {algorithmGroups.map((g, i) => {
                const sr = g.score_result; const isOpen = expanded[`algo${i}`]
                const lcUrl = sr?.leetcode_url || g.leetcode_url || null
                const lcId = sr?.leetcode_id || g.leetcode_id
                const lcText = lcId ? `LeetCode #${lcId}` : 'LeetCode'
                return (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #fa8c16', padding: 14, marginBottom: 10 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }} onClick={() => toggle(`algo${i}`)}>
                    <span style={{ color: '#fa8c16', fontSize: 14 }}>{isOpen ? '▼' : '▶'}</span>
                    <span style={{ fontWeight: 600, flex: 1 }}>{g.title}</span>
                    {lcUrl && (
                      <a href={lcUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()}
                         style={{ fontSize: 12, color: '#fa8c16', padding: '2px 8px', border: '1px solid #fa8c16', borderRadius: 4, textDecoration: 'none' }}>
                        {lcText}
                      </a>
                    )}
                  </div>
                  {isOpen && (
                    <div style={{ marginTop: 10, paddingLeft: 24 }}>
                      {/* 原始对话 */}
                      {g.original_dialogue && (
                        <div style={{ fontSize: 13, color: '#555', marginBottom: 8, padding: '8px 12px', background: '#f9fafb', border: '1px dashed #e0e0e0', borderRadius: 6, whiteSpace: 'pre-wrap', lineHeight: 1.7 }}>
                          <div style={{ fontSize: 11, color: '#aaa', marginBottom: 4 }}>📝 原始对话</div>
                          {g.original_dialogue}
                        </div>
                      )}
                      {/* 评价 */}
                      {sr?.feedback && <div style={{ fontSize: 13, color: '#555', marginBottom: 8, padding: '6px 12px', background: '#fffbe6', borderLeft: '3px solid #fa8c16', borderRadius: 4 }}>💬 {sr.feedback}</div>}
                      {/* 题目描述 */}
                      {(sr?.description || g.description) && (
                        <div style={{ fontSize: 13, color: '#555', marginBottom: 8 }}>
                          <div style={{ fontWeight: 600, marginBottom: 4 }}>📝 题目</div>
                          <div style={{ padding: '6px 12px', background: '#fafafa', borderRadius: 4, lineHeight: 1.8 }}>{sr?.description || g.description}</div>
                        </div>
                      )}
                      {/* 示例 */}
                      {sr?.example && (
                        <div style={{ fontSize: 13, color: '#555', marginBottom: 8 }}>
                          <div style={{ fontWeight: 600, marginBottom: 4 }}>💡 示例</div>
                          <pre style={{ padding: '8px 12px', background: '#f5f5f5', borderRadius: 4, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap', margin: 0, lineHeight: 1.6 }}>{sr.example}</pre>
                        </div>
                      )}
                      {/* 建议解法 */}
                      {sr?.suggested_approach && (
                        <div style={{ fontSize: 13, color: '#555' }}>
                          <div style={{ fontWeight: 600, marginBottom: 4 }}>📖 建议解法</div>
                          <div style={{ padding: '6px 12px', background: '#f6ffed', borderRadius: 4, lineHeight: 1.8 }}>{sr.suggested_approach}</div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )})}
            </div>
          )}
          {hrGroups.length > 0 && (
            <div style={{ marginBottom: 20 }}>
              <h3 style={{ fontSize: 15, marginBottom: 8, color: '#52c41a' }}>💬 HR / 行为题</h3>
              {hrGroups.map((g, i) => {
                const sr = g.score_result
                return (
                <div key={i} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', borderLeft: '3px solid #52c41a', padding: 14, marginBottom: 10 }}>
                  {g.questions?.map((q, j) => <div key={j} style={{ padding: '3px 0', fontSize: 14, color: '#333' }}>• {q}</div>)}
                  {g.user_answer && (
                    <div style={{ fontSize: 13, color: '#666', marginTop: 6, padding: '6px 12px', background: '#f6ffed', borderRadius: 4 }}>💬 {g.user_answer}</div>
                  )}
                  {sr?.feedback && <div style={{ fontSize: 13, color: '#555', marginTop: 6, padding: '6px 12px', background: '#fffbe6', borderLeft: '3px solid #52c41a', borderRadius: 4 }}>📊 {sr.feedback}</div>}
                  {sr?.suggestion && <div style={{ fontSize: 13, color: '#555', marginTop: 4, padding: '6px 12px', background: '#f0f5ff', borderRadius: 4 }}>💡 {sr.suggestion}</div>}
                </div>
              )})}
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

      <button className="parse-btn" onClick={() => { setResult(null); setText(''); sessionStorage.removeItem('iv_result') }} style={{ marginTop: 20 }}>📋 重新上传</button>
    </div>
  )
}
