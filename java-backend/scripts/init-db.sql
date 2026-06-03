-- Java 后端数据库初始化脚本（一次性手动执行）
-- 用法：psql -U postgres -f java-backend/scripts/init-db.sql
--
-- 与 Python 端 (interview_agent) 完全隔离：新 DB + 新 user，空库起步

-- 1) 创建用户（如已存在则跳过）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iagent_java') THEN
        CREATE ROLE iagent_java WITH LOGIN PASSWORD 'iagent_java';
    END IF;
END$$;

-- 2) 创建数据库（必须在 DO 块外执行）
SELECT 'CREATE DATABASE interview_agent_java OWNER iagent_java ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'interview_agent_java')\gexec

-- 3) 授权
GRANT ALL PRIVILEGES ON DATABASE interview_agent_java TO iagent_java;

-- 4) 在新库里启用 pgvector 扩展（需切到目标库）
\connect interview_agent_java
CREATE EXTENSION IF NOT EXISTS vector;
GRANT ALL ON SCHEMA public TO iagent_java;
