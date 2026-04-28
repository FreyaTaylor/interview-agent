# interview-agent

面试备考 Agent 系统 — 用 LangGraph 实现个性化面试知识学习。

## 文档

- [产品设计 v2](docs/DESIGN_v2.md)
- [技术设计](docs/TECH_DESIGN.md)
- [开发规范](CONVENTIONS.md)

## 快速开始

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
