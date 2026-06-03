/**
 * AttemptHistoryPanel — 展示某题的历史作答记录（可折叠 / 单条展开查看详情）。
 *
 * 设计：
 *   - 列表行：序号 / 状态 / 分数 / 时间
 *   - 点击行 → 展开 ConversationView（dialog + final score）
 *   - props.collapsed 控制整面板默认折叠（true 时只显示折叠条）
 *
 * 数据契约：每条 attempt 与后端 qa_engine.get_attempt 同结构
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
}) {
  const [folded, setFolded] = useState(collapsed)
  const [openId, setOpenId] = useState(null)

  // 外部 collapsed 状态变化时同步
  useEffect(() => { setFolded(collapsed) }, [collapsed])

  // 排除当前正在进行的 attempt（避免重复展示）
  const list = attempts.filter(a => a.attempt_id !== currentAttemptId)
  if (list.length === 0) return null

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
            return (
              <li key={a.attempt_id} className={`attempt-history-row ${isOpen ? 'open' : ''}`}>
                <button
                  type="button"
                  className="attempt-history-row-head"
                  onClick={() => setOpenId(isOpen ? null : a.attempt_id)}
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
                    <ConversationView attempt={a} />
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
