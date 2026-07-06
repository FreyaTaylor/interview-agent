---
applyTo: "frontend-react/src/**/*.{jsx,js}"
---

# 前端 React 规则

> 技术栈：React 19 + Vite + react-router-dom 7。

## 约定
- 函数组件 + Hooks；页面放 `pages/`，复用组件放 `components/`，跨组件状态用 `contexts/`，逻辑抽 `hooks/`。
- 调后端统一走封装的 `authFetch`（`utils/authFetch.js`），别散落裸 `fetch`。
- **后端 HTTP 字段是 snake_case**（与后端 Jackson 对齐）——解构响应时按 snake_case 取值，别写成 camelCase。
- 所有请求都是 **POST + JSON body**（含读接口 / list）；id 放 body。
- 统一响应结构 `{ code, data, message }`，`code === 0` 为成功。

## 展示正确性（重要）
- 涉及 UI 改动**必须验证渲染结果**：布局位置、CSS class 是否存在/拼对、层叠关系、响应式表现。
- 不要只改代码就完事，要确认页面真的对。
