import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'

const API = 'http://127.0.0.1:8000/api/study'

export default function StudyPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const [kpList, setKpList] = useState([])
  const [convId, setConvId] = useState(null)
  const [kpName, setKpName] = useState('')
  const [phase, setPhase] = useState('select') // select | answering | scored
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const bottomRef = useRef(null)

  // 加载知识点列表
  useEffect(() => {
    fetch(`${API}/knowledge-points`).then(r => r.json()).then(d => {
      if (d.code === 0) setKpList(d.data)
    })
  }, [])

  // 从知识树跳转：自动出题
  useEffect(() => {
    if (kpId && kpList.length > 0) {
      startStudy(parseInt(kpId))
    }
  }, [kpId, kpList])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [rounds, currentRound])

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
      setPhase('answering')
      setRounds([])
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
      let scoreHtml = `<b>得分: ${d.total_score}/100</b> — ${d.feedback}<br>`
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
        setRounds(r => [...r, [...currentRound, { type: 'a', html: `💬 ${answer}` }, { type: 's', html: scoreHtml }]])
        setCurrentRound([{ type: 'q', html: `🤔 <b>追问</b><br>${d.follow_up}` }])
        setPhase('answering')
      } else {
        setRounds(r => [...r, [...currentRound, { type: 'a', html: `💬 ${answer}` }, { type: 's', html: scoreHtml }]])
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
      setCurrentRound([{ type: 'q', html: `📝 <b>第${d.question_round}题</b><br>${d.question_content}` }])
      setPhase('answering')
    }
  }

  function renderRound(msgs, idx) {
    return (
      <div className="round-group" key={idx}>
        {msgs.map((m, i) => (
          <div key={i} className={m.type === 'q' ? 'q-box' : m.type === 'a' ? 'a-box' : 's-box'}
               dangerouslySetInnerHTML={{ __html: m.html }} />
        ))}
      </div>
    )
  }

  return (
    <div className="study-sidebar">
      <div className="sidebar">
        <h3>📋 知识点</h3>
        {kpList.map(kp => (
          <button key={kp.id} className="kp-btn" onClick={() => startStudy(kp.id)}>
            {'⭐'.repeat(kp.interview_weight)} {kp.name}
            {kp.mastery_level > 0 && <div className="kp-mastery">{kp.mastery_level}%</div>}
          </button>
        ))}
      </div>
      <div className="study-main">
        {phase === 'select' && !loading && (
          <div className="empty">👈 从左侧选择知识点，或从知识树点击进入</div>
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
