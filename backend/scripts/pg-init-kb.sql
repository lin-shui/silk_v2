-- Silk Knowledge Base — PostgreSQL 初始化脚本
-- 自动在 docker-entrypoint-initdb.d 中运行

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 验证扩展已安装
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
