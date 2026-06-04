/**
 * 通用回答输入组件 — 文本框 + 语音输入 + 发送 + 可选停止追问
 *
 * 在「答题 / 学习 / 项目拷打」三个页面复用：
 *   - 文本与语音状态完全内聚（输入框、voiceBaseRef、submittedRef、processing/error）
 *   - 已解决"提交后语音回调残留旧文本"的 bug：submittedRef 拦截 + voiceBaseRef 清空
 *   - 语音按钮区分 4 个状态：未启动 🎤 / 正在录音 ⏹ / 解析中 ⏳ / 错误 ⚠️
 *
 * Props:
 *   - onSend(text):   异步/同步均可；调用前内部已清空输入
 *   - onStopFollowUp: 传入则展示"停止追问"按钮（仅答题页用）
 *   - disabled:       loading 期间禁用全部按钮
 *   - placeholder:    输入框占位
 *   - autoFocus:      渲染后自动聚焦
 *
 * 交互约定：Enter 始终是换行，发送只能点“发送”按钮（避免误触发）。
 */
import { useRef, useState, useEffect } from 'react'
import useSpeechRecognition from '../hooks/useSpeechRecognition'

export default function AnswerInput({
  onSend,
  onStopFollowUp = null,
  disabled = false,
  placeholder = '输入你的回答...（点右侧按钮发送，Enter 换行）',
  autoFocus = false,
}) {
  const [input, setInput] = useState('')
  const voiceBaseRef = useRef('')      // 启动语音时已有的文本
  const submittedRef = useRef(false)   // 提交瞬间为 true，拦截语音的尾部回调
  const textareaRef = useRef(null)

  const {
    listening,
    processing: voiceProcessing,
    error: voiceError,
    clearError: clearVoiceError,
    start: startVoice,
    stop: stopVoice,
    abort: abortVoice,
    supported: voiceSupported,
  } = useSpeechRecognition({
    onResult: (final, interim) => {
      if (submittedRef.current) return
      setInput(voiceBaseRef.current + final + interim)
    },
  })

  useEffect(() => {
    if (autoFocus) textareaRef.current?.focus()
  }, [autoFocus])

  function handleMic() {
    submittedRef.current = false
    if (voiceError) clearVoiceError()
    if (voiceProcessing) { abortVoice(); return }
    if (listening) {
      stopVoice()
    } else {
      voiceBaseRef.current = input
      startVoice()
    }
  }

  async function handleSend() {
    if (!input.trim() || disabled) return
    const text = input.trim()
    // 拦截语音尾部回调，并彻底清空文本
    submittedRef.current = true
    if (listening || voiceProcessing) abortVoice()
    voiceBaseRef.current = ''
    setInput('')
    try {
      await onSend(text)
    } finally {
      // 允许下一轮重新使用语音
      setTimeout(() => { submittedRef.current = false }, 300)
      textareaRef.current?.focus()
    }
  }

  const micTitle = voiceError
    ? `语音失败: ${voiceError}（点击重试）`
    : voiceProcessing ? '语音解析中…（点击强制停止）'
    : listening ? '点击停止录音'
    : '语音输入'

  const micIcon = voiceError ? '⚠️'
    : voiceProcessing ? '⏳'
    : listening ? '⏹'
    : '🎤'

  const micClass = [
    'ai-mic-btn',
    listening && 'mic-active',
    voiceProcessing && 'mic-processing',
    voiceError && 'mic-error',
  ].filter(Boolean).join(' ')

  return (
    <div className="ai-input-area">
      {voiceError && (
        <div className="ai-voice-err">
          🎤 语音识别失败：<b>{voiceError}</b>
          {voiceError === 'network' && '（中文识别需访问 Google 服务器，国内通常需代理）'}
          {voiceError === 'not-allowed' && '（请在浏览器地址栏左侧允许麦克风权限）'}
          {voiceError === 'no-speech' && '（未检测到语音，再点一次重试）'}
          {voiceError === 'audio-capture' && '（未检测到麦克风设备）'}
          <button onClick={clearVoiceError} className="ai-voice-err-close">关闭</button>
        </div>
      )}
      <div className="ai-input-wrap">
        <textarea
          ref={textareaRef}
          className="ai-input"
          value={input}
          onChange={e => setInput(e.target.value)}
          placeholder={placeholder}
          rows={3}
          disabled={disabled}
        />
        <div className="ai-input-actions">
          {voiceSupported && (
            <button
              type="button"
              className={micClass}
              onClick={handleMic}
              title={micTitle}
              disabled={disabled}
            >
              {micIcon}
            </button>
          )}
          {onStopFollowUp && (
            <button
              type="button"
              className="ai-stop-btn"
              onClick={onStopFollowUp}
              disabled={disabled}
              title="停止追问，结束本题"
            >
              ⏹ 停止追问
            </button>
          )}
          <button
            type="button"
            className="ai-send-btn"
            onClick={handleSend}
            disabled={disabled || !input.trim()}
          >
            发送
          </button>
        </div>
      </div>
    </div>
  )
}
