/**
 * 时间格式化工具：后端返回 naive datetime（无时区后缀，语义为 UTC），
 * 统一在前端转成北京时间（Asia/Shanghai）展示。
 */

function parseUtc(iso) {
  const s = String(iso || '')
  if (!s) return null
  const hasTz = /[zZ]|[+-]\d\d:?\d\d$/.test(s)
  return new Date(hasTz ? s : s + 'Z')
}

/** "MM-DD HH:mm"（北京时间） */
export function formatBeijing(iso) {
  const d = parseUtc(iso)
  if (!d || isNaN(d.getTime())) return iso || ''
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(d).reduce((m, p) => (m[p.type] = p.value, m), {})
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/** "YYYY-MM-DD"（北京时间） */
export function formatBeijingDate(iso) {
  const d = parseUtc(iso)
  if (!d || isNaN(d.getTime())) return iso || ''
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(d).reduce((m, p) => (m[p.type] = p.value, m), {})
  return `${parts.year}-${parts.month}-${parts.day}`
}
