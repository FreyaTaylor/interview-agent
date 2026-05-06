/**
 * 管理页 — 用户画像 + 知识树初始化
 * SSE 流式接收初始化进度
 */
import { useState, useEffect, useRef } from 'react'

const API = 'http://127.0.0.1:8000/api'

const DEFAULT_PROFILE = `3年Java后端开发，目前在一家中型互联网公司做电商业务。
目标：大厂（字节/阿里/美团），Java后端或基础架构方向。

技术栈：
- 主力：Java/Spring Boot/MyBatis
- 中间件：Redis、Kafka、Elasticsearch
- 数据库：MySQL、一点点 MongoDB
- 基础设施：Docker、K8s 基础使用

项目经验：
- 电商订单系统（超时取消、库存扣减、分布式事务）
- 消息推送平台（百万级推送、消息去重、延迟队列）

薄弱方向：JVM 调优（基本没实操过）、分布式理论（Raft/Paxos 只看过没深入）、系统设计（没做过）
擅长方向：Redis 用得比较多、MySQL 调优有经验

计划 1 个月内开始面试。`

export default function AdminPage() {
  const [profile, setProfile] = useState('')
  const [profileSaved, setProfileSaved] = useState(false)
  const [stats, setStats] = useState(null)
  const [initing, setIniting] = useState(false)
  const [logs, setLogs] = useState([])
  const [done, setDone] = useState(false)
  const logsEndRef = useRef(null)

  // 加载画像 + 统计
  useEffect(() => {
    fetch(`${API}/admin/profile`).then(r => r.json()).then(resp => {
      if (resp.code === 0) setProfile(resp.data.profile_text || '')
    }).catch(() => {})
    fetchStats()
  }, [])

  function fetchStats() {
    fetch(`${API}/admin/tree-stats`).then(r => r.json()).then(resp => {
      if (resp.code === 0) setStats(resp.data)
    }).catch(() => {})
  }

  // 滚动到最新日志
  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  async function handleSaveProfile() {
    const resp = await fetch(`${API}/admin/profile`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profile_text: profile }),
    }).then(r => r.json())
    if (resp.code === 0) {
      setProfileSaved(true)
      setTimeout(() => setProfileSaved(false), 2000)
    }
  }

  async function handleInit() {
    const text = profile.trim()
    if (!text || initing) return

    if (stats?.initialized) {
      if (!window.confirm('⚠️ 重新初始化会清空现有知识树和掌握度数据，确定继续？')) return
    }

    setIniting(true)
    setLogs([])
    setDone(false)

    try {
      const resp = await fetch(`${API}/admin/init-tree`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profile_text: text }),
      })

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done: streamDone, value } = await reader.read()
        if (streamDone) break
        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.slice(6))
              setLogs(prev => [...prev, data])
              if (data.step === 'done' || data.step === 'error') {
                setDone(true)
                fetchStats()
              }
            } catch {}
          }
        }
      }
    } catch (e) {
      setLogs(prev => [...prev, { step: 'error', message: `请求失败: ${e.message}` }])
      setDone(true)
    }
    setIniting(false)
  }

  async function handleImageInit(file) {
    if (!file || initing) return
    if (stats?.initialized) {
      if (!window.confirm('⚠️ 重新初始化会清空现有知识树和掌握度数据，确定继续？')) return
    }

    setIniting(true)
    setLogs([])
    setDone(false)

    // 读取图片为 base64
    const reader = new FileReader()
    reader.onload = async () => {
      const base64 = reader.result.split(',')[1]
      try {
        const resp = await fetch(`${API}/admin/init-tree-from-image`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ image_base64: base64, profile_text: profile }),
        })

        const streamReader = resp.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
          const { done: streamDone, value } = await streamReader.read()
          if (streamDone) break
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''
          for (const line of lines) {
            if (line.startsWith('data: ')) {
              try {
                const data = JSON.parse(line.slice(6))
                setLogs(prev => [...prev, data])
                if (data.step === 'done' || data.step === 'error') { setDone(true); fetchStats() }
              } catch {}
            }
          }
        }
      } catch (e) {
        setLogs(prev => [...prev, { step: 'error', message: `请求失败: ${e.message}` }])
        setDone(true)
      }
      setIniting(false)
    }
    reader.readAsDataURL(file)
  }

  const lastLog = logs[logs.length - 1]
  const progress = lastLog?.completed && lastLog?.total
    ? Math.round((lastLog.completed / lastLog.total) * 100)
    : null

  return (
    <div>
      {/* ---- 用户画像 ---- */}
      <div className="tree-card" style={{ padding: '20px 24px', marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 16 }}>👤 用户画像</h3>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {profileSaved && <span style={{ fontSize: 13, color: '#52c41a' }}>✓ 已保存</span>}
            <button className="parse-btn" onClick={handleSaveProfile} disabled={!profile.trim()}>
              保存
            </button>
          </div>
        </div>
        <p style={{ fontSize: 13, color: '#888', margin: '0 0 8px' }}>
          描述你的背景和目标（自由填写），Agent 会据此个性化生成知识树
        </p>
        <textarea
          className="form-textarea"
          rows={10}
          value={profile}
          onChange={e => setProfile(e.target.value)}
          placeholder={DEFAULT_PROFILE}
          style={{ fontSize: 13 }}
        />
      </div>

      {/* ---- 知识树初始化 ---- */}
      <div className="tree-card" style={{ padding: '20px 24px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 16 }}>🌳 知识树初始化</h3>
          <div style={{ display: 'flex', gap: 8 }}>
            <label style={{
              padding: '6px 16px', borderRadius: 8, border: '1px dashed #722ed1',
              background: '#fff', color: '#722ed1', fontSize: 13, cursor: initing ? 'not-allowed' : 'pointer',
              fontFamily: 'inherit', fontWeight: 500, opacity: initing ? 0.5 : 1,
            }}>
              📷 上传脑图
              <input type="file" accept="image/*" style={{ display: 'none' }}
                disabled={initing}
                onChange={e => { if (e.target.files[0]) handleImageInit(e.target.files[0]); e.target.value = '' }} />
            </label>
            <button
              className="parse-btn"
              onClick={handleInit}
              disabled={initing || !profile.trim()}
            >
              {initing ? '⏳ 初始化中...' : stats?.initialized ? '🔄 LLM生成' : '🚀 LLM生成'}
            </button>
          </div>
        </div>

        {/* 统计信息 */}
        {stats && (
          <div style={{ fontSize: 13, color: '#666', marginBottom: 12, padding: '8px 12px', background: '#f9fafb', borderRadius: 6 }}>
            {stats.initialized
              ? <span>✅ 已初始化 — {stats.leaf_count} 个知识点，{stats.category_count} 个一级分类</span>
              : <span>⬜ 尚未初始化，请填写画像后点击「开始初始化」</span>
            }
          </div>
        )}

        {/* 进度条 */}
        {initing && progress !== null && (
          <div style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#888', marginBottom: 4 }}>
              <span>展开叶子知识点</span>
              <span>{lastLog.completed}/{lastLog.total} ({progress}%)</span>
            </div>
            <div style={{ height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${progress}%`, background: '#1677ff', borderRadius: 3, transition: 'width 0.3s' }} />
            </div>
          </div>
        )}

        {/* 日志 */}
        {logs.length > 0 && (
          <div style={{
            maxHeight: 300, overflowY: 'auto', padding: '12px 16px',
            background: '#1e1e1e', borderRadius: 8, fontSize: 12, fontFamily: 'monospace',
            lineHeight: 1.8,
          }}>
            {logs.map((log, i) => (
              <div key={i} style={{ color: log.step === 'error' ? '#ff4d4f' : log.step === 'done' ? '#52c41a' : '#d4d4d4' }}>
                {log.step === 'done' ? '✅ ' : log.step === 'error' ? '❌ ' : log.step === 'expanding' ? '📦 ' : '⏳ '}
                {log.message}
              </div>
            ))}
            <div ref={logsEndRef} />
          </div>
        )}

        {/* 完成提示 */}
        {done && lastLog?.step === 'done' && (
          <div style={{ marginTop: 12, padding: '10px 16px', background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 8, fontSize: 13 }}>
            🎉 初始化完成！共生成 <b>{lastLog.leaf_count}</b> 个知识点。
            <a href="/" style={{ marginLeft: 8, color: '#1677ff' }}>去知识树查看 →</a>
          </div>
        )}
      </div>
    </div>
  )
}
