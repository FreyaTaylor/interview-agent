/**
 * 学习页面 — 知识点讲解 + 探索对话
 * 左侧：知识树目录（整棵树，当前节点高亮）
 * 右上：Markdown 讲解 + 高频面试题 + 掌握度信息
 * 右下：探索对话框（支持引用知识文本）
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const API_LEARN = 'http://127.0.0.1:8000/api/learn'
const API_TREE = 'http://127.0.0.1:8000/api/knowledge'

// Markdown 渲染组件
function MarkdownContent({ content }) {
  if (!content) return null
  return <Markdown remarkPlugins={[remarkGfm]}>{content}</Markdown>
}

// 将讲解内容按 ### 模块分块，每个 h4 子话题渲染为卡片
function LearnContentCards({ content }) {
  if (!content) return null
  // 按 ### 分割出顶级模块
  const sections = content.split(/^(?=### )/m).filter(s => s.trim())

  return sections.map((section, si) => {
    // 提取 ### 标题行
    const lines = section.split('\n')
    const titleLine = lines[0]?.replace(/^###\s*/, '') || ''
    const body = lines.slice(1).join('\n').trim()

    // 判断是否包含 #### 子话题（兼容 #### 后有无空格）
    const hasSubTopics = /^####\s?\S/m.test(body)

    if (!hasSubTopics) {
      // 没有 #### 的模块（如一句话概述）：整块渲染为卡片
      return (
        <div key={si} className="learn-section-card">
          <div className="learn-section-title">{titleLine}</div>
          <div className="learn-section-body">
            <MarkdownContent content={body} />
          </div>
        </div>
      )
    }

    // 有 #### 的模块（如核心原理）：每个 #### 渲染为子卡片
    const subSections = body.split(/^(?=####\s?\S)/m).filter(s => s.trim())
    // 可能 #### 前还有一段引导文字
    const leadParts = []
    const subCards = []
    for (const sub of subSections) {
      if (/^####\s?\S/.test(sub)) {
        const subLines = sub.split('\n')
        const subTitle = subLines[0].replace(/^####\s*/, '')
        const subBody = subLines.slice(1).join('\n').trim()
        subCards.push({ title: subTitle, body: subBody })
      } else {
        leadParts.push(sub)
      }
    }

    return (
      <div key={si} className="learn-section-card">
        <div className="learn-section-title">{titleLine}</div>
        {leadParts.map((lp, li) => (
          <div key={li} className="learn-section-body">
            <MarkdownContent content={lp} />
          </div>
        ))}
        <div className="learn-sub-cards">
          {subCards.map((sc, ci) => (
            <div key={ci} className="learn-sub-card">
              <div className="learn-sub-card-title">{sc.title}</div>
              <div className="learn-sub-card-body">
                <MarkdownContent content={sc.body} />
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  })
}

// 查找节点的祖先 ID 列表
function findAncestorIds(tree, targetId) {
  const ancestors = new Set()
  function dfs(node, path) {
    if (node.id === targetId) {
      path.forEach(id => ancestors.add(id))
      return true
    }
    for (const child of (node.children || [])) {
      if (dfs(child, [...path, node.id])) return true
    }
    return false
  }
  for (const root of tree) {
    if (dfs(root, [])) break
  }
  return ancestors
}

// 左侧目录树节点
function SidebarNode({ node, activeId, expandedIds, onSelect, depth = 0 }) {
  const children = node.children || []
  const hasKids = children.length > 0
  const isLeaf = node.node_type === 'leaf'
  const isActive = node.id === activeId
  const shouldExpand = expandedIds.has(node.id)
  const [collapsed, setCollapsed] = useState(!shouldExpand)

  // 当 activeId 变化时自动展开祖先，折叠非祖先
  useEffect(() => {
    if (shouldExpand) setCollapsed(false)
    else if (expandedIds.size > 0) setCollapsed(true)
  }, [shouldExpand, expandedIds])

  return (
    <div>
      <div
        className={`learn-sidebar-item ${isActive ? 'active' : ''} ${isLeaf ? 'leaf' : ''}`}
        style={{ paddingLeft: 12 + depth * 16 }}
        onClick={() => {
          if (isLeaf) onSelect(node.id)
          else setCollapsed(c => !c)
        }}
      >
        {hasKids && <span className={`learn-sidebar-toggle ${collapsed ? '' : 'open'}`} />}
        {isLeaf && <span className="learn-sidebar-bullet" />}
        <span className="learn-sidebar-name">{node.name}</span>
      </div>
      {hasKids && !collapsed && children.map(ch => (
        <SidebarNode key={ch.id} node={ch} activeId={activeId} expandedIds={expandedIds} onSelect={onSelect} depth={depth + 1} />
      ))}
    </div>
  )
}

export default function LearnPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const [tree, setTree] = useState([])
  const [activeKpId, setActiveKpId] = useState(null)
  const [content, setContent] = useState(null) // { content, questions, knowledge_point_name, mastery_level, last_studied_at }
  const [loading, setLoading] = useState(false)
  const [chatHistory, setChatHistory] = useState([])
  const [chatInput, setChatInput] = useState('')
  const [chatLoading, setChatLoading] = useState(false)
  const [quotedText, setQuotedText] = useState('')
  const [showMergeConfirm, setShowMergeConfirm] = useState(false)
  const chatEndRef = useRef(null)
  const contentRef = useRef(null)
  const [expandedIds, setExpandedIds] = useState(new Set())

  // 加载知识树
  useEffect(() => {
    fetch(`${API_TREE}/tree`).then(r => r.json()).then(d => {
      if (d.code === 0) {
        setTree(d.data)
        // 如果已有 activeKpId，计算需要展开的祖先
        if (kpId) {
          setExpandedIds(findAncestorIds(d.data, parseInt(kpId)))
        }
      }
    })
  }, [])

  // URL 参数驱动
  useEffect(() => {
    if (kpId) {
      const id = parseInt(kpId)
      setActiveKpId(id)
      if (tree.length > 0) {
        setExpandedIds(findAncestorIds(tree, id))
      }
      loadContent(id)
      loadChatHistory(id)
    }
  }, [kpId, tree.length])

  const loadContent = useCallback(async (id) => {
    setLoading(true)
    setContent(null)
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const resp = await fetch(`${API_LEARN}/content/${id}`).then(r => r.json())
        if (resp.code === 0) { setContent(resp.data); setLoading(false); return }
        if (attempt === 1) setContent({ error: resp.message || '加载失败' })
      } catch (e) {
        console.error(`加载内容第${attempt+1}次失败:`, e)
        if (attempt === 1) setContent({ error: '加载失败: ' + e.message })
        else await new Promise(r => setTimeout(r, 1000)) // 等 1 秒重试
      }
    }
    setLoading(false)
  }, [])

  const loadChatHistory = useCallback(async (id) => {
    try {
      const resp = await fetch(`${API_LEARN}/chat-history/${id}`).then(r => r.json())
      if (resp.code === 0) setChatHistory(resp.data || [])
    } catch (e) {
      console.error('加载对话历史失败:', e)
    }
  }, [])

  function handleSelectKp(id) {
    setActiveKpId(id)
    setExpandedIds(findAncestorIds(tree, id))
    navigate(`/learn/${id}`, { replace: true })
    loadContent(id)
    loadChatHistory(id)
    setChatInput('')
    setQuotedText('')
  }

  // 文本选中引用
  useEffect(() => {
    function handleMouseUp(e) {
      // 点击发送按钮或引用栏时不处理
      if (e.target.closest('.learn-chat-input-wrap') || e.target.closest('.learn-quote-bar')) return
      setTimeout(() => {
        const sel = window.getSelection()
        const text = sel?.toString().trim()
        if (text && text.length > 0) {
          // 检查选区是否在内容区域（讲解 + 面试题）
          const contentArea = document.querySelector('.learn-content-area')
          if (contentArea?.contains(sel.anchorNode)) {
            setQuotedText(text)
          }
        }
      }, 10)
    }
    document.addEventListener('mouseup', handleMouseUp)
    return () => document.removeEventListener('mouseup', handleMouseUp)
  }, [])

  // 发送对话
  async function handleSendChat() {
    if (!chatInput.trim() || chatLoading || !activeKpId) return
    const msg = chatInput.trim()
    const quote = quotedText || null
    setChatInput('')
    setQuotedText('')
    setChatHistory(prev => [...prev, { role: 'user', content: msg, quoted_text: quote }])
    setChatLoading(true)

    try {
      const resp = await fetch(`${API_LEARN}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ knowledge_point_id: activeKpId, message: msg, quoted_text: quote }),
      }).then(r => r.json())
      if (resp.code === 0) {
        const { reply, updated_subtopic, updated_content } = resp.data
        setChatHistory(prev => [...prev, {
          role: 'assistant',
          content: reply,
          updated_subtopic: updated_subtopic || null,
        }])
        // 实时更新讲解内容
        if (updated_content) {
          setContent(prev => ({ ...prev, content: updated_content }))
        }
      }
    } catch (e) {
      console.error('对话失败:', e)
    } finally {
      setChatLoading(false)
      setTimeout(() => chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
    }
  }

  // 合并对话到讲解
  async function handleMerge() {
    const aiMessages = chatHistory.filter(c => c.role === 'assistant').map(c => c.content)
    if (!aiMessages.length) return
    setChatLoading(true)
    try {
      const resp = await fetch(`${API_LEARN}/merge-chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ knowledge_point_id: activeKpId, chat_messages: aiMessages }),
      }).then(r => r.json())
      if (resp.code === 0) {
        setContent(prev => ({ ...prev, content: resp.data.content }))
        setShowMergeConfirm(false)
      }
    } catch (e) {
      console.error('合并失败:', e)
    } finally {
      setChatLoading(false)
    }
  }

  const m = content?.mastery_level || 0
  const mColor = m >= 80 ? '#52c41a' : m >= 40 ? '#faad14' : m > 0 ? '#ff4d4f' : '#e0e0e0'

  return (
    <div className="learn-container">
      {/* 左侧目录 */}
      <div className="learn-sidebar">
        <div className="learn-sidebar-header">📚 知识目录</div>
        <div className="learn-sidebar-tree">
          {tree.map(root => (
            <SidebarNode key={root.id} node={root} activeId={activeKpId} expandedIds={expandedIds} onSelect={handleSelectKp} />
          ))}
          {!tree.length && <div style={{ padding: 16, color: '#ccc', fontSize: 13 }}>暂无知识树</div>}
        </div>
      </div>

      {/* 右侧内容区 */}
      <div className="learn-main">
        {!activeKpId && !loading && (
          <div className="learn-empty">👈 从左侧目录选择知识点开始学习</div>
        )}
        {loading && <div className="learn-loading">🧠 正在生成知识讲解...</div>}

        {content?.error && !loading && (
          <div className="learn-empty" style={{ color: '#ff4d4f' }}>
            ❌ {content.error}
            <br /><button onClick={() => loadContent(activeKpId)} style={{ marginTop: 12, padding: '6px 16px', cursor: 'pointer' }}>重试</button>
          </div>
        )}

        {content && !content.error && !loading && (
          <>
            {/* 上方信息栏 */}
            <div className="learn-info-bar">
              <h2 className="learn-title">{content.knowledge_point_name}</h2>
              <div className="learn-meta">
                <span className="learn-mastery">
                  掌握度 <span style={{ color: mColor, fontWeight: 600 }}>{m}%</span>
                </span>
                <button className="learn-switch-btn" style={{ color: '#ff4d4f', borderColor: '#ff4d4f' }}
                  onClick={async () => {
                    if (!confirm('确定删除当前讲解内容？删除后重新进入将重新生成。')) return
                    try {
                      const resp = await fetch(`${API_LEARN}/content/${activeKpId}`, { method: 'DELETE' }).then(r => r.json())
                      if (resp.code === 0) {
                        setContent(null)
                        setChatHistory([])
                        loadContent(activeKpId)
                        loadChatHistory(activeKpId)
                      }
                    } catch (e) { console.error('删除失败:', e) }
                  }}>🗑 重新生成</button>
                <Link to={`/exam/${activeKpId}`} className="learn-switch-btn exam">✈️ 去答题</Link>
              </div>
            </div>

            {/* 主体：左内容 + 右对话 */}
            <div className="learn-body">
              {/* 知识内容 */}
              <div className="learn-content-area">
                <div className="learn-content" ref={contentRef}>
                  <LearnContentCards content={content.content} />
                </div>

                {/* 高频面试题 */}
                {content.questions?.length > 0 && (
                  <div className="learn-questions">
                    <h3>🎯 高频面试题</h3>
                    {content.questions.map((q, i) => (
                      <div key={i} className="learn-question-card">
                        <div className="learn-q-title">Q{i + 1}: {q.question}</div>
                        <div className="learn-q-answer"><MarkdownContent content={q.answer} /></div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 右侧对话区 */}
              <div className="learn-chat-panel">
                <div className="learn-chat-header">
                  <span>💬 探索对话</span>
                  {chatHistory.length > 0 && (
                    <button className="learn-merge-btn" onClick={() => setShowMergeConfirm(true)}>
                      📝 补充到讲解
                    </button>
                  )}
                </div>

                <div className="learn-chat-messages">
                  {chatHistory.length === 0 && (
                    <div style={{ color: '#ccc', fontSize: 13, padding: '12px 0' }}>
                      选中左侧文本可引用提问
                    </div>
                  )}
                  {chatHistory.map((msg, i) => (
                    <div key={i} className={`learn-chat-msg ${msg.role}`}>
                      {msg.quoted_text && (
                        <div className="learn-chat-quote">📎 {msg.quoted_text}</div>
                      )}
                      <div><MarkdownContent content={msg.content} /></div>
                      {msg.updated_subtopic && (
                        <div className="learn-chat-subtopic-update">
                          <div className="learn-chat-subtopic-label">📝 已融合到讲解</div>
                          <div className="learn-chat-subtopic-preview">
                            <MarkdownContent content={msg.updated_subtopic} />
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                  {chatLoading && <div className="learn-chat-msg assistant">🤔 思考中...</div>}
                  <div ref={chatEndRef} />
                </div>

                {quotedText && (
                  <div className="learn-quote-bar">
                    <span>📎 "{quotedText.length > 40 ? quotedText.slice(0, 40) + '...' : quotedText}"</span>
                    <button onClick={() => setQuotedText('')}>✕</button>
                  </div>
                )}

                <div className="learn-chat-input-wrap">
                  <input
                    className="learn-chat-input"
                    placeholder="提问探索..."
                    value={chatInput}
                    onChange={e => setChatInput(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && !e.shiftKey && handleSendChat()}
                    disabled={chatLoading}
                  />
                  <button className="learn-chat-send" onClick={handleSendChat} disabled={!chatInput.trim() || chatLoading}>
                    发送
                  </button>
                </div>
              </div>
            </div>

            {/* 合并确认弹窗 */}
            {showMergeConfirm && (
              <div className="outliner-dialog-overlay" onClick={() => setShowMergeConfirm(false)}>
                <div className="outliner-dialog" style={{ width: 400 }} onClick={e => e.stopPropagation()}>
                  <div className="outliner-dialog-header">
                    <h3>确认补充到讲解</h3>
                    <button className="outliner-dialog-close" onClick={() => setShowMergeConfirm(false)}>×</button>
                  </div>
                  <div className="outliner-dialog-body">
                    <p style={{ fontSize: 14, color: '#333', lineHeight: 1.8 }}>
                      将对话中 AI 的回答内容合并到知识讲解文章中。<br />
                      LLM 会智能插入到合适位置，不会删除已有内容。
                    </p>
                  </div>
                  <div className="outliner-dialog-footer">
                    <button className="outliner-dialog-cancel" onClick={() => setShowMergeConfirm(false)}>取消</button>
                    <button className="outliner-dialog-submit" onClick={handleMerge} disabled={chatLoading}>
                      {chatLoading ? '合并中...' : '确认合并'}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
