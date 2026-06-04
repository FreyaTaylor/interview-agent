/**
 * AttemptHistoryPanel — 展示某题的历史作答记录（可折叠 / 单条展开查看详情）。
 *
 * 设计：
 *   - 列表行：序号 / 状态 / 分数 / 时间
 *   - 点击行 → 懒加载 /attempt-detail → 展开 ConversationView（dialog + 综合评分）
 *   - props.collapsed 控制整面板默认折叠（true 时只显示折叠条）
 *   - props.apiBase 传入后才会调 /attempt-detail；不传则只显示摘要（兼容老用法）
 *
 * 数据契约：列表项与 /attempts-history 一致；展开详情与 /attempt-detail 一致
 */
import { useEffect, useState } from 'react'
import ConversationView from './ConversationView'
import { formatBeijing as formatTime } from '../utils/datetime'

function scoreClass(s) {
  if (s == null) return 'na'
  if (s >= 85) return 'high'
  if (s >= 60) return 'mid'
  return 'low'
}

export default function AttemptHistoryPanel({
  attempts = [],
  collapsed = false,
  currentAttemptId = null,
  title = '历史作答',
  apiBase = null,
}) {
  const [folded, setFolded] = useState(collapsed)
  const [openId, setOpenId] = useState(null)
  // 展开行的详情缓存 { [attemptId]: fullAttempt } / 加载中 id / 错误
  const [details, setDetails] = useState({})
  const [loadingId, setLoadingId] = useState(null)
  const [errorId, setErrorId] = useState(null)

  // 外部 collapsed 状态变化时同步
  useEffect(() => { setFolded(collapsed) }, [collapsed])

  // 排除当前正在进行的 attempt（避免重复展示）
  const list = attempts.filter(a => a.attempt_id !== currentAttemptId)
  if (list.length === 0) return null

  async function handleToggle(a) {
    const id = a.attempt_id
    if (openId === id) { setOpenId(null); return }
    setOpenId(id)
    // 已有缓存 / 无 apiBase（兼容老用法）→ 不再拉
    if (details[id] || !apiBase) return
    setLoadingId(id)
    setErrorId(null)
    try {
      const resp = await fetch(`${apiBase}/attempt-detail`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ attempt_id: id }),
      })
      const data = await resp.json()
      if (data.code === 0 && data.data) {
        setDetails(prev => ({ ...prev, [id]: data.data }))
      } else {
        setErrorId(id)
      }
    } catch (_) {
      setErrorId(id)
    } finally {
      setLoadingId(null)
    }
  }

  return (
    <div className={`attempt-history ${folded ? 'folded' : 'open'}`}>
      <button
        type="button"
        className="attempt-history-head"
        onClick={() => setFolded(f => !f)}
      >
        <span className="attempt-history-title">
          {folded ? '▸' : '▾'} {title}（{list.length}）
        </span>
        <span className="attempt-history-hint">
          {folded ? '点击展开查看' : '点击收起'}
        </span>
      </button>
      {!folded && (
        <ul className="attempt-history-list">
          {list.map((a, i) => {
            const isOpen = openId === a.attempt_id
            const full = details[a.attempt_id]
            const isLoading = loadingId === a.attempt_id
            const isError = errorId === a.attempt_id
            // 合并 summary + 详情（详情没回来时退化到 summary，至少能渲染 final score）
            const merged = full ? { ...a, ...full } : a
            return (
              <li key={a.attempt_id} className={`attempt-history-row ${isOpen ? 'open' : ''}`}>
                <button
                  type="button"
                  className="attempt-history-row-head"
                  onClick={() => handleToggle(a)}
                >
                  <span className="ah-idx">#{list.length - i}</span>
                  <span className={`ah-status ${a.status}`}>
                    {a.status === 'finished' ? '已评分' :
                     a.status === 'in_progress' ? '进行中' : '未完成'}
                  </span>
                  <span className={`ah-score ${scoreClass(a.final_score)}`}>
                    {a.final_score == null ? '—' : a.final_score}
                  </span>
                  <span className="ah-time">{formatTime(a.finished_at || a.created_at)}</span>
                  <span className="ah-toggle">{isOpen ? '收起' : '查看'}</span>
                </button>
                {isOpen && (
                  <div className="attempt-history-detail">
                    {isLoading && <div className="ah-detail-loading">加载详情中…</div>}
                    {isError && <div className="ah-detail-error">详情加载失败，点击行重试</div>}
                    {!isLoading && !isError && <ConversationView attempt={merged} />}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
