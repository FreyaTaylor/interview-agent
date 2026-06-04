/**
 * useQAFlow — 通用「按题作答」流程 hook。
 *
 * 两种后端风格：
 *   - 默认 (project-grilling Python)：
 *       POST  ${apiBase}/attempts                 开始作答（body: { question_id }）
 *       POST  ${apiBase}/attempts/{id}/turn       提交一轮回答（body: { answer }）
 *       POST  ${apiBase}/attempts/{id}/finish     收尾综合评分
 *       GET   ${apiBase}/attempts/{id}            读取作答详情
 *   - style:'java' (S3 Study Java)：全 POST + action 后缀
 *       POST  ${apiBase}/attempt-start    body { question_id }
 *       POST  ${apiBase}/attempt-turn     body { attempt_id, user_answer }
 *       POST  ${apiBase}/attempt-finish   body { attempt_id }
 *       POST  ${apiBase}/attempt-detail   body { attempt_id }
 *
 * 用法：
 *   const qa = useQAFlow(API_STUDY, { style: 'java' })   // 或 useQAFlow(API_PROJECT_GRILLING)
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

export default function useQAFlow(apiBase, opts = {}) {
  const isJava = opts.style === 'java'
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
      const url = isJava ? `${apiBase}/attempt-start` : `${apiBase}/attempts`
      const data = await postJSON(url, { question_id: questionId })
      setAttempt({ ...data, status: 'in_progress' })
      return data
    } catch (e) {
      setError(e.message || '开始作答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, isJava])

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
      const url = isJava ? `${apiBase}/attempt-turn` : `${apiBase}/attempts/${attempt.attempt_id}/turn`
      const body = isJava
        ? { attempt_id: attempt.attempt_id, user_answer: text }
        : { answer: text }
      const data = await postJSON(url, body)
      merge(data)
      return data
    } catch (e) {
      setError(e.message || '提交回答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, isJava, attempt?.attempt_id, merge])

  const finish = useCallback(async () => {
    if (inflightRef.current) return
    if (!attempt?.attempt_id) throw new Error('尚未开始作答')
    inflightRef.current = true
    setLoading('finishing')
    setError(null)
    try {
      const url = isJava ? `${apiBase}/attempt-finish` : `${apiBase}/attempts/${attempt.attempt_id}/finish`
      const body = isJava ? { attempt_id: attempt.attempt_id } : null
      const data = await postJSON(url, body)
      merge(data)
      return data
    } catch (e) {
      setError(e.message || '收尾打分失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, isJava, attempt?.attempt_id, merge])

  /** 载入既往作答详情（用于查看历史）。 */
  const load = useCallback(async (attemptId) => {
    if (inflightRef.current) return
    inflightRef.current = true
    setLoading('loading')
    setError(null)
    try {
      const data = isJava
        ? await postJSON(`${apiBase}/attempt-detail`, { attempt_id: attemptId })
        : await getJSON(`${apiBase}/attempts/${attemptId}`)
      setAttempt(data)
      return data
    } catch (e) {
      setError(e.message || '加载作答失败')
      throw e
    } finally {
      setLoading(null)
      inflightRef.current = false
    }
  }, [apiBase, isJava])

  // 派生：是否还能继续追问
  const canAnswer = !!attempt
    && attempt.status === 'in_progress'
    && !loading
  const isFinished = attempt?.status === 'finished'

  return { attempt, loading, error, canAnswer, isFinished,
    start, answer, finish, load, reset, clearError: () => setError(null) }
}
