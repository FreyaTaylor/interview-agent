/**
 * ConversationView — 渲染一次作答 (question_attempt) 的完整对话 + 最终评分。
 *
 * 与后端 dialog 协议对应（每条 {role, type, ...}）：
 *   - agent / question     主问题（content）
 *   - user  / answer       用户回答（content）
 *   - agent / feedback     v1 study/项目：covered + content（范例回答，默认收起）
 *                          v2 项目拷打：note + gaps_found + signals（面试官点评，默认展开）
 *   - agent / follow_up    追问（content）
 *
 * v2 vs v1 通过 feedback 项是否含 `note` 字段自动判别；不需要 mode prop。
 *
 * status === 'finished' 时会额外渲染：rubric_result、overall_summary、design_issues。
 *
 * Props:
 *   attempt: 见 useQAFlow 返回的 attempt 对象
 *   showFinal: 默认 true，可关闭最终评分块
 */
import { useState } from 'react'
export default function ConversationView({ attempt, showFinal = true }) {
  if (!attempt) return null
  const dialog = attempt.dialog || []
  return (
    <div className="qa-conversation">
      {dialog.map((m, i) => <DialogItem key={i} m={m} />)}
      {showFinal && attempt.status === 'finished' && <FinalScoreBlock attempt={attempt} />}
    </div>
  )
}

function DialogItem({ m }) {
  const role = m.role
  const type = m.type
  if (role === 'user') {
    return (
      <div className="qa-msg qa-msg-user">
        <div className="qa-msg-tag">你的回答</div>
        <div className="qa-msg-body">{m.content}</div>
      </div>
    )
  }
  if (type === 'question') {
    return (
      <div className="qa-msg qa-msg-question">
        <div className="qa-msg-tag">主问题</div>
        <div className="qa-msg-body">{m.content}</div>
      </div>
    )
  }
  if (type === 'follow_up') {
    return (
      <div className="qa-msg qa-msg-followup">
        <div className="qa-msg-tag">追问</div>
        <div className="qa-msg-body">{m.content}</div>
      </div>
    )
  }
  if (type === 'feedback') {
    // v2 项目拷打：有 note 字段 → 面试官点评 + 漏洞
    if (m.note !== undefined && m.note !== null) {
      return <FeedbackBubbleV2 note={m.note} gapsFound={m.gaps_found || []} />
    }
    // v1 兼容：covered + content（范例回答，默认收起）
    const covered = !!m.covered
    return (
      <FeedbackBubble covered={covered} content={m.content} />
    )
  }
  return (
    <div className="qa-msg qa-msg-other">
      <div className="qa-msg-body">{m.content}</div>
    </div>
  )
}

// v2 面试官点评气泡：note 一段自然语言 + 本轮发现的漏洞（默认展开）
function FeedbackBubbleV2({ note, gapsFound }) {
  const [open, setOpen] = useState(true)
  return (
    <div className={`qa-msg qa-msg-feedback qa-msg-feedback-v2 ${open ? 'is-open' : 'is-collapsed'}`}>
      <button
        type="button"
        className="qa-msg-tag qa-feedback-toggle"
        onClick={() => setOpen(v => !v)}
        aria-expanded={open}
      >
        <span>面试官点评</span>
        <span className="qa-feedback-toggle-hint">{open ? '收起 ▲' : '展开 ▼'}</span>
      </button>
      {open && (
        <div className="qa-msg-body qa-feedback-v2-body">
          {note && <div className="qa-feedback-note">{note}</div>}
          {gapsFound.length > 0 && (
            <div className="qa-feedback-gaps">
              <div className="qa-feedback-gaps-title">本轮发现的漏洞</div>
              <ul className="qa-feedback-gaps-list">
                {gapsFound.map((g, i) => (
                  <li key={i}>
                    <span className="qa-feedback-gap-tag">[{g.category || '其他'}]</span>
                    <span className="qa-feedback-gap-point">{g.point}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// 范例回答气泡（v1）：默认收起（避免用户偷看答案），点击标题展开
function FeedbackBubble({ covered, content }) {
  const [open, setOpen] = useState(false)
  return (
    <div className={`qa-msg qa-msg-feedback ${covered ? 'covered' : 'missed'} ${open ? 'is-open' : 'is-collapsed'}`}>
      <button
        type="button"
        className="qa-msg-tag qa-feedback-toggle"
        onClick={() => setOpen(v => !v)}
        aria-expanded={open}
      >
        <span>范例回答</span>
        <span className={`qa-cov-badge ${covered ? 'ok' : 'miss'}`}>
          {covered ? '✓ 已覆盖' : '✗ 未覆盖'}
        </span>
        <span className="qa-feedback-toggle-hint">{open ? '收起 ▲' : '点击展开 ▼'}</span>
      </button>
      {open && <FeedbackBody content={content} />}
    </div>
  )
}

// 范例回答正文：兼容 string（旧数据）与 string[]（新版分点）
function FeedbackBody({ content }) {
  // 数组 → 分点列表
  if (Array.isArray(content)) {
    const items = content.map(s => String(s || '').trim()).filter(Boolean)
    if (items.length === 0) return null
    return (
      <ul className="qa-msg-body qa-feedback-list">
        {items.map((p, i) => <li key={i}>{p}</li>)}
      </ul>
    )
  }
  // 字符串 → 优先按换行拆点；否则按中文项目符号拆点
  const text = String(content || '')
  let lines = text.split(/\n+/).map(s => s.trim()).filter(Boolean)
  if (lines.length < 2) {
    // 兜底：按 "1." / "一、" / "•" / "·" / "- " 切分
    lines = text
      .split(/\s*(?:[1-9]\.|[一二三四五六七八九十]、|•|·|-\s)/)
      .map(s => s.trim())
      .filter(Boolean)
  } else {
    // 已按行切分时，去掉行首符号
    lines = lines.map(s => s.replace(/^[\s\-•·]*(?:[1-9]\.|[一二三四五六七八九十]、)?\s*/, ''))
  }
  if (lines.length >= 2) {
    return (
      <ul className="qa-msg-body qa-feedback-list">
        {lines.map((p, i) => <li key={i}>{p}</li>)}
      </ul>
    )
  }
  return <div className="qa-msg-body">{text}</div>
}

function FinalScoreBlock({ attempt }) {
  const score = attempt.final_score
  const rubric = attempt.rubric_result || []
  const summary = attempt.overall_summary || ''
  const designIssues = attempt.design_issues || []
  const extensionQa = attempt.extension_qa || []

  return (
    <div className="qa-final">
      <div className="qa-final-head">
        <div className="qa-final-title">本题综合评分</div>
        <div className={`qa-final-score ${scoreLevel(score)}`}>
          {score == null ? '—' : score}
        </div>
      </div>
      {summary && <div className="qa-final-summary">{summary}</div>}
      {rubric.length > 0 && (
        <div className="qa-rubric-compact">
          {rubric.map((r, i) => (
            <div key={i} className={`qa-rubric-line ${r.hit ? 'hit' : 'miss'}`}>
              <div className="qa-rubric-line-head">
                <span className="qa-rubric-mark">{r.hit ? '✅' : '❌'}</span>
                <span className="qa-rubric-key">{r.key_point}</span>
                {r.score != null && <span className="qa-rubric-pts">（{r.score}分）</span>}
              </div>
              {r.matched_text && <div className="qa-rubric-matched">“{r.matched_text}”</div>}
              {r.standard_answer && (
                <div className="qa-rubric-standard-inline">
                  <span className="qa-rubric-standard-label">标准答案：</span>
                  {r.standard_answer}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      {designIssues.length > 0 && (
        <div className="qa-design-issues">
          <div className="qa-design-title">设计可改进点</div>
          <ul>
            {designIssues.map((d, i) => (
              <li key={i}>{typeof d === 'string' ? d : (d.issue || JSON.stringify(d))}</li>
            ))}
          </ul>
        </div>
      )}
      {extensionQa.length > 0 && (
        <div className="qa-extension-qa">
          <div className="qa-extension-title">📚 延伸深挖（{extensionQa.length} 个方向）</div>
          <div className="qa-extension-list">
            {extensionQa.map((item, i) => (
              <div key={i} className="qa-extension-item">
                <div className="qa-extension-q">Q{i + 1}. {item.q}</div>
                <div className="qa-extension-a">{item.a}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function scoreLevel(score) {
  if (score == null) return 'na'
  if (score >= 85) return 'high'
  if (score >= 60) return 'mid'
  return 'low'
}
