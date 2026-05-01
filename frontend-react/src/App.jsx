import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import KnowledgeTreePage from './pages/KnowledgeTreePage'
import StudyPage from './pages/StudyPage'
import InterviewPage from './pages/InterviewPage'
import ProjectQuestionsPage from './pages/ProjectQuestionsPage'
import OtherQuestionsPage from './pages/OtherQuestionsPage'
import AdminPage from './pages/AdminPage'
import './styles.css'

export default function App() {
  return (
    <BrowserRouter>
      <nav className="navbar">
        <span className="nav-brand">📚 面试备考 Agent</span>
        <div className="nav-links">
          <NavLink to="/interview">📋 面试复盘</NavLink>
          <NavLink to="/study">📖 每日一学</NavLink>
          <span className="nav-divider">|</span>
          <NavLink to="/" end>🌳 知识树</NavLink>
          <NavLink to="/projects">🔨 项目拷打</NavLink>
          <NavLink to="/others">📎 其他问题</NavLink>
          <span className="nav-divider">|</span>
          <NavLink to="/admin">⚙️ 管理</NavLink>
        </div>
      </nav>
      <main className="main">
        <Routes>
          <Route path="/" element={<KnowledgeTreePage />} />
          <Route path="/study" element={<StudyPage />} />
          <Route path="/study/:kpId" element={<StudyPage />} />
          <Route path="/interview" element={<InterviewPage />} />
          <Route path="/projects" element={<ProjectQuestionsPage />} />
          <Route path="/others" element={<OtherQuestionsPage />} />
          <Route path="/admin" element={<AdminPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}
