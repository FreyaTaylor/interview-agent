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
      <div style={{ marginBottom: 10, display: 'flex', justifyContent: 'flex-end' }}>
        <button className="parse-btn" onClick={handleParse} disabled={!text.trim() || loading}>
          {loading ? '🧠 解析中...' : '🔍 开始解析'}
        </button>
      </div>
      <textarea className="form-textarea" rows={16} value={text} onChange={e => setText(e.target.value)}
        placeholder={'粘贴面试记录...\n\n示例：\n面试官问了分布式锁怎么实现，我说了SETNX加过期时间，追问看门狗我没答上来。\n然后聊了我的订单系统项目，问超时取消怎么做的。\n手撕了LRU。问了离职原因。\n中间他接了个电话等了一会。'} />
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
      {/* ---- 整体分析 ---- */}
      <div className="tree-card" style={{ padding: '16px 20px', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
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
      <div className="tree-tabs" style={{ marginBottom: 16 }}>
        {tabs.map(t => (
          <button key={t.key} className={`tree-tab ${activeTab === t.key ? 'active' : ''}`}
            onClick={() => { setActiveTab(t.key); sessionStorage.setItem('iv_tab', t.key) }}>
            {t.label}{t.count > 0 ? ` (${t.count})` : ''}
          </button>
        ))}
      </div>

      {/* ---- Tab: 知识点（幕布风格） ---- */}
      {activeTab === 'knowledge' && (knowledgeGroups.length === 0
        ? <div className="empty">暂无知识点问题</div>
        : <div className="tree-card">
        {knowledgeGroups.map((g, i) => {
        const sr = g.score_result; const isOpen = expanded[`r${i}`]
        const bg = i % 2 === 0 ? '#fff' : '#fafbfc'
        return (
          <div key={`r${i}`} className="tree-node" style={{ background: bg }}>
            <div className="node-row" style={{ paddingLeft: 16, cursor: 'pointer' }} onClick={() => toggle(`r${i}`)}>
              <span className={`toggle ${isOpen ? 'open' : ''}`} />
              <span className="node-name">
                {g.knowledge_point}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
              </span>
              {sr && <span style={{ color: sc(sr.total_score), fontWeight: 600, fontSize: 13 }}>{sr.total_score}分</span>}
            </div>
            {isOpen && (
              <div style={{ paddingLeft: 38 }}>
                {g.questions?.length > 0 && g.questions.map((q, j) => (
                  <div key={j} className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>❓ {q}</span></div></div>
                ))}
                {g.user_answer && (
                  <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#666' }}>💬 {g.user_answer}</span></div></div>
                )}
                {g.original_dialogue && (
                  <div style={{ margin: '4px 16px 4px', padding: '6px 10px', background: '#f9fafb', borderRadius: 4, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.original_dialogue}</div>
                )}
                {sr && (
                  <div style={{ margin: '4px 16px 8px', padding: '8px 12px', background: '#f6ffed', borderRadius: 6, fontSize: 13 }}>
                    <div style={{ marginBottom: 4 }}><b>{sr.total_score}/100</b> — {sr.feedback}</div>
                    {(sr.rubric_result || []).map((item, k) => (
                      <div key={k} style={{ padding: '2px 0', color: '#555' }}>
                        {item.hit ? '✅' : '❌'} {item.key_point}（{item.score}分）
                        {item.matched_text && <span style={{ color: '#999', fontStyle: 'italic' }}> {item.matched_text}</span>}
                      </div>
                    ))}
                    {sr.recommended_answer?.length > 0 && (
                      <div style={{ marginTop: 6, borderTop: '1px solid #e8f5e9', paddingTop: 6 }}>
                        <b>📖 推荐回答</b>
                        {sr.recommended_answer.map((p, j) => <div key={j} style={{ color: '#555' }}>{j + 1}. {p}</div>)}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        )
      })}
      </div>)}

      {/* ---- Tab: 项目拷打（幕布风格） ---- */}
      {activeTab === 'project' && (projectGroups.length === 0
        ? <div className="empty">暂无项目拷打记录</div>
        : <div className="tree-card">{(() => {
          const byProject = {}
          projectGroups.forEach((g, i) => {
            const name = g.project_name || '未命名项目'
            if (!byProject[name]) byProject[name] = []
            byProject[name].push({ ...g, _idx: i })
          })
          return Object.entries(byProject).map(([projName, topics]) => (
            <div key={projName} className="tree-node">
              <div className="node-row cat" style={{ paddingLeft: 16, cursor: 'pointer' }} onClick={() => toggle(`proj_${projName}`)}>
                <span className={`toggle ${expanded[`proj_${projName}`] === false ? '' : 'open'}`} />
                <span className="node-name">{projName}</span>
                <span style={{ fontSize: 12, color: '#999', marginLeft: 8 }}>{topics.length} 个话题</span>
              </div>
              {expanded[`proj_${projName}`] !== false && topics.map((g) => {
                const i = g._idx; const sr = g.score_result; const isOpen = expanded[`p${i}`]
                return (
                  <div key={`p${i}`} className="tree-node">
                    <div className="node-row" style={{ paddingLeft: 38, cursor: 'pointer' }} onClick={() => toggle(`p${i}`)}>
                      <span className={`toggle ${isOpen ? 'open' : ''}`} />
                      <span className="node-name">{g.topic || '拷打'}</span>
                    </div>
                    {isOpen && (
                      <div style={{ paddingLeft: 60 }}>
                        {g.questions?.map((q, j) => (
                          <div key={j} className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>{q}</span></div></div>
                        ))}
                        {g.user_answer && (
                          <div style={{ margin: '4px 0 4px 16px', padding: '6px 10px', background: '#f0e6ff', borderRadius: 4, fontSize: 13, color: '#666' }}>💬 {g.user_answer}</div>
                        )}
                        {sr && (
                          <div style={{ margin: '4px 0 8px 16px', padding: '8px 12px', background: '#f9f0ff', borderRadius: 6, fontSize: 13 }}>
                            <div style={{ fontWeight: 600, marginBottom: 4 }}>{sr.rating_label} {'⭐'.repeat(sr.rating || 0)}</div>
                            <div style={{ color: '#555', lineHeight: 1.7 }}>{sr.impression}</div>
                            {sr.improvements?.length > 0 && <div style={{ marginTop: 6, color: '#fa8c16' }}>💡 {sr.improvements.join(' | ')}</div>}
                            {sr.suggested_answer?.length > 0 && (
                              <div style={{ marginTop: 6 }}>
                                <b style={{ color: '#722ed1' }}>📖 建议回答</b>
                                {sr.suggested_answer.map((p, j) => <div key={j} style={{ color: '#555' }}>{j + 1}. {p}</div>)}
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
        })()}</div>
      )}

      {/* ---- Tab: 其他问题（幕布风格） ---- */}
      {activeTab === 'other' && (otherCount === 0
        ? <div className="empty">暂无其他面试问题</div>
        : <div className="tree-card">
          {algorithmGroups.length > 0 && (
            <div className="tree-node">
              <div className="node-row cat" style={{ paddingLeft: 16 }}><span className="node-name">💻 算法题</span></div>
              {algorithmGroups.map((g, i) => {
                const sr = g.score_result; const isOpen = expanded[`algo${i}`]
                const lcUrl = sr?.leetcode_url || g.leetcode_url || null
                const lcId = sr?.leetcode_id || g.leetcode_id
                return (
                <div key={i} className="tree-node">
                  <div className="node-row" style={{ paddingLeft: 38, cursor: 'pointer' }} onClick={() => toggle(`algo${i}`)}>
                    <span className={`toggle ${isOpen ? 'open' : ''}`} />
                    <span className="node-name">{g.title}</span>
                    {lcUrl ? <a href={lcUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} className="lc-tag">LeetCode{lcId ? ` #${lcId}` : ''}</a> : lcId ? <span className="lc-tag" style={{ cursor: 'default' }}>LeetCode #{lcId}</span> : null}
                  </div>
                  {isOpen && (
                    <div style={{ paddingLeft: 60 }}>
                      {g.original_dialogue && <div style={{ padding: '4px 10px', background: '#f9fafb', borderRadius: 4, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6, marginBottom: 4 }}>{g.original_dialogue}</div>}
                      {sr?.feedback && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>💬 {sr.feedback}</span></div></div>}
                      {sr?.description && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📝 {sr.description}</span></div></div>}
                      {sr?.example && <pre style={{ marginLeft: 16, padding: '4px 10px', background: '#f5f5f5', borderRadius: 4, fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{sr.example}</pre>}
                      {sr?.suggested_approach && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📖 {sr.suggested_approach}</span></div></div>}
                    </div>
                  )}
                </div>
              )})}
            </div>
          )}
          {hrGroups.length > 0 && (
            <div className="tree-node">
              <div className="node-row cat" style={{ paddingLeft: 16 }}><span className="node-name">💬 HR / 行为题</span></div>
              {hrGroups.map((g, i) => {
                const sr = g.score_result; const isOpen = expanded[`hr${i}`]
                const hasDetail = sr || g.user_answer
                return (
                <div key={i} className="tree-node">
                  <div className="node-row" style={{ paddingLeft: 38, cursor: hasDetail ? 'pointer' : 'default' }} onClick={() => hasDetail && toggle(`hr${i}`)}>
                    {hasDetail ? <span className={`toggle ${isOpen ? 'open' : ''}`} /> : <span className="bullet" />}
                    <span className="node-name">{g.questions?.[0] || '—'}</span>
                  </div>
                  {isOpen && (
                    <div style={{ paddingLeft: 60 }}>
                      {g.user_answer && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#666' }}>💬 {g.user_answer}</span></div></div>}
                      {sr?.feedback && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>📊 {sr.feedback}</span></div></div>}
                      {sr?.suggestion && <div className="tree-node"><div className="node-row" style={{ paddingLeft: 16 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#fa8c16' }}>💡 {sr.suggestion}</span></div></div>}
                    </div>
                  )}
                </div>
              )})}
            </div>
          )}
          {otherGroups.length > 0 && (
            <div className="tree-node">
              <div className="node-row cat" style={{ paddingLeft: 16 }}><span className="node-name">❓ 其他</span></div>
              {otherGroups.map((g, i) => (
                <div key={i} className="tree-node">
                  {g.questions?.map((q, j) => (
                    <div key={j} className="tree-node"><div className="node-row" style={{ paddingLeft: 38 }}><span className="bullet" /><span style={{ fontSize: 13, color: '#555' }}>{q}</span></div></div>
                  ))}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <button className="parse-btn" onClick={() => { setResult(null); setText(''); sessionStorage.removeItem('iv_result') }} style={{ marginTop: 20 }}>📋 重新上传</button>
    </div>
  )
}
