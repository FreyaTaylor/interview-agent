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

      {/* ---- Tab: 知识点（卡片风格） ---- */}
      {activeTab === 'knowledge' && (knowledgeGroups.length === 0
        ? <div className="empty">暂无知识点问题</div>
        : <div>
        {knowledgeGroups.map((g, i) => {
        const sr = g.score_result; const isOpen = expanded[`r${i}`]
        const bg = i % 2 === 0 ? '#fff' : '#f7f8fa'
        return (
          <div key={`r${i}`} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`r${i}`)}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 15, fontWeight: 600, color: '#333' }}>
                {g.knowledge_point}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
              </span>
              {sr && <span style={{ color: sc(sr.total_score), fontWeight: 700, fontSize: 15 }}>{sr.total_score}<span style={{ fontSize: 12, fontWeight: 400 }}>/100</span></span>}
            </div>
            {isOpen && (
              <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                {g.questions?.length > 0 && (
                  <div style={{ background: '#e8f4fd', borderLeft: '4px solid #1677ff', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.8 }}>
                    {g.questions.map((q, j) => <div key={j}>❓ {q}</div>)}
                  </div>
                )}
                {g.user_answer && (
                  <div style={{ background: '#fff8e1', borderLeft: '4px solid #fa8c16', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.7 }}>
                    💬 {g.user_answer}
                  </div>
                )}
                {sr && (
                  <div style={{ background: '#f6ffed', borderLeft: '4px solid #52c41a', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>
                    <div style={{ marginBottom: 6 }}><b>{sr.total_score}/100</b> — {sr.feedback}</div>
                    {(sr.rubric_result || []).map((item, k) => (
                      <div key={k} style={{ padding: '2px 0', color: '#555' }}>
                        {item.hit ? '✅' : '❌'} {item.key_point}（{item.score}分）
                        {item.matched_text && <span style={{ color: '#999', fontStyle: 'italic' }}> {item.matched_text}</span>}
                      </div>
                    ))}
                    {sr.recommended_answer?.length > 0 && (
                      <div style={{ marginTop: 8, borderTop: '1px solid #e8f5e9', paddingTop: 8 }}>
                        <b>📖 推荐回答</b>
                        {sr.recommended_answer.map((p, j) => <div key={j} style={{ color: '#555' }}>{j + 1}. {p}</div>)}
                      </div>
                    )}
                  </div>
                )}
                {g.original_dialogue && (
                  <div style={{ marginTop: 4 }}>
                    <div style={{ fontSize: 12, color: '#aaa', cursor: 'pointer', userSelect: 'none' }} onClick={() => toggle(`raw${i}`)}>
                      {expanded[`raw${i}`] ? '▾' : '▸'} 原始对话
                    </div>
                    {expanded[`raw${i}`] && (
                      <div style={{ marginTop: 4, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.original_dialogue}</div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        )
      })}
      </div>)}

      {/* ---- Tab: 项目拷打（卡片风格，同知识点） ---- */}
      {activeTab === 'project' && (projectGroups.length === 0
        ? <div className="empty">暂无项目拷打记录</div>
        : <div>{(() => {
          const byProject = {}
          projectGroups.forEach((g, i) => {
            const name = g.project_name || '未命名项目'
            if (!byProject[name]) byProject[name] = []
            byProject[name].push({ ...g, _idx: i })
          })
          let cardIdx = 0
          return Object.entries(byProject).map(([projName, topics]) => (
            <div key={projName} style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#333', padding: '8px 0', borderBottom: '2px solid #eee', marginBottom: 8 }}>
                🔨 {projName} <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>{topics.length} 个话题</span>
              </div>
              {topics.map((g) => {
                const i = g._idx; const sr = g.score_result; const isOpen = expanded[`p${i}`]
                const bg = cardIdx++ % 2 === 0 ? '#fff' : '#f7f8fa'
                return (
                  <div key={`p${i}`} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`p${i}`)}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <span style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>{g.topic || '拷打'}</span>
                      {sr && <span style={{ fontSize: 13, color: '#722ed1', fontWeight: 600 }}>{sr.rating_label} {'⭐'.repeat(sr.rating || 0)}</span>}
                    </div>
                    {isOpen && (
                      <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                        {g.questions?.length > 0 && (
                          <div style={{ background: '#e8f4fd', borderLeft: '4px solid #1677ff', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.8 }}>
                            {g.questions.map((q, j) => <div key={j}>❓ {q}</div>)}
                          </div>
                        )}
                        {g.user_answer && (
                          <div style={{ background: '#fff8e1', borderLeft: '4px solid #fa8c16', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.7 }}>
                            💬 {g.user_answer}
                          </div>
                        )}
                        {sr && (
                          <div style={{ background: '#f6ffed', borderLeft: '4px solid #52c41a', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>
                            <div style={{ color: '#555', lineHeight: 1.7 }}>{sr.impression}</div>
                            {sr.improvements?.length > 0 && <div style={{ marginTop: 6, color: '#fa8c16' }}>💡 {sr.improvements.join(' | ')}</div>}
                            {sr.suggested_answer?.length > 0 && (
                              <div style={{ marginTop: 8, borderTop: '1px solid #e8f5e9', paddingTop: 8 }}>
                                <b>📖 建议回答</b>
                                {sr.suggested_answer.map((p, j) => <div key={j} style={{ color: '#555' }}>{j + 1}. {p}</div>)}
                              </div>
                            )}
                          </div>
                        )}
                        {g.original_dialogue && (
                          <div style={{ marginTop: 4 }}>
                            <div style={{ fontSize: 12, color: '#aaa', cursor: 'pointer', userSelect: 'none' }} onClick={() => toggle(`praw${i}`)}>
                              {expanded[`praw${i}`] ? '▾' : '▸'} 原始对话
                            </div>
                            {expanded[`praw${i}`] && (
                              <div style={{ marginTop: 4, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.original_dialogue}</div>
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

      {/* ---- Tab: 其他问题（卡片风格） ---- */}
      {activeTab === 'other' && (otherCount === 0
        ? <div className="empty">暂无其他面试问题</div>
        : <div>
          {algorithmGroups.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#333', padding: '8px 0', borderBottom: '2px solid #eee', marginBottom: 8 }}>💻 算法题</div>
              {algorithmGroups.map((g, i) => {
                const sr = g.score_result; const isOpen = expanded[`algo${i}`]
                const lcUrl = sr?.leetcode_url || g.leetcode_url || null
                const lcId = sr?.leetcode_id || g.leetcode_id
                const bg = i % 2 === 0 ? '#fff' : '#f7f8fa'
                return (
                <div key={i} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`algo${i}`)}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>{g.title}</span>
                    {lcUrl ? <a href={lcUrl} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} className="lc-tag">LeetCode{lcId ? ` #${lcId}` : ''}</a> : lcId ? <span className="lc-tag" style={{ cursor: 'default' }}>LeetCode #{lcId}</span> : null}
                  </div>
                  {isOpen && (
                    <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                      {sr?.description && (
                        <div style={{ background: '#e8f4fd', borderLeft: '4px solid #1677ff', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.7 }}>
                          📝 {sr.description}
                        </div>
                      )}
                      {sr?.example && <pre style={{ padding: '10px 14px', background: '#f5f5f5', borderLeft: '4px solid #d0d0d0', borderRadius: 6, fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', lineHeight: 1.6, marginBottom: 8 }}>{sr.example}</pre>}
                      {sr?.suggested_approach && (
                        <div style={{ background: '#f9f0ff', borderLeft: '4px solid #722ed1', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>📖 {sr.suggested_approach}</div>
                      )}
                      {g.original_dialogue && (
                        <div style={{ marginTop: 4 }}>
                          <div style={{ fontSize: 12, color: '#aaa', cursor: 'pointer', userSelect: 'none' }} onClick={() => toggle(`araw${i}`)}>
                            {expanded[`araw${i}`] ? '▾' : '▸'} 原始对话
                          </div>
                          {expanded[`araw${i}`] && (
                            <div style={{ marginTop: 4, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.original_dialogue}</div>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )})}
            </div>
          )}
          {hrGroups.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#333', padding: '8px 0', borderBottom: '2px solid #eee', marginBottom: 8 }}>💬 HR / 行为题</div>
              {hrGroups.map((g, i) => {
                const sr = g.score_result
                const hasDetail = sr || g.user_answer
                const isOpen = expanded[`hr${i}`]
                const bg = i % 2 === 0 ? '#fff' : '#f7f8fa'
                return (
                <div key={i} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: hasDetail ? 'pointer' : 'default' }} onClick={() => hasDetail && toggle(`hr${i}`)}>
                  <div style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>{g.questions?.[0] || '—'}</div>
                  {isOpen && (
                    <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                      {g.user_answer && (
                        <div style={{ background: '#fff8e1', borderLeft: '4px solid #fa8c16', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>💬 {g.user_answer}</div>
                      )}
                      {sr?.feedback && (
                        <div style={{ background: '#f6ffed', borderLeft: '4px solid #52c41a', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>📊 {sr.feedback}</div>
                      )}
                      {sr?.suggestion && (
                        <div style={{ background: '#f9f0ff', borderLeft: '4px solid #722ed1', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>💡 {sr.suggestion}</div>
                      )}
                      {g.original_dialogue && (
                        <div style={{ marginTop: 4 }}>
                          <div style={{ fontSize: 12, color: '#aaa', cursor: 'pointer', userSelect: 'none' }} onClick={() => toggle(`hraw${i}`)}>
                            {expanded[`hraw${i}`] ? '▾' : '▸'} 原始对话
                          </div>
                          {expanded[`hraw${i}`] && (
                            <div style={{ marginTop: 4, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6, fontSize: 12, color: '#888', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{g.original_dialogue}</div>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )})}
            </div>
          )}
          {otherGroups.length > 0 && (
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#333', padding: '8px 0', borderBottom: '2px solid #eee', marginBottom: 8 }}>❓ 其他</div>
              {otherGroups.map((g, i) => (
                <div key={i} style={{ background: i % 2 === 0 ? '#fff' : '#f7f8fa', borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10 }}>
                  {g.questions?.map((q, j) => <div key={j} style={{ fontSize: 13, color: '#555', padding: '2px 0' }}>{q}</div>)}
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
