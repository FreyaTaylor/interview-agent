import { createRoot } from 'react-dom/client'
import './utils/authFetch'  // 安装全局 fetch 拦截器（给 API 请求注入 JWT），须在 App 渲染前
import App from './App.jsx'

// 不使用 StrictMode：避免 dev 模式下 useEffect 双调用导致接口被请求两次
createRoot(document.getElementById('root')).render(<App />)
