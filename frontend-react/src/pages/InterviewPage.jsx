import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { API_INTERVIEW } from '../config'
import { InterviewReviewModal } from './InterviewReviewPage'
import { useInterviewBusy } from '../contexts/InterviewBusyContext'
import { TypingDots } from '../components/Loading'
import { formatBeijingDate } from '../utils/datetime'

// 小定位按钮：放在卡片标题后
function LocateBtn({ onClick }) {
  return (
    <button onClick={onClick}
      style={{ marginLeft: 8, background: 'none', border: '1px solid #d9d9d9', borderRadius: 6, padding: '1px 6px', fontSize: 11, color: '#666', cursor: 'pointer', fontWeight: 400, verticalAlign: 'middle' }}>
      📍 定位
    </button>
  )
}

// 渲染原文 turn 列表：
//   - speaker 加粗
//   - 每个 turn 按所属 group 渲染浅蓝/浅白交替背景（来自 turnMeta.colorIdx）
//   - 命中 selectedIds 的 turn 叠加黄底常亮
//   - 点击 turn 触发 onTurnClick(meta) 联动右侧
function renderTurnList(turns, turnMeta, selectedIds, onTurnClick) {
  if (!turns || turns.length === 0) return null
  const selSet = new Set(selectedIds || [])
  return turns.map(t => {
    const meta = turnMeta.get(t.id)  // {key, tab, colorIdx} 或 undefined
    const baseBg = meta
      ? (meta.colorIdx % 2 === 0 ? '#eaf3fb' : '#eafaf1')  // 浅蓝 / 浅绿 交替（同色 = 同 group）
      : '#ffffff'  // 没归属：白底
    const bg = selSet.has(t.id) ? '#fff3a0' : baseBg
    const clickable = !!meta
    return (
      <div key={t.id} id={`iv-turn-${t.id}`}
        onClick={clickable ? () => onTurnClick(meta) : undefined}
        style={{
          padding: '6px 10px',
          background: bg,
          cursor: clickable ? 'pointer' : 'default',
          transition: 'background-color 0.3s',
        }}
        title={clickable ? '点击查看右侧对应问题' : undefined}>
        {t.speaker && <b style={{ color: '#222' }}>{t.speaker}：</b>}
        <span>{t.content}</span>
      </div>
    )
  })
}

export default function InterviewPage() {
  const { recordId: urlRecordId } = useParams()
  const navigate = useNavigate()

  const [text, setText] = useState('')
  const [company, setCompany] = useState('')
  const [position, setPosition] = useState('')
  const [loading, setLoading] = useState(false)
  // 当前正在解析的模式：'direct' | 'review' | null
  const [loadingMode, setLoadingMode] = useState(null)
  const [result, setResult] = useState(null)
  const [expanded, setExpanded] = useState({})
  const [activeTab, setActiveTab] = useState('knowledge')
  const [error, setError] = useState('')

  // 原文 turns：后端下发
  const turns = result?.turns || []

  // 定位高亮：常亮，再次定位会替换；新建/切换记录会清空
  const [selectedTurnIds, setSelectedTurnIds] = useState([])

  // group → turn_ids 查询表 + turnId → group meta 反查 + group 在原文中的渲染顺序（用于交替底色）
  const { turnIdsByGroup, turnMeta } = useMemo(() => {
    const byGroup = new Map()
    const meta = new Map()
    if (!result?.groups) return { turnIdsByGroup: byGroup, turnMeta: meta }
    // 1) 收集所有 group 的 (key, tab, turn_ids)
    const items = []
    let r = 0, p = 0, algo = 0, hr = 0
    for (const g of result.groups) {
      let key = null, tab = null
      if (g.type === 'knowledge') { key = `r${r++}`; tab = 'knowledge' }
      else if (g.type === 'project') { key = `p${p++}`; tab = 'project' }
      else if (g.type === 'algorithm') { key = `algo${algo++}`; tab = 'other' }
      else if (g.type === 'hr') { key = `hr${hr++}`; tab = 'other' }
      const ids = Array.isArray(g.turn_ids) ? g.turn_ids.filter(n => Number.isInteger(n)) : []
      if (!key || ids.length === 0) continue
      byGroup.set(key, ids)
      items.push({ key, tab, firstTurn: Math.min(...ids), ids })
    }
    // 2) 按 group 首个 turn 的位置排序 → 决定 colorIdx（保证原文中相邻 group 交替色）
    items.sort((a, b) => a.firstTurn - b.firstTurn)
    items.forEach((it, idx) => {
      for (const tid of it.ids) {
        meta.set(tid, { key: it.key, tab: it.tab, colorIdx: idx })
      }
    })
    return { turnIdsByGroup: byGroup, turnMeta: meta }
  }, [result])

  // 历史面试
  const [history, setHistory] = useState([])
  const [activeRecordId, setActiveRecordId] = useState(null)
  const [historyOpen, setHistoryOpen] = useState(false)  // 左侧历史下拉是否展开
  const [menuOpenId, setMenuOpenId] = useState(null)  // 哪一条历史项的 ⋯ 菜单开中
  const [editing, setEditing] = useState(null)  // ｛id, company, position｝ 或 null

  const transcriptRef = useRef(null)

  // 语音上传
  const [audioLoading, setAudioLoading] = useState(false)
  const [audioFile, setAudioFile] = useState(null)
  const audioInputRef = useRef(null)
  const [uploadTab, setUploadTab] = useState('audio') // 'text' | 'audio'

  // 重复检测弹窗
  const [duplicateInfo, setDuplicateInfo] = useState(null)
  // 预解析弹框数据：{turns, groups, company, position} 或 null
  const [reviewData, setReviewData] = useState(null)

  // 进度条

  // 同步"录音转写中 / 解析中"状态到全局，供 navbar 切换"新标签打开"行为
  const { setBusy } = useInterviewBusy()
  useEffect(() => {
    setBusy(audioLoading || loading)
  }, [audioLoading, loading, setBusy])
  useEffect(() => () => setBusy(false), [setBusy])  // 卸载时清零
  const [progress, setProgress] = useState({ stage: '', percent: 0 })

  // 加载历史列表
  const loadHistory = useCallback(async () => {
    try {
      const resp = await fetch(`${API_INTERVIEW}/history`).then(r => r.json())
      if (resp.code === 0) setHistory(resp.data || [])
    } catch (e) { console.error('加载历史失败:', e) }
  }, [])

  useEffect(() => { loadHistory() }, [loadHistory])

  // URL 中的 recordId 变化 → 加载该记录（初进页、点历史、后退都走这里）
  useEffect(() => {
    const id = urlRecordId ? Number(urlRecordId) : null
    if (id && id !== activeRecordId) {
      handleViewHistory(id)
    } else if (!id && activeRecordId) {
      // URL 回到 /interview → 清空状态
      setActiveRecordId(null)
      setResult(null)
      setText('')
      setCompany('')
      setPosition('')
      setError('')
      setExpanded({})
      setSelectedTurnIds([])
      setAudioFile(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlRecordId])

  // 查看历史详情（仅加载数据，不负责 URL）
  async function handleViewHistory(recordId) {
    setActiveRecordId(recordId)
    setLoading(true)
    setResult(null)
    setError('')
    try {
      const resp = await fetch(`${API_INTERVIEW}/history/${recordId}`).then(r => r.json())
      if (resp.code === 0) {
        setResult(resp.data)
        setCompany(resp.data.company || '')
        setPosition(resp.data.position || '')
        setText(resp.data.raw_text || '')
        setActiveTab('knowledge')
        setExpanded({})
        setSelectedTurnIds([])
      } else {
        setError(resp.message || '加载失败')
      }
    } catch (e) { setError('加载失败: ' + e.message) }
    setLoading(false)
  }

  // 新建面试 → 跳 /interview，状态清理交给上面的 useEffect
  function handleNewInterview() {
    navigate('/interview')
  }

  // 点历史项 → 跳 /interview/:id；若仅草稿（未解析），直接打开校准弹框
  function handlePickHistory(id) {
    setHistoryOpen(false)
    const h = history.find(x => x.id === id)
    if (h && !h.has_parsed && h.has_draft) {
      handleRecalibrate(id)
      return
    }
    if (id !== activeRecordId) navigate(`/interview/${id}`)
  }

  // 删除一条历史记录
  async function handleDeleteHistory(id, e) {
    e?.stopPropagation()
    setMenuOpenId(null)
    const h = history.find(x => x.id === id)
    const label = h ? `${h.company || '未命名'}${h.position ? ' · ' + h.position : ''}` : `#${id}`
    if (!window.confirm(`确定删除「${label}」？该面试的所有题目记录也会一起删除。`)) return
    try {
      const resp = await fetch(`${API_INTERVIEW}/history/${id}`, { method: 'DELETE' }).then(r => r.json())
      if (resp.code !== 0) throw new Error(resp.message || '删除失败')
      setHistory(prev => prev.filter(x => x.id !== id))
      if (id === activeRecordId) navigate('/interview')
    } catch (err) {
      setError(`删除失败: ${err.message}`)
      setTimeout(() => setError(''), 2000)
    }
  }

  // 打开编辑弹窗
  function openEdit(h, e) {
    e?.stopPropagation()
    setMenuOpenId(null)
    setEditing({ id: h.id, company: h.company || '', position: h.position || '' })
  }

  // 继续校准：拉取已存的 turns + groups，复用校对弹框；提交时走 recalibrate 端点（覆盖旧记录）
  async function handleRecalibrate(id, e) {
    e?.stopPropagation()
    setMenuOpenId(null)
    try {
      const resp = await fetch(`${API_INTERVIEW}/history/${id}`).then(r => r.json())
      if (resp.code !== 0 || !resp.data) {
        setError(resp.message || '加载失败')
        setTimeout(() => setError(''), 2000)
        return
      }
      const d = resp.data
      // 草稿优先：若存在 draft_turns/draft_groups，加载草稿，否则加载已解析
      const useDraft = d.has_draft && d.draft_turns?.length && d.draft_groups?.length
      const turns = useDraft ? d.draft_turns : d.turns
      const groups = useDraft ? d.draft_groups : d.groups
      if (!turns?.length || !groups?.length) {
        setError('该记录没有可校准的分段数据')
        setTimeout(() => setError(''), 2000)
        return
      }
      setReviewData({
        turns,
        groups,
        company: d.company || '',
        position: d.position || '',
        // 仅当已存在已解析数据时，才走 recalibrate；草稿继续编辑走 draft update + 首次 finalize
        recalibrateRecordId: d.has_parsed ? id : null,
        draftRecordId: d.has_draft ? id : null,
      })
    } catch (err) {
      setError(`加载失败: ${err.message}`)
      setTimeout(() => setError(''), 2000)
    }
  }

  // 保存编辑
  async function saveEdit() {
    if (!editing) return
    try {
      const resp = await fetch(`${API_INTERVIEW}/history/${editing.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ company: editing.company.trim(), position: editing.position.trim() }),
      }).then(r => r.json())
      if (resp.code !== 0) throw new Error(resp.message || '保存失败')
      setHistory(prev => prev.map(x => x.id === editing.id
        ? { ...x, company: resp.data.company, position: resp.data.position }
        : x))
      if (editing.id === activeRecordId && result) {
        setResult({ ...result, company: resp.data.company, position: resp.data.position })
      }
      setEditing(null)
    } catch (err) {
      setError(`保存失败: ${err.message}`)
      setTimeout(() => setError(''), 2000)
    }
  }

  // 定位到原文：选中该 group 的所有 turn（常亮黄底），并滚动到首个
  function locateGroup(groupKey) {
    const turnIds = turnIdsByGroup.get(groupKey)
    if (!turnIds || turnIds.length === 0) {
      setError('未能在原文中定位该片段')
      setTimeout(() => setError(''), 2000)
      return
    }
    const ids = [...turnIds].sort((a, b) => a - b)
    setSelectedTurnIds(ids)
    setTimeout(() => {
      document.getElementById(`iv-turn-${ids[0]}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 30)
  }

  // 点原文某条 turn → 跳右侧对应 tab + 展开卡片 + 滚到位，同时带动该 group 的所有 turn 亮起来
  function handleTurnClick(meta) {
    setActiveTab(meta.tab)
    setExpanded(prev => ({ ...prev, [meta.key]: true }))
    const ids = turnIdsByGroup.get(meta.key) || []
    setSelectedTurnIds([...ids].sort((a, b) => a - b))
    setTimeout(() => {
      const el = document.getElementById(`iv-card-${meta.key}`)
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      if (el) {
        el.style.boxShadow = '0 0 0 2px #1677ff'
        setTimeout(() => { el.style.boxShadow = '' }, 1500)
      }
    }, 50)
  }

  // 语音上传处理
  async function handleAudioUpload(e) {
    const file = e.target.files?.[0]
    if (!file) return
    setAudioFile(file)
    setAudioLoading(true)
    setError('')
    setProgress({ stage: '上传音频文件...', percent: 10 })
    try {
      const formData = new FormData()
      formData.append('file', file)
      // 模拟进度（实际转写是后端阻塞的）
      const progressTimer = setInterval(() => {
        setProgress(p => {
          if (p.percent >= 85) { clearInterval(progressTimer); return p }
          const stages = [
            [20, '音频格式转换中...'],
            [35, '上传到云端...'],
            [50, '语音识别中...'],
            [70, '说话人分离中...'],
            [85, '整理转写结果...'],
          ]
          const next = stages.find(([pct]) => pct > p.percent)
          return next ? { stage: next[1], percent: next[0] } : p
        })
      }, 8000)
      const resp = await fetch(`${API_INTERVIEW}/upload-audio`, {
        method: 'POST',
        body: formData,
      }).then(r => r.json())
      clearInterval(progressTimer)
      if (resp.code === 0) {
        setProgress({ stage: '转写完成', percent: 100 })
        setText(resp.data.text)
      } else {
        setError(resp.message || resp.detail || '转写失败')
      }
    } catch (e) { setError('语音转写失败: ' + e.message) }
    setAudioLoading(false)
    setTimeout(() => setProgress({ stage: '', percent: 0 }), 1000)
  }

  // 开始解析（带重复检测）
  // mode='direct' → /parse 一步落库；mode='review' → /preview-parse 跳校对页
  async function handleParse(forceNew = false, mode = 'direct') {
    if (!text.trim() || loading) return

    // 重复检测（语义查重：后端 embed 整段文本做最近邻）
    if (!forceNew) {
      try {
        const checkResp = await fetch(`${API_INTERVIEW}/check-duplicate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text }),
        }).then(r => r.json())
        if (checkResp.code === 0 && checkResp.data.duplicate) {
          setDuplicateInfo({ ...checkResp.data, _mode: mode })
          return
        }
      } catch (e) { /* 检测失败不阻塞，继续解析 */ }
    }

    setLoading(true)
    setLoadingMode(mode)
    setResult(null)
    setError('')
    setDuplicateInfo(null)
    setProgress({ stage: mode === 'review' ? '预解析中...' : '提交面试记录...', percent: 10 })
    try {
      const startedAt = Date.now()
      const progressTimer = setInterval(() => {
        setProgress(p => {
          const elapsed = Math.floor((Date.now() - startedAt) / 1000)
          const stages = mode === 'review'
            ? [[40, '提取面试问题...'], [70, '分组中...'], [90, '整理结果...']]
            : [
              [25, '提取面试问题...'],
              [45, '分析回答质量...'],
              [60, '知识点评分中...'],
              [75, '生成整体评价...'],
              [85, '整理结果...'],
            ]
          const next = stages.find(([pct]) => pct > p.percent)
          if (next) return { stage: next[1], percent: next[0], elapsed }
          if (p.percent < 95) return { stage: `整理结果... (已用 ${elapsed}s)`, percent: p.percent + 1, elapsed }
          return { ...p, stage: `整理结果... (已用 ${elapsed}s)`, elapsed }
        })
      }, 5000)
      const endpoint = mode === 'review' ? 'preview-parse' : 'parse'
      const resp = await fetch(`${API_INTERVIEW}/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, company, position }),
      }).then(r => r.json())
      clearInterval(progressTimer)
      if (resp.code === 0) {
        setProgress({ stage: '解析完成', percent: 100 })
        if (mode === 'review') {
          // 在当前页弹出校对 modal；取消保留原文本
          setReviewData({
            turns: resp.data.turns || [],
            groups: resp.data.groups || [],
            company, position,
          })
        } else {
          // 直解：刷新历史 + 跳到详情页（URL 切换会触发 useEffect 自动加载）
          await loadHistory()
          if (resp.data?.record_id) navigate(`/interview/${resp.data.record_id}`)
        }
      } else {
        setError(resp.message || resp.detail || '解析失败')
      }
    } catch (e) { setError('请求失败: ' + (e.message || '网络错误')) }
    setLoading(false)
    setLoadingMode(null)
    setTimeout(() => setProgress({ stage: '', percent: 0 }), 1000)
  }

  // 覆盖已有记录
  async function handleOverwrite() {
    if (!duplicateInfo) return
    try {
      await fetch(`${API_INTERVIEW}/overwrite`, {
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

  // ---- 整体布局：左侧（无记录→历史列表 / 有记录→原文）+ 右侧（无记录→上传 / 有记录→结果） ----
  return (
    <div className="learn-container">
      {/* 左侧 */}
      <div className="learn-sidebar" style={{ display: 'flex', flexDirection: 'column' }}>
        {!result && (
          <div style={{ flex: 1, overflowY: 'auto' }}>
            {history.map(h => (
              <div key={h.id}
                className={`learn-sidebar-item ${activeRecordId === h.id ? 'active' : ''}`}
                onClick={() => handlePickHistory(h.id)}
                style={{ display: 'flex', alignItems: 'center', gap: 8, position: 'relative' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 500, color: '#333', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: 4 }}>
                    {h.has_draft && (
                      <span title={h.has_parsed ? '存在未提交的校准草稿' : '仅草稿，尚未解析'}
                        style={{ fontSize: 10, padding: '1px 5px', borderRadius: 3, background: '#fef3c7', color: '#92400e', fontWeight: 600, flexShrink: 0 }}>
                        {h.has_parsed ? '草稿' : '📝 草稿'}
                      </span>
                    )}
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {h.company || '未命名面试'}{h.position ? ` · ${h.position}` : ''}
                    </span>
                  </div>
                  <div style={{ fontSize: 11, color: '#999', marginTop: 2 }}>
                    {formatBeijingDate(h.created_at)} · {h.avg_score != null ? `${h.avg_score}分` : (h.has_parsed ? '—' : '未解析')}
                  </div>
                </div>
                <button onClick={(e) => { e.stopPropagation(); setMenuOpenId(menuOpenId === h.id ? null : h.id) }}
                  title="更多"
                  style={{ background: 'none', border: 'none', color: '#999', fontSize: 16, cursor: 'pointer', padding: '4px 8px', borderRadius: 4, lineHeight: 1 }}>
                  ⋯
                </button>
                {menuOpenId === h.id && (
                  <>
                    <div onClick={(e) => { e.stopPropagation(); setMenuOpenId(null) }}
                      style={{ position: 'fixed', inset: 0, zIndex: 10 }} />
                    <div onClick={(e) => e.stopPropagation()}
                      style={{ position: 'absolute', right: 8, top: '100%', zIndex: 11, background: '#fff', border: '1px solid #e5e5e5', borderRadius: 6, boxShadow: '0 4px 12px rgba(0,0,0,0.12)', minWidth: 100, padding: '4px 0' }}>
                      <div onClick={(e) => openEdit(h, e)}
                        style={{ padding: '6px 14px', fontSize: 13, color: '#333', cursor: 'pointer' }}
                        onMouseEnter={(e) => e.currentTarget.style.background = '#f5f5f5'}
                        onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                        ✏️ 编辑
                      </div>
                      <div onClick={(e) => handleRecalibrate(h.id, e)}
                        style={{ padding: '6px 14px', fontSize: 13, color: '#333', cursor: 'pointer' }}
                        onMouseEnter={(e) => e.currentTarget.style.background = '#f5f5f5'}
                        onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                        🔄 继续校准
                      </div>
                      <div onClick={(e) => handleDeleteHistory(h.id, e)}
                        style={{ padding: '6px 14px', fontSize: 13, color: '#ff4d4f', cursor: 'pointer' }}
                        onMouseEnter={(e) => e.currentTarget.style.background = '#fff1f0'}
                        onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                        🗑 删除
                      </div>
                    </div>
                  </>
                )}
              </div>
            ))}
            {history.length === 0 && <div style={{ padding: 16, color: '#ccc', fontSize: 13 }}>暂无面试记录</div>}
          </div>
        )}
        {result && (
          <div ref={transcriptRef} style={{ flex: 1, overflowY: 'auto', padding: '12px 16px', fontSize: 13, lineHeight: 1.8, color: '#444', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {!text && <div style={{ color: '#bbb', textAlign: 'center', marginTop: 60, fontSize: 13 }}><TypingDots text="加载中" /></div>}
            {text && renderTurnList(turns, turnMeta, selectedTurnIds, handleTurnClick)}
          </div>
        )}
      </div>

      {/* 右侧内容区 */}
      <div className="learn-main">
        {/* 输入页 */}
        {!result && (
          <div style={{ maxWidth: 720, margin: '40px auto', padding: '0 20px' }}>
            <div className="outliner-dialog" style={{ position: 'relative', boxShadow: '0 4px 24px rgba(0,0,0,0.08)' }}>
              <div className="outliner-dialog-header">
                <h3>上传面试记录</h3>
              </div>
              <div className="outliner-dialog-tabs">
                {[
                  { key: 'audio', label: '🎤 语音上传' },
                  { key: 'text', label: '📄 文本输入' },
                ].map(t => (
                  <button key={t.key} className={`outliner-dialog-tab ${uploadTab === t.key ? 'active' : ''}`}
                    onClick={() => setUploadTab(t.key)} disabled={audioLoading}>{t.label}</button>
                ))}
              </div>
              <div className="outliner-dialog-body">
                {/* 公司 + 职位 */}
                <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
                  <input className="outliner-dialog-input" placeholder="公司名称（选填）" value={company}
                    onChange={e => setCompany(e.target.value)} style={{ flex: 1 }} />
                  <input className="outliner-dialog-input" placeholder="应聘职位（选填）" value={position}
                    onChange={e => setPosition(e.target.value)} style={{ flex: 1 }} />
                </div>

                {uploadTab === 'text' && (
                  <>
                    <p className="outliner-dialog-hint">粘贴面试中的对话记录、回忆笔记或文字版面试内容</p>
                    <textarea className="outliner-dialog-textarea" rows={12} value={text}
                      onChange={e => setText(e.target.value)} disabled={audioLoading}
                      placeholder={'示例：\n面试官问了分布式锁怎么实现，我说了SETNX加过期时间，追问看门狗我没答上来。\n然后聊了我的订单系统项目，问超时取消怎么做的。\n手撕了LRU。问了离职原因。'} />
                  </>
                )}

                {uploadTab === 'audio' && (
                  <>
                    <p className="outliner-dialog-hint">上传面试录音文件，自动转写为文本（支持 mp3/wav/m4a/flac，≤300MB）</p>
                    <input ref={audioInputRef} type="file" accept=".mp3,.wav,.m4a,.flac,.ogg,.wma,.aac"
                      style={{ display: 'none' }} onChange={handleAudioUpload} />
                    {!audioFile && !text && (
                      <div className="outliner-dialog-upload"
                        onClick={() => !audioLoading && audioInputRef.current?.click()}
                        onDragOver={e => e.preventDefault()}
                        onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files?.[0]; if (f) { setAudioFile(f); handleAudioUpload({ target: { files: [f] } }) } }}
                        style={{ cursor: 'pointer' }}
                      >
                        <span>点击选择或拖拽音频文件到这里</span>
                      </div>
                    )}
                    {audioFile && (
                      <div style={{ fontSize: 13, color: '#52c41a', padding: '8px 0' }}>
                        ✅ {audioFile.name} ({(audioFile.size / 1024 / 1024).toFixed(1)} MB)
                      </div>
                    )}
                    {audioLoading && (
                      <div style={{ marginTop: 8 }}>
                        <div style={{ fontSize: 13, color: '#555', marginBottom: 6 }}>🎤 {progress.stage || '转写中，请稍候...'}</div>
                        <div style={{ background: '#e8e8e8', borderRadius: 10, height: 6, overflow: 'hidden' }}>
                          <div style={{
                            background: 'linear-gradient(90deg, #667eea, #764ba2)',
                            height: '100%', borderRadius: 10,
                            width: `${progress.percent || 5}%`,
                            transition: 'width 1s ease',
                          }} />
                        </div>
                        <div style={{ textAlign: 'right', marginTop: 4, fontSize: 11, color: '#999' }}>{progress.percent || 0}%</div>
                      </div>
                    )}
                    {text && (
                      <>
                        <p className="outliner-dialog-hint" style={{ marginTop: 12 }}>转写结果（可编辑）：</p>
                        <textarea className="outliner-dialog-textarea" rows={10} value={text}
                          onChange={e => setText(e.target.value)}
                          placeholder="转写结果将显示在这里..." />
                      </>
                    )}
                  </>
                )}

                {error && (
                  <div style={{ marginTop: 12, padding: '10px 16px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, fontSize: 13, color: '#ff4d4f' }}>
                    ❌ {error}
                  </div>
                )}

                {loading && (
                  <div style={{ marginTop: 12 }}>
                    <div style={{ fontSize: 13, color: '#555', marginBottom: 6 }}>🧠 {progress.stage || '正在解析面试记录...'}</div>
                    <div style={{ background: '#e8e8e8', borderRadius: 10, height: 6, overflow: 'hidden' }}>
                      <div style={{
                        background: 'linear-gradient(90deg, #667eea, #764ba2)',
                        height: '100%', borderRadius: 10,
                        width: `${progress.percent || 5}%`,
                        transition: 'width 1s ease',
                      }} />
                    </div>
                    <div style={{ textAlign: 'right', marginTop: 4, fontSize: 11, color: '#999' }}>{progress.percent || 0}%</div>
                  </div>
                )}
              </div>
              <div className="outliner-dialog-footer" style={{ display: 'flex', gap: 10, alignItems: 'stretch' }}>
                <button
                  onClick={() => handleParse(false, 'review')}
                  disabled={!text.trim() || loading || audioLoading}
                  title="先 LLM 预解析，人工校对说话人/分组后再评分"
                  style={{
                    flex: 1, padding: '10px 16px', border: '1px solid transparent',
                    background: (!text.trim() || loading || audioLoading)
                      ? '#cfd8dc'
                      : 'linear-gradient(135deg, #43cea2 0%, #185a9d 100%)',
                    color: '#fff', borderRadius: 6, fontSize: 14, fontWeight: 600,
                    cursor: (!text.trim() || loading) ? 'not-allowed' : 'pointer',
                    boxShadow: (!text.trim() || loading) ? 'none' : '0 2px 6px rgba(24,90,157,0.3)',
                    display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                  }}>
                  <span>{loadingMode === 'review' ? '预解析中...' : '📝 预解析'}</span>
                  <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.85)', fontWeight: 400 }}>
                    {loadingMode === 'review' ? '请稍候' : '校对后再落库'}
                  </span>
                </button>
                <button
                  onClick={() => handleParse(false, 'direct')}
                  disabled={!text.trim() || loading || audioLoading}
                  title="跳过校对，直接评分落库"
                  style={{
                    flex: 1, padding: '10px 16px', border: '1px solid transparent',
                    background: (!text.trim() || loading || audioLoading)
                      ? '#cfd8dc'
                      : 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                    color: '#fff', borderRadius: 6, fontSize: 14, fontWeight: 600,
                    cursor: (!text.trim() || loading) ? 'not-allowed' : 'pointer',
                    boxShadow: (!text.trim() || loading) ? 'none' : '0 2px 6px rgba(102,126,234,0.3)',
                    display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
                  }}>
                  <span>{loadingMode === 'direct' ? '解析中...' : '⚡ 直接解析'}</span>
                  <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.85)', fontWeight: 400 }}>
                    {loadingMode === 'direct' ? '请稍候' : '一步到位'}
                  </span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* 重复检测弹窗 */}
        {duplicateInfo && (
          <div className="outliner-dialog-overlay" onClick={() => setDuplicateInfo(null)}>
            <div className="outliner-dialog" style={{ width: 440 }} onClick={e => e.stopPropagation()}>
              <div className="outliner-dialog-header">
                <h3>检测到相同面试记录</h3>
                <button className="outliner-dialog-close" onClick={() => setDuplicateInfo(null)}>×</button>
              </div>
              <div className="outliner-dialog-body" style={{ fontSize: 14, lineHeight: 1.8 }}>
                <p>该面试文本已于 <b>{formatBeijingDate(duplicateInfo.created_at)}</b> 上传过：</p>
                <p style={{ color: '#555' }}>
                  {duplicateInfo.company || '未命名'}{duplicateInfo.position ? ` · ${duplicateInfo.position}` : ''}
                  {duplicateInfo.avg_score != null && <span style={{ marginLeft: 8, color: sc(duplicateInfo.avg_score) }}>{duplicateInfo.avg_score}分</span>}
                </p>
              </div>
              <div className="outliner-dialog-footer" style={{ display: 'flex', gap: 8 }}>
                <button className="outliner-dialog-cancel" onClick={() => setDuplicateInfo(null)}>取消上传</button>
                <button className="outliner-dialog-submit" style={{ background: '#ff4d4f' }} onClick={handleOverwrite}>覆盖旧记录</button>
                <button className="outliner-dialog-submit" onClick={() => { const m = duplicateInfo._mode || 'direct'; setDuplicateInfo(null); handleParse(true, m) }}>上传为新面试</button>
              </div>
            </div>
          </div>
        )}

        {/* 校对弹框：取消保留输入框文本，提交后跳转详情 */}
        {reviewData && (
          <div
            onClick={() => setReviewData(null)}
            style={{
              position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)',
              zIndex: 1000, display: 'flex', alignItems: 'flex-start',
              justifyContent: 'center', padding: '40px 20px',
            }}
          >
            <div
              onClick={e => e.stopPropagation()}
              style={{
                background: '#fff', maxWidth: 880, width: '100%',
                maxHeight: 'calc(100vh - 80px)', overflowY: 'auto',
                borderRadius: 10, padding: '0 24px 32px',
                boxShadow: '0 8px 32px rgba(0,0,0,0.2)',
              }}
            >
              <InterviewReviewModal
                initialTurns={reviewData.turns}
                initialGroups={reviewData.groups}
                company={reviewData.company}
                position={reviewData.position}
                recalibrateRecordId={reviewData.recalibrateRecordId || null}
                initialDraftRecordId={reviewData.draftRecordId || null}
                onCancel={() => setReviewData(null)}
                onSaved={async () => { await loadHistory() }}
                onSubmitted={async (id) => {
                  setReviewData(null)
                  await loadHistory()
                  if (id) navigate(`/interview/${id}`)
                }}
              />
            </div>
          </div>
        )}

        {/* 结果页 — 解析失败/脏数据分支 */}
        {result && !loading && (result.parse_error || (result.groups?.length || 0) === 0) && (
          <div className="interview-result" style={{ padding: '20px', overflowY: 'auto', flex: 1 }}>
            <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '16px 20px', marginBottom: 16, fontSize: 14, color: '#ff4d4f' }}>
              ❌ 该面试记录解析失败或数据异常（groups 为空），无法展示分组结果。
              <div style={{ marginTop: 6, fontSize: 12, color: '#999' }}>
                建议在左侧列表删除此条后重新上传，或检查原始文本是否完整。
              </div>
            </div>
            {result.raw_text && (
              <div className="tree-card" style={{ padding: '16px 20px' }}>
                <div style={{ fontSize: 13, color: '#555', marginBottom: 8 }}>📄 原始转写文本：</div>
                <div style={{ fontSize: 13, lineHeight: 1.8, whiteSpace: 'pre-wrap', color: '#333', maxHeight: 400, overflowY: 'auto' }}>
                  {result.raw_text}
                </div>
              </div>
            )}
          </div>
        )}

        {/* 结果页 — 正常分支 */}
        {result && !loading && !(result.parse_error || (result.groups?.length || 0) === 0) && (() => {
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
    <div className="interview-result" style={{ padding: '0 20px', overflowY: 'auto', flex: 1 }}>
      {/* 面试信息栏 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, fontSize: 13, color: '#888' }}>
        {(result.company || result.position) && (
          <span>🏢 {result.company || '未命名'}{result.position ? ` · ${result.position}` : ''}</span>
        )}
        <span style={{ flex: 1 }} />
      </div>
      {/* ---- 整体分析 ---- */}
      <div className="tree-card" style={{ padding: '16px 20px', marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: oa ? 8 : 0 }}>
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
      <div className="tree-tabs" style={{ marginBottom: 16, top: 0 }}>
        {tabs.map(t => (
          <button key={t.key} className={`tree-tab ${activeTab === t.key ? 'active' : ''}`}
            onClick={() => { setActiveTab(t.key); sessionStorage.setItem('iv_tab', t.key) }}>
            {t.label}{t.count > 0 ? ` (${t.count})` : ''}
          </button>
        ))}
      </div>

      {/* ---- Tab: 知识点（卡片风格） ---- */}
      {activeTab === 'knowledge' && (
        <div style={{ minHeight: '70vh' }}>
        {knowledgeGroups.length === 0
        ? <div className="empty">暂无知识点问题</div>
        : <div>
        {knowledgeGroups.map((g, i) => {
        const sr = g.score_result; const isOpen = expanded[`r${i}`]
        const bg = i % 2 === 0 ? '#fff' : '#f7f8fa'
        return (
          <div key={`r${i}`} id={`iv-card-r${i}`} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`r${i}`)}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 15, fontWeight: 600, color: '#333' }}>
                {g.knowledge_point}
                {g.auto_created && <span style={{ fontSize: 11, color: '#fa8c16', marginLeft: 6 }}>新增</span>}
                {g.matched_node_name ? (
                  <a
                    href={`/admin/tree?node=${g.matched_node_id}`}
                    onClick={(e) => e.stopPropagation()}
                    style={{
                      display: 'inline-block',
                      fontSize: 11, color: '#52c41a', marginLeft: 8,
                      background: '#f6ffed', border: '1px solid #b7eb8f',
                      borderRadius: 4, padding: '1px 6px', fontWeight: 500,
                      textDecoration: 'none', cursor: 'pointer',
                    }}
                    title="点击到管理页定位该知识树节点"
                  >
                    → {g.matched_node_name}
                  </a>
                ) : (
                  <span style={{
                    fontSize: 11, color: '#999', marginLeft: 8,
                    background: '#fafafa', border: '1px dashed #d9d9d9',
                    borderRadius: 4, padding: '1px 6px',
                  }} title="未匹配到知识树节点">
                    未匹配
                  </span>
                )}
                {g.turn_ids?.length > 0 && <LocateBtn onClick={(e) => { e.stopPropagation(); locateGroup(`r${i}`) }} />}
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
              </div>
            )}
          </div>
        )
      })}
      </div>}
      </div>
      )}

      {/* ---- Tab: 项目拷打（卡片风格，同知识点） ---- */}
      {activeTab === 'project' && (
        <div style={{ minHeight: '70vh' }}>
        {projectGroups.length === 0
        ? <div className="empty">暂无项目拷打记录</div>
        : <div>{(() => {
          const byProject = {}
          projectGroups.forEach((g, i) => {
            const name = g.project_name || '未命名项目'
            if (!byProject[name]) byProject[name] = []
            byProject[name].push({ ...g, _idx: i })
          })
          // 每个项目一组配色：浅底 + 强调边框/标题色
          const palette = [
            { bg: '#f0f7ff', border: '#bae0ff', accent: '#1677ff' }, // 蓝
            { bg: '#fff7e6', border: '#ffd591', accent: '#fa8c16' }, // 橙
            { bg: '#f6ffed', border: '#b7eb8f', accent: '#52c41a' }, // 绿
            { bg: '#fff0f6', border: '#ffadd2', accent: '#eb2f96' }, // 粉
            { bg: '#f9f0ff', border: '#d3adf7', accent: '#722ed1' }, // 紫
            { bg: '#e6fffb', border: '#87e8de', accent: '#13c2c2' }, // 青
          ]
          return Object.entries(byProject).map(([projName, topics], pIdx) => {
            const c = palette[pIdx % palette.length]
            return (
              <div key={projName} style={{
                marginBottom: 18, background: c.bg, border: `1px solid ${c.border}`,
                borderRadius: 12, padding: '12px 14px',
              }}>
                <div style={{
                  fontSize: 15, fontWeight: 700, color: c.accent,
                  padding: '4px 4px 10px', borderBottom: `2px solid ${c.border}`, marginBottom: 10,
                }}>
                  🔨 {projName} <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>{topics.length} 个话题</span>
                </div>
                {topics.map((g) => {
                  const i = g._idx; const sr = g.score_result; const isOpen = expanded[`p${i}`]
                  return (
                    <div key={`p${i}`} id={`iv-card-p${i}`} style={{ background: '#fff', borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`p${i}`)}>
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <span style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>
                          {g.topic || '拷打'}
                          {g.matched_project_name ? (
                            <a
                              href={`/admin/project?node=${g.matched_project_id}`}
                              onClick={(e) => e.stopPropagation()}
                              style={{
                                display: 'inline-block',
                                fontSize: 11, color: '#52c41a', marginLeft: 8,
                                background: '#f6ffed', border: '1px solid #b7eb8f',
                                borderRadius: 4, padding: '1px 6px', fontWeight: 500,
                                textDecoration: 'none', cursor: 'pointer',
                              }}
                              title="点击到管理页定位该项目节点"
                            >
                              → {g.matched_project_name}
                            </a>
                          ) : (
                            <span style={{
                              fontSize: 11, color: '#999', marginLeft: 8,
                              background: '#fafafa', border: '1px dashed #d9d9d9',
                              borderRadius: 4, padding: '1px 6px',
                            }} title="未匹配到项目节点">
                              未匹配
                            </span>
                          )}
                          {g.turn_ids?.length > 0 && <LocateBtn onClick={(e) => { e.stopPropagation(); locateGroup(`p${i}`) }} />}
                        </span>
                        {sr && <span style={{ fontSize: 13, color: '#722ed1', fontWeight: 600 }}>{(sr.rating_label || '').replace(/⭐/g, '').trim()} {'⭐'.repeat(sr.rating || 0)}</span>}
                      </div>
                      {isOpen && (
                        <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                          {g.questions?.length > 0 && (
                            <div style={{ background: '#e8f4fd', borderLeft: '4px solid #1677ff', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13, lineHeight: 1.8 }}>
                              {g.questions.map((q, j) => <div key={j}>❓ {q}</div>)}
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
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            )
          })
        })()}</div>}
        </div>
      )}

      {/* ---- Tab: 其他问题（卡片风格） ---- */}
      {activeTab === 'other' && (
        <div style={{ minHeight: '70vh' }}>
        {otherCount === 0
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
                <div key={i} id={`iv-card-algo${i}`} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: 'pointer' }} onClick={() => toggle(`algo${i}`)}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>
                      {g.title}
                      {g.turn_ids?.length > 0 && <LocateBtn onClick={(e) => { e.stopPropagation(); locateGroup(`algo${i}`) }} />}
                    </span>
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
                <div key={i} id={`iv-card-hr${i}`} style={{ background: bg, borderRadius: 10, border: '1px solid #eee', padding: '14px 18px', marginBottom: 10, cursor: hasDetail ? 'pointer' : 'default' }} onClick={() => hasDetail && toggle(`hr${i}`)}>
                  <div style={{ fontSize: 14, fontWeight: 600, color: '#333' }}>
                    {g.questions?.[0] || '—'}
                    {g.turn_ids?.length > 0 && <LocateBtn onClick={(e) => { e.stopPropagation(); locateGroup(`hr${i}`) }} />}
                  </div>
                  {isOpen && (
                    <div style={{ marginTop: 10 }} onClick={e => e.stopPropagation()}>
                      {sr?.feedback && (
                        <div style={{ background: '#f6ffed', borderLeft: '4px solid #52c41a', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>📊 {sr.feedback}</div>
                      )}
                      {sr?.suggestion && (
                        <div style={{ background: '#f9f0ff', borderLeft: '4px solid #722ed1', padding: '10px 14px', borderRadius: 6, marginBottom: 8, fontSize: 13 }}>💡 {sr.suggestion}</div>
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
        </div>}
        </div>
      )}

    </div>
  )
  })()}
      </div>

      {/* 编辑面试元信息弹窗 */}
      {editing && (
        <div onClick={() => setEditing(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div onClick={(e) => e.stopPropagation()}
            style={{ background: '#fff', borderRadius: 10, padding: '20px 24px', width: 380, boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 16, color: '#333' }}>编辑面试信息</div>
            <div style={{ marginBottom: 12 }}>
              <label style={{ fontSize: 12, color: '#666', display: 'block', marginBottom: 4 }}>公司</label>
              <input value={editing.company} autoFocus
                onChange={(e) => setEditing({ ...editing, company: e.target.value })}
                onKeyDown={(e) => e.key === 'Enter' && saveEdit()}
                style={{ width: '100%', padding: '8px 10px', border: '1px solid #d9d9d9', borderRadius: 6, fontSize: 13, boxSizing: 'border-box' }} />
            </div>
            <div style={{ marginBottom: 18 }}>
              <label style={{ fontSize: 12, color: '#666', display: 'block', marginBottom: 4 }}>岗位</label>
              <input value={editing.position}
                onChange={(e) => setEditing({ ...editing, position: e.target.value })}
                onKeyDown={(e) => e.key === 'Enter' && saveEdit()}
                style={{ width: '100%', padding: '8px 10px', border: '1px solid #d9d9d9', borderRadius: 6, fontSize: 13, boxSizing: 'border-box' }} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button onClick={() => setEditing(null)}
                style={{ padding: '6px 16px', border: '1px solid #d9d9d9', background: '#fff', borderRadius: 6, cursor: 'pointer', fontSize: 13, color: '#666' }}>
                取消
              </button>
              <button onClick={saveEdit}
                style={{ padding: '6px 16px', border: 'none', background: '#1677ff', color: '#fff', borderRadius: 6, cursor: 'pointer', fontSize: 13 }}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
