/**
 * 答题页面 — 左侧知识树目录 + 右侧答题区
 * 答题区复用 StudyPage 的出题/答题/评分逻辑
 * 不需要选知识点（从 URL 参数获取），不需要探索对话
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'

const API_STUDY = 'http://127.0.0.1:8000/api/study'
const API_LEARN = 'http://127.0.0.1:8000/api/learn'
const API_TREE = 'http://127.0.0.1:8000/api/knowledge'

// 查找节点的祖先 ID 列表
function findAncestorIds(tree, targetId) {
  const ancestors = new Set()
  function dfs(node, path) {
    if (node.id === targetId) {
      path.forEach(id => ancestors.add(id))
      return true
    }
    for (const child of (node.children || [])) {
      if (dfs(child, [...path, node.id])) return true
    }
    return false
  }
  for (const root of tree) {
    if (dfs(root, [])) break
  }
  return ancestors
}

// 左侧目录树节点
function SidebarNode({ node, activeId, expandedIds, onSelect, depth = 0 }) {
  const children = node.children || []
  const hasKids = children.length > 0
  const isLeaf = node.node_type === 'leaf'
  const isActive = node.id === activeId
  const shouldExpand = expandedIds.has(node.id)
  const [collapsed, setCollapsed] = useState(!shouldExpand)

  useEffect(() => {
    if (shouldExpand) setCollapsed(false)
    else if (expandedIds.size > 0) setCollapsed(true)
  }, [shouldExpand, expandedIds])

  return (
    <div>
      <div
        className={`learn-sidebar-item ${isActive ? 'active' : ''} ${isLeaf ? 'leaf' : ''}`}
        style={{ paddingLeft: 12 + depth * 16 }}
        onClick={() => {
          if (isLeaf) onSelect(node.id)
          else setCollapsed(c => !c)
        }}
      >
        {hasKids && <span className={`learn-sidebar-toggle ${collapsed ? '' : 'open'}`} />}
        {isLeaf && <span className="learn-sidebar-bullet" />}
        <span className="learn-sidebar-name">{node.name}</span>
      </div>
      {hasKids && !collapsed && children.map(ch => (
        <SidebarNode key={ch.id} node={ch} activeId={activeId} expandedIds={expandedIds} onSelect={onSelect} depth={depth + 1} />
      ))}
    </div>
  )
}

export default function ExamPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const [tree, setTree] = useState([])
  const [expandedIds, setExpandedIds] = useState(new Set())
  const [activeKpId, setActiveKpId] = useState(null)
  const [kpName, setKpName] = useState('')
  const [convId, setConvId] = useState(null)
  const [phase, setPhase] = useState('idle') // idle | loading | answering | scored
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState([])
  const [collapsedRounds, setCollapsedRounds] = useState({})
  const [allQuestions, setAllQuestions] = useState([])
  const [activeQuestionIdx, setActiveQuestionIdx] = useState(0)
  const [input, setInput] = useState('')
  const [loadingType, setLoadingType] = useState('') // 'exam' | 'score' | 'next'
  const bottomRef = useRef(null)
  const startingRef = useRef(false)
  const lastStartedRef = useRef(null) // 防止重复启动同一知识点
  const [progress, setProgress] = useState(null)
  const [isFollowUp, setIsFollowUp] = useState(false)
  const [totalQuestionCount, setTotalQuestionCount] = useState(0)
  const [questionHistory, setQuestionHistory] = useState({}) // question -> { score, answer }
  const [expandedHistory, setExpandedHistory] = useState({}) // question index -> bool // 总题目数

  // 加载知识树
  useEffect(() => {
    fetch(`${API_TREE}/tree`).then(r => r.json()).then(d => {
      if (d.code === 0) {
        setTree(d.data)
        if (kpId) setExpandedIds(findAncestorIds(d.data, parseInt(kpId)))
      }
    })
  }, [])

  // URL 驱动
  useEffect(() => {
    if (kpId && tree.length > 0) {
      const id = parseInt(kpId)
      setActiveKpId(id)
      setExpandedIds(findAncestorIds(tree, id))
      // 先加载历史进度
      fetch(`${API_STUDY}/exam-progress/${id}`).then(r => r.json()).then(d => {
        if (d.code === 0) setProgress(d.data)
      }).catch(() => {})
      startExam(id)
    }
  }, [kpId, tree.length])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [rounds, currentRound])

  function handleSelectKp(id) {
    setActiveKpId(id)
    setExpandedIds(findAncestorIds(tree, id))
    navigate(`/exam/${id}`, { replace: true })
    fetch(`${API_STUDY}/exam-progress/${id}`).then(r => r.json()).then(d => {
      if (d.code === 0) setProgress(d.data)
    }).catch(() => {})
    startExam(id)
  }

  async function startExam(id) {
    if (startingRef.current) return
    if (lastStartedRef.current === id && convId) return // 同一知识点不重复创建
    startingRef.current = true
    lastStartedRef.current = id
    setLoadingType('exam')
    setPhase('idle')
    setRounds([])
    setCurrentRound([])
    setCollapsedRounds({})
    setAllQuestions([])
    setInput('')

    try {
      // 先确保知识内容和题目已生成
      const contentResp = await fetch(`${API_LEARN}/content/${id}`).then(r => r.json()).catch(() => null)

      const resp = await fetch(`${API_STUDY}/exam-start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ knowledge_point_id: id }),
      }).then(r => r.json())

      if (resp.code === 0) {
        const d = resp.data
        setConvId(d.conversation_id)
        setKpName(d.knowledge_point_name)
        setAllQuestions(d.all_questions || [d.question_content])
        setTotalQuestionCount((d.all_questions || [d.question_content]).length)
        setQuestionHistory(d.question_history || {})
        setExpandedHistory({})
        setActiveQuestionIdx(0)
        setCurrentRound([{ type: 'q', html: `📝 ${d.question_content}` }])
        setPhase('answering')
      } else {
        setPhase('error')
        setKpName(resp.message || '出题失败')
      }
    } catch (e) {
      console.error('出题失败:', e)
    } finally {
      setLoadingType('')
      startingRef.current = false
    }
  }

  async function submitAnswer() {
    if (!input.trim() || loadingType) return
    const answer = input.trim()
    setInput('')
    setCurrentRound(r => [...r, { type: 'a', html: `💬 ${answer}` }])
    setLoadingType('score')

    const resp = await fetch(`${API_STUDY}/answer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversation_id: convId, answer }),
    }).then(r => r.json())
    setLoadingType('')

    if (resp.code === 0) {
      const d = resp.data
      const rubricTotal = d.rubric_total || 100
      let scoreHtml = `<b>得分: ${d.total_score}/${rubricTotal}</b> — ${d.feedback}<br>`
      scoreHtml += '<table style="width:100%;border-collapse:collapse;margin:8px 0;font-size:14px;">'
      for (const item of d.rubric_result) {
        const icon = item.hit ? '✅' : '❌'
        const matched = item.matched_text || ''
        const bg = item.hit ? '#e8f5e9' : '#ffebee'
        const md = matched ? `<span style="color:#666;font-style:italic;">「${matched}」</span>` : '<span style="color:#999;">未提及</span>'
        scoreHtml += `<tr style="background:${bg};border-bottom:1px solid #e0e0e0;"><td style="padding:4px 8px;">${icon} <b>${item.key_point}</b>（${item.score}分）<br>${md}</td></tr>`
      }
      scoreHtml += '</table>'
      const rec = d.recommended_answer
      if (rec && Array.isArray(rec) && rec.length > 0) {
        scoreHtml += '<br>📖 <b>推荐回答</b>:<br>'
        rec.forEach((p, i) => { scoreHtml += `${i + 1}. ${p}<br>` })
      }

      // 每次评分后都刷新掌握度
      fetch(`${API_STUDY}/exam-progress/${activeKpId}`).then(r => r.json()).then(pd => {
        if (pd.code === 0) setProgress(pd.data)
      }).catch(() => {})

      if (d.follow_up) {
        setCurrentRound(r => [...r, { type: 's', html: scoreHtml }, { type: 'q', html: `🤔 <b>追问</b><br>${d.follow_up}` }])
        setPhase('answering')
        setIsFollowUp(true)
      } else {
        let summaryHtml = ''
        if (d.overall_summary) {
          summaryHtml += `📝 <b>本轮总结</b><br><span style="line-height:1.8">${d.overall_summary}</span>`
        }
        const ext = d.extension_questions
        if (ext && Array.isArray(ext) && ext.length > 0) {
          summaryHtml += '<br><br><b>📚 扩展题目</b>'
          ext.forEach((eq, i) => { summaryHtml += `<div style="margin:6px 0;padding:8px 12px;background:#fff;border:1px solid #e8e8e8;border-radius:8px;"><b>${i + 1}. ${eq.question}</b><br><span style="color:#666;font-size:13px;">${eq.answer}</span></div>` })
        }
        const roundMsgs = [...currentRound, { type: 's', html: scoreHtml }]
        if (summaryHtml) roundMsgs.push({ type: 'summary', html: summaryHtml })
        setRounds(r => [...r, roundMsgs])
        setCurrentRound([])
        setPhase('scored')
        setIsFollowUp(false)
      }
    }
  }

  async function stopFollowUp() {
    if (!convId || loadingType) return
    setLoadingType('score')
    try {
      const resp = await fetch(`${API_STUDY}/stop-followup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversation_id: convId }),
      }).then(r => r.json())
      setLoadingType('')
      if (resp.code === 0) {
        const d = resp.data
        let summaryHtml = ''
        if (d.overall_summary) {
          summaryHtml += `📝 <b>本轮总结</b><br><span style="line-height:1.8">${d.overall_summary}</span>`
        }
        const ext = d.extension_questions
        if (ext && Array.isArray(ext) && ext.length > 0) {
          summaryHtml += '<br><br><b>📚 扩展题目</b>'
          ext.forEach((eq, i) => { summaryHtml += `<div style="margin:6px 0;padding:8px 12px;background:#fff;border:1px solid #e8e8e8;border-radius:8px;"><b>${i + 1}. ${eq.question}</b><br><span style="color:#666;font-size:13px;">${eq.answer}</span></div>` })
        }
        const roundMsgs = [...currentRound]
        if (summaryHtml) roundMsgs.push({ type: 'summary', html: summaryHtml })
        setRounds(r => [...r, roundMsgs])
        setCurrentRound([])
        setPhase('scored')
        setIsFollowUp(false)
      }
    } catch (e) {
      setLoadingType('')
      console.error('停止追问失败:', e)
    }
  }

  async function nextQuestion() {
    setLoadingType('next')
    const resp = await fetch(`${API_STUDY}/next`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversation_id: convId }),
    }).then(r => r.json())
    setLoadingType('')
    if (resp.code === 0) {
      const d = resp.data
      if (d.finished) {
        // 全部完成
        setPhase('finished')
        return
      }
      const collapsed = {}
      rounds.forEach((_, i) => { collapsed[i] = true })
      setCollapsedRounds(collapsed)
      const newQ = d.question_content || `第${d.question_round}题`
      setAllQuestions(prev => {
        const next = [...prev]
        if (next.length < d.question_round) next.push(newQ)
        return next
      })
      setActiveQuestionIdx(d.question_round - 1)
      setCurrentRound([{ type: 'q', html: `📝 <b>第${d.question_round}题</b><br>${d.question_content}` }])
      setPhase('answering')
    }
  }

  function renderRound(msgs, idx) {
    const isCollapsed = collapsedRounds[idx]
    const firstQ = msgs.find(m => m.type === 'q')
    const titleMatch = firstQ?.html?.match(/<b>(.*?)<\/b>/)
    const title = titleMatch ? titleMatch[1] : `第 ${idx + 1} 题`

    if (isCollapsed) {
      return (
        <div key={idx} data-round={idx} className="round-group" style={{ cursor: 'pointer', padding: '12px 16px', opacity: 0.7 }}
          onClick={() => setCollapsedRounds(p => ({ ...p, [idx]: false }))}>
          <span style={{ fontSize: 14, color: '#888' }}>▸ {title}</span>
        </div>
      )
    }
    const regularMsgs = msgs.filter(m => m.type !== 'summary')
    const summaryMsgs = msgs.filter(m => m.type === 'summary')
    return (
      <div key={idx} data-round={idx}>
        <div className="round-group"
          style={typeof idx === 'number' && rounds.length > 1 ? { cursor: 'pointer' } : {}}
          onClick={() => typeof idx === 'number' && setCollapsedRounds(p => ({ ...p, [idx]: true }))}>
          {regularMsgs.map((m, i) => (
            <div key={i} className={m.type === 'q' ? 'q-box' : m.type === 'a' ? 'a-box' : 's-box'}
                 dangerouslySetInnerHTML={{ __html: m.html }} />
          ))}
        </div>
        {summaryMsgs.map((m, i) => (
          <div key={`sum${i}`} style={{ margin: '8px 0', padding: '16px 20px', background: '#f0f7ff', border: '1px solid #d6e4ff', borderRadius: 12 }}
               dangerouslySetInnerHTML={{ __html: m.html }} />
        ))}
      </div>
    )
  }

  return (
    <div className="learn-container">
      {/* 左侧目录 */}
      <div className="learn-sidebar">
        <div className="learn-sidebar-header">📚 知识目录</div>
        <div className="learn-sidebar-tree">
          {tree.map(root => (
            <SidebarNode key={root.id} node={root} activeId={activeKpId} expandedIds={expandedIds} onSelect={handleSelectKp} />
          ))}
        </div>
      </div>

      {/* 右侧答题区 */}
      <div className="learn-main" style={{ padding: 0 }}>
        {!activeKpId && !loadingType && (
          <div className="learn-empty">👈 从左侧目录选择知识点开始答题</div>
        )}
        {loadingType === 'exam' && (
          <div className="learn-loading">🧠 正在准备题目...</div>
        )}

        {phase === 'error' && (
          <div className="learn-empty" style={{ flexDirection: 'column', color: '#ff4d4f' }}>
            <span>❌ {kpName}</span>
            <button onClick={() => startExam(activeKpId)} style={{ marginTop: 12, padding: '6px 16px', cursor: 'pointer' }}>重试</button>
          </div>
        )}

        {phase !== 'idle' && phase !== 'error' && kpName && (
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            {/* 信息栏 */}
            <div className="learn-info-bar">
              <h2 className="learn-title">{kpName}</h2>
              <div className="learn-meta">
                <span className="learn-mastery">
                  掌握度 <span style={{ color: (progress?.mastery_level || 0) >= 80 ? '#52c41a' : (progress?.mastery_level || 0) >= 40 ? '#faad14' : (progress?.mastery_level || 0) > 0 ? '#ff4d4f' : '#e0e0e0', fontWeight: 600 }}>
                    {progress?.mastery_level || 0}%
                  </span>
                </span>
                <Link to={`/learn/${activeKpId}`} className="learn-switch-btn learn">📖 去学习</Link>
              </div>
            </div>

            {/* 题目栏 */}
            {allQuestions.length > 0 && (
              <div style={{ padding: '12px 20px', borderBottom: '1px solid #f0f0f0', background: '#fff', flexShrink: 0 }}>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
                {allQuestions.map((q, i) => {
                  const answered = i < rounds.length
                  const isCurrent = i === activeQuestionIdx
                  return (
                    <button key={i} onClick={(e) => {
                      e.stopPropagation()
                      const c = {}
                      rounds.forEach((_, j) => { c[j] = j !== i })
                      setCollapsedRounds(c)
                      setActiveQuestionIdx(i)
                      if (answered) {
                        setTimeout(() => {
                          const el = document.querySelector(`[data-round="${i}"]`)
                          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
                        }, 100)
                      } else {
                        setCurrentRound([{ type: 'q', html: `📝 ${q}` }])
                        setPhase('answering')
                        setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }), 100)
                      }
                    }}
                      style={{
                        padding: '6px 14px', borderRadius: 16, fontSize: 12, cursor: 'pointer',
                        border: isCurrent ? '2px solid #722ed1' : answered ? '1px solid #52c41a' : '1px solid #e0e0e0',
                        background: isCurrent ? '#f9f0ff' : answered ? '#f6ffed' : '#fafafa',
                        fontWeight: isCurrent ? 600 : 400,
                        fontFamily: 'inherit', color: '#333', transition: 'all 0.15s',
                      }}>
                      {q}
                    </button>
                  )
                })}
                </div>
              </div>
            )}

            {/* 答题对话区 */}
            <div style={{ flex: 1, overflow: 'auto', padding: '20px 24px' }}>
              {rounds.map((r, i) => renderRound(r, i))}
              {currentRound.length > 0 && renderRound(currentRound, 'cur')}
              {loadingType === 'score' && <div className="loading">🤔 正在评分...</div>}
              {loadingType === 'next' && <div className="loading">🧠 正在出题...</div>}

              {phase === 'answering' && !loadingType && (
                <div className="chat-input-wrap">
                  <input className="chat-input" placeholder="输入你的回答..."
                    value={input} onChange={e => setInput(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && !e.shiftKey && submitAnswer()} />
                  <button className="send-btn" onClick={submitAnswer} disabled={!input.trim()}>发送</button>
                  {isFollowUp && (
                    <button className="stop-followup-btn" onClick={stopFollowUp}>⏹ 停止追问</button>
                  )}
                </div>
              )}
              {phase === 'scored' && !loadingType && rounds.length < totalQuestionCount && (
                <button className="next-btn" onClick={nextQuestion}>➡️ 下一题</button>
              )}
              {(phase === 'finished' || (phase === 'scored' && rounds.length >= totalQuestionCount)) && !loadingType && (
                <div style={{ textAlign: 'center', padding: '20px 0', color: '#52c41a', fontSize: 16, fontWeight: 600 }}>
                  🎉 全部题目已完成！
                </div>
              )}
              <div ref={bottomRef} />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
