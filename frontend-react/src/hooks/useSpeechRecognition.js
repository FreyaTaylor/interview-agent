/**
 * 语音识别 Hook — 封装 Web Speech API
 * 按下麦克风开始，实时转文字追加到回调，松开或点击停止
 */
import { useState, useRef, useCallback, useEffect } from 'react'

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

export default function useSpeechRecognition({ onResult, lang = 'zh-CN' } = {}) {
  const [listening, setListening] = useState(false)
  const [processing, setProcessing] = useState(false)  // stop 后等待最终结果
  const [error, setError] = useState(null)             // 最近一次错误信息（不会自动清）
  const recognitionRef = useRef(null)
  const onResultRef = useRef(onResult)
  onResultRef.current = onResult

  const supported = !!SpeechRecognition

  const start = useCallback(() => {
    if (!SpeechRecognition || listening) return

    setError(null)
    setProcessing(false)
    const recognition = new SpeechRecognition()
    recognition.lang = lang
    recognition.continuous = true       // 持续识别
    recognition.interimResults = true   // 实时中间结果
    recognitionRef.current = recognition

    let finalTranscript = ''

    recognition.onresult = (event) => {
      let interim = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript
        if (event.results[i].isFinal) {
          finalTranscript += transcript
        } else {
          interim += transcript
        }
      }
      if (onResultRef.current) {
        onResultRef.current(finalTranscript, interim)
      }
    }

    recognition.onend = () => {
      setListening(false)
      setProcessing(false)
      recognitionRef.current = null
    }

    recognition.onerror = (event) => {
      console.log('[useSpeechRecognition] onerror:', event.error, event)
      // aborted = 用户或 watchdog 主动停止，no-speech = 没听到语音，均不作为错误弹提示
      if (event.error !== 'aborted' && event.error !== 'no-speech') {
        setError(event.error || '未知错误')
      }
      setListening(false)
      setProcessing(false)
      recognitionRef.current = null
    }

    try {
      recognition.start()
      setListening(true)
      console.log('[useSpeechRecognition] start ok, lang=', lang)
    } catch (e) {
      console.error('[useSpeechRecognition] start throw:', e)
      setError(String(e?.message || e))
      setListening(false)
      recognitionRef.current = null
    }
  }, [lang, listening])

  const stop = useCallback(() => {
    if (recognitionRef.current) {
      setProcessing(true)
      try { recognitionRef.current.stop() } catch (_) { /* ignore */ }
      // watchdog：onend 可能不回调（2s 后强制重置状态，但不再调 abort 避免触发额外 onerror）
      setTimeout(() => {
        recognitionRef.current = null
        setListening(false)
        setProcessing(false)
      }, 2000)
    }
  }, [])

  const abort = useCallback(() => {
    if (recognitionRef.current) {
      try { recognitionRef.current.abort() } catch (_) { /* ignore */ }
      recognitionRef.current = null
    }
    setListening(false)
    setProcessing(false)
    setError(null)
  }, [])

  const clearError = useCallback(() => setError(null), [])

  // 组件卸载时清理
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop()
      }
    }
  }, [])

  return { listening, processing, error, clearError, start, stop, abort, supported }
}
