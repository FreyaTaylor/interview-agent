/**
 * 学习页面 — 知识点讲解 + 探索对话
 * 左侧：知识树目录（整棵树，当前节点高亮）
 * 右上：Markdown 讲解 + 高频面试题 + 掌握度信息
 * 右下：探索对话框（支持引用知识文本）
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { API_LEARN } from '../config'
import { findAncestorIds, SidebarNode, useKnowledgeTree } from '../components/KnowledgeSidebar'
import AnswerInput from '../components/AnswerInput'
import { Skeleton, StagePulse, TypingDots } from '../components/Loading'

// 统一 POST + body 小包装（后端 java-style.md “API 形式”要求）
async function postLearn(path, body) {
  const resp = await fetch(`${API_LEARN}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  return resp.json()
}

// Markdown 渲染组件
// 预处理：在中文句号 / 问号 / 感叹号后插入硬换行（markdown 的 "  \n"），让正文一句一行更易读
// 跳过 ``` 代码块、行内 `code`、标题行、表格行
function preprocessSentences(raw) {
  if (!raw) return raw
  // 按 ``` fenced code 拆段（偶数段是普通文本，奇数段是代码块，原样保留）
  const parts = raw.split(/(```[\s\S]*?```)/g)
  return parts.map((seg, i) => {
    if (i % 2 === 1) return seg // 代码块原样
    return seg.split('\n').map(line => {
      const trimmed = line.trimStart()
      // 标题行 / 表格行 / 引用行不动
      if (!trimmed) return line
      if (trimmed.startsWith('#')) return line
      if (trimmed.startsWith('|')) return line
      if (trimmed.startsWith('>')) return line
      // 行内代码 `...` 用占位符护住，处理完再还原
      const codeSlots = []
      const guarded = line.replace(/`[^`\n]*`/g, m => {
        codeSlots.push(m)
        return `\u0000${codeSlots.length - 1}\u0000`
      })
      // 中文句号 / 问号 / 感叹号 后若还有非空白字符，则插入硬换行
      const broken = guarded.replace(/([。？！])(?=\S)/g, '$1  \n')
      return broken.replace(/\u0000(\d+)\u0000/g, (_, idx) => codeSlots[Number(idx)])
    }).join('\n')
  }).join('')
}

// 以“整块”为单位标记高亮：不再在源码插哨兵，避免被 Markdown 语法吃掉。
// addedLines 传进来是“后端返回的新增原文行”（可能含 markdown 修饰）。
function _normForBlockMatch(s) {
  if (!s) return ''
  return s.replace(/[\*_`~|>#\-\s]+/g, '')
}
function _collectText(node) {
  if (!node) return ''
  if (node.type === 'text' || node.type === 'inlineCode') return node.value || ''
  if (!node.children) return ''
  return node.children.map(_collectText).join('')
}
function remarkHighlightAdded(addedLines) {
  const normSet = new Set((addedLines || []).map(_normForBlockMatch).filter(s => s.length >= 4))
  return (tree) => {
    if (normSet.size === 0) return
    function walk(node) {
      if (!node.children) return
      for (const child of node.children) {
        if (['paragraph', 'blockquote', 'listItem', 'heading'].includes(child.type)) {
          const text = _normForBlockMatch(_collectText(child))
          if (text && [...normSet].some(n => text.includes(n) || n.includes(text))) {
            child.data = child.data || {}
            const hp = (child.data.hProperties = child.data.hProperties || {})
            const cls = Array.isArray(hp.className) ? hp.className : (hp.className ? [hp.className] : [])
            if (!cls.includes('learn-added')) cls.push('learn-added')
            hp.className = cls
          }
        }
        walk(child)
      }
    }
    walk(tree)
  }
}

function MarkdownContent({ content, addedLines }) {
  if (!content) return null
  const pre = preprocessSentences(content)
  const plugins = [remarkGfm]
  if (addedLines && addedLines.length > 0) plugins.push(remarkHighlightAdded(addedLines))
  return <Markdown remarkPlugins={plugins}>{pre}</Markdown>
}

// 对话气泡里展示融合状态的小徽章
const MERGE_STATUS_MAP = {
  merged:      { icon: '✅', text: '已补充到讲解（绿色高亮）', cls: 'ok' },
  created:     { icon: '🆕', text: '已新增子话题到讲解（绿色高亮）', cls: 'ok' },
  no_change:   { icon: 'ℹ️', text: '原文已覆盖，无新增内容', cls: 'info' },
  skipped:     { icon: 'ℹ️', text: '原文已覆盖，无新增内容', cls: 'info' },
  no_match:    { icon: '⚠️', text: '引用文本未匹配到任何子话题，未自动补充', cls: 'warn' },
  parse_error: { icon: '⚠️', text: '融合解析失败，未自动补充', cls: 'warn' },
  failed:      { icon: '⚠️', text: '融合失败，未自动补充', cls: 'warn' },
}
function MergeStatusBadge({ status }) {
  const meta = MERGE_STATUS_MAP[status]
  if (!meta) return null
  return <div className={`learn-merge-badge ${meta.cls}`}>{meta.icon} {meta.text}</div>
}

// 将讲解内容按 ### 模块分块，每个 h4 子话题渲染为卡片
function LearnContentCards({ content, addedLines }) {
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
            <MarkdownContent content={body} addedLines={addedLines} />
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
            <MarkdownContent content={lp} addedLines={addedLines} />
          </div>
        ))}
        <div className="learn-sub-cards">
          {subCards.map((sc, ci) => (
            <div key={ci} className="learn-sub-card">
              <div className="learn-sub-card-title">{sc.title}</div>
              <div className="learn-sub-card-body">
                <MarkdownContent content={sc.body} addedLines={addedLines} />
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  })
}

export default function LearnPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const tree = useKnowledgeTree()
  const [activeKpId, setActiveKpId] = useState(null)
  const [content, setContent] = useState(null) // { content, knowledge_point_name, mastery_level, last_studied_at }
  const [questions, setQuestions] = useState([]) // 题目列表（仅叶子节点非空）
  const [loading, setLoading] = useState(false)
  const [chatHistory, setChatHistory] = useState([])
  const [chatLoading, setChatLoading] = useState(false)
  const [quotedText, setQuotedText] = useState('')
  const [regenLoading, setRegenLoading] = useState(false)
  const [regenAllLoading, setRegenAllLoading] = useState(false)
  const [addedLines, setAddedLines] = useState([])  // 本轮会话中被自动补充进讲解的行，用于绿色高亮
  const chatEndRef = useRef(null)
  const contentRef = useRef(null)
  const [expandedIds, setExpandedIds] = useState(new Set())
  const contentCacheRef = useRef({}) // 前端内存缓存：{ [kpId]: contentData }
  const questionsCacheRef = useRef({}) // 前端内存缓存：{ [kpId]: questionsArray }

  // 知识树到位后，按当前 kpId 计算展开路径
  useEffect(() => {
    if (kpId && tree.length > 0) {
      setExpandedIds(findAncestorIds(tree, parseInt(kpId)))
    }
  }, [tree, kpId])

  // 对话面板自动滚到底部
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [chatHistory, chatLoading])

  // URL 参数驱动
  useEffect(() => {
    if (kpId) {
      const id = parseInt(kpId)
      // 学习/答题共用一个 lastKpId，供顶部导航直接跳回
      try { localStorage.setItem('lastKpId', String(id)) } catch (_) { /* ignore */ }
      setActiveKpId(id)
      if (tree.length > 0) {
        setExpandedIds(findAncestorIds(tree, id))
      }
      loadContent(id)
      loadQuestions(id)
      loadChatHistory(id)
    }
  }, [kpId, tree.length])

  const loadContent = useCallback(async (id) => {
    // 有缓存则立即显示，不闪 loading
    const cached = contentCacheRef.current[id]
    if (cached) {
      setContent(cached)
      setLoading(false)
      // 后台静默刷新掌握度等可能变化的字段
      try {
        const resp = await postLearn('/content', { kp_id: id, action: 'fetch' })
        if (resp.code === 0) {
          contentCacheRef.current[id] = resp.data
          setContent(resp.data)
        }
      } catch (e) { /* 静默失败，已有缓存兜底 */ }
      return
    }

    setLoading(true)
    setContent(null)
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const resp = await postLearn('/content', { kp_id: id, action: 'fetch' })
        if (resp.code === 0) {
          contentCacheRef.current[id] = resp.data
          setContent(resp.data)
          setLoading(false)
          return
        }
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
      const resp = await postLearn('/chat-history', { kp_id: id })
      if (resp.code === 0) setChatHistory(resp.data || [])
    } catch (e) {
      console.error('加载对话历史失败:', e)
    }
  }, [])

  const loadQuestions = useCallback(async (id) => {
    // 题目与讲解并发拉，失败不阻断讲解加载
    const cached = questionsCacheRef.current[id]
    if (cached) setQuestions(cached)
    try {
      const resp = await postLearn('/questions', { kp_id: id, action: 'fetch' })
      if (resp.code === 0) {
        const qs = resp.data?.questions || []
        questionsCacheRef.current[id] = qs
        setQuestions(qs)
      }
    } catch (e) {
      console.error('加载题目失败:', e)
    }
  }, [])

  function handleSelectKp(id) {
    setActiveKpId(id)
    setExpandedIds(findAncestorIds(tree, id))
    navigate(`/learn/${id}`, { replace: true })
    loadContent(id)
    loadQuestions(id)
    loadChatHistory(id)
    setQuotedText('')
    setAddedLines([])  // 切换知识点时清除上一个页的高亮
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

  // 发送对话（msg 由 AnswerInput 传入）
  async function handleSendChat(msg) {
    const text = (msg || '').trim()
    if (!text || chatLoading || !activeKpId) return
    const quote = quotedText || null
    setQuotedText('')
    setChatHistory(prev => [...prev, { role: 'user', content: text, quoted_text: quote }])
    setChatLoading(true)

    try {
      const resp = await postLearn('/chat', { knowledge_point_id: activeKpId, message: text, quoted_text: quote })
      if (resp.code === 0) {
        const { reply, updated_subtopic, updated_content, merge_status } = resp.data
        setChatHistory(prev => [...prev, {
          role: 'assistant',
          content: reply,
          updated_subtopic: updated_subtopic || null,
          merge_status: merge_status || null,
        }])
        // 实时更新讲解内容 + 计算 diff 用于红色高亮
        if (updated_content) {
          const prevText = content?.content || ''
          const prevSet = new Set(prevText.split('\n').map(s => s.trim()).filter(Boolean))
          const newAdded = updated_content.split('\n')
            .map(s => s.trim())
            .filter(s => s && s.length > 4 && !s.startsWith('#') && !s.startsWith('|') && !s.startsWith('---') && !prevSet.has(s))
          if (newAdded.length > 0) {
            setAddedLines(prev => [...prev, ...newAdded])
          }
          const newContent = { ...content, content: updated_content }
          setContent(newContent)
          if (activeKpId) contentCacheRef.current[activeKpId] = newContent
        }
      }
    } catch (e) {
      console.error('对话失败:', e)
    } finally {
      setChatLoading(false)
      setTimeout(() => chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
    }
  }

  // 合并对话到讲解 — 已取消（对话后后端会自动融入到子话题，无需手动点击）

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
        {loading && (
          <div className="learn-loading-wrap">
            <StagePulse text="正在生成知识讲解" sub="首次生成需 5-15s，请稍候" />
            <Skeleton lines={5} hasTitle hasBlock />
          </div>
        )}

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
              </div>
            </div>

            {/* 主体：左内容 + 右对话 */}
            <div className="learn-body">
              {/* 知识内容 */}
              <div className="learn-content-area">
                <div className="learn-content" ref={contentRef}>
                  <div className="learn-content-actions" style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                    <button
                      className="learn-regen-all-btn"
                      title="清空当前讲解和对话，重新生成讲解（题目不会变）"
                      disabled={regenAllLoading}
                      style={{ padding: '4px 12px', fontSize: 12, cursor: regenAllLoading ? 'not-allowed' : 'pointer', color: '#1677ff', background: 'transparent', border: '1px solid #1677ff', borderRadius: 4, opacity: regenAllLoading ? 0.6 : 1 }}
                      onClick={async () => {
                        if (!activeKpId || regenAllLoading) return
                        if (!confirm('确定重新生成讲解？当前讲解和对话会被清空（题目不受影响）。')) return
                        setRegenAllLoading(true)
                        try {
                          const resp = await postLearn('/content', { kp_id: activeKpId, action: 'regenerate' })
                          if (resp.code === 0) {
                            contentCacheRef.current[activeKpId] = resp.data
                            setContent(resp.data)
                            setChatHistory([])
                          } else {
                            alert(resp.message || '重新生成失败')
                          }
                        } catch (e) {
                          alert('重新生成失败: ' + e.message)
                        } finally {
                          setRegenAllLoading(false)
                        }
                      }}
                    >{regenAllLoading ? '生成中…' : '🔁 重新生成讲解'}</button>
                  </div>
                  <LearnContentCards content={content.content} addedLines={addedLines} />
                </div>

                {/* 高频面试题（与答题页同源：study_question） */}
                {questions.length > 0 && (
                  <div className="learn-questions">
                    <div className="learn-q-header-row">
                      <h3>🎯 高频面试题</h3>
                      <button
                        className="learn-regen-q-btn"
                        title="生成 5 道新题（已有作答的题目会保留，总数最多 15 道）"
                        disabled={regenLoading}
                        onClick={async () => {
                          if (!activeKpId || regenLoading) return
                          if (!confirm('确定生成 5 道新面试题？已有作答历史的题目会保留。')) return
                          setRegenLoading(true)
                          try {
                            const resp = await postLearn('/questions', { kp_id: activeKpId, action: 'regenerate' })
                            if (resp.code === 0) {
                              const prevIds = new Set(questions.map(q => q.id))
                              const newQs = resp.data?.questions || []
                              const addedCount = newQs.filter(q => !prevIds.has(q.id)).length
                              questionsCacheRef.current[activeKpId] = newQs
                              setQuestions(newQs)
                              if (addedCount === 0) {
                                alert(`题目总数已达上限（${newQs.length} 道），未新增。`)
                              }
                            } else {
                              alert(resp.message || '重新生成失败')
                            }
                          } catch (e) {
                            alert('重新生成失败: ' + e.message)
                          } finally {
                            setRegenLoading(false)
                          }
                        }}
                      >{regenLoading ? '⏳ 生成中...' : '🔄 重新生成题目'}</button>
                    </div>
                    {questions.map((q, i) => (
                      <div key={q.id ?? i} className="learn-question-card">
                        <div className="learn-q-title">Q{i + 1}: {q.question}</div>
                        {Array.isArray(q.recommended_answer) && q.recommended_answer.length > 0 ? (
                          <ul className="learn-q-answer">
                            {q.recommended_answer.map((line, j) => (
                              <li key={j}>{line}</li>
                            ))}
                          </ul>
                        ) : typeof q.recommended_answer === 'string' && q.recommended_answer ? (
                          <div className="learn-q-answer-text">{q.recommended_answer}</div>
                        ) : (
                          <div className="learn-q-answer-empty">（暂无范例答案）</div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 右侧对话区 */}
              <div className="learn-chat-panel">
                <div className="learn-chat-header">
                  <span>💬 探索对话</span>
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
                      {msg.merge_status && <MergeStatusBadge status={msg.merge_status} />}
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
                  {chatLoading && (
                    <div className="learn-chat-msg assistant">
                      <TypingDots text="思考中" />
                    </div>
                  )}
                  <div ref={chatEndRef} />
                </div>

                {quotedText && (
                  <div className="learn-quote-bar">
                    <span>📎 "{quotedText.length > 40 ? quotedText.slice(0, 40) + '...' : quotedText}"</span>
                    <button onClick={() => setQuotedText('')}>✕</button>
                  </div>
                )}

                <div className="learn-chat-input-wrap">
                  <AnswerInput
                    onSend={handleSendChat}
                    disabled={chatLoading}
                    placeholder="提问探索... (Shift+Enter 换行)"
                  />
                </div>
              </div>
            </div>

            {/* 合并确认弹窗 — 已移除（改为对话后后端自动融入，新增内容以绿色高亮提示） */}
          </>
        )}
      </div>
    </div>
  )
}
