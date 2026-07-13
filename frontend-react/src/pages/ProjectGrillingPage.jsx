/**
 * ProjectGrillingPage — 项目拷打 v2（按题作答）
 *
 * 三栏布局：
 *   左 : 项目画像（project_facts + weak_points）+ 项目下拉切换
 *   中 : 项目话题手风琴，每个话题展开后是题目列表
 *   右 : 当前作答会话（ConversationView + AnswerInput）
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { API_PROJECT_GRILLING, API_TEXT, API_INTERVIEW } from '../config'
import AnswerInput from '../components/AnswerInput'
import PageHeader from '../components/PageHeader'
import ConversationView from '../components/ConversationView'
import AttemptHistoryPanel from '../components/AttemptHistoryPanel'
import QuestionList from '../components/QuestionList'
import useQAFlow from '../hooks/useQAFlow'
import { TypingDots, StagePulse, Skeleton } from '../components/Loading'

export default function ProjectGrillingPage() {
  const { projectId: urlProjectId } = useParams()
  const navigate = useNavigate()

  const [projects, setProjects] = useState([])
  const [activeProjectId, setActiveProjectId] = useState(urlProjectId ? Number(urlProjectId) : null)
  const [profile, setProfile] = useState({ project_facts: [], weak_points: [] })
  // 三模块解耦 P5：该项目关联的面试真题（只读）
  const [relatedProjectQuestions, setRelatedProjectQuestions] = useState([])
  const [topics, setTopics] = useState([])
  const [openTopicId, setOpenTopicId] = useState(null)
  const [topicQuestions, setTopicQuestions] = useState({}) // topicId -> [questions]
  const [activeQuestionId, setActiveQuestionId] = useState(null)
  const [loadingMeta, setLoadingMeta] = useState(false)
  const [error, setError] = useState(null)
  // 当前题的历史拷打记录
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyCollapsed, setHistoryCollapsed] = useState(false)

  const qa = useQAFlow(API_PROJECT_GRILLING, { style: 'java' })
  const bottomRef = useRef(null)

  // ---- 项目列表 ----
  const fetchProjects = useCallback(async () => {
    try {
      const r = await fetch(`${API_PROJECT_GRILLING}/projects-list`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      const d = await r.json()
      if (d.code === 0) {
        setProjects(d.data || [])
        if (!activeProjectId && d.data?.length) {
          setActiveProjectId(d.data[0].id)
        }
      }
    } catch (e) {
      setError('加载项目列表失败')
    }
  }, [activeProjectId])

  useEffect(() => { fetchProjects() }, [])

  // ---- URL ↔ activeProjectId 同步 ----
  useEffect(() => {
    if (activeProjectId && String(activeProjectId) !== urlProjectId) {
      navigate(`/grilling/${activeProjectId}`, { replace: true })
    }
  }, [activeProjectId])

  useEffect(() => {
    const n = urlProjectId ? Number(urlProjectId) : null
    if (n && n !== activeProjectId) setActiveProjectId(n)
  }, [urlProjectId])

  // ---- 项目切换 → 拉画像 + 话题 + 重置（不自动选话题/题目）----
  useEffect(() => {
    if (!activeProjectId) return
    qa.reset()
    setActiveQuestionId(null)
    setOpenTopicId(null)
    setTopicQuestions({})
    setHistory([])
    setHistoryCollapsed(false)
    loadProjectMeta(activeProjectId)
  }, [activeProjectId])

  // 三模块解耦 P5：拉取该项目关联的面试真题（只读）
  useEffect(() => {
    if (!activeProjectId) { setRelatedProjectQuestions([]); return }
    let cancelled = false
    fetch(`${API_INTERVIEW}/related-project-questions?project_id=${activeProjectId}`)
      .then(r => r.json())
      .then(d => { if (!cancelled && d.code === 0) setRelatedProjectQuestions(d.data || []) })
      .catch(() => { if (!cancelled) setRelatedProjectQuestions([]) })
    return () => { cancelled = true }
  }, [activeProjectId])

  async function loadProjectMeta(pid) {
    setLoadingMeta(true)
    setError(null)
    const postBody = (path) => fetch(`${API_PROJECT_GRILLING}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ project_id: pid }),
    }).then(r => r.json())
    try {
      const [pRes, dRes] = await Promise.all([
        postBody('/profile-detail'),
        postBody('/dimensions-list'),
      ])
      if (pRes.code === 0) {
        setProfile({
          project_facts: pRes.data?.project_facts || [],
          weak_points: pRes.data?.weak_points || [],
        })
      }
      if (dRes.code === 0) {
        setTopics(dRes.data || [])
      }
    } catch (e) {
      setError('加载项目数据失败')
    } finally {
      setLoadingMeta(false)
    }
  }

  async function fetchTopicQs(topicId) {
    return fetch(`${API_PROJECT_GRILLING}/topic-questions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ topic_id: topicId }),
    }).then(r => r.json())
  }

  async function toggleTopic(t) {
    if (openTopicId === t.id) {
      setOpenTopicId(null)
      return
    }
    setOpenTopicId(t.id)
    // 展开话题：只加载题目列表，不自动选题（等用户点击具体题目才进入答题）
    if (!topicQuestions[t.id]) {
      try {
        const d = await fetchTopicQs(t.id)
        if (d.code === 0) {
          setTopicQuestions(prev => ({ ...prev, [t.id]: d.data.questions || [] }))
        }
      } catch (_) {
        setTopicQuestions(prev => ({ ...prev, [t.id]: [] }))
      }
    }
  }

  async function refreshTopicQuestions(topicId) {
    try {
      const d = await fetchTopicQs(topicId)
      if (d.code === 0) {
        setTopicQuestions(prev => ({ ...prev, [topicId]: d.data.questions || [] }))
      }
    } catch (_) {}
  }

  async function selectQuestion(topicId, q) {
    if (q.id === activeQuestionId) return
    setActiveQuestionId(q.id)
    qa.reset()
    setHistory([])
    setHistoryCollapsed(false)
    setHistoryLoading(true)
    let prior = []
    try {
      const resp = await fetch(`${API_PROJECT_GRILLING}/attempts-history`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question_id: q.id }),
      })
      const data = await resp.json()
      if (data.code === 0) prior = (data.data && data.data.attempts) || []
      setHistory(prior)
    } catch (_) {}
    finally { setHistoryLoading(false) }

    const inProgress = prior.find(a => a.status === 'in_progress')
    if (inProgress) {
      try { await qa.load(inProgress.attempt_id) } catch (_) {}
      return
    }
    if (prior.length === 0) {
      try { await qa.start(q.id) } catch (_) {}
    }
    // 有 finished 历史但无 in_progress → 什么都不做，右栏展示历史 + 「再来一轮」
  }

  async function startNewAttempt() {
    if (!activeQuestionId) return
    setHistoryCollapsed(true)
    try { await qa.start(activeQuestionId) } catch (_) {}
  }

  async function refreshHistory() {
    if (!activeQuestionId) return
    try {
      const resp = await fetch(`${API_PROJECT_GRILLING}/attempts-history`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question_id: activeQuestionId }),
      })
      const data = await resp.json()
      if (data.code === 0) setHistory((data.data && data.data.attempts) || [])
    } catch (_) {}
  }

  async function handleAnswer(text) {
    try {
      const data = await qa.answer(text)
      if (data && data.follow_up_question == null) {
        await qa.finish()
        if (openTopicId) refreshTopicQuestions(openTopicId)
        loadProjectMeta(activeProjectId)  // 刷新画像（项目 strategy 会异步抽取）
        setHistoryCollapsed(true)
        refreshHistory()
      }
    } catch (_) {}
  }

  async function handleManualFinish() {
    try {
      await qa.finish()
      if (openTopicId) refreshTopicQuestions(openTopicId)
      loadProjectMeta(activeProjectId)
      setHistoryCollapsed(true)
      refreshHistory()
    } catch (_) {}
  }

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [qa.attempt?.dialog?.length, qa.attempt?.status])

  const stepInfo = useMemo(() => {
    if (!qa.attempt) return ''
    const cur = qa.attempt.current_step ?? qa.attempt.follow_up_count ?? 0
    const max = qa.attempt.max_steps ?? 4
    return `第 ${cur} / ${max} 轮追问`
  }, [qa.attempt])

  const activeProject = projects.find(p => p.id === activeProjectId)
  // 过滤掉名字为空的话题（数据脏值，不应展示）
  const visibleTopics = topics.filter(t => t.name && t.name.trim())
  // 当前打开的话题（用于右栏标题「项目名 / 话题名」）
  const activeTopic = visibleTopics.find(t => t.id === openTopicId)
  const topicNameForTitle = qa.attempt?.topic_name || activeTopic?.name || ''
  const mainTitle = [activeProject?.name, topicNameForTitle].filter(Boolean).join(' / ') || '项目拷打'

  return (
    <div className="qa-page">
      {/* 左：项目画像 */}
      <aside className="qa-side-left">
        <div className="qa-side-header">
          <select
            className="qa-project-select"
            value={activeProjectId || ''}
            onChange={(e) => setActiveProjectId(Number(e.target.value) || null)}
          >
            {projects.length === 0 && <option value="">（暂无项目）</option>}
            {projects.map(p => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
        </div>
        <div className="qa-side-body">
          <div className="qa-profile-section">
            <div className="qa-profile-title">项目事实</div>
            {profile.project_facts.length === 0 ? (
              <div className="qa-profile-empty">尚未提取（开始答题后会自动积累）</div>
            ) : (
              profile.project_facts.map((f, i) => (
                <div key={i} className="qa-profile-item">
                  {typeof f === 'string' ? f : (f.fact || f.content || JSON.stringify(f))}
                </div>
              ))
            )}
          </div>

          <div className="qa-profile-section">
            <div className="qa-profile-title">📌 相关面试真题</div>
            {relatedProjectQuestions.length === 0 ? (
              <div className="qa-profile-empty">暂无关联真题</div>
            ) : (
              relatedProjectQuestions.map(rq => (
                <div key={rq.id} className="qa-profile-item">
                  {rq.company && <span className="learn-related-src" title="来源面试">{rq.company}</span>}
                  {(rq.questions && rq.questions[0]) || rq.project_name}
                </div>
              ))
            )}
          </div>

        </div>
      </aside>

      {/* 中：话题手风琴 */}
      <section className="qa-side-middle">
        <div className="qa-side-header">
          <span>{activeProject?.name ? `「${activeProject.name}」话题` : '话题'}</span>
          {visibleTopics.length > 0 && (
            <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>
              {visibleTopics.length} 个
            </span>
          )}
        </div>
        <div className="qa-side-body">
          {loadingMeta && <div className="qa-loading-wrap"><Skeleton lines={3} hasTitle={false} hasBlock={false} /></div>}
          {error && <div className="qa-error">{error}</div>}
          {!loadingMeta && visibleTopics.length === 0 && (
            <div className="qa-empty">还没有话题</div>
          )}
          {visibleTopics.map(t => {
            const open = openTopicId === t.id
            // 过滤掉内容为空的题目（数据脏值）
            const qs = topicQuestions[t.id]?.filter(q => q.content && q.content.trim())
            return (
              <div key={t.id} className="qa-topic-group">
                <div
                  className={`qa-topic-head ${open ? 'active' : ''}`}
                  onClick={() => toggleTopic(t)}
                >
                  <span className="qa-topic-name">{t.name}</span>
                  <span className="qa-topic-meta">
                    <ScoreBadge score={t.avg_score} count={t.attempt_count} qCount={t.question_count} />
                    <span className={`qa-topic-arrow ${open ? 'open' : ''}`}>▶</span>
                  </span>
                </div>
                {open && (
                  <div className="qa-topic-body">
                    {qs == null ? (
                      <div className="qa-loading"><TypingDots text="加载题目" /></div>
                    ) : (
                      <QuestionList
                        items={qs}
                        activeId={activeQuestionId}
                        onSelect={(q) => selectQuestion(t.id, q)}
                        emptyText="该话题暂无题目"
                      />
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </section>

      {/* 右：作答区 */}
      <section className="qa-main">
        {!qa.attempt && history.length === 0 && (
          <div className="qa-empty">
            {!activeProjectId
              ? '请先选择一个项目'
              : historyLoading
                ? '加载历史中…'
                : qa.loading === 'starting'
                  ? '正在准备题目…'
                  : !openTopicId
                    ? '👈 请从左侧展开一个话题'
                    : !activeQuestionId
                      ? '👈 请从话题中点选一道题目开始拷打'
                      : '👈 请从话题中点选一道题目开始拷打'
            }
          </div>
        )}
        {!qa.attempt && history.length > 0 && (
          <>
            <div className="qa-main-header">
              <PageHeader
                title={mainTitle}
                subtitle={`共 ${history.length} 次拷打记录`}
                right={
                  <button
                    type="button"
                    className="ai-stop-btn"
                    onClick={startNewAttempt}
                  >
                    再来一轮
                  </button>
                }
              />
            </div>
            <div className="qa-main-body">
              <AttemptHistoryPanel attempts={history} collapsed={false} />
            </div>
          </>
        )}
        {qa.attempt && (
          <>
            <div className="qa-main-header">
              <PageHeader
                title={mainTitle}
                subtitle={qa.isFinished ? '已完成 · 综合评分如下' : stepInfo}
                right={
                  qa.canAnswer ? (
                    <button
                      type="button"
                      className="ai-stop-btn"
                      onClick={handleManualFinish}
                      disabled={!!qa.loading}
                      title="结束本题并综合打分"
                    >
                      结束并打分
                    </button>
                  ) : qa.isFinished ? (
                    <button
                      type="button"
                      className="ai-stop-btn"
                      onClick={startNewAttempt}
                      title="再做一次本题"
                    >
                      再来一轮
                    </button>
                  ) : null
                }
              />
            </div>
            <div className="qa-main-body">
              {qa.error && <div className="qa-error">{qa.error}</div>}
              <AttemptHistoryPanel
                attempts={history}
                collapsed={historyCollapsed}
                currentAttemptId={qa.attempt.attempt_id}
              />
              <ConversationView attempt={qa.attempt} />
              {qa.loading === 'answering' && <div className="qa-loading"><TypingDots text="正在分析回答" /></div>}
              {qa.loading === 'finishing' && <StagePulse text="正在综合打分" sub="根据所有追问生成评分与总结" />}
              {qa.loading === 'starting' && <div className="qa-loading"><TypingDots text="正在准备题目" /></div>}
              <div ref={bottomRef} />
            </div>
            {qa.canAnswer && (
              <div className="qa-main-input">
                <AnswerInput
                  onSend={handleAnswer}
                  disabled={!!qa.loading}
                  placeholder="输入你的回答…（Enter 发送，Shift+Enter 换行）"
                  correctApi={API_TEXT}
                  questionContext={
                    (qa.attempt?.dialog || [])
                      .filter(m => m?.role === 'agent' && (m?.type === 'question' || m?.type === 'follow_up'))
                      .slice(-2)
                      .map(m => m.content)
                      .join('\n')
                  }
                />
              </div>
            )}
          </>
        )}
      </section>
    </div>
  )
}

function ScoreBadge({ score, count, qCount }) {
  if (score == null) {
    return <span className="ql-badge na">{qCount ?? 0} 题</span>
  }
  const lvl = score >= 85 ? 'high' : score >= 60 ? 'mid' : 'low'
  return (
    <span className={`ql-badge ${lvl}`} title={`已作答 ${count} 次 / 共 ${qCount} 题`}>
      {score}
    </span>
  )
}
