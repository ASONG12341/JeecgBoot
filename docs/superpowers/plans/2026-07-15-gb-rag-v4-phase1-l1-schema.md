# GB-RAG v4 Phase 1 (L1 数据模型) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 GB-RAG v4 的 L1 数据模型层——6 张 PG18 表 + 固定语义槽位 + 实体/Mapper/Repository，为后续 P2-P4 提供数据基础。

**Architecture:** PostgreSQL 18 手动执行的 DDL 脚本（不接入 Flyway，因项目 FlywayConfig 硬编码只跑 MySQL）；Java 侧用 MyBatis-Plus 实体（`@TableName` + `@TableId(ASSIGN_ID)` String id）+ BaseMapper + Repository 层，沿用 `gbstandard` 子包既有模式。核心创新是 `gb_clause` 的 4 个固定语义槽位列（领域无关）+ `gb_standard.domain_schema` JSONB（描述性，不参与过滤）。

**Tech Stack:** PostgreSQL 18（uuidv7 / Skip Scan / 虚拟生成列 / GIN）、MyBatis-Plus、Java 17、Lombok、JUnit 5 + Mockito（纯单元测试，无 Spring 上下文、无测试库）。

**依据设计文档：** `gb-rag-v4/02-L1-数据模型与表结构.md` + `06-现有代码处置与迁移方案.md`

## Global Constraints

- **数据库**：PostgreSQL 18（用户确认已上线）。脚本用 PG18 语法（`uuidv7()`、`GENERATED ALWAYS AS ... VIRTUAL`、`USING GIN`、`split_part`）。
- **迁移方式**：手动执行。脚本放 `jeecg-boot-module-airag/src/main/resources/db/postgresql/` 目录，用户自行在 PG18 上跑。**不改 `FlywayConfig.java`**。
- **实体 ID**：`@TableId(type = IdType.ASSIGN_ID)` + `String id`（MyBatis-Plus 雪花 ID，沿用 `gbstandard/model` 既有模式，不用 DB 的 `uuidv7()` 默认值——ID 由 Java 侧生成）。DDL 里主键列仍写 `DEFAULT uuidv7()` 作为 DB 兜底，但实际插入走 Java ASSIGN_ID。
- **实体风格**：`@Data` + `@TableName` + `@Schema(description=...)` + `implements Serializable` + `serialVersionUID=1L` + `Date createdAt`（无 createBy/updateBy 审计块，沿用 GB 表简化风格）。
- **包路径**：`org.jeecg.modules.airag.llm.gbstandard.model.<Entity>`（实体）、`.mapper.<Entity>Mapper`（Mapper）、`.repository.<Entity>Repository` + `.repository.impl.<Entity>RepositoryImpl`（Repository，沿用既有模式）。
- **变更标记**：所有新增/修改代码用 `//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】... ---` ... `//update-end---` 包裹（项目 CLAUDE.md 要求）。
- **测试**：`@ExtendWith(MockitoExtension.class)` 纯单元测试，AssertJ 断言。测试类放 `src/test/java/org/jeecg/modules/airag/llm/gbstandard/`。
- **命名**：参数表统一叫 `gb_parameter`（废弃旧 `gb_param`）；公式列在实体叫 `formula`，DDL 列叫 `formula`（对齐实体，不用 `formula_display`）。

---

## File Structure

| 文件 | 职责 | 状态 |
|------|------|------|
| `resources/db/postgresql/V1__gb_rag_v4_schema.sql` | 6 表 + 索引的 PG18 DDL（手动执行） | 新建 |
| `model/GbClause.java` | 条款实体——**加 4 槽位列** | 改 |
| `model/GbParameter.java` | 参数实体——字段已齐 | 不动 |
| `model/GbStandard.java` | 标准实体——字段已齐 | 不动 |
| `model/GbReference.java` | 引用实体 | 核对 |
| `model/GbTermDict.java` | 术语实体 | 核对 |
| `model/GbAuditLog.java` | 审计实体 | 核对 |
| `vo/DomainSchema.java` | domain_schema 的 Java POJO（4 槽位 label/enum/unit） | 新建 |
| `mapper/GbAuditLogMapper.java` | 审计 Mapper（现状缺） | 新建 |
| `repository/GbAuditLogRepository.java` + impl | 审计 Repository（现状缺） | 新建 |
| `test/.../model/GbClauseSlotTest.java` | 验证 4 槽位字段 | 新建 |
| `test/.../vo/DomainSchemaTest.java` | 验证 domain_schema 序列化 | 新建 |

---

## Task 1: 创建 PG18 DDL 脚本（6 表 + 索引）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/resources/db/postgresql/V1__gb_rag_v4_schema.sql`

**Interfaces:**
- Produces: 6 张表（`gb_standard` / `gb_clause` / `gb_parameter` / `gb_reference` / `gb_term_dict` / `gb_audit_log`）+ 索引。后续所有 Task 的实体 `@TableName` 对应这里的表名，实体字段对应这里的列名。

- [ ] **Step 1: 创建目录并写 DDL 脚本**

Create `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/resources/db/postgresql/V1__gb_rag_v4_schema.sql`:

```sql
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
```

- [ ] **Step 2: 校验脚本无 PG18 语法错误**

Run（用户在 PG18 上手动执行）:
```bash
psql -h <host> -U <user> -d <db> -f jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/resources/db/postgresql/V1__gb_rag_v4_schema.sql
```
Expected: 6 张表创建成功，无报错。验证：
```sql
\dt gb_*;
-- 应看到 gb_audit_log, gb_clause, gb_parameter, gb_reference, gb_standard, gb_term_dict
\d gb_clause;
-- 应看到 primary_type, secondary_type, quantity_value, condition_text 4 个槽位列
```

- [ ] **Step 3: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/resources/db/postgresql/V1__gb_rag_v4_schema.sql
git commit -m "feat(airag): GB-RAG v4 P1 新增 PG18 L1 schema DDL 脚本（6表+4槽位+Skip Scan索引）"
```

---

## Task 2: GbClause 实体加 4 槽位列

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/model/GbClause.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/model/GbClauseSlotTest.java`

**Interfaces:**
- Consumes: 无（首个 Java 改动）
- Produces: `GbClause` 的 `primaryType`/`secondaryType`/`quantityValue`/`conditionText` 字段（`BigDecimal` for quantityValue，其余 String）。后续 Task 3（DomainSchema）、P2/P3 的抽取器/检索器都读这些字段。

- [ ] **Step 1: 写失败测试**

Create `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/model/GbClauseSlotTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbClause 4 槽位字段测试-----------
class GbClauseSlotTest {

    @Test
    void shouldHoldFourSemanticSlots() {
        GbClause clause = new GbClause();
        clause.setPrimaryType("overcharge");
        clause.setSecondaryType("pack");
        clause.setQuantityValue(new BigDecimal("3"));
        clause.setConditionText("25±5℃");

        assertThat(clause.getPrimaryType()).isEqualTo("overcharge");
        assertThat(clause.getSecondaryType()).isEqualTo("pack");
        assertThat(clause.getQuantityValue()).isEqualByComparingTo("3");
        assertThat(clause.getConditionText()).isEqualTo("25±5℃");
    }

    @Test
    void slotsShouldDefaultToNullForDomainAgnosticEmptyClause() {
        GbClause clause = new GbClause();
        assertThat(clause.getPrimaryType()).isNull();
        assertThat(clause.getSecondaryType()).isNull();
        assertThat(clause.getQuantityValue()).isNull();
        assertThat(clause.getConditionText()).isNull();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbClause 4 槽位字段测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=GbClauseSlotTest
```
Expected: 编译失败——`setPrimaryType`/`setSecondaryType`/`setQuantityValue`/`setConditionText` 方法不存在（Lombok 还没生成，因为字段未定义）。

- [ ] **Step 3: 加 4 槽位字段**

Modify `GbClause.java`，在 `appendixLabel` 字段之后、`text` 字段之前插入（保持现有字段顺序，把 4 槽位聚在一起）：

在 `private String appendixLabel;` 之后，`@Schema(description = "条款完整文本")` 之前，插入：

```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】4 个固定语义槽位（领域无关）-----------
    @Schema(description = "槽位1-做什么: 测试类型/功能类别（领域无关）")
    private String primaryType;

    @Schema(description = "槽位2-对谁: 对象/适用物（领域无关）")
    private String secondaryType;

    @Schema(description = "槽位3-多少: 量值（领域无关）")
    private BigDecimal quantityValue;

    @Schema(description = "槽位4-什么条件: 环境条件等（领域无关）")
    private String conditionText;
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】4 个固定语义槽位（领域无关）-----------
```

同时在文件顶部 import 区加（`import java.util.Date;` 旁边）:
```java
import java.math.BigDecimal;
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=GbClauseSlotTest
```
Expected: PASS（2 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/model/GbClause.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/model/GbClauseSlotTest.java
git commit -m "feat(airag): GbClause 增加 4 个领域无关固定语义槽位列"
```

---

## Task 3: DomainSchema VO（domain_schema 的 Java 结构）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/vo/DomainSchema.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/vo/DomainSchemaTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `DomainSchema` POJO（4 槽位的 `label` + `enum` + `unit`）+ Jackson 序列化/反序列化。P2 的 `GbSchemaDeriver` 产此对象存 `gb_standard.domain_schema`；P3 的 `DefaultQueryIntentExtractor` 读此对象拼动态 JSON Schema。

- [ ] **Step 1: 写失败测试**

Create `DomainSchemaTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】DomainSchema 序列化测试-----------
class DomainSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldSerializeFourSlotsWithLabelEnumUnit() throws Exception {
        DomainSchema schema = new DomainSchema();
        DomainSchema.Slot primary = new DomainSchema.Slot();
        primary.setLabel("测试类型");
        primary.setEnumValues(Arrays.asList("overcharge", "short_circuit", "crush"));
        schema.setPrimaryType(primary);

        DomainSchema.Slot quantity = new DomainSchema.Slot();
        quantity.setLabel("电池串数");
        quantity.setUnit("S");
        schema.setQuantityValue(quantity);

        String json = mapper.writeValueAsString(schema);
        assertThat(json).contains("\"primaryType\"");
        assertThat(json).contains("\"测试类型\"");
        assertThat(json).contains("\"overcharge\"");
        assertThat(json).contains("\"quantityValue\"");
        assertThat(json).contains("\"S\"");
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = "{\"primaryType\":{\"label\":\"测试类型\",\"enumValues\":[\"overcharge\"]}}";
        DomainSchema schema = mapper.readValue(json, DomainSchema.class);
        assertThat(schema.getPrimaryType().getLabel()).isEqualTo("测试类型");
        assertThat(schema.getPrimaryType().getEnumValues()).contains("overcharge");
        // 未提供的槽位应为 null（领域无关：不是所有标准都填满 4 槽位）
        assertThat(schema.getSecondaryType()).isNull();
    }

    @Test
    void emptySchemaShouldSerializeAsEmptyObject() throws Exception {
        DomainSchema schema = new DomainSchema();
        String json = mapper.writeValueAsString(schema);
        // fallback 场景：LLM 推不出 schema，退化为空对象，不阻塞入库
        assertThat(mapper.readTree(json).size()).isEqualTo(0);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】DomainSchema 序列化测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=DomainSchemaTest
```
Expected: 编译失败——`DomainSchema` 类不存在。

- [ ] **Step 3: 实现 DomainSchema**

Create `DomainSchema.java`:

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】domain_schema Java POJO（4 槽位的 label/enum/unit，描述性，不参与检索过滤）-----------
package org.jeecg.modules.airag.llm.gbstandard.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 国标领域 Schema（领域无关骨架）
 * <p>
 * 描述 4 个固定语义槽位在本标准中的标签(label)、取值枚举(enumValues)、单位(unit)。
 * 存于 gb_standard.domain_schema JSONB。仅服务 LLM 抽取校验和前端展示，
 * 不参与检索过滤（过滤走 gb_clause 的 4 个固定列 + Skip Scan）。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Schema(description = "国标领域 Schema（4 槽位的语义描述）")
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DomainSchema implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "槽位1-做什么 的语义描述")
    private Slot primaryType;

    @Schema(description = "槽位2-对谁 的语义描述")
    private Slot secondaryType;

    @Schema(description = "槽位3-多少 的语义描述")
    private Slot quantityValue;

    @Schema(description = "槽位4-什么条件 的语义描述")
    private Slot conditionText;

    /**
     * 单个槽位的语义描述。
     * label: 展示名（如"测试类型"）
     * enumValues: 合法取值列表（可空，表示自由填值）
     * unit: 单位（如"V"/"℃"）
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Slot implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "槽位展示标签")
        private String label;

        @Schema(description = "合法取值枚举（可空）")
        private List<String> enumValues;

        @Schema(description = "单位")
        private String unit;
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】domain_schema Java POJO（4 槽位的 label/enum/unit，描述性，不参与检索过滤）-----------
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=DomainSchemaTest
```
Expected: PASS（3 个测试全过）。

- [ ] **Step 5: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/vo/DomainSchema.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/vo/DomainSchemaTest.java
git commit -m "feat(airag): 新增 DomainSchema VO（domain_schema 的 Java 结构，4 槽位描述）"
```

---

## Task 4: GbParameter 实体加 variables 字段（对齐 v4）

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/model/GbParameter.java`

**Interfaces:**
- Consumes: 无
- Produces: `GbParameter.variables` 字段（String，存 JSON）。P4 的 `GbCalculationTool` 读此字段做变量绑定。现状实体缺此字段（v4 设计要求公式结构化：param_value + formula + variables）。

- [ ] **Step 1: 核对 GbParameter 现状**

现状字段：`id/standardId/clauseId/paramName/formula/paramValue/unit/conditionExpr/sourceText/metadata/createdAt`。
v4 要求新增：`variables`（变量绑定 JSON，如 `[{"name":"n","desc":"电池串数"}]`）。
v4 文档把公式列叫 `formula_display`，但实体已有 `formula`——**统一用 `formula`**（实体优先，DDL 也叫 `formula`），不改名。

- [ ] **Step 2: 加 variables 字段**

Modify `GbParameter.java`，在 `unit` 字段之后、`conditionExpr` 之前插入：

```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbParameter 增加 variables 字段（公式变量绑定，结构化存储不 eval）-----------
    @Schema(description = "公式变量绑定 (JSON): [{\"name\":\"n\",\"desc\":\"电池串数\"}]")
    private String variables;
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbParameter 增加 variables 字段（公式变量绑定，结构化存储不 eval）-----------
```

> 注：不加独立测试，因这是纯 Lombok 字段，且 P4 才用到。若需测试可补 setter/getter 验证，但 YAGNI。

- [ ] **Step 3: 编译确认**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/model/GbParameter.java
git commit -m "feat(airag): GbParameter 增加 variables 字段（公式变量绑定，对齐 v4 结构化公式设计）"
```

---

## Task 5: GbAuditLog Mapper + Repository（现状缺）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/mapper/GbAuditLogMapper.java`
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/repository/GbAuditLogRepository.java`
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/repository/impl/GbAuditLogRepositoryImpl.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/repository/GbAuditLogRepositoryTest.java`

**Interfaces:**
- Consumes: `GbAuditLog` 实体（现状已存在，核对字段齐全）
- Produces: `GbAuditLogRepository.save(GbAuditLog)` 方法。P2 入库埋点、P3 检索埋点、P4 Tool 埋点都调此方法写审计表。

- [ ] **Step 1: 核对 GbAuditLog 实体字段**

读取 `model/GbAuditLog.java` 确认字段齐全：`id/sessionId/userQuery/routingIntent/extractedSlots/retrievalChannels/retrievedClauses/llmResponse/citedSources/toolCalls/latencyMs/success/createdAt`。若缺字段（如 `routingIntent`/`extractedSlots`/`latencyMs`/`success`），按 DDL 补齐。

- [ ] **Step 2: 写失败测试**

Create `GbAuditLogRepositoryTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbAuditLogMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbAuditLogRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository 测试-----------
@ExtendWith(MockitoExtension.class)
class GbAuditLogRepositoryTest {

    @InjectMocks
    private GbAuditLogRepositoryImpl repository;

    @Mock
    private GbAuditLogMapper mapper;

    @Test
    void shouldSaveAuditLogAndSetCreatedAt() {
        GbAuditLog log = new GbAuditLog();
        log.setUserQuery("3S 电池包过充测试阈值");
        log.setRoutingIntent("PARAM_QUERY");
        log.setSuccess(true);

        repository.save(log);

        ArgumentCaptor<GbAuditLog> captor = ArgumentCaptor.forClass(GbAuditLog.class);
        verify(mapper).insert(captor.capture());
        GbAuditLog saved = captor.getValue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUserQuery()).isEqualTo("3S 电池包过充测试阈值");
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository 测试-----------
```

- [ ] **Step 3: 运行测试确认失败**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=GbAuditLogRepositoryTest
```
Expected: 编译失败——`GbAuditLogMapper`/`GbAuditLogRepository`/`GbAuditLogRepositoryImpl` 不存在。

- [ ] **Step 4: 实现 Mapper**

Create `mapper/GbAuditLogMapper.java`:

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;

/**
 * 国标审计日志 Mapper
 *
 * @author song
 * @date 2026-07-15
 */
public interface GbAuditLogMapper extends BaseMapper<GbAuditLog> {
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Mapper-----------
```

- [ ] **Step 5: 实现 Repository 接口**

Create `repository/GbAuditLogRepository.java`:

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;

/**
 * 国标审计日志 Repository
 *
 * @author song
 * @date 2026-07-15
 */
public interface GbAuditLogRepository {

    /**
     * 保存审计日志（自动设置 createdAt）。
     *
     * @param logEntry 审计日志（createdAt 由实现层填充）
     * @return 影响行数
     */
    int save(GbAuditLog logEntry);
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
```

- [ ] **Step 6: 实现 RepositoryImpl**

Create `repository/impl/GbAuditLogRepositoryImpl.java`:

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbAuditLogMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 国标审计日志 Repository 实现
 *
 * @author song
 * @date 2026-07-15
 */
@Repository
public class GbAuditLogRepositoryImpl implements GbAuditLogRepository {

    @Autowired
    private GbAuditLogMapper mapper;

    @Override
    public int save(GbAuditLog logEntry) {
        if (logEntry.getCreatedAt() == null) {
            logEntry.setCreatedAt(new Date());
        }
        return mapper.insert(logEntry);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
```

- [ ] **Step 7: 运行测试确认通过**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=GbAuditLogRepositoryTest
```
Expected: PASS。

- [ ] **Step 8: Commit**

```bash
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/mapper/GbAuditLogMapper.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/repository/GbAuditLogRepository.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/repository/impl/GbAuditLogRepositoryImpl.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/repository/GbAuditLogRepositoryTest.java
git commit -m "feat(airag): 新增 GbAuditLog Mapper + Repository（审计埋点基础，P2 起写入）"
```

---

## Task 6: 全模块编译 + 全测试通过 + GitNexus 变更检测

**Files:** 无新文件，验证性 Task。

- [ ] **Step 1: 全模块编译**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 2: 跑全量测试**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test
```
Expected: 全部 PASS（含新加的 3 个测试类 + 既有测试）。

- [ ] **Step 3: GitNexus 变更检测（AGENTS.md 强制）**

Run:
```
mcp__gitnexus__detect_changes({scope: "compare", base_ref: "main"})
```
Expected: 变更范围仅限 `gbstandard/model/`、`gbstandard/mapper/`、`gbstandard/repository/`、`gbstandard/vo/`、测试目录、`db/postgresql/` 脚本。不应命中 `EmbeddingHandler` / `AIChatHandler` 等核心检索链路（P1 不动这些）。

- [ ] **Step 4: Commit 验收记录（可选）**

若有未提交的收尾改动：
```bash
git add -A && git commit -m "chore(airag): GB-RAG v4 P1 验收通过（编译+测试+GitNexus 变更检测）" --allow-empty
```

---

## P1 验收标准（对照 07-分阶段路线 §8）

| 验收点 | 验证方式 |
|--------|---------|
| Flyway/手动迁移成功；6 表 + 索引建好 | Task 1 Step 2 在 PG18 上 `\dt gb_*` 看到 6 表，`\d gb_clause` 看到 4 槽位列 |
| 实体/Mapper 编译通过 | Task 6 Step 1 BUILD SUCCESS |
| GbClause 4 槽位字段可用 | Task 2 测试 PASS |
| DomainSchema 序列化正确 | Task 3 测试 PASS |
| GbAuditLog 可写入 | Task 5 测试 PASS |
| 变更范围受控 | Task 6 Step 3 GitNexus 未命中核心检索链路 |

P1 完成后，P2（L2 入库管线）即可在此基础上开建。
