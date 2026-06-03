/**
 * useQAFlow — 通用「按题作答」流程 hook。
 *
 * 与后端 qa_engine 对接，study / project-grilling 两侧共用同一套交互：
 *   POST  ${apiBase}/attempts                 开始作答（body: { question_id }）
 *   POST  ${apiBase}/attempts/{id}/turn       提交一轮回答（body: { answer }）
 *   POST  ${apiBase}/attempts/{id}/finish     收尾综合评分
 *   GET   ${apiBase}/attempts/{id}            读取作答详情
 *
 * 用法：
 *   const qa = useQAFlow(API_STUDY)          // 或 API_PROJECT_GRILLING
 *   await qa.start(questionId)
 *   await qa.answer('用户回答...')
 *   await qa.finish()
 *
 * 状态字段：
 *   attempt:    null 或 { attempt_id, status, dialog[], follow_up_count?, current_step?,
 *                          max_steps?, final_score?, rubric_result?, overall_summary?,
 *                          design_issues?, question_content?, topic_name? }
 *   loading:    null | 'starting' | 'answering' | 'finishing' | 'loading'
 *   error:      string | null
 */
import { useCallback, useRef, useState } from 'react'

async function postJSON(url, body) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const data = await resp.json().catch(() => null)
  if (!resp.ok || !data || data.code !== 0) {
    throw new Error(data?.message || `请求失败 (${resp.status})`)
  }
  return data.data
}

async function getJSON(url) {
  const resp = await fetch(url)
  const data = await resp.json().catch(() => null)
  if (!resp.ok || !data || data.code !== 0) {
    throw new Error(data?.message || `请求失败 (${resp.status})`)
  }
  return data.data
}

export default function useQAFlow(apiBase) {
  const [attempt, setAttempt] = useState(null)
  const [loading, setLoading] = useState(null)
  const [error, setError] = useState(null)
  const inflightRef = useRef(false)

  /** 合并新 payload 到 attempt 状态（保留题目元信息）。 */
  const merge = useCallback((next) => {
    setAttempt(prev => ({ ...(prev || {}), ...(next || {}) }))
  }, [])

  const reset = useCallback(() => {
    setAttempt(null)
    setError(null)
    setLoading(null)
    inflightRef.current = false
  }, [])

  const start = useCallback(async (questionId) => {
    if (inflightRef.current) return
    inflightRef.current = true
    setLoading('starting')
    setError(null)
    setAttempt(null)
    try {
      const data = await postJSON(`${apiBase}/attempts`, { question_id: questionId })
      setAttempt({ ...data, status: 'in_progress' })
      return data
    } catch (e) {
      setError(e.message || '开始作答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase])

  const answer = useCallback(async (text) => {
    if (inflightRef.current) return
    if (!attempt?.attempt_id) throw new Error('尚未开始作答')
    inflightRef.current = true
    setLoading('answering')
    setError(null)
    // 乐观更新：先把用户回答塞进 dialog 气泡，等接口返回时整体被覆盖
    setAttempt(prev => prev ? {
      ...prev,
      dialog: [...(prev.dialog || []), { role: 'user', type: 'answer', content: text }],
    } : prev)
    try {
      const data = await postJSON(
        `${apiBase}/attempts/${attempt.attempt_id}/turn`,
        { answer: text },
      )
      merge(data)
      return data
    } catch (e) {
      setError(e.message || '提交回答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, attempt?.attempt_id, merge])

  const finish = useCallback(async () => {
    if (inflightRef.current) return
    if (!attempt?.attempt_id) throw new Error('尚未开始作答')
    inflightRef.current = true
    setLoading('finishing')
    setError(null)
    try {
      const data = await postJSON(
        `${apiBase}/attempts/${attempt.attempt_id}/finish`,
        null,
      )
      merge(data)
      return data
    } catch (e) {
      setError(e.message || '收尾打分失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, attempt?.attempt_id, merge])

  /** 载入既往作答详情（用于查看历史）。 */
  const load = useCallback(async (attemptId) => {
    if (inflightRef.current) return
    inflightRef.current = true
    setLoading('loading')
    setError(null)
    try {
      const data = await getJSON(`${apiBase}/attempts/${attemptId}`)
      setAttempt(data)
      return data
    } catch (e) {
      setError(e.message || '加载作答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase])

  // 派生：是否还能继续追问
  const canAnswer = !!attempt
    && attempt.status === 'in_progress'
    && !loading
  const isFinished = attempt?.status === 'finished'

  return { attempt, loading, error, canAnswer, isFinished,
    start, answer, finish, load, reset, clearError: () => setError(null) }
}
