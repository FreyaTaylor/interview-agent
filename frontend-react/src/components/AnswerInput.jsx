/**
 * 通用回答输入组件 — 文本框 + 语音输入 + 发送 + 可选停止追问
 *
 * 在「答题 / 学习 / 项目拷打」三个页面复用：
 *   - 文本与语音状态完全内聚（输入框、voiceBaseRef、submittedRef、processing/error）
 *   - 已解决"提交后语音回调残留旧文本"的 bug：submittedRef 拦截 + voiceBaseRef 清空
 *   - 语音按钮区分 4 个状态：未启动 🎤 / 正在录音 ⏹ / 解析中 ⏳ / 错误 ⚠️
 *
 * 智能纠错（两段式发送，仅当用过语音时触发）：
 *   - 用户按过麦 → voiceUsedRef=true
 *   - 点发送 → 调 POST {correctApi}/correct → textarea 替换为纠错版 → 显示
 *     [发原文] / [✓ 确认发送] 两个按钮
 *   - 用户选择后才真正 onSend；纠错失败/网络异常 → 静默退回原文发送
 *   - 手打用户全程无感（voiceUsedRef=false 不触发）
 *
 * Props:
 *   - onSend(text):       异步/同步均可；调用前内部已清空输入
 *   - onStopFollowUp:     传入则展示"停止追问"按钮（仅答题页用）
 *   - disabled:           loading 期间禁用全部按钮
 *   - placeholder:        输入框占位
 *   - autoFocus:          渲染后自动聚焦
 *   - questionContext:    可选，传给纠错 LLM 作术语提示（如当前题目原文）
 *   - correctApi:         可选，纠错接口 base URL；缺省时不做纠错
 *
 * 交互约定：Enter 始终是换行，发送只能点"发送"按钮（避免误触发）。
 */
import { useRef, useState, useEffect } from 'react'
import useSpeechRecognition from '../hooks/useSpeechRecognition'

export default function AnswerInput({
  onSend,
  onStopFollowUp = null,
  disabled = false,
  placeholder = '输入你的回答...（点右侧按钮发送，Enter 换行）',
  autoFocus = false,
  questionContext = '',
  correctApi = null,
}) {
  const [input, setInput] = useState('')
  const voiceBaseRef = useRef('')      // 启动语音时已有的文本
  const submittedRef = useRef(false)   // 提交瞬间为 true，拦截语音的尾部回调
  const textareaRef = useRef(null)
  const voiceUsedRef = useRef(false)   // 本轮是否按过麦

  // 纠错两段式：idle → correcting → choice → idle
  // choice 态约定：textarea 始终保留"原文"；纠错版仅在上方绿条预览，专门用一个按钮发。
  const [phase, setPhase] = useState('idle')
  const [corrected, setCorrected] = useState('')   // LLM 返回的纠错版

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

  // 手动改字 → 退出 choice 态（回到 idle，按当前文本走原流程）
  function handleInputChange(e) {
    setInput(e.target.value)
    if (phase === 'choice') setPhase('idle')
  }

  function handleMic() {
    submittedRef.current = false
    if (voiceError) clearVoiceError()
    if (voiceProcessing) { abortVoice(); return }
    if (listening) {
      stopVoice()
    } else {
      voiceBaseRef.current = input
      voiceUsedRef.current = true
      if (phase === 'choice') setPhase('idle')
      startVoice()
    }
  }

  /** 真正调 onSend；负责清状态、清 textarea、focus。 */
  async function doSend(text) {
    submittedRef.current = true
    if (listening || voiceProcessing) abortVoice()
    voiceBaseRef.current = ''
    voiceUsedRef.current = false
    setInput('')
    setPhase('idle')
    setCorrected('')
    try {
      await onSend(text)
    } finally {
      setTimeout(() => { submittedRef.current = false }, 300)
      textareaRef.current?.focus()
    }
  }

  /** 点"发送" —— 首发：判断是否需要纠错；choice 态：直接发 textarea 当前内容（原文 / 编辑后版本）。 */
  async function handleSend() {
    if (!input.trim() || disabled || phase === 'correcting') return
    const text = input.trim()
    // eslint-disable-next-line no-console
    console.log('[AnswerInput] handleSend', { voiceUsed: voiceUsedRef.current, correctApi, phase, text })

    // choice 态：textarea 此时是原文（或用户在原文上的二次编辑）
    if (phase === 'choice') {
      await doSend(text)
      return
    }

    // 不需纠错：手打 或 未配 correctApi
    if (!voiceUsedRef.current || !correctApi) {
      await doSend(text)
      return
    }

    // 走纠错
    if (listening || voiceProcessing) abortVoice()
    setPhase('correcting')
    try {
      // eslint-disable-next-line no-console
      console.log('[AnswerInput] POST', `${correctApi}/correct`)
      const resp = await fetch(`${correctApi}/correct`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, context: questionContext || '' }),
      })
      const data = await resp.json()
      // eslint-disable-next-line no-console
      console.log('[AnswerInput] correct resp', data)
      const fix = (data?.code === 0 ? data?.data?.corrected : '') || ''
      // 没改 / 失败 → 直接发原文
      if (!fix || fix === text) {
        await doSend(text)
        return
      }
      // textarea 保留原文不动，纠错版进入绿条预览
      setCorrected(fix)
      setPhase('choice')
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn('[AnswerInput] correct error', e)
      await doSend(text)
    }
  }

  /** 点"发纠错版" —— 发送 LLM 修正后的版本。 */
  async function handleSendCorrected() {
    if (!corrected) return
    await doSend(corrected)
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

  const sendLabel = phase === 'correcting' ? '✨ 纠错中…' : '发送'
  // choice 态下主按钮变次要按钮（发原文），另起一个醒目的“发纠错版”主按钮
  const sendClass = ['ai-send-btn', phase === 'choice' && 'ai-send-secondary']
    .filter(Boolean).join(' ')

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
      {phase === 'choice' && (
        <div className="ai-correct-hint">
          <div className="ai-correct-hint-title">✨ 智能纠错建议（下方输入框仍为语音原文）</div>
          <div className="ai-correct-hint-preview">{corrected}</div>
          <div className="ai-correct-hint-tip">默认发“纠错版”。若不需要纠错，点“发原文”或直接编辑下方输入框后发送。</div>
        </div>
      )}
      <div className="ai-input-wrap">
        <textarea
          ref={textareaRef}
          className="ai-input"
          value={input}
          onChange={handleInputChange}
          placeholder={placeholder}
          rows={3}
          disabled={disabled || phase === 'correcting'}
        />
        <div className="ai-input-actions">
          {voiceSupported && (
            <button
              type="button"
              className={micClass}
              onClick={handleMic}
              title={micTitle}
              disabled={disabled || phase === 'correcting'}
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
          {phase === 'choice' ? (
            <>
              <button
                type="button"
                className={sendClass}
                onClick={handleSend}
                disabled={disabled || !input.trim()}
                title="发送语音转写原文（下方输入框里的内容）"
              >
                发原文
              </button>
              <button
                type="button"
                className="ai-send-btn ai-send-confirm"
                onClick={handleSendCorrected}
                disabled={disabled || !corrected}
                title="发送上方预览的纠错版"
              >
                ✨ 发纠错版
              </button>
            </>
          ) : (
            <button
              type="button"
              className={sendClass}
              onClick={handleSend}
              disabled={disabled || !input.trim() || phase === 'correcting'}
            >
              {sendLabel}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
