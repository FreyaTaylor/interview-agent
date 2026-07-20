// 面试解析校对页（Stage 2）
//
// 设计：扁平 turn 列表保持原文顺序，group 仅作"分界标题"
//   - 相邻两个 turn 的 groupId 不同时，在中间画 group header
//   - 同 group 内 turn 用同色背景（蓝/绿交替按 group 顺序）
//   - 每个 turn 行：[speaker 按钮 可点切换] + [textarea] + [删除]
//   - 每个 turn 行底部 hover 出 [+ 在此后新增一行] 按钮（speaker 默认对方，同 group）
//
// 数据模型：
//   groupsMap: { gid -> {tag, type, questions, project_name} }
//   flatTurns: [{ uid, speaker, content, groupId }, ...]   按原文顺序
//
// 提交：flatTurns 按位置分配连续 id；按 groupId 聚合 turn_ids；丢弃未归属 group 的 turn

import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { API_INTERVIEW } from '../config'

let _uidSeed = 1
const newUid = () => `u${_uidSeed++}`
let _gidSeed = 1
const newGid = () => `g${_gidSeed++}`

const TYPE_LABEL = {
  knowledge: '知识点', project: '项目', algorithm: '算法',
  hr: 'HR', other: '其他',
}

const SPEAKER_COLOR = { '面试官': '#1f6feb', '我': '#1a7f37' }  // 蓝 / 绿
const ORPHAN_GID = '__orphan__'

// 段落浅色背景（按 group 出现顺序循环）
const SECTION_BG = ['#f0f6ff', '#f0faf3']  // 浅蓝 / 浅绿
const SECTION_BORDER = ['#d6e8ff', '#d6f0de']
const ORPHAN_BG = '#fff7e6'
const ORPHAN_BORDER = '#ffd591'

// 路由包装（兼容旧路径访问）—— 推荐在 InterviewPage 内部以 modal 方式使用 InterviewReviewModal
export default function InterviewReviewPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const initial = location.state || {}
  useEffect(() => {
    if (!initial.turns || !initial.groups) navigate('/interview', { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  if (!initial.turns || !initial.groups) return null
  return (
    <div style={{ background: '#fff', minHeight: '100vh' }}>
      <div style={{ maxWidth: 880, margin: '0 auto', padding: '20px 24px 60px' }}>
        <InterviewReviewModal
          initialTurns={initial.turns} initialGroups={initial.groups}
          company={initial.company || ''} position={initial.position || ''}
          onCancel={() => navigate('/interview', { replace: true })}
          onSubmitted={(recordId) => navigate(`/interview/${recordId}`, { replace: true })}
        />
      </div>
    </div>
  )
}

export function InterviewReviewModal({
  initialTurns, initialGroups, company = '', position = '',
  onCancel, onSubmitted, onSaved,
  recalibrateRecordId = null,
  // 草稿模式：初上传场景 null；保存过后会变为 record_id，后续保存走 update
  initialDraftRecordId = null,
}) {
  const [groupsMap, setGroupsMap] = useState({})
  const [flatTurns, setFlatTurns] = useState([])
  const [submitting, setSubmitting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveHint, setSaveHint] = useState('')
  // 保存后拿到的 record_id；下次保存/提交复用
  const [draftRecordId, setDraftRecordId] = useState(initialDraftRecordId)
  const [error, setError] = useState('')
  // 撤回历史栈：仅记录结构性修改（不含纯内容编辑，后者交给浏览器原生 Cmd+Z）
  const historyRef = useRef([])
  const [historyLen, setHistoryLen] = useState(0)
  function pushHistory() {
    historyRef.current.push({ flatTurns, groupsMap })
    if (historyRef.current.length > 50) historyRef.current.shift()
    setHistoryLen(historyRef.current.length)
  }
  function undo() {
    const snap = historyRef.current.pop()
    if (!snap) return
    setFlatTurns(snap.flatTurns)
    setGroupsMap(snap.groupsMap)
    setHistoryLen(historyRef.current.length)
  }
  // 全局 Cmd/Ctrl+Z 快捷键（不在可编辑元素上）
  useEffect(() => {
    const onKey = (e) => {
      const isUndo = (e.metaKey || e.ctrlKey) && !e.shiftKey && (e.key === 'z' || e.key === 'Z')
      if (!isUndo) return
      const tag = (e.target?.tagName || '').toUpperCase()
      if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable) return
      e.preventDefault()
      undo()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 初始化
  useEffect(() => {
    if (!initialTurns || !initialGroups) return
    const turnToGid = new Map()
    const gMap = {}
    for (const g of initialGroups) {
      const gid = newGid()
      gMap[gid] = {
        tag: g.tag || g.knowledge_point || g.topic || '未命名',
        type: g.type || 'other',
        questions: g.questions || [],
        project_name: g.project_name || null,
      }
      for (const tid of (g.turn_ids || [])) {
        if (!turnToGid.has(tid)) turnToGid.set(tid, gid)
      }
    }
    gMap[ORPHAN_GID] = { tag: '⚠️ 未归属（建议删除）', type: 'other', questions: [], project_name: null }
    const sortedTurns = [...initialTurns].sort((a, b) => a.id - b.id)
    setGroupsMap(gMap)
    setFlatTurns(sortedTurns.map(t => ({
      uid: newUid(),
      speaker: t.speaker || '面试官',
      content: t.content || '',
      groupId: turnToGid.get(t.id) || ORPHAN_GID,
    })))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // groupId → 出现顺序索引（仅用于 header 编号）
  const gidColorIdx = useMemo(() => {
    const seen = new Map()
    let idx = 0
    for (const t of flatTurns) {
      if (t.groupId !== ORPHAN_GID && !seen.has(t.groupId)) {
        seen.set(t.groupId, idx++)
      }
    }
    return seen
  }, [flatTurns])

  // ===== 编辑 =====
  function toggleSpeaker(uid) {
    pushHistory()
    setFlatTurns(prev => prev.map(t =>
      t.uid === uid ? { ...t, speaker: t.speaker === '面试官' ? '我' : '面试官' } : t
    ))
  }
  function updateContent(uid, content) {
    setFlatTurns(prev => prev.map(t => t.uid === uid ? { ...t, content } : t))
  }
  function deleteTurn(uid) {
    pushHistory()
    setFlatTurns(prev => prev.filter(t => t.uid !== uid))
  }
  // 在当前 turn 开头按 Backspace：将内容追加到上一条 turn 并删除当前
  function mergeWithPrev(uid) {
    pushHistory()
    setFlatTurns(prev => {
      const idx = prev.findIndex(t => t.uid === uid)
      if (idx <= 0) return prev
      const cur = prev[idx]
      const prevTurn = prev[idx - 1]
      const sep = prevTurn.content && cur.content ? ' ' : ''
      const merged = { ...prevTurn, content: (prevTurn.content || '') + sep + (cur.content || '') }
      return [...prev.slice(0, idx - 1), merged, ...prev.slice(idx + 1)]
    })
  }
  function insertAfter(uid) {
    pushHistory()
    setFlatTurns(prev => {
      const idx = prev.findIndex(t => t.uid === uid)
      if (idx < 0) return prev
      const newTurn = {
        uid: newUid(),
        // 默认切到对方（问答交替场景占多数）
        speaker: prev[idx].speaker === '面试官' ? '我' : '面试官',
        content: '',
        groupId: prev[idx].groupId,
      }
      return [...prev.slice(0, idx + 1), newTurn, ...prev.slice(idx + 1)]
    })
  }
  function updateGroupTag(gid, tag) {
    setGroupsMap(prev => ({ ...prev, [gid]: { ...prev[gid], tag } }))
  }
  // 删除整个 section：移除该 group 下所有 turn，并清掉 group 元数据
  function deleteGroup(gid) {
    if (gid === ORPHAN_GID) return
    pushHistory()
    setFlatTurns(prev => prev.filter(t => t.groupId !== gid))
    setGroupsMap(prev => {
      const next = { ...prev }
      delete next[gid]
      return next
    })
  }
  // 在指定 turn 之后拆分当前 section：后续连续同 gid 的 turn 迁到新 group
  function splitAfter(uid) {
    const idx = flatTurns.findIndex(t => t.uid === uid)
    if (idx < 0 || idx >= flatTurns.length - 1) return
    const oldGid = flatTurns[idx].groupId
    if (oldGid === ORPHAN_GID) return
    const moveUids = []
    for (let i = idx + 1; i < flatTurns.length; i++) {
      if (flatTurns[i].groupId !== oldGid) break
      moveUids.push(flatTurns[i].uid)
    }
    if (moveUids.length === 0) return
    pushHistory()
    const newId = newGid()
    const oldMeta = groupsMap[oldGid] || {}
    setGroupsMap(prev => ({
      ...prev,
      [newId]: {
        tag: (oldMeta.tag || '未命名') + '（拆分）',
        type: oldMeta.type || 'other',
        questions: [],
        project_name: oldMeta.project_name || null,
      },
    }))
    const moveSet = new Set(moveUids)
    setFlatTurns(prev => prev.map(t => moveSet.has(t.uid) ? { ...t, groupId: newId } : t))
  }
  // 合并：把 srcGid 下所有 turn 迁到 dstGid，并删除 srcGid 元数据
  function mergeInto(srcGid, dstGid) {
    if (!srcGid || !dstGid || srcGid === dstGid) return
    if (srcGid === ORPHAN_GID || dstGid === ORPHAN_GID) return
    pushHistory()
    setFlatTurns(prev => prev.map(t => t.groupId === srcGid ? { ...t, groupId: dstGid } : t))
    setGroupsMap(prev => {
      const next = { ...prev }
      delete next[srcGid]
      return next
    })
  }
  // 删除未归属组下所有 turn（保留 ORPHAN_GID 元数据）
  function clearOrphan() {
    pushHistory()
    setFlatTurns(prev => prev.filter(t => t.groupId !== ORPHAN_GID))
  }

  // ===== 公共：构建 finalTurns + groupsOut payload（保存/提交共用）=====
  // 返回 { payload: {finalTurns, groupsOut} } 或 { error: '...' }
  function buildPayload() {
    const tmpTurns = []
    const gidToTmpIds = new Map()
    flatTurns.forEach(t => {
      const content = (t.content || '').trim()
      if (!content) return
      const tmpId = tmpTurns.length
      tmpTurns.push({ tmpId, speaker: t.speaker || '面试官', content, gid: t.groupId })
      if (!gidToTmpIds.has(t.groupId)) gidToTmpIds.set(t.groupId, [])
      gidToTmpIds.get(t.groupId).push(tmpId)
    })
    if (tmpTurns.length === 0) return { error: '校对结果为空' }
    const validTmpIds = new Set()
    const groupsOut = []
    for (const [gid, tmpIds] of gidToTmpIds.entries()) {
      if (gid === ORPHAN_GID) continue
      tmpIds.forEach(t => validTmpIds.add(t))
      const meta = groupsMap[gid] || {}
      groupsOut.push({
        type: meta.type, tag: meta.tag,
        questions: meta.questions, project_name: meta.project_name,
        _tmpIds: tmpIds,
      })
    }
    if (groupsOut.length === 0) return { error: '所有 turn 都在"未归属"组中，请先删除或归类' }
    const finalTurns = []
    const idMap = new Map()
    tmpTurns.forEach(t => {
      if (!validTmpIds.has(t.tmpId)) return
      idMap.set(t.tmpId, finalTurns.length)
      finalTurns.push({ id: finalTurns.length, speaker: t.speaker, content: t.content })
    })
    for (const g of groupsOut) {
      g.turn_ids = g._tmpIds.map(t => idMap.get(t)).filter(x => x !== undefined)
      delete g._tmpIds
    }
    return { payload: { finalTurns, groupsOut } }
  }

  // ===== 保存草稿（不触发解析/评分）=====
  async function handleSave() {
    setError('')
    setSaveHint('')
    const { payload, error: err } = buildPayload()
    if (err) { setError(err); return }
    setSaving(true)
    try {
      const resp = await fetch(`${API_INTERVIEW}/draft`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          record_id: draftRecordId || recalibrateRecordId || null,
          turns: payload.finalTurns,
          groups: payload.groupsOut,
          company, position,
        }),
      }).then(r => r.json())
      if (resp.code === 0 && resp.data?.record_id) {
        setDraftRecordId(resp.data.record_id)
        setSaveHint(`✓ 已保存草稿 #${resp.data.record_id}`)
        onSaved?.(resp.data.record_id)
        setTimeout(() => setSaveHint(''), 2500)
      } else {
        setError(resp.message || resp.detail || '保存失败')
      }
    } catch (e) {
      setError('请求失败: ' + (e.message || '网络错误'))
    } finally {
      setSaving(false)
    }
  }

  // ===== 提交（finalize 或 recalibrate，触发解析+评分）=====
  async function handleSubmit() {
    setError('')
    const { payload, error: err } = buildPayload()
    if (err) { setError(err); return }
    const { finalTurns, groupsOut } = payload

    // 优先级：recalibrateRecordId（已解析的继续校准）→ draftRecordId（草稿提交）→ finalize（首次）
    const targetRecordId = recalibrateRecordId || draftRecordId
    setSubmitting(true)
    try {
      const url = targetRecordId
        ? `${API_INTERVIEW}/history/${targetRecordId}/recalibrate`
        : `${API_INTERVIEW}/finalize`
      const body = targetRecordId
        ? { turns: finalTurns, groups: groupsOut }
        : { turns: finalTurns, groups: groupsOut, company, position }
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }).then(r => r.json())
      if (resp.code === 0 && resp.data?.record_id) {
        onSubmitted?.(resp.data.record_id)
      } else {
        setError(resp.message || resp.detail || '提交失败')
      }
    } catch (e) {
      setError('请求失败: ' + (e.message || '网络错误'))
    } finally {
      setSubmitting(false)
    }
  }

  function handleCancel() {
    onCancel?.()
  }

  // ===== 渲染：把连续相同 groupId 的 turns 聚成段落 section =====
  const sections = []
  for (const t of flatTurns) {
    const last = sections[sections.length - 1]
    if (last && last.gid === t.groupId) last.turns.push(t)
    else sections.push({ gid: t.groupId, turns: [t] })
  }

  return (
    <>
      {/* 顶栏 */}
      <div style={{
        position: 'sticky', top: 0, background: '#fff', padding: '14px 0 12px',
        borderBottom: '1px solid #eef0f3', marginBottom: 20, zIndex: 10,
        display: 'flex', alignItems: 'center', gap: 12,
      }}>
          <div style={{ flex: 1 }}>
            <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600, color: '#1f2328', letterSpacing: 0.2 }}>
              面试解析校对
            </h2>
            <div style={{ fontSize: 12, color: '#6e7781', marginTop: 5 }}>
              {company && (
                <>
                  <span style={{ color: '#1f2328', fontWeight: 500 }}>
                    {company}
                  </span>
                  <span style={{ margin: '0 8px', color: '#d0d7de' }}>|</span>
                </>
              )}
              点说话人切换 · 鼠标悬停可 + 新增 / × 删除 · 点知识点名可改
            </div>
          </div>
          <button onClick={undo} disabled={submitting || saving || historyLen === 0}
            title={historyLen > 0 ? `撤回上一步（还可撤 ${historyLen} 步 · Cmd/Ctrl+Z）` : '没有可撤回的操作'}
            style={{
              padding: '8px 14px', border: '1px solid #d0d7de',
              background: historyLen === 0 ? '#f6f8fa' : '#fff',
              borderRadius: 6, cursor: historyLen === 0 ? 'not-allowed' : 'pointer',
              fontSize: 14, color: historyLen === 0 ? '#8b949e' : '#24292f',
              fontWeight: 500, display: 'inline-flex', alignItems: 'center', gap: 4,
            }}>
            撤回
          </button>
          <button onClick={handleCancel} disabled={submitting || saving}
            style={{
              padding: '8px 18px', border: '1px solid #d0d7de', background: '#fff',
              borderRadius: 6, cursor: 'pointer', fontSize: 14, color: '#24292f',
              fontWeight: 500,
            }}>
            取消
          </button>
          <button onClick={handleSave} disabled={submitting || saving}
            title="仅保存当前校对内容到草稿，不触发解析/评分；下次可继续编辑"
            style={{
              padding: '8px 16px', border: '1px solid #d0d7de',
              background: saving ? '#f6f8fa' : '#fff',
              borderRadius: 6, cursor: saving ? 'wait' : 'pointer',
              fontSize: 14, color: '#24292f', fontWeight: 500,
            }}>
            {saving ? '保存中…' : (draftRecordId ? '💾 更新草稿' : '💾 保存草稿')}
          </button>
          <button onClick={handleSubmit} disabled={submitting || saving}
            style={{
              padding: '8px 20px', border: '1px solid transparent', fontWeight: 600,
              background: submitting
                ? '#94a3b8'
                : 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
              color: '#fff', borderRadius: 6, cursor: submitting ? 'wait' : 'pointer',
              fontSize: 14, boxShadow: submitting ? 'none' : '0 2px 6px rgba(102,126,234,0.35)',
            }}>
            {submitting ? '解析中…' : '解析'}
          </button>
        </div>

        {saveHint && (
          <div style={{
            padding: '8px 14px', background: '#f0fdf4', border: '1px solid #bbf7d0',
            color: '#166534', borderRadius: 6, fontSize: 13, marginTop: 8,
          }}>
            {saveHint}
          </div>
        )}

        {error && (
          <div style={{
            padding: '10px 14px', background: '#fff2f0', border: '1px solid #ffccc7',
            borderRadius: 6, fontSize: 13, color: '#cf1322', marginBottom: 16,
          }}>
            {error}
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {sections.map((sec, secIdx) => {
            const g = groupsMap[sec.gid]
            if (!g) return null
            const isOrphan = sec.gid === ORPHAN_GID
            const colorIdx = gidColorIdx.get(sec.gid)
            const bg = isOrphan ? ORPHAN_BG : SECTION_BG[(colorIdx ?? 0) % SECTION_BG.length]
            const bd = isOrphan ? ORPHAN_BORDER : SECTION_BORDER[(colorIdx ?? 0) % SECTION_BORDER.length]
            return (
              <section key={`sec-${sec.turns[0]?.uid ?? sec.gid}`} style={{
                background: bg, border: `1px solid ${bd}`, borderRadius: 8,
                padding: '12px 16px',
              }}>
                {/* group header */}
                <div style={{
                  display: 'flex', alignItems: 'baseline', gap: 8, fontSize: 12,
                  marginBottom: 8, paddingBottom: 8,
                  borderBottom: `1px dashed ${bd}`,
                }}>
                  {!isOrphan && (
                    <span style={{
                      color: '#6e7781', fontWeight: 600,
                      background: 'rgba(255,255,255,0.7)',
                      padding: '1px 6px', borderRadius: 3, fontSize: 11,
                    }}>
                      #{(colorIdx ?? 0) + 1}
                    </span>
                  )}
                  {isOrphan ? (
                    <span style={{ color: '#fa8c16', fontWeight: 600, fontSize: 13 }}>
                      {g.tag}
                    </span>
                  ) : (
                    <EditableText
                      value={g.tag}
                      onChange={(v) => updateGroupTag(sec.gid, v)}
                      style={{
                        color: '#1f2328', fontWeight: 600, fontSize: 14,
                        borderBottom: '1px dotted transparent',
                        padding: '0 2px',
                      }}
                      hoverBorderColor="#8b949e"
                      title="点击修改知识点名称"
                    />
                  )}
                  {!isOrphan && (
                    <span style={{ color: '#8b949e' }}>
                      · {TYPE_LABEL[g.type] || g.type}
                      {g.project_name && ` · ${g.project_name}`}
                    </span>
                  )}
                  {isOrphan && (
                    <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 2 }}>
                      <IconBtn onClick={() => {
                        const count = sec.turns.length
                        if (window.confirm(`删除「未归属」模块下的全部 ${count} 条对话？`)) {
                          clearOrphan()
                        }
                      }} title="删除未归属下的全部对话" color="#ff4d4f">
                        <IconTrash />
                      </IconBtn>
                    </span>
                  )}
                  {!isOrphan && (() => {
                    // 上一个非 ORPHAN section 作为合并目标
                    let prevGid = null
                    for (let i = secIdx - 1; i >= 0; i--) {
                      if (sections[i].gid !== ORPHAN_GID) { prevGid = sections[i].gid; break }
                    }
                    const prevTag = prevGid ? groupsMap[prevGid]?.tag : null
                    return (
                      <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 2 }}>
                        <IconBtn
                          onClick={() => prevGid && mergeInto(sec.gid, prevGid)}
                          title={prevGid ? `合并到上一模块「${prevTag}」` : '没有上一模块可合并'}
                          color={prevGid ? '#1677ff' : '#bbb'}>
                          <IconMerge />
                        </IconBtn>
                        <IconBtn onClick={() => {
                          if (window.confirm(`删除整个模块「${g.tag}」？该模块下所有对话都会被移除。`)) {
                            deleteGroup(sec.gid)
                          }
                        }} title="删除整个模块" color="#ff4d4f">
                          <IconTrash />
                        </IconBtn>
                      </span>
                    )
                  })()}
                </div>
                {/* turns */}
                <div>
                  {sec.turns.map(t => (
                    <TurnRow key={t.uid} turn={t}
                      onSpeakerToggle={() => toggleSpeaker(t.uid)}
                      onContentChange={(v) => updateContent(t.uid, v)}
                      onDelete={() => deleteTurn(t.uid)}
                      onInsertAfter={() => insertAfter(t.uid)}
                      onSplitAfter={isOrphan ? null : () => splitAfter(t.uid)}
                      onMergeWithPrev={() => mergeWithPrev(t.uid)}
                    />
                  ))}
                </div>
              </section>
            )
          })}
        </div>

        {flatTurns.length === 0 && (
          <div style={{ padding: 40, textAlign: 'center', color: '#999' }}>
            暂无内容。<button onClick={handleCancel} style={{ marginLeft: 8 }}>返回</button>
          </div>
        )}
    </>
  )
}


function TurnRow({ turn, onSpeakerToggle, onContentChange, onDelete, onInsertAfter, onSplitAfter, onMergeWithPrev }) {
  const [hover, setHover] = useState(false)
  const speakerColor = SPEAKER_COLOR[turn.speaker] || '#666'
  // 在内容开头按 Backspace 合并到上一条
  const handleKeyDown = (e) => {
    if (e.key !== 'Backspace') return
    const sel = window.getSelection()
    if (!sel || sel.rangeCount === 0) return
    const range = sel.getRangeAt(0)
    if (!range.collapsed) return
    // 判断光标是否在可编辑元素的最开头
    const editable = e.currentTarget
    const preRange = range.cloneRange()
    preRange.selectNodeContents(editable)
    preRange.setEnd(range.endContainer, range.endOffset)
    if (preRange.toString().length === 0) {
      e.preventDefault()
      onMergeWithPrev && onMergeWithPrev()
    }
  }
  return (
    <div
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{ padding: '2px 0', lineHeight: 1.7, fontSize: 14 }}>
      <span onClick={onSpeakerToggle} title="点击切换 面试官/我"
        style={{
          cursor: 'pointer', color: speakerColor, fontWeight: 500,
          userSelect: 'none', marginRight: 4,
        }}>
        {turn.speaker}：
      </span>
      <EditableText value={turn.content} onChange={onContentChange} onKeyDown={handleKeyDown} />
      <span style={{
        display: 'inline-flex', gap: 2, marginLeft: 6, verticalAlign: 'middle',
        opacity: hover ? 1 : 0, transition: 'opacity 0.15s',
      }}>
        <IconBtn onClick={onInsertAfter} title="在此条下方新增一行" color="#52c41a">
          <IconPlus />
        </IconBtn>
        {onSplitAfter && (
          <IconBtn onClick={onSplitAfter} title="在此条之后拆分为新模块" color="#1677ff">
            <IconSplit />
          </IconBtn>
        )}
        <IconBtn onClick={onDelete} title="删除此条" color="#ff4d4f">
          <IconTrash />
        </IconBtn>
      </span>
    </div>
  )
}

// contentEditable 包装：按钮可紧跟文字末尾排版（textarea 做不到）
function EditableText({ value, onChange, style, hoverBorderColor, title, onKeyDown }) {
  const ref = useRef(null)
  const [hover, setHover] = useState(false)
  // 仅在外部值变化且与 DOM 不一致时同步（避免光标跳）
  useEffect(() => {
    if (ref.current && ref.current.innerText !== value) {
      ref.current.innerText = value || ''
    }
  }, [value])
  return (
    <span
      ref={ref}
      contentEditable
      suppressContentEditableWarning
      title={title}
      onInput={e => onChange(e.currentTarget.innerText)}
      onKeyDown={onKeyDown}
      onMouseEnter={() => hoverBorderColor && setHover(true)}
      onMouseLeave={() => hoverBorderColor && setHover(false)}
      style={{
        outline: 'none', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
        color: '#333', minWidth: 4,
        ...(style || {}),
        ...(hover && hoverBorderColor ? { borderBottomColor: hoverBorderColor } : {}),
      }}
    />
  )
}

function IconBtn({ onClick, title, color, children }) {
  const [h, setH] = useState(false)
  return (
    <button onClick={onClick} title={title}
      onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}
      style={{
        width: 20, height: 20, padding: 0, border: 'none',
        background: h ? color + '15' : 'transparent', borderRadius: 4,
        cursor: 'pointer', color, display: 'inline-flex',
        alignItems: 'center', justifyContent: 'center',
        verticalAlign: 'middle', transition: 'background 0.15s',
      }}>
      {children}
    </button>
  )
}

function IconPlus() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  )
}

function IconSplit() {
  // 剪刀图标：表示拆分
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="6" r="3" />
      <circle cx="6" cy="18" r="3" />
      <line x1="20" y1="4" x2="8.12" y2="15.88" />
      <line x1="14.47" y1="14.48" x2="20" y2="20" />
      <line x1="8.12" y1="8.12" x2="12" y2="12" />
    </svg>
  )
}

function IconMerge() {
  // 向上箭头：表示合并到上一模块
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="12" y1="20" x2="12" y2="5" />
      <polyline points="6 11 12 5 18 11" />
    </svg>
  )
}



function IconTrash() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="3 6 5 6 21 6" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
      <path d="M10 11v6" />
      <path d="M14 11v6" />
      <path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2" />
    </svg>
  )
}
