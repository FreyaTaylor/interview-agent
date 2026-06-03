/**
 * 个人页面 — 用户画像编辑 + 退出登录
 */
import { useState, useEffect } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { API_USER } from '../config'

const DEFAULT_PROFILE = `3年Java后端开发，目前在一家中型互联网公司做电商业务。

技术栈：
- 主力：Java/Spring Boot/MyBatis
- 中间件：Redis、Kafka、Elasticsearch
- 数据库：MySQL、一点点 MongoDB

项目经验：
- 电商订单系统（超时取消、库存扣减、分布式事务）
- 消息推送平台（百万级推送、消息去重、延迟队列）

薄弱方向：JVM 调优、分布式理论、系统设计
擅长方向：Redis、MySQL 调优

计划 1 个月内开始面试。`

export default function ProfilePage() {
  const { user, logout } = useAuth()
  const [profile, setProfile] = useState('')
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [saved, setSaved] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`${API_USER}/profile`).then(r => r.json()).then(d => {
      if (d.code === 0) setProfile(d.data.profile_text || '')
    }).catch(() => {}).finally(() => setLoading(false))
  }, [])

  function handleEdit() {
    setDraft(profile)
    setEditing(true)
  }

  function handleCancel() {
    setEditing(false)
    setDraft('')
  }

  async function handleSave() {
    const resp = await fetch(`${API_USER}/profile`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile_text: draft }),
    }).then(r => r.json())
    if (resp.code === 0) {
      setProfile(draft)
      setEditing(false)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    }
  }

  return (
    <div className="profile-page">
      {/* 用户卡片 */}
      <div className="profile-card">
        <div className="profile-card-bg" />
        <div className="profile-card-body">
          {user?.avatar_url
            ? <img src={user.avatar_url} alt="" className="profile-avatar" />
            : <div className="profile-avatar profile-avatar-placeholder">👤</div>
          }
          <h2 className="profile-name">{user?.github_login || user?.username}</h2>
        </div>
      </div>

      {/* 用户画像 */}
      <div className="profile-section">
        <div className="profile-section-top">
          <h3>👤 用户画像</h3>
          {!editing && (
            <button className="profile-edit-btn" onClick={handleEdit}>✏️ 编辑</button>
          )}
        </div>
        <p className="profile-hint">你的背景和目标，Agent 会据此个性化生成知识树和面试题</p>

        {editing ? (
          <>
            <textarea
              className="profile-textarea"
              rows={14}
              value={draft}
              onChange={e => setDraft(e.target.value)}
              placeholder={DEFAULT_PROFILE}
              autoFocus
            />
            <div className="profile-actions">
              <button className="profile-cancel-btn" onClick={handleCancel}>取消</button>
              <button className="btn-primary" onClick={handleSave} disabled={!draft.trim()}>保存</button>
            </div>
          </>
        ) : (
          <div className="profile-display">
            {loading ? (
              <span className="profile-empty">加载中...</span>
            ) : profile ? (
              <pre className="profile-text">{profile}</pre>
            ) : (
              <div className="profile-empty-box" onClick={handleEdit}>
                <span className="profile-empty-icon">📝</span>
                <span>点击填写你的用户画像</span>
              </div>
            )}
            {saved && <span className="profile-saved-toast">✓ 保存成功</span>}
          </div>
        )}
      </div>

      {/* 退出 */}
      <div className="profile-section profile-logout-section">
        <button className="profile-logout-btn" onClick={logout}>退出登录</button>
      </div>
    </div>
  )
}
