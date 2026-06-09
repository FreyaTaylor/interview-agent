/**
 * 统一 API 基础 URL 配置
 * 修改此处即可切换后端地址
 */
const API_BASE = import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8080/api'

export const API_STUDY = `${API_BASE}/study`
export const API_KNOWLEDGE = `${API_BASE}/knowledge`
export const API_LEARN = `${API_BASE}/learn`
export const API_INTERVIEW = `${API_BASE}/interview`
export const API_ADMIN = `${API_BASE}/admin`
export const API_USER = `${API_BASE}/user`
export const API_PROJECT_GRILLING = `${API_BASE}/project-grilling`
export const API_TEXT = `${API_BASE}/text`
export const API_ROOT = API_BASE
