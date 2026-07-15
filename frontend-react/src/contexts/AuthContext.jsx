/**
 * 认证上下文
 * 管理 JWT token + 用户信息，提供登录/登出
 */
import { createContext, useContext, useState, useEffect } from 'react'
import { API_ROOT } from '../config'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)
  const [authConfig, setAuthConfig] = useState(null)
  const [authError, setAuthError] = useState('')

  // 启动时：检查 URL 中的 token（GitHub 回调）或 localStorage
  useEffect(() => {
    bootstrapAuth()
  }, [])

  // 全局 fetch 拦截器在收到 401 时广播；这里据此退回登录页
  useEffect(() => {
    function onUnauthorized() {
      setUser(null)
    }
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [])

  async function fetchUser(token) {
    try {
      const resp = await fetch(`${API_ROOT}/auth/me?token=${token}`)
      const data = await resp.json()
      if (data.code === 0 && data.data) {
        setUser({ ...data.data, token })
      } else {
        localStorage.removeItem('token')
      }
    } catch {
      localStorage.removeItem('token')
    }
    setLoading(false)
  }

  async function bootstrapAuth() {
    try {
      const configResp = await fetch(`${API_ROOT}/auth/config`)
      const configData = await configResp.json()
      const config = configData.code === 0 ? configData.data : null
      setAuthConfig(config)

      const params = new URLSearchParams(window.location.search)
      const urlToken = params.get('token')
      const urlError = params.get('error')
      if (urlError) {
        setAuthError('登录失败，请重试')
      }
      if (urlToken) {
        localStorage.setItem('token', urlToken)
      }
      if (urlToken || urlError) {
        window.history.replaceState({}, '', window.location.pathname)
      }

      if (config?.auth_mode === 'single_user') {
        await fetchUser('')
        return
      }

      const token = urlToken || localStorage.getItem('token')
      if (!token) {
        setLoading(false)
        return
      }
      await fetchUser(token)
    } catch {
      localStorage.removeItem('token')
      setLoading(false)
    }
  }

  function logout() {
    localStorage.removeItem('token')
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, loading, authConfig, authError, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
