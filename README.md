# interview-agent · 面试备考 Agent 系统

> **以考代学**：通过 Agent 持续给你出题、按 Rubric 评分、追踪掌握度，并基于面试复盘反向定位薄弱知识点，形成个性化的面试备考闭环。

一个开源的、可本地自部署的面试备考系统。把你的知识体系整理成知识树，系统会像面试官一样动态出题、结构化评分、追问，并实时更新每个知识点的掌握度。

## ✨ 核心功能

- **知识树**：手写 / LLM 生成 / 文本解析 / 图片解析 / `.mm` 导入 / 优化 / 合并 / 编辑
- **学习与答题**：动态出题 + Rubric 结构化评分 + 智能追问 + 掌握度 EMA 更新
- **知识点讲解**：LLM 生成讲解内容（带缓存）+ 自由探索对话 + 对话回写讲解
- **项目拷打**：Tool-Calling Agent 基于你的项目档案逐层深挖（项目 → 话题 → 问题）
- **面试复盘**：粘贴面试文本，两阶段解析（preview → finalize），自动分类评分并更新掌握度
- **部署模式**：`self_hosted`（开源自用，单用户）/ `hosted`（GitHub OAuth + 邀请码）

## 🧱 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3 · Spring MVC（虚拟线程）· MyBatis · Flyway · HikariCP |
| LLM 接入 | Spring AI（OpenAI 兼容 → DeepSeek Chat）· LangChain4j + DashScope |
| 数据库 | PostgreSQL 16 + pgvector |
| Embedding / 视觉 / ASR | DashScope（`text-embedding-v3` / `qwen-vl-max` / Paraformer） |
| 前端 | React 19 · Vite · react-router-dom 7 · react-markdown |

## 📁 仓库结构

```
interview-agent/
├── java-backend/        # Spring Boot 后端（API / Service / Agent / Mapper / Flyway 迁移）
├── frontend-react/      # React + Vite 前端
├── docker-compose.yml   # 本地 PostgreSQL (pgvector/pg16)
├── dev-run.sh           # 一键启动后端（转发到 java-backend/scripts/dev-run.sh）
├── CONVENTIONS.md       # 开发规范
└── .env.example         # 环境变量样例
```

## 🚀 快速开始

> 依赖：Java 21、Maven 3.9+、Node.js 20+、Docker（用于本地 PostgreSQL）。

### 1. 启动数据库（PostgreSQL 16 + pgvector）

```bash
docker compose up -d
# 初始化 Java 后端使用的数据库 / 角色 / pgvector 扩展
psql -h localhost -U postgres -f java-backend/scripts/init-db.sql   # 密码：postgres
```

`init-db.sql` 会创建数据库 `interview_agent_java`、角色 `iagent_java` 并启用 `vector` 扩展。后端启动时 Flyway 会自动执行所有迁移。

### 2. 配置环境变量

```bash
cp .env.example .env
```

至少填入两个 API Key（本地自用模式下其余项保持默认即可）：

```bash
DEEPSEEK_API_KEY=sk-xxx        # https://platform.deepseek.com
DASHSCOPE_API_KEY=sk-xxx       # https://dashscope.console.aliyun.com

# 部署模式（默认开源自用：单用户、免登录）
IAGENT_DEPLOY_MODE=self_hosted
IAGENT_AUTH_MODE=single_user
IAGENT_INVITE_REQUIRED=false
```

### 3. 启动后端（:8080）

```bash
./dev-run.sh
# 等价于：cd java-backend && mvn spring-boot:run
```

### 4. 启动前端（:5173）

```bash
cd frontend-react
npm install
npm run dev
```

打开 http://localhost:5173 即可使用。前端默认请求 `http://127.0.0.1:8080/api`，可用环境变量 `VITE_API_BASE` 覆盖。

## ⚙️ 部署模式

| 变量 | `self_hosted`（默认） | `hosted` |
|---|---|---|
| `IAGENT_DEPLOY_MODE` | `self_hosted` | `hosted` |
| `IAGENT_AUTH_MODE` | `single_user` | `github` |
| `IAGENT_INVITE_REQUIRED` | `false` | `true`（可选） |

对外部署（`hosted`）时还需配置 GitHub OAuth 与 JWT：

```bash
JWT_SECRET=please-use-a-long-random-secret-at-least-32-bytes
GITHUB_CLIENT_ID=xxx
GITHUB_CLIENT_SECRET=xxx
FRONTEND_URL=https://your-frontend.example.com
```

## 🤝 贡献

欢迎 Issue 与 PR。提交代码前请阅读 [CONVENTIONS.md](CONVENTIONS.md)。

## 📄 License

[MIT](LICENSE) © FreyaTaylor
