import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api/study'

export default function StudyPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const [kpList, setKpList] = useState([])
  const [convId, setConvId] = useState(null)
  const [kpName, setKpName] = useState('')
  const [activeKpId, setActiveKpId] = useState(null)
  const [phase, setPhase] = useState('select') // select | answering | scored
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState([])
  const [collapsedRounds, setCollapsedRounds] = useState({})
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const bottomRef = useRef(null)

  // 加载知识点列表
  useEffect(() => {
    fetch(`${API}/knowledge-points`).then(r => r.json()).then(d => {
      if (d.code === 0) setKpList(d.data)
    })
  }, [])

  // 从知识树跳转 或 面试复盘跳转
  useEffect(() => {
    if (kpId && kpList.length > 0) {
      // 检查是否有面试复盘的评分结果
      const stored = sessionStorage.getItem('interview_study_result')
      if (stored) {
        sessionStorage.removeItem('interview_study_result')
        const data = JSON.parse(stored)
        loadInterviewResult(data)
      } else {
        startStudy(parseInt(kpId))
      }
    }
  }, [kpId, kpList])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [rounds, currentRound])

  function loadInterviewResult(data) {
    setConvId(data.conversation_id)
    setKpName(data.knowledge_point_name)
    navigate('/study', { replace: true })

    if (data.mode === 'scored') {
      // 构建面试回答 + 评分的展示
      const qHtml = `📝 <b>面试题目</b><br>${data.question_content}`
      const aHtml = `💬 <b>面试中的回答</b><br>${data.user_answer}`

      let scoreHtml = `<b>得分: ${data.total_score}/100</b> — ${data.feedback}<br>`
      scoreHtml += '<table style="width:100%;border-collapse:collapse;margin:8px 0;font-size:14px;">'
      for (const item of (data.rubric_result || [])) {
        const icon = item.hit ? '✅' : '❌'
        const matched = item.matched_text || ''
        const bg = item.hit ? '#e8f5e9' : '#ffebee'
        const md = matched ? `<span style="color:#666;font-style:italic;">「${matched}」</span>` : '<span style="color:#999;">未提及</span>'
        scoreHtml += `<tr style="background:${bg};border-bottom:1px solid #e0e0e0;"><td style="padding:4px 8px;">${icon} <b>${item.key_point}</b>（${item.score}分）<br>${md}</td></tr>`
      }
      scoreHtml += '</table>'
      const rec = data.recommended_answer
      if (rec && Array.isArray(rec) && rec.length > 0) {
        scoreHtml += '<br>📖 <b>推荐回答</b>:<br>'
        rec.forEach((p, i) => { scoreHtml += `${i + 1}. ${p}<br>` })
      }

      const round = [
        { type: 'q', html: qHtml },
        { type: 'a', html: aHtml },
        { type: 's', html: scoreHtml },
      ]

      if (data.follow_up) {
        setRounds([round])
        setCurrentRound([{ type: 'q', html: `🤔 <b>追问</b><br>${data.follow_up}` }])
        setPhase('answering')
      } else {
        setRounds([round])
        setCurrentRound([])
        setPhase('scored')
      }
    } else {
      // question_only 模式
      setCurrentRound([{ type: 'q', html: `📝 <b>第${data.question_round}题</b><br>${data.question_content}` }])
      setPhase('answering')
    }
  }

  async function startStudy(id) {
    setLoading(true)
    const resp = await fetch(`${API}/start`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ knowledge_point_id: id }),
    }).then(r => r.json())
    setLoading(false)
    if (resp.code === 0) {
      const d = resp.data
      setConvId(d.conversation_id)
      setKpName(d.knowledge_point_name)
      setActiveKpId(id)
      setPhase('answering')
      setRounds([])
      setCollapsedRounds({})
      setCurrentRound([{ type: 'q', html: `📝 <b>第${d.question_round}题</b><br>${d.question_content}` }])
      navigate('/study', { replace: true })
    }
  }

  async function submitAnswer() {
    if (!input.trim() || loading) return
    const answer = input.trim()
    setInput('')
    setCurrentRound(r => [...r, { type: 'a', html: `💬 ${answer}` }])
    setLoading(true)

    const resp = await fetch(`${API}/answer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversation_id: convId, answer }),
    }).then(r => r.json())
    setLoading(false)

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

      if (d.follow_up) {
        // 追问：追加到 currentRound（不创建新 round）
        setCurrentRound(r => [...r, { type: 'a', html: `💬 ${answer}` }, { type: 's', html: scoreHtml }, { type: 'q', html: `🤔 <b>追问</b><br>${d.follow_up}` }])
        setPhase('answering')
      } else {
        // 追问结束 — 总结和扩展题放到独立 summary 消息
        let summaryHtml = ''
        if (d.overall_summary) {
          summaryHtml += `📝 <b>本轮总结</b><br><span style="line-height:1.8">${d.overall_summary}</span>`
        }
        const ext = d.extension_questions
        if (ext && Array.isArray(ext) && ext.length > 0) {
          summaryHtml += '<br><br><b>📚 扩展题目</b>'
          ext.forEach((eq, i) => { summaryHtml += `<div style="margin:6px 0;padding:8px 12px;background:#fff;border:1px solid #e8e8e8;border-radius:8px;"><b>${i + 1}. ${eq.question}</b><br><span style="color:#666;font-size:13px;">${eq.answer}</span></div>` })
        }
        const roundMsgs = [...currentRound, { type: 'a', html: `💬 ${answer}` }, { type: 's', html: scoreHtml }]
        if (summaryHtml) roundMsgs.push({ type: 'summary', html: summaryHtml })
        setRounds(r => [...r, roundMsgs])
        setCurrentRound([])
        setPhase('scored')
      }
    }
  }

  async function nextQuestion() {
    setLoading(true)
    const resp = await fetch(`${API}/next`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversation_id: convId }),
    }).then(r => r.json())
    setLoading(false)
    if (resp.code === 0) {
      const d = resp.data
      // 收起所有旧题
      const collapsed = {}
      rounds.forEach((_, i) => { collapsed[i] = true })
      setCollapsedRounds(collapsed)
      setCurrentRound([{ type: 'q', html: `📝 <b>第${d.question_round}题</b><br>${d.question_content}` }])
      setPhase('answering')
    }
  }

  function renderRound(msgs, idx) {
    const isCollapsed = collapsedRounds[idx]
    // 从 msgs 中提取标题信息
    const firstQ = msgs.find(m => m.type === 'q')
    const titleMatch = firstQ?.html?.match(/<b>(.*?)<\/b>/)
    const title = titleMatch ? titleMatch[1] : `第 ${idx + 1} 题`

    if (isCollapsed) {
      return (
        <div key={idx} className="round-group" style={{ cursor: 'pointer', padding: '10px 16px', opacity: 0.7 }}
          onClick={() => setCollapsedRounds(p => ({ ...p, [idx]: false }))}>
          <span style={{ fontSize: 13, color: '#888' }}>▸ {title}</span>
        </div>
      )
    }

    // 分离出 summary 类型的消息（独立卡片展示）
    const regularMsgs = msgs.filter(m => m.type !== 'summary')
    const summaryMsgs = msgs.filter(m => m.type === 'summary')

    return (
      <div key={idx}>
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
    <div>
      {/* ---- 知识点横向选择栏 ---- */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
        {kpList.map(kp => {
          const isActive = kp.id === activeKpId
          return (
            <button key={kp.id} onClick={() => { if (kp.id !== activeKpId) { setActiveKpId(kp.id); setKpName(kp.name); setRounds([]); setCurrentRound([]); setCollapsedRounds({}); startStudy(kp.id) } }}
              style={{
                padding: '8px 16px', borderRadius: 20, border: isActive ? '2px solid #1677ff' : '1px solid #e0e0e0',
                background: isActive ? '#f0f7ff' : '#fff', fontWeight: isActive ? 600 : 400,
                fontSize: 13, cursor: 'pointer', fontFamily: 'inherit', color: '#333',
                transition: 'all 0.15s',
              }}>
              {kp.name}
              {kp.mastery_level > 0 && <span style={{ fontSize: 11, color: '#aaa', marginLeft: 4 }}>{kp.mastery_level}%</span>}
            </button>
          )
        })}
      </div>

      {/* ---- 题目栏（显示所有题目含当前） ---- */}
      {activeKpId && (rounds.length > 0 || currentRound.length > 0) && (
        <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', padding: '12px 16px', marginBottom: 16 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 13, color: '#999', marginRight: 4 }}>题目</span>
          {rounds.map((r, i) => {
            const firstQ = r.find(m => m.type === 'q')
            const title = firstQ?.html?.replace(/<[^>]*>/g, '').replace(/📝|🤔/g, '').replace(/第\d+题/,'').trim() || `第${i+1}题`
            const isCurrent = !collapsedRounds[i]
            return (
              <button key={i} onClick={() => {
                const c = {}; rounds.forEach((_, j) => { c[j] = j !== i }); setCollapsedRounds(c)
              }}
                style={{
                  padding: '6px 14px', borderRadius: 16, fontSize: 12, cursor: 'pointer',
                  border: isCurrent ? '2px solid #722ed1' : '1px solid #e0e0e0',
                  background: isCurrent ? '#f9f0ff' : '#fafafa', fontWeight: isCurrent ? 600 : 400,
                  fontFamily: 'inherit', color: '#333', transition: 'all 0.15s',
                }}>
                {title}
              </button>
            )
          })}
          {/* 当前正在答的题 */}
          {currentRound.length > 0 && (
            <span style={{ padding: '6px 14px', borderRadius: 16, fontSize: 12, border: '2px solid #722ed1', background: '#f9f0ff', fontWeight: 600, color: '#722ed1' }}>
              {currentRound.find(m => m.type === 'q')?.html?.replace(/<[^>]*>/g, '').replace(/📝|🤔/g, '').replace(/第\d+题/,'').trim() || '当前题'}
            </span>
          )}
          {phase === 'scored' && (
            <button onClick={nextQuestion}
              style={{
                padding: '6px 14px', borderRadius: 16, fontSize: 12, cursor: 'pointer',
                border: '1px dashed #1677ff', background: '#fff', color: '#1677ff',
                fontFamily: 'inherit', fontWeight: 500,
              }}>
              ＋ 下一题
            </button>
          )}
          </div>
        </div>
      )}

      {/* ---- 对话区 ---- */}
      <div style={{ background: '#fff', borderRadius: 12, border: '1px solid #eee', padding: '24px', minHeight: 200 }}>
        {phase === 'select' && !loading && (
          <div className="empty">👆 从上方选择知识点开始学习</div>
        )}
        {loading && phase === 'select' && <div className="loading">🧠 正在出题...</div>}
        {phase !== 'select' && (
          <>
            <h2 style={{ marginBottom: 12 }}>📖 {kpName}</h2>
            {rounds.map((r, i) => renderRound(r, i))}
            {currentRound.length > 0 && renderRound(currentRound, 'cur')}
            {loading && <div className="loading">🤔 正在评分...</div>}

            {phase === 'answering' && !loading && (
              <div className="chat-input-wrap">
                <input className="chat-input" placeholder="输入你的回答..."
                       value={input} onChange={e => setInput(e.target.value)}
                       onKeyDown={e => e.key === 'Enter' && !e.shiftKey && submitAnswer()} />
                <button className="send-btn" onClick={submitAnswer} disabled={!input.trim()}>发送</button>
              </div>
            )}
            {phase === 'scored' && !loading && (
              <button className="next-btn" onClick={nextQuestion}>➡️ 下一题</button>
            )}
            <div ref={bottomRef} />
          </>
        )}
      </div>
    </div>
  )
}
