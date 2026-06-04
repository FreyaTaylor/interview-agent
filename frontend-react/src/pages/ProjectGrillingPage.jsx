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
import { API_PROJECT_GRILLING, API_TEXT } from '../config'
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

  const qa = useQAFlow(API_PROJECT_GRILLING)
  const bottomRef = useRef(null)
  const autoSelectedRef = useRef(false)  // 每次切项目后自动选题只做一次

  // ---- 项目列表 ----
  const fetchProjects = useCallback(async () => {
    try {
      const r = await fetch(`${API_PROJECT_GRILLING}/projects`)
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

  // ---- 项目切换 → 拉画像 + 话题 + 重置 ----
  useEffect(() => {
    if (!activeProjectId) return
    qa.reset()
    setActiveQuestionId(null)
    setOpenTopicId(null)
    setTopicQuestions({})
    setHistory([])
    setHistoryCollapsed(false)
    autoSelectedRef.current = false
    loadProjectMeta(activeProjectId)
  }, [activeProjectId])

  // ---- 话题加载完 → 自动展开并选中「第一个未作答」题目（与 ExamPage 行为一致）----
  useEffect(() => {
    if (autoSelectedRef.current) return
    if (!topics.length) return
    if (activeQuestionId) return
    autoSelectedRef.current = true
    ;(async () => {
      const fetchQuestions = async (topicId) => {
        try {
          const r = await fetch(`${API_PROJECT_GRILLING}/topics/${topicId}/questions`)
          const d = await r.json()
          const qs = d.code === 0 ? (d.data.questions || []) : []
          setTopicQuestions(prev => ({ ...prev, [topicId]: qs }))
          return qs
        } catch (_) {
          setTopicQuestions(prev => ({ ...prev, [topicId]: [] }))
          return []
        }
      }
      // 顺序扫描，命中第一个未答即停
      for (const t of topics) {
        const qs = await fetchQuestions(t.id)
        const target = qs.find(q => !q.attempt_count)
        if (target) {
          setOpenTopicId(t.id)
          await selectQuestion(t.id, target)
          return
        }
      }
      // 全部已答 → 展开第一个话题第一题
      const t0 = topics[0]
      const qs0 = topicQuestions[t0.id] || await fetchQuestions(t0.id)
      if (qs0.length) {
        setOpenTopicId(t0.id)
        await selectQuestion(t0.id, qs0[0])
      }
    })()
  }, [topics])

  async function loadProjectMeta(pid) {
    setLoadingMeta(true)
    setError(null)
    try {
      const [pRes, dRes] = await Promise.all([
        fetch(`${API_PROJECT_GRILLING}/projects/${pid}/profile`).then(r => r.json()),
        fetch(`${API_PROJECT_GRILLING}/projects/${pid}/dimensions`).then(r => r.json()),
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

  async function toggleTopic(t) {
    if (openTopicId === t.id) {
      setOpenTopicId(null)
      return
    }
    setOpenTopicId(t.id)
    if (topicQuestions[t.id]) return
    try {
      const r = await fetch(`${API_PROJECT_GRILLING}/topics/${t.id}/questions`)
      const d = await r.json()
      if (d.code === 0) {
        setTopicQuestions(prev => ({ ...prev, [t.id]: d.data.questions || [] }))
      }
    } catch (_) {
      setTopicQuestions(prev => ({ ...prev, [t.id]: [] }))
    }
  }

  async function refreshTopicQuestions(topicId) {
    try {
      const r = await fetch(`${API_PROJECT_GRILLING}/topics/${topicId}/questions`)
      const d = await r.json()
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
      const resp = await fetch(`${API_PROJECT_GRILLING}/questions/${q.id}/attempts`)
      const data = await resp.json()
      if (data.code === 0) prior = data.data || []
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
  }

  async function startNewAttempt() {
    if (!activeQuestionId) return
    setHistoryCollapsed(true)
    try { await qa.start(activeQuestionId) } catch (_) {}
  }

  async function refreshHistory() {
    if (!activeQuestionId) return
    try {
      const resp = await fetch(`${API_PROJECT_GRILLING}/questions/${activeQuestionId}/attempts`)
      const data = await resp.json()
      if (data.code === 0) setHistory(data.data || [])
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
            <div className="qa-profile-title">薄弱点</div>
            {profile.weak_points.length === 0 ? (
              <div className="qa-profile-empty">暂无</div>
            ) : (
              profile.weak_points.map((w, i) => (
                <span key={i} className="qa-profile-tag weak">
                  {typeof w === 'string' ? w : (w.topic || w.point || JSON.stringify(w))}
                </span>
              ))
            )}
          </div>
        </div>
      </aside>

      {/* 中：话题手风琴 */}
      <section className="qa-side-middle">
        <div className="qa-side-header">
          <span>{activeProject?.name ? `${activeProject.name} 话题` : '话题'}</span>
          {topics.length > 0 && (
            <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>
              {topics.length} 个
            </span>
          )}
        </div>
        <div className="qa-side-body">
          {loadingMeta && <div className="qa-loading-wrap"><Skeleton lines={3} hasTitle={false} hasBlock={false} /></div>}
          {error && <div className="qa-error">{error}</div>}
          {!loadingMeta && topics.length === 0 && (
            <div className="qa-empty">还没有话题</div>
          )}
          {topics.map(t => {
            const open = openTopicId === t.id
            const qs = topicQuestions[t.id]
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
            {activeProjectId ? (historyLoading ? '加载历史中…' : '👈 展开话题，选择一道题目开始拷打') : '请选择项目'}
          </div>
        )}
        {!qa.attempt && history.length > 0 && (
          <>
            <div className="qa-main-header">
              <PageHeader
                title={activeProject?.name || '历史拷打记录'}
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
                title={activeProject?.name || qa.attempt.topic_name || '项目拷打'}
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
