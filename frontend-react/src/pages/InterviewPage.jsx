import { useState, useEffect, useCallback, useRef } from 'react'

const API = 'http://127.0.0.1:8000/api'

// SHA-256 hash（浏览器原生）
async function sha256(text) {
  const encoder = new TextEncoder()
  const data = encoder.encode(text.trim())
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

export default function InterviewPage() {
  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [expanded, setExpanded] = useState({})
  const [activeTab, setActiveTab] = useState('knowledge')
  const [error, setError] = useState('')

  // 历史面试
  const [history, setHistory] = useState([])
  const [activeRecordId, setActiveRecordId] = useState(null)

  // 语音上传
  const [audioLoading, setAudioLoading] = useState(false)
  const [audioFile, setAudioFile] = useState(null)
  const audioInputRef = useRef(null)

  // 重复检测弹窗
  const [duplicateInfo, setDuplicateInfo] = useState(null)

  // 加载历史列表
  const loadHistory = useCallback(async () => {
    try {
      const resp = await fetch(`${API}/interview/history`).then(r => r.json())
      if (resp.code === 0) setHistory(resp.data || [])
    } catch (e) { console.error('加载历史失败:', e) }
  }, [])

  useEffect(() => { loadHistory() }, [loadHistory])

  // 查看历史详情
  async function handleViewHistory(recordId) {
    setActiveRecordId(recordId)
    setLoading(true)
    setResult(null)
    setError('')
    try {
      const resp = await fetch(`${API}/interview/history/${recordId}`).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data)
        setCompany(resp.data.company || '')
        setPosition(resp.data.position || '')
        setActiveTab('knowledge')
        setExpanded({})
      } else {
        setError(resp.message || '加载失败')
      }
    } catch (e) { setError('加载失败: ' + e.message) }
    setLoading(false)
  }

  // 新建面试
  function handleNewInterview() {
    setActiveRecordId(null)
    setResult(null)
    setText('')
    setCompany('')
    setPosition('')
    setError('')
    setExpanded({})
    setAudioFile(null)
  }

  // 语音上传处理
  async function handleAudioUpload(e) {
    const file = e.target.files?.[0]
    if (!file) return
    setAudioFile(file)
    setAudioLoading(true)
    setError('')
    try {
      const formData = new FormData()
      formData.append('file', file)
      const resp = await fetch(`${API}/interview/upload-audio`, {
        method: 'POST',
        body: formData,
      }).then(r => r.json())
      if (resp.code === 0) {
        setText(resp.data.text)
      } else {
        setError(resp.message || resp.detail || '转写失败')
      }
    } catch (e) { setError('语音转写失败: ' + e.message) }
    setAudioLoading(false)
  }

  // 开始解析（带重复检测）
  async function handleParse(forceNew = false) {
    if (!text.trim() || loading) return

    // 重复检测
    if (!forceNew) {
      try {
        const hash = await sha256(text)
        const checkResp = await fetch(`${API}/interview/check-duplicate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text_hash: hash }),
        }).then(r => r.json())
        if (checkResp.code === 0 && checkResp.data.duplicate) {
          setDuplicateInfo(checkResp.data)
          return
        }
      } catch (e) { /* 检测失败不阻塞，继续解析 */ }
    }

    setLoading(true)
    setResult(null)
    setError('')
    setDuplicateInfo(null)
    try {
      const resp = await fetch(`${API}/interview/parse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data)
        setActiveRecordId(resp.data.record_id)
        setActiveTab('knowledge')
        setExpanded({})
        await loadHistory()
      } else {
        setError(resp.message || resp.detail || '解析失败')
      }
    } catch (e) { setError('请求失败: ' + (e.message || '网络错误')) }
    setLoading(false)
  }

  // 覆盖已有记录
  async function handleOverwrite() {
    if (!duplicateInfo) return
    try {
      await fetch(`${API}/interview/overwrite`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ record_id: duplicateInfo.record_id }),
      })
    } catch (e) { /* 删除失败不阻塞 */ }
    setDuplicateInfo(null)
    handleParse(true)
  }

  const toggle = i => setExpanded(p => ({ ...p, [i]: !p[i] }))
  const sc = s => s >= 80 ? '#52c41a' : s >= 60 ? '#faad14' : '#ff4d4f'

  // ---- 整体布局：左侧历史 + 右侧内容 ----
  return (
    <div className="learn-layout">
      {/* 左侧历史列表 */}
      <div className="learn-sidebar">
        <div className="learn-sidebar-header">
          <span style={{ fontSize: 14, fontWeight: 600 }}>📋 面试记录</span>
          <button onClick={handleNewInterview} style={{ fontSize: 12, color: '#1677ff', background: 'none', border: 'none', cursor: 'pointer' }}>+ 新建</button>
        </div>
        <div className="learn-sidebar-list">
          {history.map(h => (
            <div key={h.id}
              className={`learn-sidebar-item ${activeRecordId === h.id ? 'active' : ''}`}
              onClick={() => handleViewHistory(h.id)}>
              <div style={{ fontSize: 13, fontWeight: 500, color: '#333', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {h.company || '未命名面试'}{h.position ? ` · ${h.position}` : ''}
              </div>
              <div style={{ fontSize: 11, color: '#999', marginTop: 2, display: 'flex', justifyContent: 'space-between' }}>
                <span>{h.created_at?.slice(0, 10)}</span>
                {h.avg_score != null && <span style={{ color: sc(h.avg_score), fontWeight: 600 }}>{h.avg_score}分</span>}
              </div>
            </div>
          ))}
          {history.length === 0 && <div style={{ padding: 16, color: '#ccc', fontSize: 13 }}>暂无面试记录</div>}
        </div>
      </div>

      {/* 右侧内容区 */}
      <div className="learn-main">
        {/* 输入页 */}
        {!result && !loading && (
          <div className="interview-upload">
            {/* 语音上传 */}
            <div style={{ display: 'flex', gap: 12, marginBottom: 12, alignItems: 'center' }}>
              <input type="file" ref={audioInputRef} accept=".mp3,.wav,.m4a,.flac,.ogg,.wma,.aac"
                style={{ display: 'none' }} onChange={handleAudioUpload} />
              <button onClick={() => audioInputRef.current?.click()} disabled={audioLoading}
                style={{ padding: '8px 16px', fontSize: 13, border: '1px solid #d9d9d9', borderRadius: 8, background: '#fff', cursor: audioLoading ? 'not-allowed' : 'pointer', fontFamily: 'inherit' }}>
                {audioLoading ? '🎤 转写中...' : '🎤 上传录音'}
              </button>
              {audioFile && <span style={{ fontSize: 12, color: '#999' }}>📎 {audioFile.name}</span>}
              <div style={{ flex: 1 }} />
              <input placeholder="公司" value={company} onChange={e => setCompany(e.target.value)}
                style={{ width: 120, padding: '6px 10px', border: '1px solid #d9d9d9', borderRadius: 6, fontSize: 13, fontFamily: 'inherit' }} />
              <input placeholder="职位" value={position} onChange={e => setPosition(e.target.value)}
                style={{ width: 120, padding: '6px 10px', border: '1px solid #d9d9d9', borderRadius: 6, fontSize: 13, fontFamily: 'inherit' }} />
            </div>

            <textarea className="form-textarea" rows={16} value={text} onChange={e => setText(e.target.value)}
              placeholder={'粘贴面试记录 或 上传录音自动转写...\n\n示例：\n面试官问了分布式锁怎么实现，我说了SETNX加过期时间，追问看门狗我没答上来。\n然后聊了我的订单系统项目，问超时取消怎么做的。\n手撕了LRU。问了离职原因。'} />
            <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }}>
              <button onClick={() => handleParse(false)} disabled={!text.trim() || loading}
                style={{
                  padding: '10px 28px', fontSize: 14, fontWeight: 600, border: 'none', borderRadius: 10,
                  background: loading ? '#d9d9d9' : 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                  color: '#fff', cursor: loading ? 'not-allowed' : 'pointer',
                  boxShadow: loading ? 'none' : '0 4px 14px rgba(102,126,234,0.4)',
                  transition: 'all 0.3s', fontFamily: 'inherit',
                }}>
                🔍 开始解析
              </button>
            </div>
            {error && (
              <div style={{ marginTop: 12, padding: '10px 16px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, fontSize: 13, color: '#ff4d4f' }}>
                ❌ {error}
              </div>
            )}
          </div>
        )}

        {loading && <div className="learn-loading">🧠 正在解析面试记录...</div>}

        {/* 重复检测弹窗 */}
        {duplicateInfo && (
          <div className="outliner-dialog-overlay" onClick={() => setDuplicateInfo(null)}>
            <div className="outliner-dialog" style={{ width: 440 }} onClick={e => e.stopPropagation()}>
              <div className="outliner-dialog-header">
                <h3>检测到相同面试记录</h3>
                <button className="outliner-dialog-close" onClick={() => setDuplicateInfo(null)}>×</button>
              </div>
              <div className="outliner-dialog-body" style={{ fontSize: 14, lineHeight: 1.8 }}>
                <p>该面试文本已于 <b>{duplicateInfo.created_at?.slice(0, 10)}</b> 上传过：</p>
                <p style={{ color: '#555' }}>
                  {duplicateInfo.company || '未命名'}{duplicateInfo.position ? ` · ${duplicateInfo.position}` : ''}
                  {duplicateInfo.avg_score != null && <span style={{ marginLeft: 8, color: sc(duplicateInfo.avg_score) }}>{duplicateInfo.avg_score}分</span>}
                </p>
              </div>
              <div className="outliner-dialog-footer" style={{ display: 'flex', gap: 8 }}>
                <button className="outliner-dialog-cancel" onClick={() => setDuplicateInfo(null)}>取消上传</button>
                <button className="outliner-dialog-submit" style={{ background: '#ff4d4f' }} onClick={handleOverwrite}>覆盖旧记录</button>
                <button className="outliner-dialog-submit" onClick={() => { setDuplicateInfo(null); handleParse(true) }}>上传为新面试</button>
              </div>
            </div>
          </div>
        )}

        {/* 结果页 */}
        {result && !loading && (() => {
  const knowledgeGroups = result.groups?.filter(g => g.type === 'knowledge') || []
  const projectGroups = result.groups?.filter(g => g.type === 'project') || []
  const algorithmGroups = result.groups?.filter(g => g.type === 'algorithm') || []
  const hrGroups = result.groups?.filter(g => g.type === 'hr') || []
  const otherGroups = result.groups?.filter(g => g.type === 'other') || []
  const otherCount = algorithmGroups.length + hrGroups.length + otherGroups.length

  const tabs = [
    { key: 'knowledge', label: '📖 知识点', count: knowledgeGroups.length },
    { key: 'project', label: '🔨 项目拷打', count: projectGroups.length },
    { key: 'other', label: '📎 其他问题', count: otherCount },
  ]

  const oa = result.overall_analysis

  return (
    <div className="interview-result" style={{ padding: '0 20px' }}>
      {/* ---- 整体分析 ---- */}
      <div className="tree-card" style={{ padding: '16px 20px', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          {oa && <span style={{ fontSize: 15 }}>通过概率 <b style={{ color: oa.pass_probability >= 70 ? '#52c41a' : oa.pass_probability >= 40 ? '#faad14' : '#ff4d4f', fontSize: 20 }}>{oa.pass_probability}%</b></span>}
          {oa && <span style={{ fontSize: 14 }}><b style={{ color: '#722ed1' }}>{oa.overall_label}</b></span>}
        </div>
        {oa && (
          <>
            <div style={{ fontSize: 13, color: '#555', lineHeight: 1.8, marginBottom: 8 }}>{oa.comment}</div>
            {oa.signals?.length > 0 && (
              <div style={{ fontSize: 13, marginBottom: 8 }}>
                <b style={{ color: '#1677ff' }}>📡 面试官信号</b>
                {oa.signals.map((s, i) => <div key={i} style={{ color: '#666', paddingLeft: 12 }}>{s}</div>)}
              </div>
            )}
            {(oa.review_points || oa.top3_improvements)?.length > 0 && (
              <div style={{ fontSize: 13 }}>
                <b style={{ color: '#ff4d4f' }}>📋 推荐复习</b>
                {(oa.review_points || oa.top3_improvements).map((s, i) => <div key={i} style={{ color: '#666', paddingLeft: 12 }}>{i + 1}. {s}</div>)}
              </div>
            )}
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
                    <div style={{ fontSize: 14, color: '#999', cursor: 'pointer', userSelect: 'none', padding: '4px 0' }} onClick={() => toggle(`raw${i}`)}>
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
                      {sr && <span style={{ fontSize: 13, color: '#722ed1', fontWeight: 600 }}>{(sr.rating_label || '').replace(/⭐/g, '').trim()} {'⭐'.repeat(sr.rating || 0)}</span>}
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
                            <div style={{ fontSize: 14, color: '#999', cursor: 'pointer', userSelect: 'none', padding: '4px 0' }} onClick={() => toggle(`praw${i}`)}>
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
                      {(g.description || sr?.description) && (
                        <div style={{ background: '#e8f4fd', borderLeft: '4px solid #1677ff', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.7 }}>
                          📝 {g.description || sr?.description}
                        </div>
                      )}
                      {(g.example || sr?.example) && <pre style={{ padding: '10px 14px', background: '#f5f5f5', borderLeft: '4px solid #d0d0d0', borderRadius: 6, fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', lineHeight: 1.6, marginBottom: 8 }}>{g.example || sr?.example}</pre>}
                      {sr?.suggested_approach && (
                        <div style={{ background: '#f9f0ff', borderLeft: '4px solid #722ed1', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>📖 {sr.suggested_approach}</div>
                      )}
                      {g.original_dialogue && (
                        <div style={{ marginTop: 4 }}>
                          <div style={{ fontSize: 14, color: '#999', cursor: 'pointer', userSelect: 'none', padding: '4px 0' }} onClick={() => toggle(`araw${i}`)}>
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
                          <div style={{ fontSize: 14, color: '#999', cursor: 'pointer', userSelect: 'none', padding: '4px 0' }} onClick={() => toggle(`hraw${i}`)}>
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

      <button className="parse-btn" onClick={handleNewInterview} style={{ marginTop: 20 }}>📋 新建面试</button>
    </div>
  )
  })()}
      </div>
    </div>
  )
}
