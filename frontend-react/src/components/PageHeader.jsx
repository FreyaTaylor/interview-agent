/**
 * PageHeader — 页面顶部标题条（study / project-grilling 共用）。
 *
 * Props:
 *   title:    主标题
 *   subtitle: 副标题（可空）
 *   right:    右侧操作槽（任意 ReactNode）
 */
export default function PageHeader({ title, subtitle = null, right = null }) {
  return (
    <div className="page-header">
      <div className="page-header-text">
        <div className="page-header-title">{title}</div>
        {subtitle && <div className="page-header-subtitle">{subtitle}</div>}
      </div>
      {right && <div className="page-header-right">{right}</div>}
    </div>
  )
}
