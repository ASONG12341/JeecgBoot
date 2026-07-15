-- ============================================================
-- GB-RAG v4 L1 数据模型（PostgreSQL 18）
-- 依据：gb-rag-v4/02-L1-数据模型与表结构.md
-- 执行方式：手动在 PG18 上执行（项目 FlywayConfig 只跑 MySQL，不接入 Flyway）
-- 前置：确认 PG >= 18（uuidv7 / Skip Scan / VIRTUAL 生成列 / GIN 均需 PG18）
-- ============================================================

-- 必备扩展
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ------------------------------------------------------------
-- 1. gb_standard — 标准实体
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_standard (
    id                  VARCHAR(36) PRIMARY KEY,             -- Java ASSIGN_ID 雪花 ID
    standard_no         VARCHAR(50) NOT NULL,
    version             VARCHAR(20),
    full_name           TEXT,
    publish_date        DATE,
    implementation_date DATE,
    withdrawal_date     DATE,                                -- null = 现行
    status              VARCHAR(20) DEFAULT 'current',       -- current/superseded/withdrawn
    domain              VARCHAR(100),
    knowledge_id        VARCHAR(36),
    doc_id              VARCHAR(36),
    supersedes          TEXT,                                -- JSON 数组字符串
    normative_refs      TEXT,                                -- JSON 数组字符串
    domain_schema       JSONB DEFAULT '{}',                  -- 【核心】4 槽位的 label/enum/unit
    metadata            JSONB DEFAULT '{}',
    parse_status        VARCHAR(20) DEFAULT 'UPLOADED',      -- UPLOADED/PARSING/PARSED/CONFIRMED/INDEXING/COMPLETED
    pdf_url             VARCHAR(500),
    markdown_content    TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(standard_no, version)
);
CREATE INDEX IF NOT EXISTS idx_gb_standard_knowledge ON gb_standard(knowledge_id);
CREATE INDEX IF NOT EXISTS idx_gb_standard_status    ON gb_standard(status);

-- ------------------------------------------------------------
-- 2. gb_clause — 条款实体（含 4 固定语义槽位 + 层级树）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_clause (
    id                  VARCHAR(36) PRIMARY KEY,
    standard_id         VARCHAR(36) NOT NULL,
    clause_path         VARCHAR(50) NOT NULL,                -- '9' / '9.2' / '9.2.3'
    parent_path         VARCHAR(50),
    depth               INT NOT NULL,
    title               TEXT,
    clause_type         VARCHAR(30),                         -- normative/informative/scope/reference/definition
    polarity            VARCHAR(20) DEFAULT 'positive',      -- positive/negative/exception
    exception_of        VARCHAR(50),
    requirement_strength VARCHAR(20),                        -- mandatory(应)/recommended(宜)/permissible(可)
    is_scope            BOOLEAN DEFAULT false,
    is_appendix         BOOLEAN DEFAULT false,
    appendix_label      VARCHAR(10),

    -- 【核心】4 个固定语义槽位（领域无关）
    primary_type        VARCHAR(100),                        -- 做什么
    secondary_type      VARCHAR(100),                        -- 对谁
    quantity_value      NUMERIC,                             -- 多少
    condition_text      VARCHAR(200),                        -- 什么条件

    text                TEXT NOT NULL,
    page_no             INT,
    confidence          VARCHAR(20),                         -- high/medium/low（解析置信度）
    chunk_id            VARCHAR(100),
    metadata            JSONB DEFAULT '{}',                  -- 超长尾兜底
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    FOREIGN KEY (standard_id) REFERENCES gb_standard(id)
);

-- PG18 Skip Scan 多列索引：覆盖通用骨架 + 4 槽位中的高频过滤字段
CREATE INDEX IF NOT EXISTS idx_gb_clause_skip
    ON gb_clause (standard_id, primary_type, secondary_type, clause_path, polarity);
CREATE INDEX IF NOT EXISTS idx_gb_clause_path   ON gb_clause(standard_id, clause_path);
CREATE INDEX IF NOT EXISTS idx_gb_clause_parent ON gb_clause(standard_id, parent_path);
CREATE INDEX IF NOT EXISTS idx_gb_clause_quantity ON gb_clause(standard_id, quantity_value);
-- GIN 兜底超长尾动态字段
CREATE INDEX IF NOT EXISTS idx_gb_clause_metadata_gin ON gb_clause USING GIN (metadata);

-- PG18 虚拟生成列：clause_path 拆解 chapter（领域无关派生）
CREATE INDEX IF NOT EXISTS idx_gb_clause_chapter ON gb_clause (standard_id, (split_part(clause_path, '.', 1)));

-- ------------------------------------------------------------
-- 3. gb_parameter — 结构化参数/公式
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_parameter (
    id                  VARCHAR(36) PRIMARY KEY,
    standard_id         VARCHAR(36) NOT NULL,
    clause_id           VARCHAR(36) NOT NULL,
    param_name          VARCHAR(100),
    formula             TEXT,                                -- 公式展示串 'U = n × 6.0 V'（不 eval）
    param_value         NUMERIC,                             -- 静态值
    unit                VARCHAR(30),
    variables           JSONB,                               -- [{"name":"n","desc":"电池串数"}]
    condition_expr      TEXT,
    source_text         TEXT,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    FOREIGN KEY (standard_id) REFERENCES gb_standard(id),
    FOREIGN KEY (clause_id)  REFERENCES gb_clause(id)
);
CREATE INDEX IF NOT EXISTS idx_gb_parameter_standard ON gb_parameter(standard_id);
CREATE INDEX IF NOT EXISTS idx_gb_parameter_clause   ON gb_parameter(clause_id);
CREATE INDEX IF NOT EXISTS idx_gb_parameter_name     ON gb_parameter(param_name);

-- ------------------------------------------------------------
-- 4. gb_reference — 统一引用关系（标准内 + 跨标准）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_reference (
    id                  VARCHAR(36) PRIMARY KEY,
    source_standard_id  VARCHAR(36) NOT NULL,
    source_clause_path  VARCHAR(50) NOT NULL,
    target_type         VARCHAR(20) NOT NULL,                -- intra(标准内)/inter(跨标准)
    target_standard_id  VARCHAR(36),
    target_standard_no  VARCHAR(50),
    target_clause_path  VARCHAR(50),
    ref_type            VARCHAR(30),                         -- normative_reference/prerequisite/informative
    ref_text            TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    FOREIGN KEY (source_standard_id) REFERENCES gb_standard(id)
);
CREATE INDEX IF NOT EXISTS idx_gb_reference_source ON gb_reference(source_standard_id, source_clause_path);
CREATE INDEX IF NOT EXISTS idx_gb_reference_target ON gb_reference(target_standard_no);

-- ------------------------------------------------------------
-- 5. gb_term_dict — 术语同义词表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_term_dict (
    id                  VARCHAR(36) PRIMARY KEY,
    standard_id         VARCHAR(36),                         -- null = 全局术语
    canonical_term      VARCHAR(100) NOT NULL,
    synonyms            TEXT[],                              -- Postgres 数组
    definition          TEXT,
    domain              VARCHAR(100),
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_gb_term_standard ON gb_term_dict(standard_id);
CREATE INDEX IF NOT EXISTS idx_gb_term_canonical ON gb_term_dict(canonical_term);

-- ------------------------------------------------------------
-- 6. gb_audit_log — 审计追溯表（P2 起写入）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gb_audit_log (
    id                  VARCHAR(36) PRIMARY KEY,
    session_id          VARCHAR(50),
    user_query          TEXT,
    routing_intent      VARCHAR(30),                         -- CLAUSE_LOOKUP/PARAM_QUERY/SEMANTIC_SEARCH
    extracted_slots     JSONB,                               -- 4 槽位抽取结果
    retrieval_channels  TEXT[],
    retrieved_clauses   JSONB,
    llm_response        TEXT,
    cited_sources       JSONB,
    tool_calls          JSONB,
    latency_ms          INT,
    success             BOOLEAN,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_gb_audit_session ON gb_audit_log(session_id, created_at DESC);
