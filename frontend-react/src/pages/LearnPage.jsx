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
import { findAncestorIds, SidebarNode, useKnowledgeTree, refreshKnowledgeTree } from '../components/KnowledgeSidebar'
import { Skeleton, StagePulse } from '../components/Loading'

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
      // 保护行内代码 `code` 与加粗 **bold**：句号硬换行不能插进它们内部，
      // 否则会把 ** 分隔符拆到两行 → 加粗解析失败，渲染出裸 ** 记号。
      const guarded = line.replace(/`[^`\n]*`|\*\*[^*\n]+?\*\*/g, m => {
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

// 行内 Markdown（渲染加粗/行内代码等，不做整段换行处理，<p> 降级为 <span> 保持内联）
function InlineMd({ children }) {
  if (!children) return null
  return (
    <Markdown remarkPlugins={[remarkGfm]} components={{ p: ({ node, ...props }) => <span {...props} /> }}>
      {String(children)}
    </Markdown>
  )
}

// 单条目标题：题干前带 tier 徽标 + 查看/收起参考答案按钮；展开在嵌套子框内分点展示参考答案
function TargetItem({ q, onToggleTier }) {
  const [showAnswer, setShowAnswer] = useState(false)
  const answer = Array.isArray(q.recommended_answer) ? q.recommended_answer : []
  const rubric = Array.isArray(q.rubric) ? q.rubric : []
  const hasAnswer = answer.length > 0 || rubric.length > 0
  return (
    <li className="learn-target-item">
      <div className="learn-target-line">
        <button
          type="button"
          className={`learn-tier-badge ${q.tier === 'ext' ? 'ext' : 'core'}`}
          title="点击切换 高频/扩展"
          onClick={() => onToggleTier(q.id, q.tier)}
        >
          {q.tier === 'ext' ? '扩展' : '高频'}
        </button>
        {hasAnswer && (
          <button
            type="button"
            className="learn-answer-toggle"
            onClick={() => setShowAnswer(v => !v)}
          >{showAnswer ? '收起参考答案' : '查看参考答案'}</button>
        )}
        <span className="learn-target-text">{q.content}</span>
      </div>
      {showAnswer && hasAnswer && (
        <div className="learn-answer-box">
          {rubric.length > 0 && (
            <div className="learn-rubric-block">
              <div className="learn-answer-subtitle">📊 评分采分点</div>
              <table className="learn-rubric-table">
                <thead>
                  <tr><th>采分点</th><th>命中规则</th><th>权重</th></tr>
                </thead>
                <tbody>
                  {rubric.map((r, i) => (
                    <tr key={i}>
                      <td><InlineMd>{r.key_point}</InlineMd></td>
                      <td><InlineMd>{r.hit_rule}</InlineMd></td>
                      <td className="learn-rubric-score">{r.score}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {answer.length > 0 && (
            <div className="learn-refans-block">
              <div className="learn-answer-subtitle">✍️ 参考答案</div>
              <ul className="learn-answer-list">
                {answer.map((pt, i) => <li key={i}><InlineMd>{pt}</InlineMd></li>)}
              </ul>
            </div>
          )}
        </div>
      )}
    </li>
  )
}

// 单个子话题卡片
function SubtopicCard({ st, onDelete, onGenerate, onToggleTier }) {
  const [generating, setGenerating] = useState(false)
  const [bodyCollapsed, setBodyCollapsed] = useState(false)
  const isPending = st.content_status === 'pending' || !st.body_md
  const targets = Array.isArray(st.target_questions) ? st.target_questions : []
  async function handleGenerate() {
    if (generating) return
    setGenerating(true)
    try { await onGenerate(st.id) } finally { setGenerating(false) }
  }
  return (
    <div
      className="learn-sub-card"
      data-subtopic-id={st.id}
    >
      <div className="learn-sub-card-head">
        <div className="learn-sub-card-title">
          <span className="learn-sub-card-title-text">{st.title}</span>
          {!st.is_hot && (
            <button
              className="learn-sub-card-delete"
              title="删除此子话题（仅含扩展题的子话题可删）"
              onClick={(e) => { e.stopPropagation(); onDelete(st) }}
              aria-label="删除此子话题"
            >×</button>
          )}
        </div>
        <div className="learn-sub-card-head-right">
          {typeof st.mastery_level === 'number' && (
            <span className="learn-sub-mastery" title="该子话题掌握度（近期答题均分）">
              掌握 {st.mastery_level}
            </span>
          )}
        </div>
      </div>
      {targets.length > 0 && (
        <div className="learn-sub-targets">
          <div className="learn-sub-targets-label">🎯 学完你应能回答：</div>
          <ul className="learn-sub-targets-list">
            {targets.map(q => (
              <TargetItem
                key={q.id}
                q={q}
                onToggleTier={(qid, tier) => onToggleTier(st.id, qid, tier)}
              />
            ))}
          </ul>
        </div>
      )}
      <div className="learn-sub-card-body">
        {isPending ? (
          <button className="learn-sub-gen-btn" disabled={generating} onClick={handleGenerate}>
            {generating ? '⏳ 生成中…' : '📖 生成讲解'}
          </button>
        ) : (
          <>
            <button
              type="button"
              className="learn-sub-gen-btn"
              onClick={() => setBodyCollapsed(c => !c)}
            >{bodyCollapsed ? '📖 展开讲解' : '📖 收起讲解'}</button>
            {!bodyCollapsed && (
              <div className="learn-body-box">
                <MarkdownContent content={st.body_md} />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export default function LearnPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const tree = useKnowledgeTree()
  const [activeKpId, setActiveKpId] = useState(null)
  const [content, setContent] = useState(null) // { knowledge_point_name, subtopics, mastery_level }
  const [loading, setLoading] = useState(false)
  const [regenAllLoading, setRegenAllLoading] = useState(false)
  const [expandedIds, setExpandedIds] = useState(new Set())
  const [selfVal, setSelfVal] = useState(0)   // 自评滑块本地值（0-100）
  const contentCacheRef = useRef({})
  // 记录正在请求中的 kp：防止 effect 依赖变化（如 tree.length 由 0→N）导致同一 kp 的 /content 被并发触发两次，
  // 首次生成尚未返回时缓存挡不住 → 会在后端重复生成两批子话题。
  const contentInflightRef = useRef({})

  useEffect(() => {
    if (kpId && tree.length > 0) {
      setExpandedIds(findAncestorIds(tree, parseInt(kpId)))
    }
  }, [tree, kpId])

  // 内容切换后同步自评滑块显示值
  useEffect(() => {
    setSelfVal(content?.self_mastery ?? 0)
  }, [content?.self_mastery])

  useEffect(() => {
    if (kpId) {
      const id = parseInt(kpId)
      try { localStorage.setItem('lastKpId', String(id)) } catch (_) { /* ignore */ }
      setActiveKpId(id)
      if (tree.length > 0) setExpandedIds(findAncestorIds(tree, id))
      loadContent(id)
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
    // 同一 kp 已有请求在飞 → 直接跳过，避免并发触发后端重复生成
    if (contentInflightRef.current[id]) return
    contentInflightRef.current[id] = true
    try {
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
    } finally {
      delete contentInflightRef.current[id]
    }
  }, [])

  function handleSelectKp(id) {
    setActiveKpId(id)
    setExpandedIds(findAncestorIds(tree, id))
    navigate(`/learn/${id}`, { replace: true })
    loadContent(id)
  }

  // Step B：点击 pending 子话题的"生成讲解" → 调 /subtopic-content 生成正文 → 就地替换该卡片 + 同步缓存
  async function handleGenerateSubtopic(subtopicId) {
    try {
      const resp = await postLearn('/subtopic-content', { subtopic_id: subtopicId, action: 'fetch' })
      if (resp.code !== 0) {
        alert('生成讲解失败: ' + (resp.message || '未知错误'))
        return
      }
      setContent(prev => {
        if (!prev || !Array.isArray(prev.subtopics)) return prev
        const next = { ...prev, subtopics: prev.subtopics.map(s => (s.id === subtopicId ? resp.data : s)) }
        if (activeKpId) contentCacheRef.current[activeKpId] = next
        return next
      })
    } catch (e) {
      alert('生成讲解失败: ' + e.message)
    }
  }

  // 切换某目标题的 tier（core↔ext）：乐观更新本地态 + 缓存；后端失败则回滚
  async function handleToggleTier(subtopicId, questionId, curTier) {
    if (!activeKpId) return
    const nextTier = curTier === 'ext' ? 'core' : 'ext'
    const applyTier = (tier) => setContent(prev => {
      if (!prev?.subtopics) return prev
      const next = {
        ...prev,
        subtopics: prev.subtopics.map(s => s.id !== subtopicId ? s : {
          ...s,
          is_hot: (s.target_questions || []).some(q => q.id === questionId ? tier === 'core' : q.tier === 'core'),
          target_questions: (s.target_questions || []).map(q => q.id === questionId ? { ...q, tier } : q),
        }),
      }
      contentCacheRef.current[activeKpId] = next
      return next
    })
    applyTier(nextTier)
    try {
      const resp = await postLearn('/question-tier', { kp_id: Number(activeKpId), question_id: questionId, tier: nextTier })
      if (resp.code !== 0) {
        applyTier(curTier)
        alert('切换失败: ' + (resp.message || '未知错误'))
      }
    } catch (e) {
      applyTier(curTier)
      alert('切换失败: ' + e.message)
    }
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
    } catch (e) {
      alert('删除失败: ' + e.message)
    }
  }

  // 设置自评掌握度（滑块 0-100）
  async function handleSetSelfMastery(value) {
    if (!activeKpId) return
    const next = value
    if (content?.self_mastery === next) return   // 值未变，跳过请求
    try {
      const resp = await postLearn('/self-mastery', { kp_id: Number(activeKpId), self_mastery: next })
      if (resp.code !== 0) {
        alert('设置失败: ' + (resp.message || '未知错误'))
        return
      }
      setContent(prev => {
        if (!prev) return prev
        const updated = { ...prev, self_mastery: resp.data ?? null }
        contentCacheRef.current[activeKpId] = updated
        return updated
      })
      // 刷新左侧/知识树掌握度圆环（有效掌握度 = max(答题, 自评)）
      refreshKnowledgeTree()
    } catch (e) {
      alert('设置失败: ' + e.message)
    }
  }

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
                <span className="learn-self-mastery">
                  <span className="lsm-label" title="看完讲解后自评掌握程度">自评掌握度</span>
                  <input
                    type="range"
                    min="0"
                    max="100"
                    step="5"
                    value={selfVal}
                    className="lsm-slider"
                    onChange={e => setSelfVal(Number(e.target.value))}
                    onMouseUp={e => handleSetSelfMastery(Number(e.target.value))}
                    onTouchEnd={e => handleSetSelfMastery(Number(e.target.value))}
                    onKeyUp={e => handleSetSelfMastery(Number(e.target.value))}
                  />
                  <span className="lsm-value">{selfVal}%</span>
                </span>
              </div>
            </div>

            <div className="learn-body">
              {/* 子话题卡片区 */}
              <div className="learn-content-area">
                <div className="learn-content-actions" style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                  <button
                    className="learn-regen-all-btn"
                    title="清空当前学习内容，重新生成（题目不变）"
                    disabled={regenAllLoading}
                    style={{ padding: '4px 12px', fontSize: 12, cursor: regenAllLoading ? 'not-allowed' : 'pointer', color: '#1677ff', background: 'transparent', border: '1px solid #1677ff', borderRadius: 4, opacity: regenAllLoading ? 0.6 : 1 }}
                    onClick={async () => {
                      if (!activeKpId || regenAllLoading) return
                      if (!confirm('确定重新生成学习内容？当前学习目标/学习内容会被清空（题目不受影响）。')) return
                      setRegenAllLoading(true)
                      try {
                        const resp = await postLearn('/content', { kp_id: activeKpId, action: 'regenerate' })
                        if (resp.code === 0) {
                          contentCacheRef.current[activeKpId] = resp.data
                          setContent(resp.data)
                        } else {
                          alert(resp.message || '重新生成失败')
                        }
                      } catch (e) {
                        alert('重新生成失败: ' + e.message)
                      } finally {
                        setRegenAllLoading(false)
                      }
                    }}
                  >{regenAllLoading ? '生成中…' : '🔁 重新生成学习内容'}</button>
                </div>

                <div className="learn-sub-cards">
                  {subtopics.length === 0 && (
                    <div className="learn-empty" style={{ padding: 24, color: '#999' }}>暂无子话题</div>
                  )}
                  {subtopics.map(st => (
                    <SubtopicCard
                      key={st.id}
                      st={st}
                      onDelete={handleDeleteSubtopic}
                      onGenerate={handleGenerateSubtopic}
                      onToggleTier={handleToggleTier}
                    />
                  ))}
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
