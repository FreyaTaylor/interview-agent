/**
 * 看看面经页面 —— 左侧面经树（域→问题），点开一个问题懒生成「讲解 + rubric + 推荐答案」，
 * 带一个木鱼图标，点一下看过次数 +1。结构镜像 LearnPage（复用 learn-* 样式），砂掉子话题多卡与探索对话。
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { API_ROOT } from '../config'
import { useExpTree, findExpAncestorIds, refreshExpTree, ExpSidebarNode } from '../components/ExpSidebar'
import { Skeleton, StagePulse } from '../components/Loading'

const API_EXP = `${API_ROOT}/interview-exp`

async function postExp(path, body) {
  const resp = await fetch(`${API_EXP}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  })
  return resp.json()
}

// 中文句号后插硬换行（复用 LearnPage 逻辑的精简版）
function preprocessSentences(raw) {
  if (!raw) return raw
  const parts = raw.split(/(```[\s\S]*?```)/g)
  return parts.map((seg, i) => {
    if (i % 2 === 1) return seg
    return seg.replace(/([。？！])\n(?!\n)(?=[^\s#|>])/g, '$1  \n')
  }).join('')
}

function MarkdownContent({ content }) {
  if (!content) return null
  return <Markdown remarkPlugins={[remarkGfm]}>{preprocessSentences(content)}</Markdown>
}

function InlineMd({ children }) {
  if (!children) return null
  return (
    <Markdown remarkPlugins={[remarkGfm]} components={{ p: ({ node, ...props }) => <span {...props} /> }}>
      {String(children)}
    </Markdown>
  )
}

export default function InterviewExpStudyPage() {
  const { questionId } = useParams()
  const navigate = useNavigate()
  const activeId = questionId ? Number(questionId) : null

  const tree = useExpTree()
  const [content, setContent] = useState(null)
  const [loading, setLoading] = useState(false)
  const [viewCount, setViewCount] = useState(0)
  const [knocking, setKnocking] = useState(false)   // 木鱼敲击动画
  const [merits, setMerits] = useState([])          // 飘字「功德+1」队列
  const [regenLoading, setRegenLoading] = useState(false)
  const [showAnswer, setShowAnswer] = useState(false)
  const cacheRef = useRef({})
  const inflightRef = useRef(new Set())   // 正在生成中的 qid 集合（按节点去重，不再全局互斥）
  const activeIdRef = useRef(activeId)     // 当前激活节点，异步回来时用于判断是否还该渲染

  const expandedIds = activeId ? findExpAncestorIds(tree, activeId) : new Set()

  const loadContent = useCallback(async (qid, action = 'fetch') => {
    if (!qid) return
    // 命中缓存直接展示（已生成的完整内容）
    if (action === 'fetch' && cacheRef.current[qid]) {
      setContent(cacheRef.current[qid])
      setViewCount(cacheRef.current[qid].view_count ?? 0)
      setLoading(false)
      return
    }
    // 切到该节点先进入 loading 空态
    setContent(null)
    setLoading(true)
    // 同一节点已在生成中：不重复发请求，等原请求回来（届时若仍是当前节点会渲染）
    if (inflightRef.current.has(qid)) return
    inflightRef.current.add(qid)
    try {
      const resp = await postExp('/content', { question_id: qid, action })
      // 无论用户是否已切走都缓存 → 下次点回即完整页面，不再是空的
      if (resp.code === 0) cacheRef.current[qid] = resp.data
      // 仅当结果仍对应当前激活节点才渲染，避免把 A 的内容画到 B 上
      if (activeIdRef.current === qid) {
        if (resp.code === 0) {
          setContent(resp.data)
          setViewCount(resp.data.view_count ?? 0)
        } else {
          setContent({ error: resp.message || '生成失败' })
        }
        setLoading(false)
      }
    } catch (e) {
      if (activeIdRef.current === qid) {
        setContent({ error: e.message })
        setLoading(false)
      }
    } finally {
      inflightRef.current.delete(qid)
    }
  }, [])

  useEffect(() => {
    activeIdRef.current = activeId
    setShowAnswer(false)
    if (activeId) loadContent(activeId)
    else { setContent(null); setLoading(false) }
  }, [activeId, loadContent])

  function handleSelect(qid) {
    localStorage.setItem('lastExpQuestionId', String(qid))
    navigate(`/interview-exp/${qid}`)
  }

  async function handleKnock() {
    if (!activeId) return
    // 立即乐观 +1 + 动画 + 飘字（不等后端，敲得爽）
    setViewCount(v => v + 1)
    setKnocking(true)
    setTimeout(() => setKnocking(false), 180)
    const mid = Date.now() + Math.random()
    setMerits(list => [...list, mid])
    setTimeout(() => setMerits(list => list.filter(x => x !== mid)), 900)
    try {
      const resp = await postExp('/view', { question_id: activeId })
      if (resp.code === 0 && typeof resp.data === 'number') {
        setViewCount(resp.data)
        setContent(prev => {
          if (!prev) return prev
          const updated = { ...prev, view_count: resp.data }
          cacheRef.current[activeId] = updated
          return updated
        })
        refreshExpTree()
      }
    } catch {
      // 敲木鱼失败静默即可（乐观值已 +1，下次刷新会校正）
    }
  }

  const rubric = Array.isArray(content?.rubric) ? content.rubric : []
  const answer = Array.isArray(content?.recommended_answer) ? content.recommended_answer : []
  const hasAnswer = rubric.length > 0 || answer.length > 0

  return (
    <div className="learn-container">
      {/* 左侧目录 */}
      <div className="learn-sidebar">
        <div className="learn-sidebar-header">📖 看看面经</div>
        <div className="learn-sidebar-tree">
          {tree.map(root => (
            <ExpSidebarNode key={root.id} node={root} activeId={activeId} expandedIds={expandedIds} onSelect={handleSelect} />
          ))}
          {!tree.length && <div style={{ padding: 16, color: '#ccc', fontSize: 13 }}>暂无面经，去「管理 → 面经」新增</div>}
        </div>
      </div>

      {/* 右侧内容区 */}
      <div className="learn-main">
        {!activeId && !loading && (
          <div className="learn-empty">👈 从左侧选择一个面经问题开始学习</div>
        )}
        {loading && (
          <div className="learn-loading-wrap">
            <StagePulse text="正在生成讲解与参考答案" sub="首次生成需 5-15s，请稍候" />
            <Skeleton lines={5} hasTitle hasBlock />
          </div>
        )}
        {content?.error && !loading && (
          <div className="learn-empty" style={{ color: '#ff4d4f' }}>
            ❌ {content.error}
            <br /><button onClick={() => loadContent(activeId, 'regenerate')} style={{ marginTop: 12, padding: '6px 16px', cursor: 'pointer' }}>重试</button>
          </div>
        )}

        {content && !content.error && !loading && (
          <>
            <div className="learn-info-bar">
              <h2 className="learn-title">{content.name}</h2>
              <div className="learn-meta">
                {content.domain_name && <span className="exp-domain-tag">{content.domain_name}</span>}
                {content.frequency > 0 && <span className="exp-freq-inline" title="出现频率">🔥 {content.frequency} 篇面经问过</span>}
                <span className="exp-woodfish" title="看过一遍就敲一下木鱼">
                  <button
                    type="button"
                    className={`exp-woodfish-btn ${knocking ? 'knock' : ''}`}
                    onClick={handleKnock}
                    aria-label="敲木鱼，看过 +1"
                  >
                    🐟
                    {merits.map(id => <span key={id} className="exp-merit-float">看过 +1</span>)}
                  </button>
                  <span className="exp-woodfish-count">看过 {viewCount} 次</span>
                </span>
              </div>
            </div>

            <div className="learn-body">
              <div className="learn-content-area">
                <div className="learn-content-actions" style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 12 }}>
                  <button
                    className="learn-regen-all-btn"
                    title="重新生成讲解 + 参考答案"
                    disabled={regenLoading}
                    style={{ padding: '4px 12px', fontSize: 12, cursor: regenLoading ? 'not-allowed' : 'pointer', color: '#1677ff', background: 'transparent', border: '1px solid #1677ff', borderRadius: 4, opacity: regenLoading ? 0.6 : 1 }}
                    onClick={async () => {
                      if (!activeId || regenLoading) return
                      if (!confirm('确定重新生成讲解与参考答案？')) return
                      setRegenLoading(true)
                      try { await loadContent(activeId, 'regenerate') } finally { setRegenLoading(false) }
                    }}
                  >{regenLoading ? '生成中…' : '🔁 重新生成'}</button>
                </div>

                {/* 参考答案（rubric + 推荐答案） */}
                {hasAnswer && (
                  <div className="learn-sub-card">
                    <div className="learn-sub-card-head">
                      <div className="learn-sub-card-title">
                        <button className="learn-answer-toggle" onClick={() => setShowAnswer(v => !v)}>
                          {showAnswer ? '收起' : '展开'}
                        </button>
                        <span className="learn-sub-card-title-text">🎯 参考答案</span>
                      </div>
                    </div>
                    {showAnswer && (
                      <div className="learn-answer-box">
                        {rubric.length > 0 && (
                          <div className="learn-rubric-block">
                            <div className="learn-answer-subtitle">📊 评分采分点</div>
                            <table className="learn-rubric-table">
                              <thead><tr><th>采分点</th><th>命中规则</th><th>权重</th></tr></thead>
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
                  </div>
                )}

                {/* 讲解正文 */}
                <div className="learn-sub-card">
                  <div className="learn-sub-card-head">
                    <div className="learn-sub-card-title">
                      <span className="learn-sub-card-title-text">📖 讲解</span>
                    </div>
                  </div>
                  <div className="learn-sub-card-body">
                    <MarkdownContent content={content.body_md} />
                  </div>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
