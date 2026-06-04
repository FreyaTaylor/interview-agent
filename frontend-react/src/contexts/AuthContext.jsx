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

  // 启动时：检查 URL 中的 token（GitHub 回调）或 localStorage
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const urlToken = params.get('token')
    if (urlToken) {
      localStorage.setItem('token', urlToken)
      // 清理 URL 参数
      window.history.replaceState({}, '', window.location.pathname)
    }

    // 一期 Mock：Java 后端 /api/auth/me 永远返回固定 user_id=1，无需 token
    const token = urlToken || localStorage.getItem('token') || 'dev'
    fetchUser(token)
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

  function logout() {
    localStorage.removeItem('token')
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{ user, loading, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
