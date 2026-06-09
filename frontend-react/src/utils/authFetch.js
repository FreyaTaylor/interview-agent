/**
 * 全局 fetch 拦截器 —— 给所有发往后端 API 的请求自动带上 JWT。
 *
 * 背景：项目里有大量分散的 `fetch(`${API_XXX}/...`)` 调用，逐个改造成本高且易漏。
 * 在应用启动时一次性包裹 window.fetch，对 API 请求注入 `Authorization: Bearer <token>`，
 * 既不动业务调用点，又能保证多用户隔离所需的身份透传。
 *
 * 行为：
 *   - 仅对发往后端 API（URL 以 API_ROOT 开头，或以 /api 开头）的请求注入 token；
 *   - 已显式带 Authorization 的请求不覆盖；
 *   - 收到 401 时清掉本地 token 并广播 `auth:unauthorized`，由 AuthContext 退回登录页。
 */
import { API_ROOT } from '../config'

const origFetch = window.fetch.bind(window)

function isApiUrl(url) {
  if (typeof url !== 'string') return false
  return url.startsWith(API_ROOT) || url.startsWith('/api')
}

window.fetch = (input, init = {}) => {
  const url = typeof input === 'string' ? input : (input && input.url) || ''
  const api = isApiUrl(url)

  let nextInit = init
  if (api) {
    const token = localStorage.getItem('token')
    if (token) {
      const headers = new Headers(init.headers || undefined)
      if (!headers.has('Authorization')) {
        headers.set('Authorization', `Bearer ${token}`)
      }
      nextInit = { ...init, headers }
    }
  }

  return origFetch(input, nextInit).then((resp) => {
    if (api && resp.status === 401) {
      localStorage.removeItem('token')
      window.dispatchEvent(new Event('auth:unauthorized'))
    }
    return resp
  })
}
