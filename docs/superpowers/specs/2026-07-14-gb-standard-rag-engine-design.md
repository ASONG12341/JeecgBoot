# GB 国标知识引擎设计文档

> **日期**：2026-07-14
> **作者**：song（与 Claude Code 协作设计）
> **模块**：`jeecg-boot-module-airag`
> **替代**：`GB-RAG-14根因复盘与完整落地方案.md`（v3）
> **技术栈**：Spring Boot 4.1.0 / Java 17 / LangChain4j 1.17.2 / PGVector / MiniMax-M3 / Qwen text-embedding-v3 / MinerU Cloud

---

## 1. 问题背景与根因分析

### 1.1 核心问题

当前 airag 模块把国标文档当作"普通文档"处理（扁平文本 → 向量 → 相似度匹配），但国标是**法规级结构化文档**——有层级体系（章→条→款）、版本效力、跨标准引用、术语规范、极性条款、公式参数等。系统需要从根本架构上解决以下根因：

### 1.2 根因清单

| # | 根因 | 具体表现 |
|---|------|---------|
| 1 | 缺少国标的元数据模型/知识本体 | 没有标准号、版本、条款层级等结构化模型 |
| 2 | 入库时没有提取足够的结构化信息 | 条款层级、引用关系、公式参数在入库时丢失 |
| 3 | 向量空间太扁平，缺少层次化索引 | 所有文本块平铺在一个向量空间，无法按结构过滤 |
| 4 | 领域知识硬编码在代码里 | GbQueryIntent 硬编码电池安全领域字段（testType、nCells 等） |
| 5 | 检索和推理/计算没有分离 | LLM 被期望做算术、判断极性等确定性任务 |
| 6 | 语义相似 ≠ 逻辑等价 | "按4.5.1充满电"与"按4.5.2放完电"向量距离极近 |
| 7 | LLM 不会计算只会续写 | 算术幻觉无法通过调参消除 |
| 8 | 隐含前提无法自动解析 | "3串电池组"的"3串"未被识别为触发条件 |
| 9 | 否定/例外条款语义反转 | "不应"、"除外"等条款被向量检索忽略 |
| 10 | 术语标准化不一致 | 同一概念在不同标准中名称不同 |
| 11 | 量化精度与单位丢失 | embedding 对小数级别数值失精 |
| 12 | 跨标准引用关系断裂 | 引用其他标准的内容无法展开 |
| 13 | 可解释性与审计追溯缺失 | 无法追踪答案来源 |
| 14 | 评测机制缺失 | 无法量化改进效果 |

### 1.3 MinerU 解析质量差异

通过实际解析三份国标文档发现：

| 标准 | 解析质量 | 主要问题 |
|------|---------|---------|
| GB 31241-2022 | 良好 | 条款编号清晰，公式完整，表格正确 |
| GB/T 34131-2023 | 非常好 | 层级清晰，HTML 表格含 rowspan/colspan，公式完整 |
| GB 38031-2025 | **严重退化** | 条款号丢失小数点（"31"应为"3.1"），大量数值缺失，标准号被吞 |

**结论**：MinerU 解析质量不可控，必须有用户确认环节。

---

## 2. 整体架构

### 2.1 架构定位

- airag 是 JeecgBoot 平台的**通用 AI 知识库模块**（支持普通文档、记忆库、网页等）
- 国标是 airag 中的**一种知识库类型**，通过增强层叠加特殊处理
- 不破坏 airag 的通用能力

### 2.2 五层架构

```
┌─────────────────────────────────────────────────────────────┐
│  L5 评测与可观测层                                           │
│  ├── RAGAS 评测集 + LLM-as-Judge                            │
│  ├── 审计追溯表（每条回答 → 来源条款 → 标准版本 → 页码）      │
│  └── 监控看板（召回率、引用准确率、fallback 命中率）           │
├─────────────────────────────────────────────────────────────┤
│  L4 在线推理层（Agent + Tool Calling）                        │
│  ├── GbCalculationTool：SQL 查参数表，精确计算公式            │
│  ├── GbCitationTool：组装带来源引用的结构化回答               │
│  └── 与 AIChatHandler 集成（通过 GbRetrievalOrchestrator）    │
├─────────────────────────────────────────────────────────────┤
│  L3 在线检索层（Multi-Channel Retrieval）                     │
│  ├── AdaptiveIntentExtractor：自适应意图抽取（文档驱动）       │
│  ├── QueryPlanner：决定走哪些检索通道                         │
│  ├── VectorChannel：pgvector HNSW + HYBRID                   │
│  ├── StructureChannel：条款层级树遍历（父/子/兄弟条款）       │
│  ├── ParamChannel：结构化参数表 SQL 查询                      │
│  ├── GraphChannel：引用关系图 1-2 跳展开                      │
│  └── RRF 融合 + ContextAssembler                             │
├─────────────────────────────────────────────────────────────┤
│  L2 离线索引层（Ingestion Pipeline）                          │
│  ├── MinerU 解析 → Markdown + 图片                           │
│  ├── GbDocumentStructureParser：章→条→款层级树解析            │
│  ├── 【用户确认页】：PDF 原文（左）+ 解析结果（右）对照校对    │
│  ├── GbClauseMetadataExtractor：LLM 两阶段 metadata 抽取     │
│  ├── GbParamExtractor：公式/数值参数抽取                      │
│  ├── GbReferenceExtractor：标准内 + 跨标准引用关系抽取        │
│  ├── GbPolarityAnnotator：条款极性标注                       │
│  └── 双写：结构化表 + 向量库（metadata 增强）                 │
├─────────────────────────────────────────────────────────────┤
│  L1 数据模型层（Ontology / Schema）                           │
│  ├── gb_standard：标准实体（编号、版本、效力状态）             │
│  ├── gb_clause：条款实体（路径、父条款、类型、极性）           │
│  ├── gb_parameter：参数实体（公式、数值、单位、条件）          │
│  ├── gb_reference：统一引用关系（标准内 + 跨标准）             │
│  ├── gb_term_dict：术语同义词表                               │
│  └── 现有 airag_embedding 向量表（metadata JSONB 增强）       │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 核心原则

1. **数据模型驱动**：所有逻辑基于 L1 本体模型，不硬编码任何领域知识
2. **入库时重提取**：结构化信息在 L2 入库阶段完成，检索时直接使用
3. **文档驱动**：LLM 从文档本身推导领域 Schema，新增标准无需改代码
4. **多通道检索**：L3 不依赖单一向量通道，多通道并行 + RRF 融合
5. **推理与检索分离**：L4 的 Tool Calling 负责确定性计算，LLM 只做语义理解
6. **用户确认**：MinerU 解析质量不可控，用户必须确认后才进入 LLM 抽取
7. **全程可审计**：L5 记录每个回答的完整证据链

---

## 3. L1 数据模型层

### 3.1 实体关系

```
gb_standard (标准)
  ├── 1:N → gb_clause (条款)
  │          ├── 物化路径 parent_path (章→条→款层级树)
  │          ├── 1:N → gb_parameter (参数/公式)
  │          └── N:M → gb_reference (引用关系)
  ├── 1:N → gb_term_dict (术语同义词)
  └── 关联 → airag_embedding (向量 chunks，通过 chunk_id 反查)
```

### 3.2 表结构

#### gb_standard — 标准实体

```sql
CREATE TABLE gb_standard (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    standard_no VARCHAR(50) NOT NULL,       -- 'GB 31241' / 'GB/T 31467.3'
    version VARCHAR(20),                    -- '2022' / '2014'
    full_name TEXT,                          -- '便携式电子产品用锂离子电池和电池组 安全技术规范'
    publish_date DATE,
    implementation_date DATE,
    withdrawal_date DATE,                   -- null = 现行
    status VARCHAR(20) DEFAULT 'current',   -- current / superseded / withdrawn
    domain VARCHAR(100),                    -- 领域标签（自动推导或人工标注）
    knowledge_id VARCHAR(36),               -- 关联 airag_knowledge 表
    doc_id VARCHAR(36),                     -- 关联 airag_knowledge_doc 表
    supersedes TEXT[],                      -- 替代的旧标准列表 {'GB 31241-2014'}
    normative_refs TEXT[],                  -- 第2章规范性引用文件清单
    domain_schema JSONB DEFAULT '{}',       -- 领域属性 schema（L2 推导）
    metadata JSONB DEFAULT '{}',            -- 标准级扩展属性
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(standard_no, version)
);
```

#### gb_clause — 条款实体（层级树）

```sql
CREATE TABLE gb_clause (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    standard_id UUID NOT NULL REFERENCES gb_standard(id),
    clause_path VARCHAR(50) NOT NULL,       -- '9' / '9.2' / '9.2.3'
    parent_path VARCHAR(50),                -- '9.2' (9.2.3 的父条款)
    depth INT NOT NULL,                     -- 1=章, 2=条, 3=款...
    title TEXT,                             -- '过压充电'
    clause_type VARCHAR(30),                -- normative / informative / scope / reference / definition
    polarity VARCHAR(20) DEFAULT 'positive', -- positive / negative / exception
    exception_of VARCHAR(50),               -- 例外针对的条款路径
    requirement_strength VARCHAR(20),       -- mandatory(应) / recommended(宜) / permissible(可)
    is_scope BOOLEAN DEFAULT false,         -- 是否为范围条款（第1章）
    is_appendix BOOLEAN DEFAULT false,      -- 是否为附录条款
    appendix_label VARCHAR(10),             -- 'A' / 'B' / 'C' (附录编号)
    text TEXT NOT NULL,                     -- 条款完整文本
    page_no INT,                            -- 原文页码
    chunk_id VARCHAR(100),                  -- 关联 airag_embedding
    metadata JSONB DEFAULT '{}',            -- 条款级动态属性（LLM 抽取）
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_clause_standard ON gb_clause(standard_id);
CREATE INDEX idx_clause_path ON gb_clause(standard_id, clause_path);
CREATE INDEX idx_clause_parent ON gb_clause(standard_id, parent_path);
CREATE INDEX idx_clause_type ON gb_clause(standard_id, clause_type);
CREATE INDEX idx_clause_polarity ON gb_clause(standard_id, polarity);
```

#### gb_parameter — 结构化参数/公式

```sql
CREATE TABLE gb_parameter (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    standard_id UUID NOT NULL REFERENCES gb_standard(id),
    clause_id UUID NOT NULL REFERENCES gb_clause(id),
    param_name VARCHAR(100),                -- 'overcharge_threshold'
    formula TEXT,                            -- 'U = n × 6.0 V'
    param_value NUMERIC,                     -- 计算结果（可空）
    unit VARCHAR(30),                        -- 'V' / 'A' / '℃' / 'min'
    condition_expr TEXT,                     -- 'n_cells > 1'
    source_text TEXT,                        -- 原始文本片段
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_param_standard ON gb_parameter(standard_id);
CREATE INDEX idx_param_clause ON gb_parameter(clause_id);
```

#### gb_reference — 统一引用关系（标准内 + 跨标准）

```sql
CREATE TABLE gb_reference (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    source_standard_id UUID NOT NULL REFERENCES gb_standard(id),
    source_clause_path VARCHAR(50) NOT NULL,
    target_type VARCHAR(20) NOT NULL,       -- 'intra'(标准内) / 'inter'(跨标准)
    target_standard_id UUID REFERENCES gb_standard(id),
    target_standard_no VARCHAR(50),         -- inter 时的目标标准号
    target_clause_path VARCHAR(50),
    ref_type VARCHAR(30),                   -- normative_reference / prerequisite / informative
    ref_text TEXT,                          -- 引用上下文原文
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_ref_source ON gb_reference(source_standard_id, source_clause_path);
CREATE INDEX idx_ref_target ON gb_reference(target_standard_id, target_clause_path);
```

#### gb_term_dict — 术语同义词表

```sql
CREATE TABLE gb_term_dict (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    standard_id UUID REFERENCES gb_standard(id),  -- null = 全局术语
    canonical_term VARCHAR(100) NOT NULL,
    synonyms TEXT[] NOT NULL,
    definition TEXT,                        -- 术语定义（从第3章提取）
    domain VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

#### gb_audit_log — 审计追溯表

```sql
CREATE TABLE gb_audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    session_id VARCHAR(50),
    user_query TEXT NOT NULL,
    extracted_intent JSONB,
    retrieval_channels TEXT[],
    retrieved_clauses JSONB,
    llm_response TEXT,
    cited_sources JSONB,
    tool_calls JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 3.3 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 条款层级建模 | 物化路径 (clause_path='9.2.3') | 查询简单，不需要递归 CTE |
| 领域属性存储 | 基础列 + JSONB 扩展 | 高频过滤字段走索引，动态字段用 JSONB |
| UUID 版本 | uuid_generate_v7() (PG 17+) | 时间有序，B-Tree 友好 |
| 与 airag_embedding 关系 | 通过 chunk_id 关联 | 不替代现有向量表 |

---

## 4. L2 离线索引层

### 4.1 入库流程

```
用户上传 PDF
      │
      ▼
 ① MinerU 解析（已有）→ Markdown + 图片
      │
      ▼
 ② GbDocumentStructureParser（自动）
    → 识别标准号/版本/章条款层级树
    → 标记条款解析质量评分（🟢高/🟡中/🔴低）
    → 特殊处理：范围条款、规范性引用文件、术语定义、附录
      │
      ▼
 ③ 【用户确认页】
    → 左侧：PDF 原文（PDF.js 渲染，支持翻页）
    → 右侧：解析结果（章节树 + 条款详情 + 质量标记）
    → 交互：点击条款 → PDF 自动跳转对应页
    → 操作：确认/编辑/重新解析/取消
      │
      ▼
 ④ GbClauseMetadataExtractor（LLM 两阶段抽取）
    → 阶段1：标准级 Schema 推导（每份标准一次）
    → 阶段2：条款级 metadata 抽取（每个条款一次）
      │
      ▼
 ⑤ GbParamExtractor → gb_parameter 表
 ⑥ GbReferenceExtractor → gb_reference 表
 ⑦ GbPolarityAnnotator → gb_clause.polarity
      │
      ▼
 ⑧ 双写：
    a) 结构化表：gb_standard + gb_clause + gb_parameter + gb_reference
    b) 向量库：airag_embedding（metadata 注入 clause_path/polarity/standard_no 等）
```

### 4.2 GbDocumentStructureParser — 层级树解析

解析策略（优先级从高到低）：

1. **正则匹配国标编号规范**：`^(\d+)(\.\d+)*\s+(.+)$`
2. **Markdown 标题层级**：`#` / `##` / `###`
3. **OCR 退化修复**：检测 "31" 可能是 "3.1" 的情况（根据上下文推断）
4. **LLM 辅助补全**：正则和标题都无法确定时

特殊处理：
- **第1章 范围** → 标记 `is_scope=true`
- **第2章 规范性引用文件** → 提取标准号列表 → 写入 `gb_standard.normative_refs`
- **第3章 术语和定义** → 提取术语 → 写入 `gb_term_dict`
- **附录**（附录A/B/C）→ 标记 `is_appendix=true`，使用字母编号
- **注（Notes）** → 关联到所属条款
- **表格** → 保持与所属条款的关联

### 4.3 用户确认页

**布局**：左侧 PDF 原文 + 右侧解析结果

**前端组件**：`GbStandardPreview.vue`

**后端 API**：
- `POST /airag/knowledge/doc/preview` — 触发解析，返回预览数据
- `PUT /airag/knowledge/doc/{id}/structure` — 用户修正后的结构数据
- `POST /airag/knowledge/doc/{id}/confirm` — 确认后触发后续管线

**文档状态机**：
```
UPLOADED → PARSING → PARSED(等待确认) → CONFIRMED → INDEXING → COMPLETED
```

**PDF 渲染**：优先 PDF.js 渲染原始 PDF；备选使用 MinerU 生成的逐页图片

### 4.4 GbClauseMetadataExtractor — 两阶段 LLM 抽取

**阶段 1：标准级 Schema 推导**（每份标准一次）

输入：标准的前言 + 目录 + 前3章内容
输出：该标准的领域属性 JSON Schema

```json
// GB 31241 的推导结果
{
  "properties": {
    "testType": {"type": "string", "description": "测试类型"},
    "applicableBatteryType": {"type": "string", "description": "适用电池类型"},
    "testConditions": {"type": "string", "description": "测试条件"}
  }
}

// GB/T 34131 的推导结果（完全不同的领域）
{
  "properties": {
    "batteryType": {"type": "string", "description": "电池类型"},
    "functionCategory": {"type": "string", "description": "功能类别"},
    "measurementParam": {"type": "string", "description": "测量参数"}
  }
}
```

Schema 存储在 `gb_standard.domain_schema` 字段，可人工修正。

**阶段 2：条款级 metadata 抽取**（每个条款一次）

输入：条款文本 + 标准级 Schema
输出：该条款的 metadata JSON（符合 Schema）+ 通用字段（clause_type, polarity）

### 4.5 配置与 Kill Switch

```yaml
jeecg:
  airag:
    gb-standard:
      enabled: true                    # 总开关
      user-review-enabled: true        # 用户确认页
      structure-parser:
        enabled: true
        llm-fallback: true
      clause-metadata-extractor:
        enabled: true
        batch-size: 10
        model-name: "qwen-flash"
      param-extractor:
        enabled: true
      reference-extractor:
        enabled: true
      polarity-annotator:
        enabled: true
```

---

## 5. L3 在线检索层

### 5.1 检索流程

```
用户提问
    │
    ▼
① AdaptiveIntentExtractor（自适应意图抽取）
    → 查知识库关联的 gb_standard
    → 合并 domainSchema → 动态 JSON Schema
    → LLM 抽取意图（轻量模型，3s 超时）
    │
    ▼
② QueryPlanner（查询规划器）
    → 决定激活哪些检索通道
    │
    ├──▶ VectorChannel（始终激活）
    ├──▶ StructureChannel（有条款定位时激活）
    ├──▶ ParamChannel（数值/公式问题时激活）
    ├──▶ GraphChannel（有引用关系时激活）
    │
    ▼
③ RRF 融合 + 去重 + 排序
    │
    ▼
④ ContextAssembler（上下文组装）
    → 带来源标注的结构化上下文
```

### 5.2 AdaptiveIntentExtractor

```java
public class AdaptiveIntentExtractor {
    public QueryIntent extract(String userQuery, List<String> knowIds) {
        // 1. 查知识库关联的标准
        List<GbStandard> standards = gbStandardMapper.selectByKnowledgeIds(knowIds);
        // 2. 合并领域 schema
        JsonSchema domainSchema = mergeDomainSchemas(standards);
        // 3. 构建动态 JSON Schema = 通用字段 + 领域字段
        JsonSchema fullSchema = buildFullSchema(domainSchema);
        // 4. LLM 抽取
        return llmExtract(userQuery, fullSchema, standards);
    }
}
```

**通用字段**（所有标准共享）：standardNo, objectType, inferredChapter, clauseId, version, status
**领域字段**（从 domainSchema 合并）：不同标准有不同的领域属性

### 5.3 四个检索通道

| 通道 | 数据源 | 触发条件 | 权重 |
|------|--------|---------|------|
| VectorChannel | airag_embedding (pgvector) | 始终 | 标准 |
| StructureChannel | gb_clause 表 | 意图含 clauseId 或 chapter | 最高（精确命中） |
| ParamChannel | gb_parameter 表 | 数值/公式类问题 | 次高 |
| GraphChannel | gb_reference 表 | 检索结果有引用关系 | 较低（补充上下文） |

### 5.4 StructureChannel — 条款层级展开

```java
// 精确匹配：用户提到 "9.2" → WHERE clause_path='9.2'
// 父条款展开：命中 9.2.1 → 自动包含 9.2（概述）提供上下文
// 子条款展开：命中 9.2 → 可选包含 9.2.1, 9.2.2 等子条款
// 引用展开：命中条款有 prerequisite 引用 → 自动包含被引用条款
```

### 5.5 与现有代码集成

```java
public class GbRetrievalOrchestrator {
    public QueryRouter getQueryRouter(List<String> knowIds, ...) {
        if (isGbStandardKnowledge(knowIds)) {
            return buildMultiChannelRouter(knowIds, ...);
        } else {
            return embeddingHandler.getQueryRouter(knowIds, ...);  // 委托现有
        }
    }
}
```

---

## 6. L4 在线推理层

### 6.1 Tool Calling

```java
@Tool("查询国标中的技术参数和公式计算结果")
public class GbCalculationTool {
    @Tool("查询指定标准条款中的公式参数并计算结果")
    public String queryParameter(String standardNo, String paramName,
                                  Map<String, Object> variables) {
        // SQL 查 gb_parameter → 代入公式计算 → 返回精确结果
    }
}

@Tool("引用国标条款作为回答的依据")
public class GbCitationTool {
    @Tool("获取指定条款的完整引用信息")
    public String getCitation(String standardNo, String clausePath,
                               String version) {
        // 查 gb_clause + gb_standard → 格式化引用
    }
}
```

### 6.2 System Prompt 增强

当知识库包含国标时，自动追加：
```
你是国标安全合规助手。回答时必须：
1. 引用具体的标准号和条款号
2. 数值计算必须使用 GbCalculationTool，禁止自行估算
3. 注意否定/例外条款（标记为 negative/exception 的条款）
4. 如果多个标准有不同要求，明确指出差异
```

---

## 7. L5 评测与可观测层

### 7.1 评测框架

- 评测集构造：LLM 从条款中自动生成问题 + 人工标注 ground truth
- 评测指标：Recall@K、CitationAccuracy、CalculationAccuracy、PolarityAwareness
- 定期跑评测集，结果存 gb_audit_log

### 7.2 监控指标

```
airag_gb_retrieval_latency_ms       — 检索延迟
airag_gb_intent_fallback_total      — 意图抽取 fallback 次数
airag_gb_retrieval_channel_hits     — 各通道命中率
airag_gb_llm_citation_accuracy      — 引用准确率
```

---

## 8. GraphRAG 集成（Phase 5 可选）

- Python 微服务，对每份国标执行三元组抽取
- 实体/关系存储：PostgreSQL JSONB（不上 Neo4j）
- Java 客户端查询 2 跳邻居，注入 LLM 上下文
- 可选组件：不部署时，`gb_reference` 表已覆盖显式引用

---

## 9. 分阶段实施计划

| 阶段 | 内容 | 周期 | Kill Switch |
|------|------|------|-------------|
| **Phase 1** | L1 数据模型（DDL + Flyway）+ L2 结构解析器 + 用户确认页 | 3 周 | `gb-standard.enabled=false` |
| **Phase 2** | L2 完整管线（LLM metadata 抽取 + 参数抽取 + 引用抽取）+ L3 向量通道增强 | 3 周 | `clause-metadata-extractor.enabled=false` |
| **Phase 3** | L3 结构通道 + 参数通道 + 自适应意图抽取 | 2 周 | 各通道独立开关 |
| **Phase 4** | L4 Tool Calling + L5 审计表 + 评测框架 | 2 周 | Tool 注册开关 |
| **Phase 5** | L3 引用图通道 + GraphRAG + ColPali（可选） | 3-4 周 | `graphrag.enabled=false` |
| **总计** | | **13-14 周** | |

### 9.1 Phase 1 详细范围

1. 创建 Flyway 迁移脚本（gb_standard / gb_clause / gb_parameter / gb_reference / gb_term_dict / gb_audit_log）
2. 实现 GbDocumentStructureParser（正则 + 标题层级 + OCR 退化修复）
3. 新增 AiragKnowledgeDoc.parseStatus 字段（UPLOADED → PARSING → PARSED → CONFIRMED → INDEXING → COMPLETED）
4. 前端 GbStandardPreview.vue（左侧 PDF + 右侧解析结果）
5. 后端 Preview/Confirm API

### 9.2 回滚矩阵

| Phase | 回滚方式 | 耗时 | 数据影响 |
|-------|---------|------|---------|
| P1 | Kill Switch + Flyway 回滚 | < 5 分钟 | 结构化表可保留 |
| P2 | Kill Switch（跳过 LLM 抽取） | < 1 分钟 | 无 |
| P3 | Kill Switch（退回纯向量检索） | < 1 分钟 | 无 |
| P4 | 不注册 Tool（LLM 不感知） | < 1 分钟 | 审计表可保留 |
| P5 | 卸载 Python 微服务 | < 30 分钟 | 图谱数据非主路径 |

---

## 10. 包结构

```
org.jeecg.modules.airag.llm.gbstandard/
├── model/
│   ├── GbStandard.java              // 标准实体
│   ├── GbClause.java                // 条款实体
│   ├── GbParameter.java             // 参数实体
│   ├── GbReference.java             // 引用关系实体
│   ├── GbTermDict.java              // 术语同义词实体
│   └── GbAuditLog.java              // 审计日志实体
├── mapper/
│   ├── GbStandardMapper.java
│   ├── GbClauseMapper.java
│   ├── GbParameterMapper.java
│   ├── GbReferenceMapper.java
│   └── GbTermDictMapper.java
├── ingestion/
│   ├── GbDocumentStructureParser.java
│   ├── GbClauseMetadataExtractor.java
│   ├── GbParamExtractor.java
│   ├── GbReferenceExtractor.java
│   ├── GbPolarityAnnotator.java
│   └── GbIngestionPipeline.java     // 编排整个入库流程
├── query/
│   ├── AdaptiveIntentExtractor.java
│   ├── QueryPlanner.java
│   └── QueryIntent.java             // 通用意图 POJO
├── retrieval/
│   ├── GbRetrievalOrchestrator.java
│   ├── VectorChannel.java
│   ├── StructureChannel.java
│   ├── ParamChannel.java
│   ├── GraphChannel.java
│   ├── RrfMerger.java
│   └── ContextAssembler.java
├── tool/
│   ├── GbCalculationTool.java
│   └── GbCitationTool.java
├── config/
│   └── GbStandardProperties.java    // 配置属性类
└── controller/
    └── GbStandardController.java     // Preview/Confirm API
```

---

## 11. 不做的事

- ❌ 用"更大的上下文窗口"覆盖多章 — 成本/失焦双重恶化
- ❌ 只用"更好的向量模型"替代 — 不能解决极性反转与逻辑互斥
- ❌ 任何 YAML 字典 + 正则做意图解析 — 用 LLM Structured Output 替代
- ❌ 自训 7B 法律模型 — 强规则文档应靠确定性工程
- ❌ 硬编码任何领域知识 — 全部文档驱动
- ❌ 破坏 airag 通用能力 — 国标增强层只在知识库为国标类型时激活
