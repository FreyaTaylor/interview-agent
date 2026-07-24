import { BrowserRouter, Routes, Route, NavLink, Navigate, Link } from 'react-router-dom'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { InterviewBusyProvider, useInterviewBusy } from './contexts/InterviewBusyContext'
import LoginPage from './pages/LoginPage'
import KnowledgeTreePage from './pages/KnowledgeTreePage'
import LearnPage from './pages/LearnPage'
import ExamPage from './pages/ExamPage'
import InterviewPage from './pages/InterviewPage'
import InterviewReviewPage from './pages/InterviewReviewPage'
import InterviewExpStudyPage from './pages/InterviewExpStudyPage'
import ProjectGrillingPage from './pages/ProjectGrillingPage'
import OutlinerPage from './pages/OutlinerPage'
import ProfilePage from './pages/ProfilePage'
import './styles.css'

// 学习 / 答题入口（无 kpId 时跳到上次打开的知识点，没有则回到知识树）
function LastKpRedirect({ base, storageKey }) {
  const last = localStorage.getItem(storageKey)
  if (last) return <Navigate to={`${base}/${last}`} replace />
  return (
    <div className="empty-page-state">
      <div className="empty-page-icon">📚</div>
      <div className="empty-page-title">还没有学习记录</div>
      <div className="empty-page-desc">请先到知识树选择一个知识点</div>
      <Link to="/knowledge" className="empty-page-btn">前往知识树</Link>
    </div>
  )
}

function AppContent() {
  const { user, loading } = useAuth()
  const { busy: interviewBusy } = useInterviewBusy()

  if (loading) return <div className="login-loading">加载中...</div>
  if (!user) return <LoginPage />

  // 仅当面试复盘页正在录音转写或解析时，点击其他导航项 → 新标签打开（保留当前解析进度）
  const navItem = (to, label, end = false) => {
    if (interviewBusy && to !== '/interview') {
      return <a href={to} target="_blank" rel="noreferrer" className="nav-link-external">{label}</a>
    }
    return <NavLink to={to} end={end}>{label}</NavLink>
  }

  return (
    <>
      <nav className="navbar">
        <span className="nav-brand">📚 面试备考 Agent</span>
        <div className="nav-links">
          {navItem('/knowledge', '知识树')}
          {navItem('/learn', '学习')}
          {navItem('/exam', '答题')}
          <span className="nav-divider">|</span>
          {navItem('/grilling', '项目拷打')}
          <span className="nav-divider">|</span>
          {navItem('/interview', '面试复盘')}
          {navItem('/interview-exp', '📖 看看面经')}
          <span className="nav-divider">|</span>
          {navItem('/admin', '⚙️ 管理')}
        </div>
        <div className="nav-user">
          {user.avatar_url && <img src={user.avatar_url} alt="" className="nav-avatar" />}
          <NavLink to="/profile" className="nav-username">{user.github_login || user.username}</NavLink>
        </div>
      </nav>
      <main className="main learn-main-full">
        <Routes>
          <Route path="/" element={<Navigate to="/knowledge" replace />} />
          <Route path="/knowledge" element={<KnowledgeTreePage />} />
          <Route path="/learn" element={<LastKpRedirect base="/learn" storageKey="lastKpId" />} />
          <Route path="/learn/:kpId" element={<LearnPage />} />
          <Route path="/exam" element={<LastKpRedirect base="/exam" storageKey="lastKpId" />} />
          <Route path="/exam/:kpId" element={<ExamPage />} />
          <Route path="/interview" element={<InterviewPage />} />
          <Route path="/interview/new/review" element={<InterviewReviewPage />} />
          <Route path="/interview/:recordId" element={<InterviewPage />} />
          <Route path="/interview-exp" element={<InterviewExpStudyPage />} />
          <Route path="/interview-exp/:questionId" element={<InterviewExpStudyPage />} />
          <Route path="/grilling" element={<ProjectGrillingPage />} />
          <Route path="/grilling/:projectId" element={<ProjectGrillingPage />} />
          <Route path="/admin" element={<Navigate to="/admin/tree" replace />} />
          <Route path="/admin/:tab" element={<OutlinerPage />} />
          <Route path="/profile" element={<ProfilePage />} />
        </Routes>
      </main>
    </>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <InterviewBusyProvider>
          <AppContent />
        </InterviewBusyProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
