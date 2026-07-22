/**
 * 管理页「📋 面试真题」子 tab — 三层结构：面试(公司/岗位/时间) → 主题 → 问题。可内联编辑。
 *
 * 编辑：问题文本（点✎/点文本，失焦/Enter 保存 PATCH text）；主题（点主题名 PATCH topic，
 * 组内多 ref 逐一改；主题来自关联知识点时置灰不可改）；删除（点× 删该问题，删空则整行没）。
 * 数据来自 GET /api/interview/admin/all-questions（全局 fetch 已注入 JWT）。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { API_INTERVIEW } from '../config'
import { formatBeijing } from '../utils/datetime'

// 问题类型 → 中文小徽标
const TYPE_LABEL = { knowledge: '八股', project: '项目', other: '其他' }
const QURL = `${API_INTERVIEW}/admin/question`

async function apiPatch(body) {
  const r = await fetch(QURL, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }).then(res => res.json())
  if (r.code !== 0) throw new Error(r.message || '保存失败')
}
async function apiDelete(q) {
  const r = await fetch(`${QURL}?ref_type=${q.ref_type}&ref_id=${q.ref_id}&idx=${q.idx}`, { method: 'DELETE' })
    .then(res => res.json())
  if (r.code !== 0) throw new Error(r.message || '删除失败')
}

function QuestionRow({ q, onChanged }) {
  const [editing, setEditing] = useState(false)
  const [val, setVal] = useState(q.text)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef(null)

  useEffect(() => { if (editing) inputRef.current?.focus() }, [editing])

  function start() { setVal(q.text); setEditing(true) }
  async function save() {
    setEditing(false)
    if (val === q.text) return
    setBusy(true)
    try { await apiPatch({ ref_type: q.ref_type, ref_id: q.ref_id, idx: q.idx, text: val }); await onChanged() }
    catch (e) { alert(e.message) } finally { setBusy(false) }
  }
  async function del() {
    if (!window.confirm(`确定删除该问题？\n「${q.text}」`)) return
    setBusy(true)
    try { await apiDelete(q); await onChanged() }
    catch (e) { alert(e.message) } finally { setBusy(false) }
  }

  return (
    <div className="iqe-q-row">
      <span className={`iqe-type-badge ${q.ref_type}`}>{TYPE_LABEL[q.ref_type] || q.ref_type}</span>
      {editing ? (
        <textarea
          ref={inputRef} className="iqe-q-input" rows={1} value={val}
          onChange={e => setVal(e.target.value)} onBlur={save}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); save() }
            if (e.key === 'Escape') setEditing(false)
          }}
        />
      ) : (
        <span className="iqe-q-text" onClick={start} title="点击编辑">
          {q.text || <em className="iqe-empty-text">（空）</em>}
        </span>
      )}
      {!editing && q.leetcode_url && (
        <a
          className="lc-tag"
          href={q.leetcode_url}
          target="_blank"
          rel="noreferrer"
          onClick={e => e.stopPropagation()}
          title="在 LeetCode 打开"
        >
          🔗 {q.leetcode_title || 'LeetCode'}
        </a>
      )}
      {!editing && (
        <span className="iqe-q-actions">
          <button className="iqe-q-btn" disabled={busy} onClick={start} title="编辑">✎</button>
          <button className="iqe-q-btn danger" disabled={busy} onClick={del} title="删除">×</button>
        </span>
      )}
    </div>
  )
}

function TopicGroup({ tg, onChanged }) {
  const [collapsed, setCollapsed] = useState(false)
  const [editing, setEditing] = useState(false)
  const [val, setVal] = useState(tg.topic)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef(null)
  const editable = tg.questions.every(q => q.topic_editable)

  useEffect(() => { if (editing) inputRef.current?.focus() }, [editing])

  function start() { if (!editable) return; setVal(tg.topic); setEditing(true) }
  async function save() {
    setEditing(false)
    const t = val.trim()
    if (!t || t === tg.topic) return
    setBusy(true)
    try {
      // 组内可能含多个 ref（如多条 other），逐个改主题
      const refs = [...new Map(tg.questions.map(q => [`${q.ref_type}-${q.ref_id}`, q])).values()]
      for (const q of refs) await apiPatch({ ref_type: q.ref_type, ref_id: q.ref_id, topic: t })
      await onChanged()
    } catch (e) { alert(e.message) } finally { setBusy(false) }
  }

  return (
    <div className="iqe-topic">
      <div className="iqe-topic-head">
        <span className={`iqe-toggle ${collapsed ? '' : 'open'}`} onClick={() => setCollapsed(c => !c)} />
        {editing ? (
          <input ref={inputRef} className="iqe-topic-input" value={val} disabled={busy}
            onChange={e => setVal(e.target.value)} onBlur={save}
            onKeyDown={e => { if (e.key === 'Enter') save(); if (e.key === 'Escape') setEditing(false) }} />
        ) : (
          <span className={`iqe-topic-name ${editable ? 'editable' : ''}`} onClick={start}
            title={editable ? '点击编辑主题' : '主题来自关联知识点，不可在此修改'}>
            {tg.topic}
          </span>
        )}
        {!editable && <span className="iqe-topic-lock" title="主题来自关联知识点，不可在此修改">🔒</span>}
        <span className="iqe-topic-count">{tg.questions.length}</span>
      </div>
      {!collapsed && (
        <div className="iqe-topic-body">
          {tg.questions.map(q => (
            <QuestionRow key={`${q.ref_type}-${q.ref_id}-${q.idx}`} q={q} onChanged={onChanged} />
          ))}
        </div>
      )}
    </div>
  )
}

function RecordGroup({ rec, onChanged }) {
  const [collapsed, setCollapsed] = useState(false)
  const qCount = rec.topics.reduce((n, t) => n + t.questions.length, 0)
  return (
    <div className="iqe-record">
      <div className="iqe-record-head" onClick={() => setCollapsed(c => !c)}>
        <span className={`iqe-toggle ${collapsed ? '' : 'open'}`} />
        <span className="iqe-record-title">
          {rec.company || '未知公司'}{rec.position ? ` · ${rec.position}` : ''}
        </span>
        <span className="iqe-record-meta">{formatBeijing(rec.created_at)} · {qCount} 题</span>
      </div>
      {!collapsed && (
        <div className="iqe-record-body">
          {rec.topics.map(tg => <TopicGroup key={tg.topic} tg={tg} onChanged={onChanged} />)}
        </div>
      )}
    </div>
  )
}

export default function InterviewQuestionsEditor() {
  const [records, setRecords] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  const reload = useCallback(() => {
    return fetch(`${API_INTERVIEW}/admin/all-questions`)
      .then(r => r.json())
      .then(d => {
        if (d.code !== 0) throw new Error(d.message || '加载失败')
        setRecords(d.data || [])
        setError(null)
      })
      .catch(e => setError(e.message || '加载面试问题失败'))
  }, [])

  useEffect(() => { reload().finally(() => setLoading(false)) }, [reload])

  if (loading) return <div className="iqe-state">加载中…</div>
  if (error) return <div className="iqe-state iqe-error">{error}</div>
  if (!records.length) return <div className="iqe-state">暂无面试问题 —— 去「面试复盘」录入并解析后，这里会按 面试 → 主题 → 问题 展示。</div>

  return (
    <div className="iqe-container">
      {records.map(rec => <RecordGroup key={rec.record_id} rec={rec} onChanged={reload} />)}
    </div>
  )
}
