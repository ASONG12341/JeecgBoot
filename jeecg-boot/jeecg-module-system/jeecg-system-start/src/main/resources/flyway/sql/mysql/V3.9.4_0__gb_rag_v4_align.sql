-- ============================================================
-- GB-RAG v4 延后项 F9：MySQL gb_* 表结构对齐 PG18（V1__gb_rag_v4_schema.sql）
-- 把 P1/P4/F6 新增的字段补到 MySQL 的 gb_* 表（V3.9.3_0 初始建表时还没有这些）
-- Flyway 自动执行（FlywayConfig 仅对 MySQL 数据源生效）
-- 兼容 MySQL 8（用 procedure + INFORMATION_SCHEMA 做 IF NOT EXISTS 守卫，避免重复列报错）
-- ============================================================

-- 守卫：列存在则跳过（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 procedure 模拟）
DELIMITER $$
DROP PROCEDURE IF EXISTS add_column_if_missing$$
CREATE PROCEDURE add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN def TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', def);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- gb_clause: 4 个固定语义槽位（P1）
CALL add_column_if_missing('gb_clause', 'primary_type',    'VARCHAR(100) NULL COMMENT \"槽位1-做什么\"');
CALL add_column_if_missing('gb_clause', 'secondary_type',  'VARCHAR(100) NULL COMMENT \"槽位2-对谁\"');
CALL add_column_if_missing('gb_clause', 'quantity_value',  'DECIMAL NULL COMMENT \"槽位3-多少\"');
CALL add_column_if_missing('gb_clause', 'condition_text',  'VARCHAR(200) NULL COMMENT \"槽位4-什么条件\"');

-- gb_parameter: variables（P1，公式变量绑定）
CALL add_column_if_missing('gb_parameter', 'variables', 'TEXT NULL COMMENT \"公式变量绑定 JSON\"');

-- gb_reference: target_version + target_page_no（F6，让引用格式完整）
CALL add_column_if_missing('gb_reference', 'target_version', 'VARCHAR(20) NULL COMMENT \"目标标准版次\"');
CALL add_column_if_missing('gb_reference', 'target_page_no', 'INT NULL COMMENT \"目标条款页码\"');

-- gb_audit_log: P1 新增字段（routing_intent/extracted_slots/latency_ms/success）
CALL add_column_if_missing('gb_audit_log', 'routing_intent',    'VARCHAR(30) NULL COMMENT \"路由意图\"');
CALL add_column_if_missing('gb_audit_log', 'extracted_slots',   'TEXT NULL COMMENT \"抽取的槽位 JSON\"');
CALL add_column_if_missing('gb_audit_log', 'latency_ms',        'INT NULL COMMENT \"端到端耗时\"');
CALL add_column_if_missing('gb_audit_log', 'success',           'TINYINT(1) NULL COMMENT \"是否成功\"');
-- MySQL 的 retrieval_channels 用 TEXT 存 JSON 数组字符串（PG 用 TEXT[]，Java 侧统一按 String 处理）

DROP PROCEDURE IF EXISTS add_column_if_missing;
