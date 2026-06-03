import { createContext, useContext, useState, useMemo } from 'react'

// 面试复盘页"正在解析/转写中"的全局标记
// 用于：解析过程中点击导航条其他页面时，改为新标签打开（保留当前解析进度）
const InterviewBusyContext = createContext({ busy: false, setBusy: () => {} })

export function InterviewBusyProvider({ children }) {
  const [busy, setBusy] = useState(false)
  const value = useMemo(() => ({ busy, setBusy }), [busy])
  return <InterviewBusyContext.Provider value={value}>{children}</InterviewBusyContext.Provider>
}

export function useInterviewBusy() {
  return useContext(InterviewBusyContext)
}
