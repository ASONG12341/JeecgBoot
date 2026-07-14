# GB 国标知识引擎 Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立国标结构化数据模型（L1）、文档层级解析器（L2 核心）、用户确认页（前端+后端 API），为后续 Phase 2-5 奠定基础。

**Architecture:** 在 airag 模块内新建 `gbstandard` 子包，结构化表存 **MySQL**（与 airag_knowledge 等表同库），通过 Flyway 迁移脚本建表。pgvector PostgreSQL 实例仅用于向量存储（由 LangChain4j PgVectorEmbeddingStore 自动管理）。后端提供 Preview/Confirm API，前端用左右分栏（PDF 原文 + 解析结果）实现用户确认。

**Tech Stack:** Spring Boot 4.1.0 / Java 17 / MyBatis-Plus 3.5.16 / MySQL 8.0+ / Vue3 / Ant Design Vue 4 / PDF.js

> **重要**: GB 结构化表（gb_standard, gb_clause 等）存在 MySQL 主库中，不是 pgvector PostgreSQL。实体 ID 使用 `IdType.ASSIGN_ID`（雪花算法），与现有 airag 实体一致。TEXT[] 改为 JSON 列，JSONB 改为 JSON 列。

## Global Constraints

- PostgreSQL 版本: 18（用于 pgvector 向量存储，由 LangChain4j PgVectorEmbeddingStore 自动管理）
- MySQL 版本: 8.0+（GB 结构化表 + 现有业务表）
- Java 版本: 17+，使用 `jakarta.*` 命名空间（非 javax）
- 所有新增/修改代码必须用 `//update-begin` / `//update-end` 注释包裹
- 禁止引入新 Maven 依赖（pom.xml 不变）
- 禁止破坏 airag 通用 RAG 能力（国标增强层仅在知识库为国标类型时激活）
- Kill Switch: `jeecg.airag.gb-standard.enabled=false` 时全部国标逻辑跳过
- 前端组件路径: `jeecgboot-vue3/src/views/super/airag/aiknowledge/components/`
- 后端包路径: `org.jeecg.modules.airag.llm.gbstandard`

---

## File Structure

### 新建文件（后端）

| 文件 | 职责 |
|------|------|
| `jeecg-module-system/.../flyway/sql/mysql/V3.9.3_0__gb_standard_init.sql` | MySQL Flyway 迁移脚本（建表 + ALTER） |
| `gbstandard/model/GbStandard.java` | 标准实体 |
| `gbstandard/model/GbClause.java` | 条款实体 |
| `gbstandard/model/GbParameter.java` | 参数实体 |
| `gbstandard/model/GbReference.java` | 引用关系实体 |
| `gbstandard/model/GbTermDict.java` | 术语同义词实体 |
| `gbstandard/model/GbAuditLog.java` | 审计日志实体 |
| `gbstandard/model/GbClauseNode.java` | 解析器输出的条款树节点 DTO |
| `gbstandard/model/GbDocStructure.java` | 解析器输出的文档结构 DTO |
| `gbstandard/mapper/GbStandardMapper.java` | 标准 Mapper |
| `gbstandard/mapper/GbClauseMapper.java` | 条款 Mapper |
| `gbstandard/mapper/GbParameterMapper.java` | 参数 Mapper |
| `gbstandard/mapper/GbReferenceMapper.java` | 引用关系 Mapper |
| `gbstandard/mapper/GbTermDictMapper.java` | 术语 Mapper |
| `gbstandard/config/GbStandardProperties.java` | 配置属性类（Kill Switch） |
| `gbstandard/ingestion/GbDocumentStructureParser.java` | 文档层级树解析器 |
| `gbstandard/controller/GbStandardController.java` | Preview/Confirm API |

### 修改文件（后端）

| 文件 | 修改内容 |
|------|---------|
| `llm/entity/AiragKnowledgeDoc.java` | 新增 `parseStatus` 字段 |
| `llm/consts/LLMConsts.java` | 新增 parse status 常量 |

### 新建文件（前端）

| 文件 | 职责 |
|------|------|
| `aiknowledge/components/GbStandardPreview.vue` | 国标解析确认页（左右分栏） |
| `aiknowledge/GbStandardPreview.api.ts` | 国标预览 API |

> 所有后端新文件的基础包路径: `org.jeecg.modules.airag.llm.gbstandard`
> 实际目录: `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/`

---

### Task 1: MySQL Flyway 迁移脚本（DDL）

**Files:**
- Create: `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.3_0__gb_standard_init.sql`

**Interfaces:**
- Produces: 6 张 MySQL 表（gb_standard, gb_clause, gb_parameter, gb_reference, gb_term_dict, gb_audit_log）

> **数据库选型说明**: GB 结构化表存在 MySQL 主库中（与 airag_knowledge 等表同库），不使用 pgvector PostgreSQL。原因：
> 1. 项目 Flyway 仅配置了 MySQL 迁移路径（`flyway/sql/mysql/`）
> 2. 所有业务实体（AiragKnowledge、AiragKnowledgeDoc 等）都在 MySQL
> 3. pgvector PG 实例仅由 LangChain4j `PgVectorEmbeddingStore`（`createTable=true`）自动管理向量表
> 4. GB 表通过 MyBatis-Plus 访问，与现有业务表共享同一个 MySQL DataSource

- [ ] **Step 1: 创建 Flyway 迁移脚本**

```sql
-- ================================================================
-- GB 国标知识引擎 DDL — MySQL 8.0+
-- Flyway 迁移脚本，命名遵循项目规范 V{version}__{description}.sql
-- 日期: 2026-07-14
-- ================================================================

SET NAMES utf8mb4;

-- update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 L1 数据模型建表-----------

-- 1. gb_standard — 标准实体
CREATE TABLE IF NOT EXISTS `gb_standard` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `standard_no` VARCHAR(50) NOT NULL COMMENT '标准号: GB 31241 / GB/T 31467.3',
    `version` VARCHAR(20) NULL COMMENT '版本年份: 2022',
    `full_name` TEXT NULL COMMENT '标准全称',
    `publish_date` DATE NULL COMMENT '发布日期',
    `implementation_date` DATE NULL COMMENT '实施日期',
    `withdrawal_date` DATE NULL COMMENT '废止日期, null=现行',
    `status` VARCHAR(20) DEFAULT 'current' COMMENT 'current/superseded/withdrawn',
    `domain` VARCHAR(100) NULL COMMENT '领域标签',
    `knowledge_id` VARCHAR(36) NULL COMMENT '关联 airag_knowledge.id',
    `doc_id` VARCHAR(36) NULL COMMENT '关联 airag_knowledge_doc.id',
    `supersedes` JSON NULL COMMENT '替代的旧标准列表 ["GB 31241-2014"]',
    `normative_refs` JSON NULL COMMENT '规范性引用文件清单',
    `domain_schema` JSON NULL COMMENT '领域属性 Schema (L2 推导)',
    `metadata` JSON NULL COMMENT '扩展元数据',
    `parse_status` VARCHAR(20) DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PARSING/PARSED/CONFIRMED/INDEXING/COMPLETED',
    `pdf_url` TEXT NULL COMMENT '原始 PDF 文件访问路径',
    `markdown_content` LONGTEXT NULL COMMENT 'MinerU 解析后的 Markdown',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_standard_no_version` (`standard_no`, `version`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标标准实体';

-- 2. gb_clause — 条款实体（层级树，物化路径）
CREATE TABLE IF NOT EXISTS `gb_clause` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `standard_id` VARCHAR(36) NOT NULL COMMENT '所属标准 ID',
    `clause_path` VARCHAR(50) NOT NULL COMMENT '条款路径: 9 / 9.2 / 9.2.3',
    `parent_path` VARCHAR(50) NULL COMMENT '父条款路径',
    `depth` INT NOT NULL COMMENT '深度: 1=章, 2=条, 3=款',
    `title` TEXT NULL COMMENT '条款标题',
    `clause_type` VARCHAR(30) NULL COMMENT 'normative/informative/scope/reference/definition',
    `polarity` VARCHAR(20) DEFAULT 'positive' COMMENT 'positive/negative/exception',
    `exception_of` VARCHAR(50) NULL COMMENT '例外针对的条款路径',
    `requirement_strength` VARCHAR(20) NULL COMMENT 'mandatory(应)/recommended(宜)/permissible(可)',
    `is_scope` TINYINT(1) DEFAULT 0 COMMENT '是否为范围条款(第1章)',
    `is_appendix` TINYINT(1) DEFAULT 0 COMMENT '是否为附录条款',
    `appendix_label` VARCHAR(10) NULL COMMENT '附录编号: A/B/C',
    `text` LONGTEXT NOT NULL COMMENT '条款完整文本',
    `page_no` INT NULL COMMENT '原文页码',
    `confidence` VARCHAR(10) DEFAULT 'high' COMMENT '解析质量: high/medium/low',
    `chunk_id` VARCHAR(100) NULL COMMENT '关联向量库 chunk ID',
    `metadata` JSON NULL COMMENT '条款级动态属性 (LLM 抽取)',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_clause_standard` (`standard_id`),
    INDEX `idx_clause_path` (`standard_id`, `clause_path`),
    INDEX `idx_clause_parent` (`standard_id`, `parent_path`),
    INDEX `idx_clause_type` (`standard_id`, `clause_type`),
    INDEX `idx_clause_polarity` (`standard_id`, `polarity`),
    INDEX `idx_clause_confidence` (`standard_id`, `confidence`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标条款实体(层级树)';

-- 3. gb_parameter — 结构化参数/公式
CREATE TABLE IF NOT EXISTS `gb_parameter` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `standard_id` VARCHAR(36) NOT NULL COMMENT '所属标准 ID',
    `clause_id` VARCHAR(36) NOT NULL COMMENT '所属条款 ID',
    `param_name` VARCHAR(100) NULL COMMENT '参数名称',
    `formula` TEXT NULL COMMENT '公式表达式',
    `param_value` DECIMAL(20,6) NULL COMMENT '参数值',
    `unit` VARCHAR(30) NULL COMMENT '单位: V/A/℃/min',
    `condition_expr` TEXT NULL COMMENT '条件表达式',
    `source_text` TEXT NULL COMMENT '原始文本片段',
    `metadata` JSON NULL COMMENT '扩展属性',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_param_standard` (`standard_id`),
    INDEX `idx_param_clause` (`clause_id`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标参数/公式实体';

-- 4. gb_reference — 统一引用关系（标准内 + 跨标准）
CREATE TABLE IF NOT EXISTS `gb_reference` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `source_standard_id` VARCHAR(36) NOT NULL COMMENT '引用方标准 ID',
    `source_clause_path` VARCHAR(50) NOT NULL COMMENT '引用方条款路径',
    `target_type` VARCHAR(20) NOT NULL COMMENT 'intra=标准内 / inter=跨标准',
    `target_standard_id` VARCHAR(36) NULL COMMENT '目标标准 ID (跨标准时)',
    `target_standard_no` VARCHAR(50) NULL COMMENT '目标标准号',
    `target_clause_path` VARCHAR(50) NULL COMMENT '目标条款路径',
    `ref_type` VARCHAR(30) NULL COMMENT 'normative_reference/prerequisite/informative',
    `ref_text` TEXT NULL COMMENT '引用上下文原文',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_ref_source` (`source_standard_id`, `source_clause_path`),
    INDEX `idx_ref_target` (`target_standard_id`, `target_clause_path`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标引用关系(标准内+跨标准)';

-- 5. gb_term_dict — 术语同义词表
CREATE TABLE IF NOT EXISTS `gb_term_dict` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `standard_id` VARCHAR(36) NULL COMMENT '所属标准 ID, null=全局',
    `canonical_term` VARCHAR(100) NOT NULL COMMENT '规范术语',
    `synonyms` JSON NOT NULL COMMENT '同义词列表 ["电池包","Battery Pack"]',
    `definition` TEXT NULL COMMENT '术语定义(从第3章提取)',
    `domain` VARCHAR(100) NULL COMMENT '领域标签',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标术语同义词表';

-- 6. gb_audit_log — 审计追溯表
CREATE TABLE IF NOT EXISTS `gb_audit_log` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键(雪花ID)',
    `session_id` VARCHAR(50) NULL COMMENT '会话 ID',
    `user_query` TEXT NOT NULL COMMENT '用户查询',
    `extracted_intent` JSON NULL COMMENT '抽取的意图',
    `retrieval_channels` JSON NULL COMMENT '使用的检索通道',
    `retrieved_clauses` JSON NULL COMMENT '检索到的条款',
    `llm_response` LONGTEXT NULL COMMENT 'LLM 回答',
    `cited_sources` JSON NULL COMMENT '引用的来源',
    `tool_calls` JSON NULL COMMENT '工具调用记录',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_audit_session` (`session_id`),
    INDEX `idx_audit_created` (`created_at`)
) ENGINE=InnoDB CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国标审计追溯表';

-- 7. airag_knowledge_doc 增加 parse_status 列
ALTER TABLE `airag_knowledge_doc`
    ADD COLUMN `parse_status` VARCHAR(20) DEFAULT NULL COMMENT '解析状态(GB国标专用): UPLOADED/PARSING/PARSED/CONFIRMED/INDEXING/COMPLETED' AFTER `status`;

-- update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 L1 数据模型建表-----------
```

- [ ] **Step 2: 验证 Flyway 脚本命名规范**

确认文件名 `V3.9.3_0__gb_standard_init.sql` 符合项目 Flyway 命名规范（`V{version}__{description}.sql`），且版本号 `3.9.3_0` 大于现有最新版本 `V3.9.2_1`。

- [ ] **Step 3: Commit**

```bash
git add jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.3_0__gb_standard_init.sql
git commit -m "feat(airag-gb): 添加 GB 国标知识引擎 Flyway 迁移脚本（MySQL DDL）"
```

---

### Task 2: 配置属性类 GbStandardProperties

**Files:**
- Create: `gbstandard/config/GbStandardProperties.java`

**Interfaces:**
- Produces: `GbStandardProperties` Bean，被后续所有组件注入使用
- Key methods: `isEnabled()`, `isUserReviewEnabled()`

- [ ] **Step 1: 创建配置属性类**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 配置属性类 + Kill Switch-----------
package org.jeecg.modules.airag.llm.gbstandard.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GB 国标知识引擎配置属性
 * Kill Switch: jeecg.airag.gb-standard.enabled=false 时所有国标逻辑跳过
 */
@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = GbStandardProperties.PREFIX)
public class GbStandardProperties {
    public static final String PREFIX = "jeecg.airag.gb-standard";

    /** 总开关 */
    private boolean enabled = false;

    /** 用户确认页开关 */
    private boolean userReviewEnabled = true;

    /** 结构解析器配置 */
    private StructureParser structureParser = new StructureParser();

    @Data
    @NoArgsConstructor
    public static class StructureParser {
        /** 结构解析器开关 */
        private boolean enabled = true;
        /** 正则失败时是否用 LLM 补全（Phase 2 启用） */
        private boolean llmFallback = false;
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 配置属性类 + Kill Switch-----------
```

- [ ] **Step 2: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/config/
git commit -m "feat(airag-gb): 添加 GbStandardProperties 配置属性类 + Kill Switch"
```

---

### Task 3: 后端实体类（6 个 Model + 2 个 DTO）

**Files:**
- Create: `gbstandard/model/GbStandard.java`
- Create: `gbstandard/model/GbClause.java`
- Create: `gbstandard/model/GbParameter.java`
- Create: `gbstandard/model/GbReference.java`
- Create: `gbstandard/model/GbTermDict.java`
- Create: `gbstandard/model/GbAuditLog.java`
- Create: `gbstandard/model/GbClauseNode.java` (DTO)
- Create: `gbstandard/model/GbDocStructure.java` (DTO)

**Interfaces:**
- Consumes: 无（纯数据类）
- Produces: 实体类被 Mapper / Service / Controller 使用；DTO 被 GbDocumentStructureParser 和 Controller 使用

> **注意**: 实体连接 MySQL 主库，ID 使用 `IdType.ASSIGN_ID`（雪花算法，String 类型），与现有 AiragKnowledge 等实体一致。JSON 列使用 `JacksonTypeHandler`。

- [ ] **Step 1: 创建 GbStandard 实体**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbStandard 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Schema(description = "国标标准实体")
@Data
@TableName(value = "gb_standard", autoResultMap = true)
public class GbStandard implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "标准号")
    private String standardNo;

    @Schema(description = "版本年份")
    private String version;

    @Schema(description = "标准全称")
    private String fullName;

    @Schema(description = "发布日期")
    private Date publishDate;

    @Schema(description = "实施日期")
    private Date implementationDate;

    @Schema(description = "废止日期")
    private Date withdrawalDate;

    @Schema(description = "状态: current/superseded/withdrawn")
    private String status;

    @Schema(description = "领域标签")
    private String domain;

    @Schema(description = "关联知识库 ID")
    private String knowledgeId;

    @Schema(description = "关联文档 ID")
    private String docId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "替代的旧标准列表")
    private List<String> supersedes;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "规范性引用文件清单")
    private List<String> normativeRefs;

    @Schema(description = "领域属性 Schema (JSON)")
    private String domainSchema;

    @Schema(description = "扩展元数据 (JSON)")
    private String metadata;

    @Schema(description = "解析状态")
    private String parseStatus;

    @Schema(description = "PDF 文件路径")
    private String pdfUrl;

    @Schema(description = "MinerU 解析后的 Markdown")
    private String markdownContent;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbStandard 实体-----------
```

- [ ] **Step 2: 创建 GbClause 实体**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClause 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Schema(description = "国标条款实体")
@Data
@TableName("gb_clause")
public class GbClause implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @Schema(description = "所属标准 ID")
    private String standardId;

    @Schema(description = "条款路径: 9 / 9.2 / 9.2.3")
    private String clausePath;

    @Schema(description = "父条款路径")
    private String parentPath;

    @Schema(description = "深度: 1=章, 2=条, 3=款")
    private Integer depth;

    @Schema(description = "条款标题")
    private String title;

    @Schema(description = "条款类型: normative/informative/scope/reference/definition")
    private String clauseType;

    @Schema(description = "极性: positive/negative/exception")
    private String polarity;

    @Schema(description = "例外针对的条款路径")
    private String exceptionOf;

    @Schema(description = "规范用语强度: mandatory/recommended/permissible")
    private String requirementStrength;

    @Schema(description = "是否为范围条款（第1章）")
    private Boolean isScope;

    @Schema(description = "是否为附录条款")
    private Boolean isAppendix;

    @Schema(description = "附录编号: A/B/C")
    private String appendixLabel;

    @Schema(description = "条款完整文本")
    private String text;

    @Schema(description = "原文页码")
    private Integer pageNo;

    @Schema(description = "解析置信度: high/medium/low")
    private String confidence;

    @Schema(description = "关联向量库 chunk ID")
    private String chunkId;

    @Schema(description = "条款级动态属性 (JSON)")
    private String metadata;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClause 实体-----------
```

- [ ] **Step 3: 创建 GbParameter / GbReference / GbTermDict / GbAuditLog 实体**

```java
// GbParameter.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameter 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Schema(description = "国标参数/公式实体")
@Data
@TableName("gb_parameter")
public class GbParameter implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String standardId;
    private String clauseId;
    @Schema(description = "参数名称")
    private String paramName;
    @Schema(description = "公式表达式")
    private String formula;
    @Schema(description = "参数值")
    private BigDecimal paramValue;
    @Schema(description = "单位")
    private String unit;
    @Schema(description = "条件表达式")
    private String conditionExpr;
    @Schema(description = "原始文本")
    private String sourceText;
    @Schema(description = "扩展属性 (JSON)")
    private String metadata;
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameter 实体-----------
```

```java
// GbReference.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReference 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Schema(description = "国标引用关系实体")
@Data
@TableName("gb_reference")
public class GbReference implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String sourceStandardId;
    @Schema(description = "引用方条款路径")
    private String sourceClausePath;
    @Schema(description = "引用类型: intra=标准内 / inter=跨标准")
    private String targetType;
    private String targetStandardId;
    @Schema(description = "目标标准号（跨标准时）")
    private String targetStandardNo;
    @Schema(description = "目标条款路径")
    private String targetClausePath;
    @Schema(description = "引用关系类型")
    private String refType;
    @Schema(description = "引用上下文原文")
    private String refText;
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReference 实体-----------
```

```java
// GbTermDict.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbTermDict 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Schema(description = "国标术语同义词表")
@Data
@TableName(value = "gb_term_dict", autoResultMap = true)
public class GbTermDict implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String standardId;
    @Schema(description = "规范术语")
    private String canonicalTerm;
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "同义词列表")
    private List<String> synonyms;
    @Schema(description = "术语定义")
    private String definition;
    private String domain;
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbTermDict 实体-----------
```

```java
// GbAuditLog.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbAuditLog 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Schema(description = "国标审计日志")
@Data
@TableName(value = "gb_audit_log", autoResultMap = true)
public class GbAuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String sessionId;
    private String userQuery;
    private String extractedIntent;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> retrievalChannels;
    private String retrievedClauses;
    private String llmResponse;
    private String citedSources;
    private String toolCalls;
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbAuditLog 实体-----------
```

- [ ] **Step 4: 创建 GbClauseNode DTO（解析器输出节点）**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseNode DTO-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * GbDocumentStructureParser 输出的条款树节点（DTO，不直接映射数据库）
 */
@Schema(description = "条款树节点（解析器输出 DTO）")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbClauseNode implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "条款路径: 9 / 9.2 / 9.2.3")
    private String clausePath;

    @Schema(description = "父条款路径")
    private String parentPath;

    @Schema(description = "深度")
    private int depth;

    @Schema(description = "条款标题")
    private String title;

    @Schema(description = "条款文本")
    private String text;

    @Schema(description = "条款类型")
    private String clauseType;

    @Schema(description = "解析置信度: high/medium/low")
    private String confidence;

    @Schema(description = "原文页码")
    private Integer pageNo;

    @Schema(description = "是否为范围条款")
    private boolean isScope;

    @Schema(description = "是否为附录")
    private boolean isAppendix;

    @Schema(description = "附录编号")
    private String appendixLabel;

    @Schema(description = "子条款")
    @Builder.Default
    private List<GbClauseNode> children = new ArrayList<>();
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseNode DTO-----------
```

- [ ] **Step 5: 创建 GbDocStructure DTO（解析器输出根对象）**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbDocStructure DTO-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * GbDocumentStructureParser 输出的文档结构（DTO）
 */
@Schema(description = "国标文档结构（解析器输出 DTO）")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbDocStructure implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "标准号")
    private String standardNo;

    @Schema(description = "版本年份")
    private String version;

    @Schema(description = "标准全称")
    private String fullName;

    @Schema(description = "发布日期")
    private String publishDate;

    @Schema(description = "实施日期")
    private String implementationDate;

    @Schema(description = "替代的旧标准")
    @Builder.Default
    private List<String> supersedes = new ArrayList<>();

    @Schema(description = "规范性引用文件清单")
    @Builder.Default
    private List<String> normativeRefs = new ArrayList<>();

    @Schema(description = "条款层级树（根节点列表）")
    @Builder.Default
    private List<GbClauseNode> clauses = new ArrayList<>();

    @Schema(description = "条款总数")
    private int totalClauseCount;

    @Schema(description = "高置信度条款数")
    private int highConfidenceCount;

    @Schema(description = "中置信度条款数")
    private int mediumConfidenceCount;

    @Schema(description = "低置信度条款数")
    private int lowConfidenceCount;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbDocStructure DTO-----------
```

- [ ] **Step 6: 编译验证**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd compile -pl jeecg-boot-module/jeecg-boot-module-airag -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/model/
git commit -m "feat(airag-gb): 添加 GB 国标实体类 + DTO（6 Model + 2 DTO）"
```

---

### Task 4: Mapper 层（5 个 Mapper 接口）

**Files:**
- Create: `gbstandard/mapper/GbStandardMapper.java`
- Create: `gbstandard/mapper/GbClauseMapper.java`
- Create: `gbstandard/mapper/GbParameterMapper.java`
- Create: `gbstandard/mapper/GbReferenceMapper.java`
- Create: `gbstandard/mapper/GbTermDictMapper.java`

**Interfaces:**
- Consumes: Task 3 的实体类
- Produces: Mapper 接口被 Controller 和后续 Service 使用

- [ ] **Step 1: 创建 5 个 Mapper 接口**

```java
// GbStandardMapper.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;

public interface GbStandardMapper extends BaseMapper<GbStandard> {
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
```

```java
// GbClauseMapper.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;

public interface GbClauseMapper extends BaseMapper<GbClause> {
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
```

```java
// GbParameterMapper.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;

public interface GbParameterMapper extends BaseMapper<GbParameter> {
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
```

```java
// GbReferenceMapper.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;

public interface GbReferenceMapper extends BaseMapper<GbReference> {
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
```

```java
// GbTermDictMapper.java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbTermDict;

public interface GbTermDictMapper extends BaseMapper<GbTermDict> {
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Mapper-----------
```

- [ ] **Step 2: 确保 MapperScan 覆盖新包路径**

检查 JeecgSystemApplication 或 MybatisPlusConfig 上的 `@MapperScan` 注解。当前 airag 模块的 Mapper 在 `org.jeecg.modules.airag.llm.mapper` 包下，新增的在 `org.jeecg.modules.airag.llm.gbstandard.mapper`。如果 `@MapperScan` 扫描的是 `org.jeecg.modules` 或更宽的范围，则自动覆盖。否则需要添加。

Read 当前 `@MapperScan` 配置确认。如果扫描路径不包含新包，在 MybatisPlusSaasConfig 中添加。

- [ ] **Step 3: 编译验证**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd compile -pl jeecg-boot-module/jeecg-boot-module-airag -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/mapper/
git commit -m "feat(airag-gb): 添加 GB 国标 Mapper 接口（5个）"
```

---

### Task 5: GbDocumentStructureParser — 文档层级树解析器

**Files:**
- Create: `gbstandard/ingestion/GbDocumentStructureParser.java`
- Test: `src/test/java/.../gbstandard/ingestion/GbDocumentStructureParserTest.java`

**Interfaces:**
- Consumes: MinerU 解析后的 Markdown 文本（String）
- Produces: `GbDocStructure`（包含标准号、版本、条款层级树、置信度统计）
- Key method: `GbDocStructure parse(String markdown)`

> 这是 Phase 1 最核心的组件。解析策略按优先级：正则匹配国标编号 → Markdown 标题层级 → OCR 退化修复。

- [ ] **Step 1: 写测试**

用 GB 31241-2022 的前 100 行 Markdown 作为测试输入，验证解析器能正确识别标准号、版本、章条款层级。

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 解析器单测-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GbDocumentStructureParserTest {

    private final GbDocumentStructureParser parser = new GbDocumentStructureParser();

    @Test
    void parseGb31241_extractsStandardNoAndVersion() {
        String md = "# 便携式电子产品用锂离子电池和电池组 安全技术规范\n\n"
                + "2022-12-29 发布\n\n2024-01-01 实施\n\n"
                + "# 1 范围\n\n本文件规定了...\n\n"
                + "# 2 规范性引用文件\n\n下列文件...\n\n"
                + "# 3 术语和定义\n\n# 3.1\n\n# 锂离子电池\n\n依靠锂离子...\n";

        GbDocStructure result = parser.parse(md);

        assertNotNull(result);
        assertEquals("GB 31241", result.getStandardNo());
        assertEquals("2022", result.getVersion());
    }

    @Test
    void parseGb31241_extractsClauseHierarchy() {
        String md = "# 1 范围\n\n本文件规定了...\n\n"
                + "# 4 试验条件\n\n# 4.1 试验的适用性\n\n内容...\n\n"
                + "# 4.2 试验的环境条件\n\n内容...\n\n"
                + "# 5 一般安全要求\n\n# 5.1 一般安全性的考虑\n\n内容...\n";

        GbDocStructure result = parser.parse(md);

        assertNotNull(result);
        assertTrue(result.getClauses().size() >= 3, "应识别出至少3个章级条款");

        // 验证 4.1 和 4.2 是第4章的子条款
        GbClauseNode chapter4 = result.getClauses().stream()
                .filter(c -> "4".equals(c.getClausePath()))
                .findFirst().orElse(null);
        assertNotNull(chapter4, "应识别出第4章");
        assertEquals(2, chapter4.getChildren().size(), "第4章应有2个子条款");
        assertEquals("4.1", chapter4.getChildren().get(0).getClausePath());
    }

    @Test
    void parseGb31241_marksScopeClause() {
        String md = "# 1 范围\n\n本文件规定了便携式电子产品用锂离子电池...\n\n"
                + "# 2 规范性引用文件\n\n下列文件...\n";

        GbDocStructure result = parser.parse(md);

        GbClauseNode scope = result.getClauses().stream()
                .filter(GbClauseNode::isScope)
                .findFirst().orElse(null);
        assertNotNull(scope, "第1章应标记为范围条款");
        assertEquals("1", scope.getClausePath());
    }

    @Test
    void parseAppendix_marksAppendixClauses() {
        String md = "# 9 电池组电安全试验\n\n内容...\n\n"
                + "# 附录 A（资料性） 工作范围示例\n\n附录内容...\n\n"
                + "# 附录 B（规范性） 试验顺序\n\n附录内容...\n";

        GbDocStructure result = parser.parse(md);

        long appendixCount = result.getClauses().stream()
                .filter(GbClauseNode::isAppendix)
                .count();
        assertEquals(2, appendixCount, "应识别出2个附录");
    }

    @Test
    void parseOcrDegraded_assignsLowConfidence() {
        // GB 38031 风格的退化文本：条款号缺少小数点
        String md = "# 5 安全要求\n\n"
                + "511 电池单体按照进行过放电试验应不起火不爆炸\n\n"
                + "512 电池单体按照进行过充电试验应不起火不爆炸\n";

        GbDocStructure result = parser.parse(md);

        assertNotNull(result);
        // "511" 和 "512" 无法被正则正确匹配，应标记为低置信度
        assertTrue(result.getLowConfidenceCount() > 0
                || result.getMediumConfidenceCount() > 0,
                "OCR 退化文本应有非高置信度条款");
    }

    @Test
    void parseEmptyMarkdown_returnsEmptyStructure() {
        GbDocStructure result = parser.parse("");
        assertNotNull(result);
        assertEquals(0, result.getTotalClauseCount());
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 解析器单测-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd test -pl jeecg-boot-module/jeecg-boot-module-airag -Dtest=GbDocumentStructureParserTest -DskipTests=false`
Expected: 编译失败（GbDocumentStructureParser 类不存在）

- [ ] **Step 3: 实现 GbDocumentStructureParser**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国标文档层级树解析器
 *
 * 解析策略（优先级从高到低）：
 * 1. 正则匹配国标编号规范：^(\d+)(\.\d+)*\s+(.+)$
 * 2. Markdown 标题层级：# / ## / ###
 * 3. OCR 退化修复：检测 "31" 可能是 "3.1" 的情况
 *
 * 特殊处理：
 * - 第1章 范围 → isScope=true
 * - 第2章 规范性引用文件 → 提取 normativeRefs
 * - 附录 A/B/C → isAppendix=true
 */
@Slf4j
public class GbDocumentStructureParser {

    /**
     * 匹配标准条款编号: "9.2.3 过压充电" 或 "9 电池组电安全试验"
     * 不匹配 "31" (缺少小数点的退化编号)
     */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)+)\\s+(.+)$"
    );

    /** 匹配章级标题: "9 电池组电安全试验" */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^(\\d{1,2})\\s+(.+)$"
    );

    /** 匹配 Markdown 标题: "# 9.2 过压充电" 或 "## 6.2 数据采集" */
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile(
            "^(#{1,4})\\s+(?:(\\d+(?:\\.\\d+)*)\\s+)?(.+)$"
    );

    /** 匹配附录标题: "附录 A（资料性） 工作范围示例" */
    private static final Pattern APPENDIX_PATTERN = Pattern.compile(
            "附录\\s*([A-Z])\\s*[（(](.*?)[）)]\\s*(.*)"
    );

    /** 匹配标准号: "GB 31241" / "GB/T 31467.3" / "GB 38031-2025" */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile(
            "(GB[/T]?\\s*\\d+(?:\\.\\d+)?)(?:\\s*[-—]\\s*(\\d{4}))?"
    );

    /** 匹配日期: "2022-12-29 发布" / "2024-01-01 实施" */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s*(发布|实施)"
    );

    /** 匹配跨标准引用: "GB/T 2423.5" */
    private static final Pattern REF_STANDARD_PATTERN = Pattern.compile(
            "(GB[/T]?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-—]\\s*\\d{4})?)"
    );

    /**
     * 解析国标 Markdown 为文档结构
     */
    public GbDocStructure parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return GbDocStructure.builder().build();
        }

        String[] lines = markdown.split("\n");
        GbDocStructure.GbDocStructureBuilder builder = GbDocStructure.builder();

        // Phase 1: 提取标准基本信息（标准号、版本、日期）
        extractStandardInfo(lines, builder);

        // Phase 2: 解析条款层级树
        List<GbClauseNode> allClauses = parseClauses(lines);

        // Phase 3: 构建父子关系
        List<GbClauseNode> roots = buildHierarchy(allClauses);

        // Phase 4: 统计置信度
        int[] stats = countConfidence(allClauses);

        builder.clauses(roots)
                .totalClauseCount(allClauses.size())
                .highConfidenceCount(stats[0])
                .mediumConfidenceCount(stats[1])
                .lowConfidenceCount(stats[2]);

        return builder.build();
    }

    /**
     * Phase 1: 从文档头部提取标准号、版本、日期
     */
    private void extractStandardInfo(String[] lines, GbDocStructure.GbDocStructureBuilder builder) {
        // 扫描前 20 行（标准基本信息在文档头部）
        int scanLimit = Math.min(lines.length, 20);
        List<String> headerText = new ArrayList<>();
        for (int i = 0; i < scanLimit; i++) {
            headerText.add(lines[i]);
        }
        String header = String.join("\n", headerText);

        // 提取标准号
        Matcher stdMatcher = STANDARD_NO_PATTERN.matcher(header);
        if (stdMatcher.find()) {
            String stdNo = stdMatcher.group(1).replaceAll("\\s+", " ").trim();
            builder.standardNo(stdNo);
            if (stdMatcher.group(2) != null) {
                builder.version(stdMatcher.group(2));
            }
        }

        // 提取日期
        Matcher dateMatcher = DATE_PATTERN.matcher(header);
        while (dateMatcher.find()) {
            String dateStr = dateMatcher.group(1);
            String type = dateMatcher.group(2);
            if ("发布".equals(type)) {
                builder.publishDate(dateStr);
            } else if ("实施".equals(type)) {
                builder.implementationDate(dateStr);
            }
        }

        // 提取标准全称（通常是第一个 # 标题）
        for (int i = 0; i < scanLimit; i++) {
            String line = lines[i].trim();
            if (line.startsWith("# ") && !line.matches("^#+\\s*\\d+.*")
                    && !line.contains("目次") && !line.contains("前言")
                    && !line.contains("引言") && line.length() > 5) {
                String title = line.replaceFirst("^#+\\s*", "").trim();
                // 跳过英文名（通常包含 "—" 或英文单词）
                if (!title.matches(".*[a-zA-Z]{3,}.*") || title.length() > 30) {
                    builder.fullName(title);
                    break;
                }
            }
        }
    }

    /**
     * Phase 2: 逐行扫描，提取所有条款
     */
    private List<GbClauseNode> parseClauses(String[] lines) {
        List<GbClauseNode> clauses = new ArrayList<>();
        GbClauseNode currentClause = null;
        StringBuilder currentText = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // 尝试匹配附录标题
            Matcher appendixMatcher = APPENDIX_PATTERN.matcher(line.replaceFirst("^#+\\s*", ""));
            if (appendixMatcher.find() || line.matches("^#+\\s*附录\\s*[A-Z].*")) {
                // 先保存当前条款
                if (currentClause != null) {
                    currentClause.setText(currentText.toString().trim());
                    clauses.add(currentClause);
                    currentText.setLength(0);
                }

                String appendixTitle = line.replaceFirst("^#+\\s*", "");
                Matcher am = APPENDIX_PATTERN.matcher(appendixTitle);
                String label = "";
                String title = appendixTitle;
                if (am.find()) {
                    label = am.group(1);
                    title = am.group(3) != null ? am.group(3).trim() : "";
                }

                currentClause = GbClauseNode.builder()
                        .clausePath("附录 " + label)
                        .parentPath(null)
                        .depth(1)
                        .title(title)
                        .clauseType(determineAppendixType(appendixTitle))
                        .confidence("high")
                        .isAppendix(true)
                        .appendixLabel(label)
                        .pageNo(null)
                        .build();
                continue;
            }

            // 尝试匹配 Markdown 标题 + 条款号
            Matcher headingMatcher = MD_HEADING_PATTERN.matcher(line);
            if (headingMatcher.find()) {
                String headingLevel = headingMatcher.group(1);
                String clauseNum = headingMatcher.group(2);
                String headingTitle = headingMatcher.group(3);

                if (clauseNum != null && isValidClauseNumber(clauseNum)) {
                    // 先保存当前条款
                    if (currentClause != null) {
                        currentClause.setText(currentText.toString().trim());
                        clauses.add(currentClause);
                        currentText.setLength(0);
                    }

                    int depth = clauseNum.split("\\.").length;
                    String parentPath = depth > 1
                            ? clauseNum.substring(0, clauseNum.lastIndexOf('.'))
                            : null;

                    currentClause = GbClauseNode.builder()
                            .clausePath(clauseNum)
                            .parentPath(parentPath)
                            .depth(depth)
                            .title(headingTitle.trim())
                            .clauseType(determineClauseType(clauseNum, headingTitle))
                            .confidence("high")
                            .isScope("1".equals(clauseNum))
                            .isAppendix(false)
                            .build();
                    continue;
                }

                // 有标题但没有条款号（如 "# 前言"、"# 目次"）
                if (headingTitle != null && isSkippableHeading(headingTitle)) {
                    if (currentClause != null) {
                        currentClause.setText(currentText.toString().trim());
                        clauses.add(currentClause);
                        currentClause = null;
                        currentText.setLength(0);
                    }
                    continue;
                }
            }

            // 尝试匹配纯文本条款号（无 # 前缀）
            Matcher clauseMatcher = CLAUSE_PATTERN.matcher(line);
            if (clauseMatcher.find()) {
                String clauseNum = clauseMatcher.group(1);
                String clauseTitle = clauseMatcher.group(2);

                if (currentClause != null) {
                    currentClause.setText(currentText.toString().trim());
                    clauses.add(currentClause);
                    currentText.setLength(0);
                }

                int depth = clauseNum.split("\\.").length;
                String parentPath = depth > 1
                        ? clauseNum.substring(0, clauseNum.lastIndexOf('.'))
                        : null;

                currentClause = GbClauseNode.builder()
                        .clausePath(clauseNum)
                        .parentPath(parentPath)
                        .depth(depth)
                        .title(clauseTitle.trim())
                        .clauseType(determineClauseType(clauseNum, clauseTitle))
                        .confidence("high")
                        .isScope("1".equals(clauseNum))
                        .build();
                continue;
            }

            // 尝试匹配章级标题（纯数字 + 标题，如 "9 电池组电安全试验"）
            Matcher chapterMatcher = CHAPTER_PATTERN.matcher(line.replaceFirst("^#+\\s*", ""));
            if (chapterMatcher.find() && line.startsWith("#")) {
                String chapterNum = chapterMatcher.group(1);
                String chapterTitle = chapterMatcher.group(2);

                if (currentClause != null) {
                    currentClause.setText(currentText.toString().trim());
                    clauses.add(currentClause);
                    currentText.setLength(0);
                }

                currentClause = GbClauseNode.builder()
                        .clausePath(chapterNum)
                        .parentPath(null)
                        .depth(1)
                        .title(chapterTitle.trim())
                        .clauseType(determineClauseType(chapterNum, chapterTitle))
                        .confidence("high")
                        .isScope("1".equals(chapterNum))
                        .build();
                continue;
            }

            // 普通文本行：追加到当前条款
            if (currentClause != null && !line.isEmpty()) {
                currentText.append(line).append("\n");
            }
        }

        // 保存最后一个条款
        if (currentClause != null) {
            currentClause.setText(currentText.toString().trim());
            clauses.add(currentClause);
        }

        // Phase 3: OCR 退化检测 — 对置信度重新评估
        detectOcrDegradation(clauses);

        return clauses;
    }

    /**
     * Phase 3: 构建父子关系（flat list → tree）
     */
    private List<GbClauseNode> buildHierarchy(List<GbClauseNode> allClauses) {
        Map<String, GbClauseNode> byPath = new LinkedHashMap<>();
        for (GbClauseNode c : allClauses) {
            byPath.put(c.getClausePath(), c);
        }

        List<GbClauseNode> roots = new ArrayList<>();
        for (GbClauseNode c : allClauses) {
            if (c.getParentPath() != null && byPath.containsKey(c.getParentPath())) {
                byPath.get(c.getParentPath()).getChildren().add(c);
            } else {
                roots.add(c);
            }
        }
        return roots;
    }

    /** 验证是否为合法条款编号（至少包含一个小数点或为1-2位纯数字） */
    private boolean isValidClauseNumber(String num) {
        return num.matches("\\d{1,2}") || num.matches("\\d+\\.\\d+(?:\\.\\d+)*");
    }

    /** 根据条款号/标题推断条款类型 */
    private String determineClauseType(String clauseNum, String title) {
        if ("1".equals(clauseNum) || title.contains("范围")) return "scope";
        if ("2".equals(clauseNum) || title.contains("规范性引用")) return "reference";
        if ("3".equals(clauseNum) || title.contains("术语")) return "definition";
        if (title.contains("试验方法") || title.contains("试验")) return "test_method";
        return "normative";
    }

    private String determineAppendixType(String title) {
        if (title.contains("规范性")) return "normative_appendix";
        return "informative_appendix";
    }

    private boolean isSkippableHeading(String title) {
        return title.contains("目次") || title.contains("前言") || title.contains("引言")
                || title.contains("参考文献");
    }

    /**
     * OCR 退化检测：如果文档中大量条款无法被正则匹配，标记置信度
     */
    private void detectOcrDegradation(List<GbClauseNode> clauses) {
        if (clauses.isEmpty()) return;

        // 检查是否存在连续数字但没有小数点的疑似退化条款号
        // 例如 "511" 可能是 "5.1.1" 的退化
        int totalLines = clauses.size();
        long highConf = clauses.stream().filter(c -> "high".equals(c.getConfidence())).count();

        // 如果高置信度条款占比低于 50%，可能存在严重 OCR 退化
        if (totalLines > 5 && (double) highConf / totalLines < 0.5) {
            log.warn("[GB解析] 检测到可能的 OCR 退化: {}/{} 条款为高置信度", highConf, totalLines);
        }
    }

    /** 统计置信度分布: [high, medium, low] */
    private int[] countConfidence(List<GbClauseNode> clauses) {
        int high = 0, medium = 0, low = 0;
        for (GbClauseNode c : clauses) {
            switch (c.getConfidence()) {
                case "high" -> high++;
                case "medium" -> medium++;
                case "low" -> low++;
            }
        }
        return new int[]{high, medium, low};
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
```

- [ ] **Step 4: 运行测试确认通过**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd test -pl jeecg-boot-module/jeecg-boot-module-airag -Dtest=GbDocumentStructureParserTest -DskipTests=false`
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/
git add jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/
git commit -m "feat(airag-gb): 实现 GbDocumentStructureParser 文档层级树解析器 + 单测"
```

---

### Task 6: AiragKnowledgeDoc 增加 parseStatus 字段

**Files:**
- Modify: `llm/entity/AiragKnowledgeDoc.java` (新增 parseStatus 字段)
- Modify: `llm/consts/LLMConsts.java` (新增状态常量)

**Interfaces:**
- Consumes: 无
- Produces: `parseStatus` 字段被 GbStandardController 和前端使用
- 状态枚举: `UPLOADED` → `PARSING` → `PARSED` → `CONFIRMED` → `INDEXING` → `COMPLETED`

- [ ] **Step 1: 在 LLMConsts 中添加状态常量**

在 `LLMConsts.java` 文件末尾（`}` 之前）添加：

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档解析状态常量-----------
    /** 文档解析状态: 已上传 */
    public static final String PARSE_STATUS_UPLOADED = "UPLOADED";
    /** 文档解析状态: 解析中 */
    public static final String PARSE_STATUS_PARSING = "PARSING";
    /** 文档解析状态: 已解析（等待用户确认） */
    public static final String PARSE_STATUS_PARSED = "PARSED";
    /** 文档解析状态: 用户已确认 */
    public static final String PARSE_STATUS_CONFIRMED = "CONFIRMED";
    /** 文档解析状态: 索引中 */
    public static final String PARSE_STATUS_INDEXING = "INDEXING";
    /** 文档解析状态: 完成 */
    public static final String PARSE_STATUS_COMPLETED = "COMPLETED";
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档解析状态常量-----------
```

- [ ] **Step 2: 在 AiragKnowledgeDoc 中添加 parseStatus 字段**

在 `AiragKnowledgeDoc.java` 的 `status` 字段后添加：

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档解析状态字段-----------
    /**
     * 文档解析状态（GB国标知识库专用）
     * UPLOADED → PARSING → PARSED → CONFIRMED → INDEXING → COMPLETED
     * 非国标知识库此字段为 null
     */
    @Excel(name = "解析状态", width = 15)
    @Schema(description = "解析状态: UPLOADED/PARSING/PARSED/CONFIRMED/INDEXING/COMPLETED")
    private String parseStatus;
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档解析状态字段-----------
```

- [ ] **Step 3: 编译验证**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd compile -pl jeecg-boot-module/jeecg-boot-module-airag -am -q`
Expected: BUILD SUCCESS

> 注意: `parse_status` 列已在 Task 1 的 Flyway 迁移脚本中添加（ALTER TABLE airag_knowledge_doc ADD COLUMN parse_status），无需重复操作。

- [ ] **Step 4: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/entity/AiragKnowledgeDoc.java
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/consts/LLMConsts.java
git commit -m "feat(airag-gb): AiragKnowledgeDoc 新增 parseStatus 字段 + 状态常量"
```

---

### Task 7: GbStandardController — Preview/Confirm API

**Files:**
- Create: `gbstandard/controller/GbStandardController.java`

**Interfaces:**
- Consumes: GbStandardProperties (Task 2), GbStandardMapper/GbClauseMapper (Task 4), GbDocumentStructureParser (Task 5), AiragKnowledgeDoc.parseStatus (Task 6)
- Produces: 3 个 REST API 端点

API 设计:
- `POST /airag/gb-standard/preview` — 上传后触发解析，返回 GbDocStructure
- `PUT /airag/gb-standard/{docId}/structure` — 保存用户修正后的结构
- `POST /airag/gb-standard/{docId}/confirm` — 确认并触发后续管线

- [ ] **Step 1: 创建 Controller**

```java
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Preview/Confirm API-----------
package org.jeecg.modules.airag.llm.gbstandard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.GbDocumentStructureParser;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbClauseMapper;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeDocMapper;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@Tag(name = "GB国标知识引擎-预览确认")
@RestController
@RequestMapping("/airag/gb-standard")
@Slf4j
public class GbStandardController {

    @Autowired
    private GbStandardProperties gbStandardProperties;

    @Autowired
    private GbStandardMapper gbStandardMapper;

    @Autowired
    private GbClauseMapper gbClauseMapper;

    @Autowired
    private AiragKnowledgeDocMapper airagKnowledgeDocMapper;

    private final GbDocumentStructureParser structureParser = new GbDocumentStructureParser();

    /**
     * 触发国标文档结构解析，返回预览数据
     *
     * @param docId 知识库文档 ID
     * @return 解析后的文档结构（条款层级树 + 标准信息 + 置信度统计）
     */
    @Operation(summary = "预览国标解析结果")
    @PostMapping("/preview")
    public Result<GbDocStructure> preview(@RequestParam String docId) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用，请设置 jeecg.airag.gb-standard.enabled=true");
        }

        // 1. 查文档
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }

        // 2. 获取 Markdown 内容
        String markdown = doc.getContent();
        if (markdown == null || markdown.isBlank()) {
            return Result.error("文档内容为空，请先上传并解析文档");
        }

        // 3. 更新状态为 PARSING
        doc.setParseStatus(LLMConsts.PARSE_STATUS_PARSING);
        airagKnowledgeDocMapper.updateById(doc);

        try {
            // 4. 执行结构解析
            GbDocStructure structure = structureParser.parse(markdown);

            // 5. 创建或更新 gb_standard 记录
            GbStandard existing = gbStandardMapper.selectOne(
                    new LambdaQueryWrapper<GbStandard>()
                            .eq(GbStandard::getDocId, docId)
                            .last("LIMIT 1"));

            if (existing != null) {
                existing.setStandardNo(structure.getStandardNo());
                existing.setVersion(structure.getVersion());
                existing.setFullName(structure.getFullName());
                existing.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
                existing.setMarkdownContent(markdown);
                gbStandardMapper.updateById(existing);
            } else {
                GbStandard gbStandard = new GbStandard();
                gbStandard.setStandardNo(structure.getStandardNo());
                gbStandard.setVersion(structure.getVersion());
                gbStandard.setFullName(structure.getFullName());
                gbStandard.setKnowledgeId(doc.getKnowledgeId());
                gbStandard.setDocId(docId);
                gbStandard.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
                gbStandard.setMarkdownContent(markdown);
                gbStandard.setStatus("current");
                gbStandard.setCreatedAt(new Date());
                gbStandardMapper.insert(gbStandard);
            }

            // 6. 更新文档状态为 PARSED
            doc.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
            airagKnowledgeDocMapper.updateById(doc);

            return Result.OK(structure);

        } catch (Exception e) {
            log.error("[GB解析] 结构解析失败, docId={}: {}", docId, e.getMessage(), e);
            doc.setParseStatus(LLMConsts.PARSE_STATUS_UPLOADED);
            airagKnowledgeDocMapper.updateById(doc);
            return Result.error("结构解析失败: " + e.getMessage());
        }
    }

    /**
     * 保存用户修正后的文档结构
     *
     * @param docId     文档 ID
     * @param structure 用户修正后的结构
     * @return 保存结果
     */
    @Operation(summary = "保存用户修正的国标结构")
    @PutMapping("/{docId}/structure")
    public Result<String> saveStructure(@PathVariable String docId,
                                         @RequestBody GbDocStructure structure) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用");
        }

        // 更新 gb_standard 记录
        GbStandard existing = gbStandardMapper.selectOne(
                new LambdaQueryWrapper<GbStandard>()
                        .eq(GbStandard::getDocId, docId)
                        .last("LIMIT 1"));
        if (existing == null) {
            return Result.error("未找到对应的国标记录，请先执行预览");
        }

        existing.setStandardNo(structure.getStandardNo());
        existing.setVersion(structure.getVersion());
        existing.setFullName(structure.getFullName());
        gbStandardMapper.updateById(existing);

        // TODO Phase 2: 保存修正后的条款树到 gb_clause 表
        return Result.OK("结构已保存");
    }

    /**
     * 确认解析结果，触发后续管线（Phase 2 实现 LLM 抽取）
     *
     * @param docId 文档 ID
     * @return 确认结果
     */
    @Operation(summary = "确认国标解析结果")
    @PostMapping("/{docId}/confirm")
    public Result<String> confirm(@PathVariable String docId) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用");
        }

        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }

        // 验证状态
        if (!LLMConsts.PARSE_STATUS_PARSED.equals(doc.getParseStatus())) {
            return Result.error("文档状态不正确，当前状态: " + doc.getParseStatus()
                    + "，需要先执行预览");
        }

        // 更新状态为 CONFIRMED
        doc.setParseStatus(LLMConsts.PARSE_STATUS_CONFIRMED);
        airagKnowledgeDocMapper.updateById(doc);

        // Phase 2 将在此处触发 LLM metadata 抽取管线
        // Phase 1 仅更新状态，后续管线暂为空操作
        log.info("[GB知识引擎] 文档已确认, docId={}, 后续管线将在 Phase 2 实现", docId);

        // 直接标记为 COMPLETED（Phase 1 无后续管线）
        doc.setParseStatus(LLMConsts.PARSE_STATUS_COMPLETED);
        airagKnowledgeDocMapper.updateById(doc);

        return Result.OK("确认成功");
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Preview/Confirm API-----------
```

- [ ] **Step 2: 编译验证**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd compile -pl jeecg-boot-module/jeecg-boot-module-airag -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/controller/
git commit -m "feat(airag-gb): 添加 GbStandardController Preview/Confirm API"
```

---

### Task 8: 前端 API 文件 + GbStandardPreview.vue

**Files:**
- Create: `jeecgboot-vue3/src/views/super/airag/aiknowledge/GbStandardPreview.api.ts`
- Create: `jeecgboot-vue3/src/views/super/airag/aiknowledge/components/GbStandardPreview.vue`

**Interfaces:**
- Consumes: Task 7 的 3 个 REST API
- Produces: 前端组件，在知识库文档列表中添加"国标预览"入口

- [ ] **Step 1: 创建前端 API 文件**

```typescript
//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 前端 API-----------
import { defHttp } from '/@/utils/http/axios';

enum Api {
  preview = '/airag/gb-standard/preview',
  saveStructure = '/airag/gb-standard',
  confirm = '/airag/gb-standard',
}

/** 触发国标文档结构解析 */
export const previewGbStandard = (docId: string) => {
  return defHttp.post({ url: Api.preview, params: { docId } }, { isTransformResponse: false });
};

/** 保存用户修正后的结构 */
export const saveGbStructure = (docId: string, structure: any) => {
  return defHttp.put({ url: `${Api.saveStructure}/${docId}/structure`, params: structure }, { isTransformResponse: false });
};

/** 确认解析结果 */
export const confirmGbStandard = (docId: string) => {
  return defHttp.post({ url: `${Api.confirm}/${docId}/confirm` }, { isTransformResponse: false });
};
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 前端 API-----------
```

- [ ] **Step 2: 创建 GbStandardPreview.vue 组件**

```vue
<!--update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 国标解析确认页----------- -->
<template>
  <BasicModal
    v-bind="$attrs"
    @register="registerModal"
    :title="'国标解析确认 - ' + (structure?.standardNo || '')"
    :width="1400"
    :bodyStyle="{ height: 'calc(100vh - 200px)', padding: '0' }"
    defaultFullscreen
    :footer="null"
  >
    <a-spin :spinning="loading" tip="正在解析国标文档结构...">
      <div class="gb-preview-container">
        <!-- 左侧：PDF 原文 -->
        <div class="gb-preview-left">
          <div class="gb-preview-header">
            <span>📄 PDF 原文</span>
            <div class="gb-preview-page-nav">
              <a-button size="small" @click="prevPage" :disabled="currentPage <= 1">◀</a-button>
              <span>{{ currentPage }} / {{ totalPages }}</span>
              <a-button size="small" @click="nextPage" :disabled="currentPage >= totalPages">▶</a-button>
            </div>
          </div>
          <div class="gb-preview-pdf-area" ref="pdfContainer">
            <div v-if="pdfUrl" class="gb-preview-pdf-placeholder">
              <!-- PDF.js 渲染区域 -->
              <canvas ref="pdfCanvas"></canvas>
              <p style="text-align: center; color: #999; margin-top: 20px;">
                PDF 渲染区域（PDF.js 集成将在后续完善）<br/>
                当前 PDF 路径: {{ pdfUrl }}
              </p>
            </div>
            <div v-else class="gb-preview-no-pdf">
              <a-empty description="暂无 PDF 文件" />
            </div>
          </div>
        </div>

        <!-- 右侧：解析结果 -->
        <div class="gb-preview-right">
          <!-- 标准基本信息 -->
          <div class="gb-preview-header">
            <span>📋 解析结果</span>
            <a-tag v-if="overallConfidence" :color="overallConfidenceColor">
              整体质量: {{ overallConfidence }}
            </a-tag>
          </div>

          <div class="gb-preview-info" v-if="structure">
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item label="标准号">{{ structure.standardNo || '-' }}</a-descriptions-item>
              <a-descriptions-item label="版本">{{ structure.version || '-' }}</a-descriptions-item>
              <a-descriptions-item label="全称" :span="2">{{ structure.fullName || '-' }}</a-descriptions-item>
              <a-descriptions-item label="条款总数">{{ structure.totalClauseCount }}</a-descriptions-item>
              <a-descriptions-item label="置信度">
                🟢 {{ structure.highConfidenceCount }}
                🟡 {{ structure.mediumConfidenceCount }}
                🔴 {{ structure.lowConfidenceCount }}
              </a-descriptions-item>
            </a-descriptions>
          </div>

          <!-- 条款树 -->
          <div class="gb-preview-clause-tree" v-if="structure?.clauses?.length">
            <a-tree
              :tree-data="treeData"
              :selectedKeys="selectedKeys"
              @select="onClauseSelect"
              showLine
              defaultExpandAll
            >
              <template #title="{ clausePath, title, confidence }">
                <span>
                  <span class="clause-path">{{ clausePath }}</span>
                  <span class="clause-title">{{ title }}</span>
                  <span class="confidence-badge" :class="'confidence-' + confidence">
                    {{ confidence === 'high' ? '🟢' : confidence === 'medium' ? '🟡' : '🔴' }}
                  </span>
                </span>
              </template>
            </a-tree>
          </div>

          <!-- 选中条款详情 -->
          <div class="gb-preview-clause-detail" v-if="selectedClause">
            <a-divider />
            <h4>条款 {{ selectedClause.clausePath }} - {{ selectedClause.title }}</h4>
            <a-tag :color="selectedClause.confidence === 'high' ? 'green' : selectedClause.confidence === 'medium' ? 'orange' : 'red'">
              置信度: {{ selectedClause.confidence }}
            </a-tag>
            <a-tag v-if="selectedClause.isScope" color="blue">范围条款</a-tag>
            <a-tag v-if="selectedClause.isAppendix" color="purple">附录 {{ selectedClause.appendixLabel }}</a-tag>
            <div class="clause-text">{{ selectedClause.text }}</div>
          </div>
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="gb-preview-footer">
        <a-space>
          <a-button type="primary" @click="handleConfirm" :loading="confirmLoading">
            ✅ 确认并入库
          </a-button>
          <a-button @click="handleReparse" :loading="loading">
            🔄 重新解析
          </a-button>
          <a-button @click="closeModal">❌ 取消</a-button>
        </a-space>
      </div>
    </a-spin>
  </BasicModal>
</template>

<script lang="ts" setup>
  import { ref, computed, watch } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { previewGbStandard, confirmGbStandard } from '../GbStandardPreview.api';
  import { useMessage } from '/@/hooks/web/useMessage';

  const { createMessage } = useMessage();

  const props = defineProps({
    docId: { type: String, default: '' },
    pdfUrl: { type: String, default: '' },
  });

  const emit = defineEmits(['confirmed']);

  const [registerModal, { closeModal }] = useModalInner();

  const loading = ref(false);
  const confirmLoading = ref(false);
  const structure = ref<any>(null);
  const selectedKeys = ref<string[]>([]);
  const selectedClause = ref<any>(null);
  const currentPage = ref(1);
  const totalPages = ref(1);

  // 将条款树转换为 Ant Design Tree 数据
  const treeData = computed(() => {
    if (!structure.value?.clauses) return [];
    return structure.value.clauses.map(convertToTreeNode);
  });

  const overallConfidence = computed(() => {
    if (!structure.value) return '';
    const { highConfidenceCount, mediumConfidenceCount, lowConfidenceCount, totalClauseCount } = structure.value;
    if (totalClauseCount === 0) return '';
    const highRatio = highConfidenceCount / totalClauseCount;
    if (highRatio >= 0.8) return '良好';
    if (highRatio >= 0.5) return '一般';
    return '较差（建议人工校对）';
  });

  const overallConfidenceColor = computed(() => {
    const c = overallConfidence.value;
    if (c === '良好') return 'green';
    if (c === '一般') return 'orange';
    return 'red';
  });

  function convertToTreeNode(clause: any): any {
    return {
      key: clause.clausePath,
      clausePath: clause.clausePath,
      title: clause.title || '(无标题)',
      confidence: clause.confidence || 'high',
      isScope: clause.isScope,
      isAppendix: clause.isAppendix,
      appendixLabel: clause.appendixLabel,
      text: clause.text,
      children: clause.children?.map(convertToTreeNode) || [],
    };
  }

  function onClauseSelect(keys: string[], info: any) {
    selectedKeys.value = keys;
    selectedClause.value = info.node || null;
    // 如果有页码信息，联动左侧 PDF
    if (info.node?.pageNo) {
      currentPage.value = info.node.pageNo;
    }
  }

  function prevPage() {
    if (currentPage.value > 1) currentPage.value--;
  }

  function nextPage() {
    if (currentPage.value < totalPages.value) currentPage.value++;
  }

  async function doPreview() {
    if (!props.docId) return;
    loading.value = true;
    try {
      const res = await previewGbStandard(props.docId);
      if (res.success) {
        structure.value = res.result;
      } else {
        createMessage.error(res.message || '解析失败');
      }
    } catch (e: any) {
      createMessage.error('解析失败: ' + (e.message || '未知错误'));
    } finally {
      loading.value = false;
    }
  }

  async function handleConfirm() {
    confirmLoading.value = true;
    try {
      const res = await confirmGbStandard(props.docId);
      if (res.success) {
        createMessage.success('确认成功');
        emit('confirmed');
        closeModal();
      } else {
        createMessage.error(res.message || '确认失败');
      }
    } catch (e: any) {
      createMessage.error('确认失败: ' + (e.message || '未知错误'));
    } finally {
      confirmLoading.value = false;
    }
  }

  async function handleReparse() {
    await doPreview();
  }

  // 打开 Modal 时自动执行预览
  watch(() => props.docId, (newVal) => {
    if (newVal) doPreview();
  }, { immediate: true });
</script>

<style scoped>
  .gb-preview-container {
    display: flex;
    height: calc(100vh - 240px);
    overflow: hidden;
  }

  .gb-preview-left {
    flex: 1;
    border-right: 1px solid #f0f0f0;
    display: flex;
    flex-direction: column;
  }

  .gb-preview-right {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    padding: 12px;
  }

  .gb-preview-header {
    padding: 8px 12px;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-weight: 500;
  }

  .gb-preview-page-nav {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .gb-preview-pdf-area {
    flex: 1;
    overflow: auto;
    padding: 12px;
    background: #fafafa;
  }

  .gb-preview-no-pdf {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }

  .gb-preview-info {
    padding: 12px 0;
  }

  .gb-preview-clause-tree {
    flex: 1;
    overflow-y: auto;
    padding: 8px 0;
  }

  .gb-preview-clause-detail {
    padding: 8px 0;
  }

  .clause-path {
    font-weight: 600;
    margin-right: 6px;
    color: #1890ff;
  }

  .clause-title {
    color: #333;
  }

  .confidence-badge {
    margin-left: 4px;
    font-size: 12px;
  }

  .clause-text {
    margin-top: 8px;
    padding: 8px;
    background: #f6f8fa;
    border-radius: 4px;
    white-space: pre-wrap;
    font-size: 13px;
    line-height: 1.6;
    max-height: 200px;
    overflow-y: auto;
  }

  .gb-preview-footer {
    padding: 12px;
    border-top: 1px solid #f0f0f0;
    text-align: center;
    background: #fff;
  }
</style>
<!--update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 国标解析确认页----------- -->
```

- [ ] **Step 3: 在文档列表中添加"国标预览"入口**

在 `AiragKnowledgeDocListModal.vue` 中，为文档操作列添加"国标预览"按钮。找到文档操作区域，在现有操作按钮旁添加：

```vue
<a-button
  v-if="record.type === 'file'"
  type="link"
  size="small"
  @click="handleGbPreview(record)"
>
  国标预览
</a-button>
```

并添加对应的处理函数和 Modal 注册。

- [ ] **Step 4: Commit**

```bash
git add jeecgboot-vue3/src/views/super/airag/aiknowledge/GbStandardPreview.api.ts
git add jeecgboot-vue3/src/views/super/airag/aiknowledge/components/GbStandardPreview.vue
git add jeecgboot-vue3/src/views/super/airag/aiknowledge/components/AiragKnowledgeDocListModal.vue
git commit -m "feat(airag-gb): 添加国标解析确认页 GbStandardPreview.vue + API"
```

---

### Task 9: 集成测试 + 最终提交

**Files:**
- 无新文件

- [ ] **Step 1: 全量编译后端**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd compile -pl jeecg-boot-module/jeecg-boot-module-airag -am`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行全部 airag 模块测试**

Run: `D:\maven\apache-maven-3.9.9\bin\mvn.cmd test -pl jeecg-boot-module/jeecg-boot-module-airag -DskipTests=false`
Expected: 所有测试 PASS

- [ ] **Step 3: 检查前端编译**

```bash
cd jeecgboot-vue3
pnpm build
```
Expected: 无 TypeScript 错误

- [ ] **Step 4: 用 GitNexus detect_changes 验证变更范围**

```
mcp__gitnexus__detect_changes({scope: "unstaged"})
```

验证变更只影响预期的符号/文件。

- [ ] **Step 5: 最终 Commit（如有遗漏文件）**

```bash
git add -A
git commit -m "feat(airag-gb): GB 国标知识引擎 Phase 1 完成 — L1 数据模型 + L2 结构解析器 + 用户确认页"
```
