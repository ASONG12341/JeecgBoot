-- ============================================================
-- GB-RAG v4 延后项 F6 迁移（PostgreSQL 18）
-- 给 gb_reference 加 target_version + target_page_no 字段
-- 让 ContextAssembler 引用格式完整 [标准号 版本] §条款号 (页码)
-- 执行方式：手动在 PG18 上执行（项目 FlywayConfig 只跑 MySQL，不接入 Flyway）
-- 前置：V1__gb_rag_v4_schema.sql 已执行
-- ============================================================

ALTER TABLE gb_reference ADD COLUMN IF NOT EXISTS target_version VARCHAR(20);
ALTER TABLE gb_reference ADD COLUMN IF NOT EXISTS target_page_no INT;
