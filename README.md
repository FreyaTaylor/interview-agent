# interview-agent

面试备考 Agent 系统 — 用 LangGraph 实现个性化面试知识学习。

## 文档

- [产品设计 v2](docs/DESIGN_v2.md)
- [技术设计](docs/TECH_DESIGN.md)
- [Azure 自动部署](docs/AZURE_DEPLOYMENT.md)
- [开发规范](CONVENTIONS.md)

## 快速开始

当前 Java 后端支持两种部署模式：

- `self_hosted`：默认开源自用模式，固定本地用户 `id=1`，不需要 GitHub OAuth / 邀请码。
- `hosted`：对外部署模式，启用 GitHub OAuth；可通过邀请码限制新用户注册。

开发者本地自用时只需要复制 `.env.example`，填自己的 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY`，保持：

```bash
IAGENT_DEPLOY_MODE=self_hosted
IAGENT_AUTH_MODE=single_user
IAGENT_INVITE_REQUIRED=false
```

对外部署时改为：

```bash
IAGENT_DEPLOY_MODE=hosted
IAGENT_AUTH_MODE=github
IAGENT_INVITE_REQUIRED=true
JWT_SECRET=please-use-a-long-random-secret-at-least-32-bytes
GITHUB_CLIENT_ID=xxx
GITHUB_CLIENT_SECRET=xxx
FRONTEND_URL=https://your-frontend.example.com
```

```bash
# 1. 启动 PostgreSQL
docker run -d --name interview-pg \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=interview_agent \
  -p 5432:5432 pgvector/pgvector:pg16

# 2. Python 环境
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 3. 配置环境变量
cp .env.example .env
# 编辑 .env 填入 API Key

# 4. 启动后端
cd backend && uvicorn main:app --reload

# 5. 启动前端
cd frontend && streamlit run app.py
```

## 技术栈

Python · FastAPI · LangGraph · PostgreSQL · pgvector · DeepSeek · Streamlit
