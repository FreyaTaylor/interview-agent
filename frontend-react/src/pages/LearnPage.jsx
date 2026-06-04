/**
 * 学习页面 — 知识点子话题卡片 + 探索对话（S4 重构后）
 * 左侧：知识树目录
 * 右上：子话题卡片（含 ⭐ 重要度 + 追问折叠）
 * 右下：探索对话框（前端把"用户停留在哪个子话题卡片内"的 subtopic_id 一并传给后端）
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { API_LEARN } from '../config'
import { findAncestorIds, SidebarNode, useKnowledgeTree } from '../components/KnowledgeSidebar'
import AnswerInput from '../components/AnswerInput'
import { Skeleton, StagePulse, TypingDots } from '../components/Loading'

// 统一 POST + body 小包装（后端 java-style.md "API 形式"要求）
async function postLearn(path, body) {
  const resp = await fetch(`${API_LEARN}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  return resp.json()
}

// Markdown 渲染：在中文句号 / 问号 / 感叹号后插入硬换行（"  \n"），跳过代码块/标题/表格/引用
// 两种情况都要处理：
//   (1) 句号后同行非空字符 → 强行插换行   "AssertionError。 Exception" → "AssertionError。\nException"
//   (2) 句号后是软换行（单 \n） → Markdown 默认渲染为空格，需升级为硬换行
function preprocessSentences(raw) {
  if (!raw) return raw
  const parts = raw.split(/(```[\s\S]*?```)/g)
  return parts.map((seg, i) => {
    if (i % 2 === 1) return seg
    // 第一遍：行内 "句号 + 空格? + 非空白" → 插入硬换行
    const broken = seg.split('\n').map(line => {
      const trimmed = line.trimStart()
      if (!trimmed) return line
      if (trimmed.startsWith('#') || trimmed.startsWith('|') || trimmed.startsWith('>')) return line
      const codeSlots = []
      const guarded = line.replace(/`[^`\n]*`/g, m => {
        codeSlots.push(m)
        return `\u0000${codeSlots.length - 1}\u0000`
      })
      const fixed = guarded.replace(/([。？！])[ \t　]*(?=\S)/g, '$1  \n')
      return fixed.replace(/\u0000(\d+)\u0000/g, (_, idx) => codeSlots[Number(idx)])
    }).join('\n')
    // 第二遍：跨行 "句号 + 单换行" → "句号  \n"（保留段落结构，仅升级软换行为硬换行）
    // 不动 "句号 + 空行 + ..."（那是段落分隔，已经是硬分段）
    return broken.replace(/([。？！])\n(?!\n)(?=[^\s#|>])/g, '$1  \n')
  }).join('')
}

function MarkdownContent({ content }) {
  if (!content) return null
  return <Markdown remarkPlugins={[remarkGfm]}>{preprocessSentences(content)}</Markdown>
}

// 重要度 ⭐⭐⭐⭐⭐（实心数 = importance）
function ImportanceStars({ value }) {
  const n = Math.max(1, Math.min(5, value || 3))
  return (
    <span className="learn-importance" title={`面试重要度 ${n}/5`}>
      {'★'.repeat(n)}<span className="learn-importance-dim">{'★'.repeat(5 - n)}</span>
    </span>
  )
}

// 追问直接展开显示：Q 一行 + A 正文（原“对话气泡”样式）
function FollowupItem({ fu }) {
  return (
    <div className="learn-fu">
      <div className="learn-fu-q">
        <span className="learn-fu-icon">🎙</span>
        <span className="learn-fu-q-text">{fu.q}</span>
      </div>
      <div className="learn-fu-a">
        <MarkdownContent content={fu.a} />
      </div>
    </div>
  )
}

// 单个子话题卡片
function SubtopicCard({ st, isHighlighted, isFlash, onQuote, onDelete }) {
  const cardRef = useRef(null)
  function handleQuoteSelection() {
    const sel = window.getSelection()
    const text = sel?.toString().trim() || ''
    if (text && cardRef.current?.contains(sel.anchorNode)) {
      onQuote(st.id, text)
    }
  }
  return (
    <div
      ref={cardRef}
      className={`learn-sub-card ${isHighlighted ? 'learn-sub-card-quoted' : ''} ${isFlash ? 'learn-sub-card-flash' : ''}`}
      data-subtopic-id={st.id}
      onMouseUp={handleQuoteSelection}
    >
      <div className="learn-sub-card-head">
        <div className="learn-sub-card-title">{st.title}</div>
        <ImportanceStars value={st.importance} />
        <button
          className="learn-sub-card-delete"
          title="删除此子话题"
          onClick={(e) => { e.stopPropagation(); onDelete(st) }}
        >×</button>
      </div>
      <div className="learn-sub-card-body">
        <MarkdownContent content={st.body_md} />
      </div>
      {Array.isArray(st.followups) && st.followups.length > 0 && (
        <div className="learn-fu-list">
          {st.followups.map((fu, i) => <FollowupItem key={i} fu={fu} />)}
        </div>
      )}
    </div>
  )
}

export default function LearnPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const tree = useKnowledgeTree()
  const [activeKpId, setActiveKpId] = useState(null)
  const [content, setContent] = useState(null) // { knowledge_point_name, subtopics, mastery_level }
  const [questions, setQuestions] = useState([])
  const [loading, setLoading] = useState(false)
  const [chatHistory, setChatHistory] = useState([])
  const [chatLoading, setChatLoading] = useState(false)
  const [quotedSubtopicId, setQuotedSubtopicId] = useState(null)
  const [quotedText, setQuotedText] = useState('')
  const [regenLoading, setRegenLoading] = useState(false)
  const [regenAllLoading, setRegenAllLoading] = useState(false)
  const chatEndRef = useRef(null)
  const [expandedIds, setExpandedIds] = useState(new Set())
  const [flashSubtopicId, setFlashSubtopicId] = useState(null) // 刚被追加/新增的子话题：顶层添加闪烁红色背景

  // 追加/新增后：滚动目标卡到可视区中间 + 闪烁红背景 ~2s。
  // 仅仅 setFlashSubtopicId 是不够的 — 同一 id 连续触发不会重启 CSS 动画，所以先 null 再 set。
  const flashAndScroll = useCallback((subtopicId) => {
    if (!subtopicId) return
    setFlashSubtopicId(null)
    // 等 DOM 重染（新卡刚插入）后再查询 + 启动动画
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = document.querySelector(`[data-subtopic-id="${subtopicId}"]`)
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        setFlashSubtopicId(subtopicId)
        setTimeout(() => setFlashSubtopicId(prev => (prev === subtopicId ? null : prev)), 2200)
      })
    })
  }, [])
  const contentCacheRef = useRef({})
  const questionsCacheRef = useRef({})

  useEffect(() => {
    if (kpId && tree.length > 0) {
      setExpandedIds(findAncestorIds(tree, parseInt(kpId)))
    }
  }, [tree, kpId])

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [chatHistory, chatLoading])

  useEffect(() => {
    if (kpId) {
      const id = parseInt(kpId)
      try { localStorage.setItem('lastKpId', String(id)) } catch (_) { /* ignore */ }
      setActiveKpId(id)
      if (tree.length > 0) setExpandedIds(findAncestorIds(tree, id))
      loadContent(id)
      loadQuestions(id)
      loadChatHistory(id)
    }
  }, [kpId, tree.length])

  const loadContent = useCallback(async (id) => {
    const cached = contentCacheRef.current[id]
    if (cached) {
      setContent(cached)
      setLoading(false)
      // 后台静默刷新
      try {
        const resp = await postLearn('/content', { kp_id: id, action: 'fetch' })
        if (resp.code === 0) {
          contentCacheRef.current[id] = resp.data
          setContent(resp.data)
        }
      } catch (_) { /* 静默 */ }
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
        if (attempt === 1) setContent({ error: '加载失败: ' + e.message })
        else await new Promise(r => setTimeout(r, 1000))
      }
    }
    setLoading(false)
  }, [])

  const loadChatHistory = useCallback(async (id) => {
    try {
      const resp = await postLearn('/chat-history', { kp_id: id })
      if (resp.code === 0) setChatHistory(resp.data || [])
    } catch (e) { console.error('加载对话历史失败:', e) }
  }, [])

  const loadQuestions = useCallback(async (id) => {
    const cached = questionsCacheRef.current[id]
    if (cached) setQuestions(cached)
    try {
      const resp = await postLearn('/questions', { kp_id: id, action: 'fetch' })
      if (resp.code === 0) {
        const qs = resp.data?.questions || []
        questionsCacheRef.current[id] = qs
        setQuestions(qs)
      }
    } catch (e) { console.error('加载题目失败:', e) }
  }, [])

  function handleSelectKp(id) {
    setActiveKpId(id)
    setExpandedIds(findAncestorIds(tree, id))
    navigate(`/learn/${id}`, { replace: true })
    loadContent(id)
    loadQuestions(id)
    loadChatHistory(id)
    setQuotedSubtopicId(null)
    setQuotedText('')
  }

  // 用户在某个 subtopic 卡片内选中文字 → 同时记录 id + text
  function handleQuoteFromCard(subtopicId, text) {
    setQuotedSubtopicId(subtopicId)
    setQuotedText(text)
  }

  // 删除某个子话题：二次确认 → 后端删 → 本地状态 + 缓存同步剔除
  async function handleDeleteSubtopic(st) {
    if (!activeKpId) return
    if (!window.confirm(`确定删除子话题「${st.title}」？此操作不可撤销。`)) return
    try {
      const resp = await postLearn('/subtopic-delete', {
        kp_id: Number(activeKpId), subtopic_id: st.id,
      })
      if (resp.code !== 0) {
        alert('删除失败: ' + (resp.message || '未知错误'))
        return
      }
      setContent(prev => {
        if (!prev || !Array.isArray(prev.subtopics)) return prev
        const next = { ...prev, subtopics: prev.subtopics.filter(s => s.id !== st.id) }
        contentCacheRef.current[activeKpId] = next
        return next
      })
      // 若刚好引用的是被删卡片，清掉引用
      if (quotedSubtopicId === st.id) {
        setQuotedSubtopicId(null)
        setQuotedText('')
      }
    } catch (e) {
      alert('删除失败: ' + e.message)
    }
  }

  // 发送对话
  async function handleSendChat(msg) {
    const text = (msg || '').trim()
    if (!text || chatLoading || !activeKpId) return
    const quoteId = quotedSubtopicId
    const quoteText = quotedText || null
    setQuotedSubtopicId(null)
    setQuotedText('')
    setChatHistory(prev => [...prev, {
      role: 'user', content: text, quoted_text: quoteText, quoted_subtopic_id: quoteId,
    }])
    setChatLoading(true)

    try {
      const resp = await postLearn('/chat', {
        knowledge_point_id: activeKpId,
        message: text,
        quoted_subtopic_id: quoteId,
        quoted_text: quoteText,
      })
      if (resp.code === 0) {
        const { reply, action, appended_to, followup, new_subtopic } = resp.data
        setChatHistory(prev => [...prev, {
          role: 'assistant',
          content: reply,
          action: action || 'none',
          appended_to: appended_to || null,
          followup: followup || null,
          new_subtopic: new_subtopic || null,
        }])
        // 副作用反映到 content：append_followup → 找到 subtopic 追加 followup；new_subtopic → 末尾追加
        if (action === 'append_followup' && appended_to && followup) {
          setContent(prev => {
            if (!prev?.subtopics) return prev
            const next = {
              ...prev,
              subtopics: prev.subtopics.map(st =>
                st.id === appended_to
                  ? { ...st, followups: [...(st.followups || []), { ...followup, created_at: new Date().toISOString() }] }
                  : st
              ),
            }
            if (activeKpId) contentCacheRef.current[activeKpId] = next
            return next
          })
          flashAndScroll(appended_to)
        } else if (action === 'new_subtopic' && new_subtopic) {
          setContent(prev => {
            if (!prev?.subtopics) return prev
            const next = { ...prev, subtopics: [...prev.subtopics, new_subtopic] }
            if (activeKpId) contentCacheRef.current[activeKpId] = next
            return next
          })
          flashAndScroll(new_subtopic.id)
        }
      }
    } catch (e) {
      console.error('对话失败:', e)
    } finally {
      setChatLoading(false)
      setTimeout(() => chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50)
    }
  }

  const m = content?.mastery_level || 0
  const mColor = m >= 80 ? '#52c41a' : m >= 40 ? '#faad14' : m > 0 ? '#ff4d4f' : '#e0e0e0'
  const subtopics = content?.subtopics || []

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
            <StagePulse text="正在生成知识子话题" sub="首次生成需 5-15s，请稍候" />
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
            <div className="learn-info-bar">
              <h2 className="learn-title">{content.knowledge_point_name}</h2>
              <div className="learn-meta">
                <span className="learn-mastery">
                  掌握度 <span style={{ color: mColor, fontWeight: 600 }}>{m}%</span>
                </span>
              </div>
            </div>

            <div className="learn-body">
              {/* 子话题卡片区 */}
              <div className="learn-content-area">
                <div className="learn-content-actions" style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                  <button
                    className="learn-regen-all-btn"
                    title="清空当前讲解和对话，重新生成（题目不变）"
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

                <div className="learn-sub-cards">
                  {subtopics.length === 0 && (
                    <div className="learn-empty" style={{ padding: 24, color: '#999' }}>暂无子话题</div>
                  )}
                  {subtopics.map(st => (
                    <SubtopicCard
                      key={st.id}
                      st={st}
                      isHighlighted={st.id === quotedSubtopicId}
                      isFlash={st.id === flashSubtopicId}
                      onQuote={handleQuoteFromCard}
                      onDelete={handleDeleteSubtopic}
                    />
                  ))}
                </div>

                {/* 高频面试题 */}
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
                            {q.recommended_answer.map((line, j) => (<li key={j}>{line}</li>))}
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
                <div className="learn-chat-header"><span>💬 探索对话</span></div>

                <div className="learn-chat-messages">
                  {chatHistory.length === 0 && (
                    <div style={{ color: '#ccc', fontSize: 13, padding: '12px 0' }}>
                      在任一子话题卡片内选中文字可引用提问
                    </div>
                  )}
                  {chatHistory.map((msg, i) => (
                    <div key={i} className={`learn-chat-msg ${msg.role}`}>
                      {msg.quoted_text && (
                        <div className="learn-chat-quote">
                          📎 {msg.quoted_text}
                          {msg.quoted_subtopic_id && <span className="learn-chat-quote-tag"> · #{msg.quoted_subtopic_id}</span>}
                        </div>
                      )}
                      <div><MarkdownContent content={msg.content} /></div>
                      {msg.action === 'append_followup' && msg.followup && (
                        <div className="learn-merge-badge ok">✅ 已追加到子话题 #{msg.appended_to}：{msg.followup.q}</div>
                      )}
                      {msg.action === 'new_subtopic' && msg.new_subtopic && (
                        <div className="learn-merge-badge ok">🆕 已新增子话题：{msg.new_subtopic.title}</div>
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
                    <span>
                      📎 "{quotedText.length > 40 ? quotedText.slice(0, 40) + '...' : quotedText}"
                      {quotedSubtopicId && <span style={{ color: '#888', marginLeft: 6 }}>· #{quotedSubtopicId}</span>}
                    </span>
                    <button onClick={() => { setQuotedText(''); setQuotedSubtopicId(null) }}>✕</button>
                  </div>
                )}

                <div className="learn-chat-input-wrap">
                  <AnswerInput
                    onSend={handleSendChat}
                    disabled={chatLoading}
                    placeholder="提问探索...（点右侧按钮发送，Enter 换行）"
                  />
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
