/**
 * QuestionList — 题目列表（带分数徽章）。
 *
 * study / project-grilling 共用；列表项点击触发 onSelect。
 *
 * Props:
 *   items:     [{ id, content, sort_order, score, attempt_count }]
 *   activeId:  当前高亮的 question id
 *   onSelect(item): 点击回调
 *   emptyText: 空态文案
 */
export default function QuestionList({ items, activeId, onSelect, emptyText = '暂无题目' }) {
  if (!items || items.length === 0) {
    return <div className="ql-empty">{emptyText}</div>
  }
  return (
    <div className="ql-list">
      {items.map((q, idx) => (
        <button
          key={q.id}
          type="button"
          className={`ql-item ${activeId === q.id ? 'active' : ''}`}
          onClick={() => onSelect?.(q)}
        >
          <div className="ql-item-head">
            <span className="ql-item-idx">Q{idx + 1}</span>
            <ScoreBadge score={q.score} count={q.attempt_count} />
          </div>
          <div className="ql-item-content">{q.content}</div>
        </button>
      ))}
    </div>
  )
}

function ScoreBadge({ score, count }) {
  if (score == null) {
    return <span className="ql-badge na">未作答</span>
  }
  const lvl = score >= 85 ? 'high' : score >= 60 ? 'mid' : 'low'
  return (
    <span className={`ql-badge ${lvl}`} title={count ? `已作答 ${count} 次` : ''}>
      {score}
      {count > 1 && <span className="ql-badge-cnt">×{count}</span>}
    </span>
  )
}
