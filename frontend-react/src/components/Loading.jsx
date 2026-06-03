/**
 * 通用等待状态组件
 *
 * 三类用法：
 *  - <TypingDots />         短等待（< 2s）：聊天/打分中的跳动点
 *  - <Skeleton />           中等待（2-10s）：内容首次加载占位
 *  - <StagePulse text="" /> 长等待（> 10s）：阶段化进度叙事
 */

/** 跳动三点：用在按钮/气泡内做"对方正在输入"效果 */
export function TypingDots({ text = '', className = '' }) {
  return (
    <span className={`loading-dots ${className}`}>
      {text && <span className="loading-dots-text">{text}</span>}
      <i /><i /><i />
    </span>
  )
}

/** 骨架屏：内容生成中的占位灰条
 *  - lines: 段落条数（不含标题）
 *  - hasTitle: 是否带一行 22px 高的标题占位
 *  - hasBlock: 是否带一个 80px 高的代码/图块占位
 */
export function Skeleton({ lines = 4, hasTitle = true, hasBlock = true }) {
  // 让宽度有点参差更真实
  const widths = ['w95', 'w80', 'w90', 'w60', 'w85', 'w70']
  return (
    <div className="skeleton-page" aria-busy="true" aria-label="加载中">
      {hasTitle && <div className="sk-line sk-title" />}
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className={`sk-line ${widths[i % widths.length]}`} />
      ))}
      {hasBlock && <div className="sk-block" />}
    </div>
  )
}

/** 阶段提示：脉动小圆点 + 当前阶段文本（适合 10s+ 的长任务） */
export function StagePulse({ text = '正在生成...', sub }) {
  return (
    <div className="loading-stage" role="status">
      <span className="pulse-dot" />
      <div className="loading-stage-text">
        <div className="loading-stage-main">{text}</div>
        {sub && <div className="loading-stage-sub">{sub}</div>}
      </div>
    </div>
  )
}

/** 极简内联 spinner：常用在按钮内 */
export function Spinner({ size = 14 }) {
  return <span className="inline-spinner" style={{ width: size, height: size }} />
}
