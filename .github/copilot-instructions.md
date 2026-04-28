You are an AI assistant helping build an interview preparation Agent system.

## Project Context

This is a **面试备考 Agent 系统** (Interview Prep Agent System) built with LangGraph + FastAPI + PostgreSQL.
Core concept: "以考代学" (learn by testing) — the system quizzes users on interview knowledge points, scores answers via Rubric, tracks mastery, and recommends what to review next.

## Must-Read Before Coding

1. `CONVENTIONS.md` — coding standards, naming rules, business rules
2. `docs/TECH_DESIGN.md` — data model (13 tables), page interaction flow, tech stack
3. `docs/DESIGN_v2.md` — product design, feature scope, why Agent

## Tech Stack

- Python 3.11+, FastAPI (async), LangGraph, SQLAlchemy 2.0 (async + asyncpg)
- PostgreSQL 16 + pgvector, DeepSeek Chat API, DashScope Embedding
- Frontend: Streamlit (Phase 0)

## Key Rules

- All code in Chinese comments where explaining business logic
- Type annotations on all function signatures
- Async everywhere: routes, DB, LLM calls
- No direct push to main — create feature branch first, commit locally only
- Prompts in Chinese (target users are Chinese developers preparing for interviews)
- User input may contain typos (from speech-to-text) — always match by semantics, never exact string
- Questions + Rubric are lazy-generated: only created when a user first studies a knowledge point
- One period does NOT include: user auth, role check, data dashboard, speech upload, interview scraping pipeline

## Database Conventions

- Table names: snake_case singular (`knowledge_node`, not `knowledge_nodes`)
- All tables have `id BIGSERIAL PRIMARY KEY` and `created_at TIMESTAMP DEFAULT NOW()`
- `user_id BIGINT DEFAULT 1` on key tables — reserved for multi-user, not enforced in phase 1
- Use Alembic for migrations, not raw SQL files
- JSONB for semi-structured data (e.g., `rubric_result`)

## Agent Conventions

- One Agent per file in `backend/agents/`
- State defined as `TypedDict` with type annotations
- Node functions: verb-first naming (`generate_question`, `score_answer`)
- Conditional edges: `should_xxx()` naming
- Prompts in `backend/prompts/`, never hardcoded in agent code
- Rubric scoring must output structured JSON
- Max 5 rounds of free exploration per knowledge point

## API Conventions

- RESTful, kebab-case paths, plural nouns
- Unified response: `{"code": 0, "data": {...}, "message": "success"}`
- Error: `{"code": 40001, "data": null, "message": "..."}`
- One `APIRouter` per file, grouped by domain
