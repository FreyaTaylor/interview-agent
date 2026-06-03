/**
 * ExamPage — 按题作答 v2
 *
 * 三栏布局：
 *   左 : 知识树目录（与 LearnPage 同款 SidebarNode）
 *   中 : 当前知识点下的题目列表（带分数徽章）
 *   右 : 当前作答会话（ConversationView + AnswerInput）
 *
 * 流程：
 *   选知识点 → 拉题目列表 → 点击题目 → start attempt
 *   每轮 answer → 后端返追问 or 结束
 *   后端不再返追问 时（follow_up_question === null）→ 自动 finish 触发综合评分
 *   也可手动「结束并打分」按钮立即收尾
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { API_STUDY } from '../config'
import { findAncestorIds, SidebarNode, useKnowledgeTree } from '../components/KnowledgeSidebar'
import AnswerInput from '../components/AnswerInput'
import PageHeader from '../components/PageHeader'
import ConversationView from '../components/ConversationView'
import AttemptHistoryPanel from '../components/AttemptHistoryPanel'
import QuestionList from '../components/QuestionList'
import { TypingDots, StagePulse, Skeleton } from '../components/Loading'
import useQAFlow from '../hooks/useQAFlow'

export default function ExamPage() {
  const { kpId } = useParams()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const desiredQidFromUrl = searchParams.get('qid')

  const tree = useKnowledgeTree()
  const [expandedIds, setExpandedIds] = useState(new Set())
  const [activeKpId, setActiveKpId] = useState(kpId ? Number(kpId) : null)
  const [kpName, setKpName] = useState('')
  const [questions, setQuestions] = useState([])
  const [activeQuestionId, setActiveQuestionId] = useState(null)
  const [listLoading, setListLoading] = useState(false)
  const [listError, setListError] = useState(null)
  // 当前题的历史作答记录（含未完成/已评分）
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  // 历史面板默认是否折叠：未开始时展开，完成本轮后折叠
  const [historyCollapsed, setHistoryCollapsed] = useState(false)

  const qa = useQAFlow(API_STUDY)
  const bottomRef = useRef(null)

  // ---- 知识树到位后计算展开路径 ----
  useEffect(() => {
    if (tree.length > 0 && activeKpId) {
      setExpandedIds(findAncestorIds(tree, activeKpId))
    }
  }, [tree, activeKpId])

  // ---- URL → 状态 ----
  useEffect(() => {
    if (kpId && Number(kpId) !== activeKpId) {
      setActiveKpId(Number(kpId))
    }
    // 学习/答题共用一个 lastKpId，供顶部导航直接跳回
    if (kpId) {
      try { localStorage.setItem('lastKpId', String(kpId)) } catch (_) { /* ignore */ }
    }
  }, [kpId])

  // ---- 选中知识点 → 拉题目列表 ----
  useEffect(() => {
    if (!activeKpId) return
    loadQuestions(activeKpId)
  }, [activeKpId])

  // ---- URL ?qid=… → 选中题目（等题目列表加载后生效）----
  useEffect(() => {
    if (!desiredQidFromUrl) return
    const target = questions.find(q => String(q.id) === String(desiredQidFromUrl))
    if (target && target.id !== activeQuestionId) {
      selectQuestion(target)
      // 消费后清掉参数，避免重复触发
      setSearchParams({}, { replace: true })
    }
  }, [desiredQidFromUrl, questions])

  // ---- 题目列表加载完后默认选中：优先第一道"未作答"，否则按顺序第一道 ----
  useEffect(() => {
    if (desiredQidFromUrl) return            // 让 URL ?qid 优先
    if (activeQuestionId) return             // 已有选中
    if (!questions.length) return
    const firstUnfinished = questions.find(q => !q.attempt_count)
    const target = firstUnfinished || questions[0]
    if (target) selectQuestion(target)
  }, [questions, desiredQidFromUrl, activeQuestionId])

  async function loadQuestions(id) {
    setListLoading(true)
    setListError(null)
    setQuestions([])
    setActiveQuestionId(null)
    setHistory([])
    setHistoryCollapsed(false)
    setKpName('')   // 立即清空，避免切换时标题仍显示上一个知识点名字
    qa.reset()
    try {
      const resp = await fetch(`${API_STUDY}/knowledge-points/${id}/questions`)
      const data = await resp.json()
      if (data.code !== 0) throw new Error(data.message || '加载失败')
      setKpName(data.data.knowledge_point_name || '')
      setQuestions(data.data.questions || [])
    } catch (e) {
      setListError(e.message || '加载题目失败')
    } finally {
      setListLoading(false)
    }
  }

  function selectKp(id) {
    if (id === activeKpId) return
    setActiveKpId(id)
    navigate(`/exam/${id}`, { replace: true })
  }

  async function selectQuestion(q) {
    if (q.id === activeQuestionId) return
    setActiveQuestionId(q.id)
    qa.reset()
    setHistory([])
    setHistoryCollapsed(false)
    // 先拉历史作答
    setHistoryLoading(true)
    let prior = []
    try {
      const resp = await fetch(`${API_STUDY}/questions/${q.id}/attempts`)
      const data = await resp.json()
      if (data.code === 0) prior = data.data || []
      setHistory(prior)
    } catch (_) { /* 忽略，留空数组 */ }
    finally { setHistoryLoading(false) }

    // 存在进行中的作答 → 加载它继续，不另开新
    const inProgress = prior.find(a => a.status === 'in_progress')
    if (inProgress) {
      try { await qa.load(inProgress.attempt_id) } catch (_) {}
      return
    }
    // 无历史 → 直接开始新一轮；有历史 → 等用户点“再来一轮”
    if (prior.length === 0) {
      try { await qa.start(q.id) } catch (_) {}
    }
  }

  async function startNewAttempt() {
    if (!activeQuestionId) return
    setHistoryCollapsed(true)
    try { await qa.start(activeQuestionId) } catch (_) {}
  }

  async function handleAnswer(text) {
    try {
      const data = await qa.answer(text)
      // 后端判定本题结束 → 自动收尾打分
      if (data && data.follow_up_question == null) {
        await qa.finish()
        // 刷新题目分数
        loadQuestionsScores()
        // 本轮已评分 → 历史折叠起来 + 刷新历史列表
        setHistoryCollapsed(true)
        refreshHistory()
      }
    } catch (_) {}
  }

  async function handleManualFinish() {
    try {
      await qa.finish()
      loadQuestionsScores()
      setHistoryCollapsed(true)
      refreshHistory()
    } catch (_) {}
  }

  async function refreshHistory() {
    if (!activeQuestionId) return
    try {
      const resp = await fetch(`${API_STUDY}/questions/${activeQuestionId}/attempts`)
      const data = await resp.json()
      if (data.code === 0) setHistory(data.data || [])
    } catch (_) {}
  }

  /** 收尾后单独刷新题目分数（不重置当前作答）。 */
  async function loadQuestionsScores() {
    if (!activeKpId) return
    try {
      const resp = await fetch(`${API_STUDY}/knowledge-points/${activeKpId}/questions`)
      const data = await resp.json()
      if (data.code === 0) setQuestions(data.data.questions || [])
    } catch (_) {}
  }

  // 滚动到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [qa.attempt?.dialog?.length, qa.attempt?.status])

  const stepInfo = useMemo(() => {
    if (!qa.attempt) return ''
    const cur = qa.attempt.current_step ?? qa.attempt.follow_up_count ?? 0
    const max = qa.attempt.max_steps ?? 4
    return `第 ${cur} / ${max} 轮追问`
  }, [qa.attempt])

  return (
    <div className="qa-page">
      {/* 左：知识树 */}
      <aside className="qa-side-left">
        <div className="qa-side-header">📚 知识目录</div>
        <div className="qa-side-body">
          {tree.map(root => (
            <SidebarNode
              key={root.id} node={root}
              activeId={activeKpId} expandedIds={expandedIds}
              onSelect={selectKp}
            />
          ))}
        </div>
      </aside>

      {/* 中：题目列表 */}
      <section className="qa-side-middle">
        <div className="qa-side-header">
          <span>{kpName ? `「${kpName}」题目` : '题目'}</span>
          {questions.length > 0 && (
            <span style={{ fontSize: 12, color: '#999', fontWeight: 400 }}>
              {questions.length} 题
            </span>
          )}
        </div>
        <div className="qa-side-body">
          {!activeKpId && <div className="qa-empty">👈 选择一个知识点</div>}
          {listLoading && <div className="qa-loading-wrap"><Skeleton lines={3} hasTitle={false} hasBlock={false} /></div>}
          {listError && <div className="qa-error">{listError}</div>}
          {!listLoading && !listError && activeKpId && (
            <QuestionList
              items={questions}
              activeId={activeQuestionId}
              onSelect={selectQuestion}
              emptyText="该知识点还没有题目"
            />
          )}
        </div>
      </section>

      {/* 右：作答区 */}
      <section className="qa-main">
        {!qa.attempt && history.length === 0 && (
          <div className="qa-empty">
            {activeKpId ? (historyLoading ? '加载历史中…' : '👈 选择一道题目开始作答') : '请先选择知识点'}
          </div>
        )}
        {/* 有历史但当前无作答（用户刚选中带历史的题目）→ 只显示历史 + 开始按钮 */}
        {!qa.attempt && history.length > 0 && (
          <>
            <div className="qa-main-header">
              <PageHeader
                title={kpName || '历史作答'}
                subtitle={`共 ${history.length} 次作答记录`}
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
                title={qa.attempt.topic_name || kpName || '答题'}
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
              {/* 历史作答面板（不含当前 attempt）：默认按 historyCollapsed */}
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
                />
              </div>
            )}
          </>
        )}
      </section>
    </div>
  )
}
