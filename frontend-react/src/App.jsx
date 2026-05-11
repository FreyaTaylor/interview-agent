import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import KnowledgeTreePage from './pages/KnowledgeTreePage'
import LearnPage from './pages/LearnPage'
import ExamPage from './pages/ExamPage'
import StudyPage from './pages/StudyPage'
import InterviewPage from './pages/InterviewPage'
import ProjectQuestionsPage from './pages/ProjectQuestionsPage'
import OtherQuestionsPage from './pages/OtherQuestionsPage'
import OutlinerPage from './pages/OutlinerPage'
import './styles.css'

export default function App() {
  return (
    <BrowserRouter>
      <nav className="navbar">
        <span className="nav-brand">📚 面试备考 Agent</span>
        <div className="nav-links">
          <NavLink to="/" end>🌳 知识树</NavLink>
          <span className="nav-divider">|</span>
          <NavLink to="/interview">📋 面试复盘</NavLink>
          <NavLink to="/projects">🔨 项目拷打</NavLink>
          <NavLink to="/others">📎 其他问题</NavLink>
          <span className="nav-divider">|</span>
          <NavLink to="/admin">⚙️ 管理</NavLink>
        </div>
      </nav>
      <main className="main learn-main-full">
        <Routes>
          <Route path="/" element={<KnowledgeTreePage />} />
          <Route path="/learn/:kpId" element={<LearnPage />} />
          <Route path="/exam/:kpId" element={<ExamPage />} />
          <Route path="/study" element={<StudyPage />} />
          <Route path="/study/:kpId" element={<StudyPage />} />
          <Route path="/interview" element={<InterviewPage />} />
          <Route path="/projects" element={<ProjectQuestionsPage />} />
          <Route path="/others" element={<OtherQuestionsPage />} />
          <Route path="/admin" element={<OutlinerPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}
