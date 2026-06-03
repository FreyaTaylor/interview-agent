import { createRoot } from 'react-dom/client'
import App from './App.jsx'

// 不使用 StrictMode：避免 dev 模式下 useEffect 双调用导致接口被请求两次
createRoot(document.getElementById('root')).render(<App />)
