import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import KnowledgeTreePage from './pages/KnowledgeTreePage'
import StudyPage from './pages/StudyPage'
import InterviewPage from './pages/InterviewPage'
import ProjectQuestionsPage from './pages/ProjectQuestionsPage'
import OtherQuestionsPage from './pages/OtherQuestionsPage'
import './styles.css'

export default function App() {
  return (
    <BrowserRouter>
      <nav className="navbar">
        <span className="nav-brand">📚 面试备考 Agent</span>
        <div className="nav-links">
          <NavLink to="/" end>🌳 知识树</NavLink>
          <NavLink to="/study">📖 学习</NavLink>
          <NavLink to="/interview">📋 面试复盘</NavLink>
        </div>
      </nav>
      <main className="main">
        <Routes>
          <Route path="/" element={<KnowledgeTreePage />} />
          <Route path="/study" element={<StudyPage />} />
          <Route path="/study/:kpId" element={<StudyPage />} />
          <Route path="/interview" element={<InterviewPage />} />
          <Route path="/interview/projects" element={<ProjectQuestionsPage />} />
          <Route path="/interview/others" element={<OtherQuestionsPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}
