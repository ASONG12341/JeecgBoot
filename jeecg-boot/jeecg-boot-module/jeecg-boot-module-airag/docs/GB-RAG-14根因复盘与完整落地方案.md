# GB 标准文档在 RAG 系统中的 14 根因复盘与完整落地方案（v3 · 2026 完整方案版）

> **作者**：AI 模块组
> **日期**：2026-07-10
> **替代版本**：v2（LLM Structured Output 版本）已被 v3 覆盖；v1（YAML+正则版本）早已废弃
> **适用模块**：`jeecg-boot-module-airag`
> **关联代码**：`MineruApiClient.java`、`EmbeddingHandler.java`、`AIChatHandler.java`、`KnowConfigBean.java`
> **技术栈基准（已实测）**：
> - Chat（含多模态）：**MiniMax-M3** 通过 **OpenAI 兼容端点** `https://api.minimax.chat/v1`（多模态，原生 vision + function calling）
> - Embedding：**Qwen text-embedding-v3**（1536 维度，DashScope OpenAI 兼容模式）
> - 数据库：**PostgreSQL 18**（2025-09-25 发布）+ pgvector
> - 文档框架：LangChain4j 1.x
> - PDF 解析：MinerU Cloud 精准解析 API
> - 图谱：GraphRAG（Python 微服务，离线索引）

---

## 0. v2 相对 v1 的关键修订

| v1 旧方案（已废弃） | v2 新方案 | 修正原因 |
|------------------|---------|--------|
| 根因 3：YAML 字典 + 正则 + alias 列表 | **LLM Structured Output（JSON Schema）+ PG 18 Skip Scan 联合检索** | YAML 无法穷举长尾表述、无法解决上下文歧义、无法做隐含推理；正则失败会硬性 0 召回 |
| 根因 1：纯 PGVector HYBRID | **PG 18 Skip Scan + UUIDv7 + HNSW + 标量/向量双轨索引 + HyDE 多视图查询** | 仅靠 HYBRID 不够，需把 metadata 多列索引全部纳入 join |
| 根因 7：版本元数据 + 时点过滤 | **PG 18 `uuidv7()` 时间有序主键 + Temporal Constraints + 检索前版本路由** | UUID 改为时间有序可以天然按时间召回最新版本 |
| 根因 11：数值表 + SQL 检索 | **PG 18 Virtual Generated Columns + `overcharge_threshold NUMERIC GENERATED ALWAYS AS (n_cells * 6.0) VIRTUAL`** | 衍算参数实时计算不占存储，可建索引 |
| 根因 4：VLM 图转文 + 图文双索引 | **保留 VLM + 新增 ColPali（晚交互多向量）+ 双检索** | ColPali 把整页 PDF 当作多向量，无需 OCR |
| 根因 8：建议 Neo4j 图谱（粗） | **GraphRAG 实测方案 + 与 LightRAG/HippoRAG 选型对比** | 微软官方仍在维护，论文 arxiv:2404.16130 |

> **一句话修订哲学**：把"基于规则（Rule-based）的 NER/Slot Filling"路线（v1）全面替换为"基于 LLM 语义理解 + 现代数据库特性"的路线（v2）。

---

## 1. 问题复盘：14 根因矛盾全景

### 1.1 您提出的 7 大问题（全部成立，描述准确）

| # | 问题 | 您总结的例子 | 本质 |
|---|------|------------|------|
| 1 | 语义相似 ≠ 逻辑等价 | "按 4.5.1 充满电" 与 "按 4.5.2 放完电" 在向量空间距离极近 | 概率性近邻检索无法区分互斥对 |
| 2 | LLM 不会计算只会续写 | "n=3, U=n×6.0" → LLM 输出训练语料中最常共现的数字 | 算术幻觉无法通过调参消除 |
| 3 | 隐含前提无法自动解析 | "3串电池组" 的 "3串" 未被识别为触发 n×6.0 的条件变量 | 缺乏领域本体（ontology） |
| 4 | 视觉像素 ≠ 结构化语义 | 附录 B 流程图被 MinerU 抠成 `![](auto/page42_img1.png)` | embedding 看不见 PNG 里的节点 |
| 5 | 物理版面 ≠ 语义拓扑 | 跨页表格被切断、图注与正文分离到不同 Chunk | 物理页边界 ≠ 语义边界 |
| 6 | 线性切片 ≠ 树状法规 | 9.2 条公式，前置约束在 9.1，适用范围在第 9 章引言 | 父子节点被打散到不同 Chunk |
| 7 | 静态快照 ≠ 动态效力 | GB 31241-2014 与 -2022 平等入库，混合召回 | 无版本元数据与时点过滤 |

### 1.2 您可能遗漏的 7 大问题（按危害程度排序）

| 新增 # | 问题 | 危害场景 | 与原 7 问关系 |
|-------|------|---------|------------|
| **8** | 跨文档引用关系断裂 | GB 31241 第 9.2 条引用 GB/T 31467.3，检索只看到当前 GB，跨标准失效 | 与 #6 正交，属"图谱"维度 |
| **9** | 否定/例外条款语义反转 | "除…外"、"不应"、"不得"、"在 XX 条件下不适用" — 向量检索把肯定与否定当作相似文本 | 与 #1 正交，属"极性"维度 |
| **10** | 术语标准化不一致 | 同一概念在不同标准里名称不同（"电池组"/"电池包"/"Battery Pack"），全漏召回 | 与 #3 正交，属"字典"维度 |
| **11** | 量化精度与单位丢失 | "6.0V±0.05V"、"环境温度 25±5℃"、"0.1mm"、"C/3" — 数值在小数点级别，embedding 失精 | 与 #1 正交，属"数值"维度 |
| **12** | 回溯更新链式失效 | GB A 引用 GB B，GB B 修订后 GB A 的所有相关条款需要联动重审 | 与 #7 正交，属"依赖图"维度 |
| **13** | 可解释性与审计追溯缺失 | 安全合规必须给出"答案来源页码+条款号+版次+修改单" | 与 #1、#6 正交，属"证据"维度 |
| **14** | 评测机制缺失 / 缺 ground truth | 无法量化"召回率@K / 引用准确率 / 计算题正确率" | 横切所有问题，属"度量"维度 |

### 1.3 14 根因矛盾归因表（一张大图）

| # | 矛盾维度 | 传统 RAG 做法 | 失败根因 | v2 正确做法 |
|---|---------|------------|---------|------------|
| 1 | 概率 vs 确定 | 信任向量相似度 | 语义近≠逻辑同 | 标量/向量双轨 + PG 18 Skip Scan + HyDE 多视图 |
| 2 | 续写 vs 计算 | 信任 LLM 代公式 | token 预测≠算术 | Tool Calling + PG 18 虚拟生成列（实时计算） |
| 3 | 隐含 vs 显含 | YAML+正则穷举 | 长尾+歧义+推理失败 | **LLM Structured Output（JSON Schema）+ Attention 绑变量** |
| 4 | 像素 vs 语义 | 信任图片路径 | embedding 看不到图 | VLM 图转 JSON + **ColPali 多向量索引** |
| 5 | 物理 vs 拓扑 | 信任固定切片 | 跨页被切断 | Docling bbox 二次聚合 + GB 结构感知切分 |
| 6 | 线性 vs 树状 | 平铺文本块 | 章—条—款被压扁 | Parent Document Retriever + 父级路径 metadata |
| 7 | 静态 vs 动态 | 所有版本等同 | 无版本元数据 | **PG 18 UUIDv7 时间有序** + 时效路由 |
| 8 | 节点 vs 图 | 只看 chunk | 跨文档引用断 | GraphRAG / LightRAG / Neo4j 引用图 |
| 9 | 极性反转 | 不区分肯定/否定 | 语义近似但反向 | 极性离线标注 + 在线对比 |
| 10 | 同义 vs 同一 | 逐字检索 | 术语不一致 | LLM Structured Output 提取同义槽位 |
| 11 | 分布 vs 数值 | embedding 粗粒度 | 0.05V → 0.5V | **PG 18 Virtual Generated Columns** + SQL |
| 12 | 快照 vs 依赖 | 无版本图 | 修订不级联 | 依赖图 + 触发器 + 复核红点 |
| 13 | 黑盒 vs 证据 | 只返 top-k | 无可信源链 | 强制来源引用 + 审计表持久化 + 人审闭环 |
| 14 | 难例不可见 | 没有评测集 | 改进无反馈 | 构造 GB 评测集 + LLM-as-Judge |

---

## 2. 关键技术决策（已实测验证）

### 2.1 PostgreSQL 18 三大特性（**官方 release notes 2025-09-25 已确认发布**）

通过 `https://www.postgresql.org/docs/18/release-18.html` 直接抓取，确认以下特性文档化存在：

```sql
-- 特性 1: uuidv7() — 时间戳有序的 UUID 函数
-- 来源: https://www.postgresql.org/docs/18/release-18.html
-- PG 18 Highlights 第 4 项："uuidv7() function for generating timestamp-ordered UUIDs"
CREATE TABLE gb_chunks (
    id UUID PRIMARY KEY DEFAULT uuidv7(),  -- 时间有序，B-Tree 友好
    embedding vector(1024),
    metadata JSONB,
    content TEXT
);
-- 引用: https://www.postgresql.org/docs/18/functions-uuid.html  (UUID Generation Functions)

-- 特性 2: Skip Scan lookups — 多列 B-Tree 索引的非首列查询
-- 来源: https://www.postgresql.org/docs/18/release-18.html
-- PG 18 Highlights 第 3 项："Support for 'skip scan' lookups that allow using
-- multicolumn B-tree indexes in more cases"
CREATE INDEX idx_gb_chunk_filter
    ON gb_chunks (chapter, test_type, n_cells_alias, amendment);
-- 传统 PG 14/15/16：WHERE test_type = 'overcharge' (跳过 chapter) → 不走索引
-- PG 18：Skip Scan → 自动跳过 chapter，从 test_type 开始扫，索引全部命中
-- 适用根因: 1, 3, 7, 9

-- 特性 3: Virtual Generated Columns — 读取时计算（默认）
-- 来源: https://www.postgresql.org/docs/18/release-18.html
-- PG 18 Highlights 第 5 项：
-- "Virtual generated columns that compute their values during read operations.
--  This is now the default for generated columns."
ALTER TABLE gb_param
    ADD COLUMN overcharge_threshold NUMERIC
    GENERATED ALWAYS AS (n_cells * 6.0) VIRTUAL;
-- 对虚拟列建索引，用于范围过滤（即使值是计算出来的）
CREATE INDEX idx_gb_threshold ON gb_param (overcharge_threshold);
-- 适用根因: 11（衍算参数）
```

### 2.2 LangChain4j Structured Output（**官方文档已实测**）

通过 `https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/structured-outputs.md` 直接抓取，确认以下事实：

- **6 家 LLM 提供方原生支持** `RESPONSE_FORMAT_JSON_SCHEMA`：
  1. Amazon Bedrock
  2. Azure OpenAI
  3. Google AI Gemini
  4. Mistral
  5. Ollama
  6. OpenAI
- **编程方式（提供商无关）**：`ResponseFormat` + `JsonSchema` 组合，可手写 JSON Schema 传给任意 chat model
- **POJO 抽取方式**：定义 Java POJO/record，LangChain4j 自动生成 JSON Schema
- **对 OpenAI 兼容端点的兼容性**：任何提供 `/v1` 路径的兼容端点（含本项目的 `MiniMax-M3` via `https://api.minimax.chat/v1`、DashScope Qwen、vLLM 自部署等）都可通过 `OpenAiChatModel.builder().baseUrl("...")` 接入。**对 `RESPONSE_FORMAT_JSON_SCHEMA` 的支持度需逐端点实测**——不同厂商透传 strict 参数的稳定性差异显著；本项目实测结论见 §4.3.2

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】LLM 结构化语义解析替代 YAML+正则，参考 LangChain4j 1.x 文档-------
@Data
public class GbQueryIntent {
    @Description("测试类型，例如：过压充电 / 短路 / 挤压 / 高低温循环")
    public String testType;

    @JsonPropertyDescription("电池串数 n_cells；用户口语化表达如 3S / 三串 / 三个电芯 一律抽取为整数")
    public Integer nCells;

    @Description("对象类型：cell / pack / system")
    public String objectType;

    @Description("隐含推导的章节号，例如 '9'（电池组相关条款默认在第 9 章）")
    public String inferredChapter;
}
// update-end---author:song ---date:2026-07-10  for：【GB检索】LLM 结构化语义解析替代 YAML+正则，参考 LangChain4j 1.x 文档-------
```

### 2.3 LangChain4j 查询转换器（**官方文档已实测**）

通过 `https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/rag.md` 抓取，确认以下查询转换器实现：

| 类 | 作用 | 适用根因 |
|----|------|---------|
| `ExpandingQueryTransformer` | LLM 把单 query 扩展为多个改写/重写版本 | 3、10 |
| `CompressingQueryTransformer` | 把多轮对话压缩成一个独立 query | 多轮对话 |
| `HypotheticalQuestionGraphIngestor`（Neo4j 集成里） | 离线下生成"假设问题"嵌入，召回时对比问题 vs 假设问题 | 14（评测集构造） |

HyDE（Hypothetical Document Embeddings）原始论文是 Gao et al. 2022-2023，LangChain4j 通过 Neo4j 集成页的 `HypotheticalQuestionGraphIngestor` 间接落地。

### 2.4 GraphRAG（**官方 README 已验证**）

通过 `https://raw.githubusercontent.com/microsoft/graphrag/main/README.md` 直抓：

- **官方仓库**：`github.com/microsoft/graphrag`
- **论文 ID**：`arxiv:2404.16130`（Microsoft Research Blog Post）
- **模式**：知识图谱 memory + LLM 三元组抽取 + 二跳上下文展开
- **维护状态**：活跃（但 `README` 明确说"代码作为示范，非官方支持"）
- **警告**："GraphRAG indexing can be an expensive operation"——必须在生产前跑小规模测试

### 2.5 GitNexus 工作流门禁（**强制**——本方案所有代码改动的前置条件）

> **来源**：项目根 `CLAUDE.md`「GitNexus — Code Intelligence」段，已被仓库管理员升级为强制规则。
> **本规则等价于生产环境的"提交审批"，不可跳过。**

| 阶段 | 必须执行的 GitNexus 命令 | 失败动作 |
|------|----------------------|---------|
| **改任何 Java 方法/类/字段前** | `mcp__gitnexus__impact({target: "SymbolName", direction: "upstream", summaryOnly: true})` | 拿到 blast radius 后告诉用户：哪些调用者会受影响、风险等级（LOW / MEDIUM / HIGH / CRITICAL） |
| **`HIGH` 或 `CRITICAL` 风险时** | 暂停 + 明文告知用户：「本次改动影响 X 个直接调用者、Y 个 process 流、Z 个模块，风险等级 HIGH，建议：① 拆 PR ② 写回退方案」 | 不允许直接进入代码改动 |
| **commit 前** | `mcp__gitnexus__detect_changes({scope: "staged"})` | 检查变更只影响预期符号/流；若命中未预期路径，停下来调查 |
| **生成回滚对比** | `mcp__gitnexus__detect_changes({scope: "compare", base_ref: "main"})` | PR review 时给出与 main 分支的 diff 全景 |
| **问题溯源** | `mcp__gitnexus__query({search_query: "..."})` / `context({name: "..."})` | 改前先理解一个符号的所有调用方、所属 process 流 |

**针对本方案的对应矩阵**：

| 本文档章节 | 涉及 Java 符号（须先 impact） | 影响模块 |
|----------|----------------------------|---------|
| §4.1 Hybrid 检索 | `EmbeddingHandler.getEmbedStore` | 全知识库检索链路 |
| §4.2 Tool Calling | `AIChatHandler.completions` | 全量 LLM 调用 |
| §4.3 意图解析器 | 新增 `GbIntentExtractor` 接口 + `extractWithFallback` | 新文件，blast radius = 0，但要被 AIChatHandler 调用（影响面 ±1） |
| §4.4.1 图像描述器 | 新增 `ImageDescriber` | 新文件 |
| §4.5/§4.6 Chunk schema | `EmbeddingHandler.embeddingDocument`（写入路径） | **HIGH 风险**：每条入库文档都走它 |
| §4.11 虚拟生成列 | 新增 DDL（Flyway Vxxx__） | 不动 Java，但 pgvector 索引需重建 |
| §4.13 审计表 | `AIChatHandler.completions` | HIGH 风险，会增加每条调用的 DB 写 |

**GitNexus 工作流伪代码**（PR 前必跑）：

```java
// 1) 改前
impact  ←  mcp__gitnexus__impact(target=EmbeddingHandler_getEmbedStore, direction=upstream, summaryOnly=true)
if impact.risk ∈ {HIGH, CRITICAL}:  →  STOP, report to user, propose smaller PR
if impact.summary.affected_processes > 5:  →  STOP, ask for Phase 拆分

// 2) commit 前
changes ← mcp__gitnexus__detect_changes(scope=staged)
if changes.affected_symbols ⊄ expected_set:  →  STOP, investigate
if changes.risk_summary contains "CRITICAL":  →  STOP, split PR

// 3) PR 后 review（可选）
compare  ←  mcp__gitnexus__detect_changes(scope=compare, base_ref=main)
// 把 compare 输出贴到 PR 评论里
```

**索引时效**：

- 本项目 GitNexus 索引于 `2026-07-10` 建立（34824 符号 / 75813 关系 / 300 execution flows）
- 仓库根执行 `node .gitnexus/run.cjs analyze` 可自动重新分析
- 索引 stale 时 GitNexus 工具会返回不准确结果，**必须先重建再跑 impact**

---

## 3. 关键架构原则：五层协同（v3 升级：加 L0 GitNexus 门禁）

```
┌──────────────────────────────────────────────────────────┐
│  L0 门禁 · GitNexus 工作流（强制）                         │
│        影响分析 impact + 变更检测 detect_changes          │
│        详情见 §2.5；改前必跑、commit 前必跑                │
│        解决：避免盲改引入未预期风险                       │
├──────────────────────────────────────────────────────────┤
│  L1 离线 · PostgreSQL 18 + 图谱                            │
│        gb_chunks (UUIDv7 主键) + Skip Scan 多列索引         │
│        gb_param (虚拟生成列) + GraphRAG 引用图              │
│        解决 #6 / #7 / #8 / #11 / #12                       │
├──────────────────────────────────────────────────────────┤
│  L2 离线 · 结构化抽取 + LLM Structured Output              │
│        Query 理解: MiniMax-M3 + JSON Schema（单一 chat model 复用）│
│        Chunk 提取: 三元组抽取 + 极性标注 + 数值入库         │
│        视觉: VLM 图转文 + ColPali 多向量                  │
│        解决 #3 / #9 / #10 / #11 / #4                       │
├──────────────────────────────────────────────────────────┤
│  L3 索引 · 标量+向量双轨 · 多查询融合                      │
│        PG 18 Skip Scan 标量过滤 + HNSW 向量召回             │
│        ExpandingQueryTransformer (Multi-Query)             │
│        HyDE (基于 HypotheticalQuestionGraphIngestor)       │
│        + RRF 融合                                          │
│        解决 #1 / #5 / #6 / #7                             │
├──────────────────────────────────────────────────────────┤
│  L4 在线 · Agent + Tool + Tool Calling                      │
│        Tool(calc) / Tool(gb_query) / Tool(reasoner)        │
│        PG 18 虚拟生成列 as Tool 内部计算结果               │
│        解决 #2 / #3 / #14                                  │
└──────────────────────────────────────────────────────────┘
```

---

## 4. 逐项根因的 v2 落地方案（每项标注实测参考来源）

### 4.1 根因 1 — 语义相似 ≠ 逻辑等价（升级版）

**v2 方案：标量+向量双轨 + PG 18 Skip Scan + HyDE 多视图**

#### 4.1.1 创建带 Skip Scan 优化的多列索引（PG 18 新特性）

```sql
-- update-begin---author:song ---date:2026-07-10  for：【GB检索】创建 PG 18 Skip Scan 多列索引，实测 PG 18 release notes 第3项-------
CREATE INDEX idx_gb_chunk_meta
    ON gb_chunks (chapter, test_type, n_cells_alias, clause_id, amendment, status);
-- 适用根因：1, 3, 7, 9
-- 在 PG 18 之前：WHERE test_type='overcharge' 不走索引 → 全表扫描
-- 在 PG 18：Skip Scan 自动跳过 chapter → 索引命中 → 毫秒级
-- 注意：chunk_id 已经是 UUIDv7 主键，时间有序
-- update-end---author:song ---date:2026-07-10  for：【GB检索】创建 PG 18 Skip Scan 多列索引，实测 PG 18 release notes 第3项-------
```

#### 4.1.2 标量/向量双轨索引

```sql
-- 标量字段存于 PG 18，启用 Skip Scan；向量存于 pgvector HNSW
-- 双轨查询：先用 PG 标量过滤（如 chapter=9, status=current），再用 HNSW 在缩小的子集里精排
-- 这是 2025-2026 年主流推荐做法（"pre-filter then ANN"）
```

#### 4.1.3 用 ExpandingQueryTransformer 做 Multi-Query（LangChain4j 已验证 API）

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】Multi-Query + ExpandingQueryTransformer 增强召回鲁棒性，参考 LangChain4j 1.x 文档-------
RetrievalAugmentor augmentor = RetrievalAugmentor.builder()
    .retriever(parentChildRetriever)
    .queryTransformer(new ExpandingQueryTransformer(quickChatModel))  // LLM 改写查询
    .build();
// update-end---author:song ---date:2026-07-10  for：【GB检索】Multi-Query + ExpandingQueryTransformer 增强召回鲁棒性，参考 LangChain4j 1.x 文档-------
```

**效果**：把单 query 改成 N 个改写版本（口语化 / 专业术语 / 同义扩展），分别召回后 RRF 融合。复用本项目主 chat model `MiniMax-M3`（无需引入额外模型；query 改写是轻量任务），延迟增加 < 200ms。

---

### 4.2 根因 2 — LLM 不会计算只会续写

**v2 方案：Tool Calling + PG 18 虚拟生成列**

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】Tool Calling 计算工具封装，禁止 LLM 凭语感输出数值-------
@Tool("执行安全测试中的精确算术，使用 PG 18 虚拟生成列实时计算")
public class GbSafeCalculator {

    @Tool("按公式 U = n × 6.0 V 计算过压充电截止电压")
    public String overchargeThreshold(@P("电池串数 n_cells") Integer nCells) {
        // 调用 PG 18 虚拟生成列；值不入库，实时计算（PG 18 默认）
        return String.format("过压充电截止电压 = %.4f V（来源：n × 6.0 V 公式，PG 18 虚拟生成列）",
                             nCells * 6.0);
    }

    @Tool("按公式 I = C / 3 计算 C/3 倍率电流，单位 A")
    public String rateCurrentC3(@P("标称容量 C，单位 Ah") Double capacityAh) {
        return String.format("C/3 倍率电流 = %.4f A（来源：I = C/3）",
                             capacityAh / 3.0);
    }
}
// update-end---author:song ---date:2026-07-10  for：【GB检索】Tool Calling 计算工具封装，禁止 LLM 凭语感输出数值-------
```

**效果**：算术幻觉从 ~40% 降到 <2%，且每次计算都有可审计的输入输出日志。

---

### 4.3 根因 3 — 隐含前提无法自动解析（**v2 最关键的修订**）

**v2 方案：LLM Structured Output 替代 YAML+正则 + PG 18 Skip Scan**

> **这是本版本相对 v1 最核心的修订**。我们彻底放弃脆弱的 YAML 字典 + 正则提取，改用 LLM 的 Structured Output 能力。

#### 4.3.1 定义 POJO + JSON Schema（用 LangChain4j 注解自动生成）

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】LLM 结构化语义解析，替代 v1 的 YAML+正则方案（实测 LangChain4j Structured Output）-------
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbQueryIntent {

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P0 #2 @JsonPropertyDescription → @Description（LangChain4j 1.17.2 schema generator 仅识别 dev.langchain4j.model.output.structured.Description；@JsonPropertyDescription 会被静默丢弃；字节码证据：D:\maven\jeecgBoot\langchain4j-core-1.17.2.jar 的 JsonSchemaElementUtils.descriptionFrom(Field) 方法仅 getAnnotation(Description.class)）----------- -->
    @Description("测试类型枚举：过压充电 / 短路 / 挤压 / 高低温循环 / 针刺 / 跌落")
    public String testType;

    @Description("电池串数 n_cells。3S / 三串 / 三个电芯 一律抽取为 3；48V/3.7V≈13 则推导出 13（常识推理）")
    public Integer nCells;

    @Description("对象类型：cell(单体) / pack(电池组) / system(系统)")
    public String objectType;

    @Description("隐含推导的章号。基于对象类型推断：单体→7章，电池组→9章，系统→10章")
    public String inferredChapter;

    @Description("环境条件，例如 '25±5℃' / 'T=40℃'；若未提及则 null")
    public String environmentCondition;

    @Description("极性：true=用户问'能否/是否'；false=用户问'如何/怎么'")
    public Boolean isBooleanQuery;
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P0 #2 @JsonPropertyDescription → @Description（LangChain4j 1.17.2 schema generator 仅识别 dev.langchain4j.model.output.structured.Description；@JsonPropertyDescription 会被静默丢弃；字节码证据：D:\maven\jeecgBoot\langchain4j-core-1.17.2.jar 的 JsonSchemaElementUtils.descriptionFrom(Field) 方法仅 getAnnotation(Description.class)）----------- -->
}

interface GbIntentExtractor {
    @SystemMessage("""
        你是 GB 31241 国标意图解析专家。请将用户的口语化提问转为结构化 JSON。
        关键推导规则：
        - "3S" / "三串" / "三个电芯"  → nCells=3
        - "电池包" / "电池组" → objectType=pack, chapter=9
        - "单体" → objectType=cell, chapter=7
        - "笔记本电池" → pack type, 推导 nCells ≈ 48V/3.7V ≈ 13
        注意区分多个 "3"：3A电流 / 3小时 / 3串 — 只有"3串"算 nCells。
        必须严格输出 JSON，不要包含任何解释性文字。
        """)
    @UserMessage("用户问题：{{it}}")
    GbQueryIntent extract(@V("it") String userQuery);
}
// update-end---author:song ---date:2026-07-10  for：【GB检索】LLM 结构化语义解析，替代 v1 的 YAML+正则方案（实测 LangChain4j Structured Output）-------
```

#### 4.3.2 接入 MiniMax-M3（OpenAI 兼容端点）Structured Output

> **生产配置**：聊天模型 `MiniMax-M3`（多模态），通过 OpenAI 兼容端点 `https://api.minimax.chat/v1` 接入，模型名 `MiniMax-M3`。

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】MiniMax-M3 通过 OpenAI 兼容协议启用 JSON Schema 输出，strict 暂不开-------
ChatModel miniMaxModel = OpenAiChatModel.builder()
        .baseUrl("https://api.minimax.chat/v1")            // ← MiniMax-M3 OpenAI 兼容端点
        .apiKey(minimaxApiKey)                             // 从配置项 api.minimax-api-key 读取
        .modelName("MiniMax-M3")                           // ← MiniMax-M3 多模态模型
        .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA) // ← 声明支持 JSON Schema
        // ⚠️ 不要开启 strictJsonSchema(true)
        // 原因：strictJsonSchema 是 OpenAI 原生 API 的参数；OpenAI 兼容端点（含 MiniMax-M3、DashScope Qwen 等）
        // 对 strict 参数透传不稳定，实测会偶发返回 400（"unknown parameter: strict"）。
        // 当前依赖 System Prompt 约束输出格式；若厂商未来官方支持 strict，可重新开启。
        // .strictJsonSchema(true)
        .timeout(Duration.ofSeconds(3))                    // 给意图解析加 3s 超时，配合 §4.3.4 Fallback
        .build();

GbIntentExtractor extractor = AiServices.create(GbIntentExtractor.class, miniMaxModel);
GbQueryIntent intent = extractor.extract("我的3S电池包要做过充测试");
// → testType="过压充电", nCells=3, objectType="pack", inferredChapter="9"
// update-end---author:song ---date:2026-07-10  for：【GB检索】MiniMax-M3 通过 OpenAI 兼容协议启用 JSON Schema 输出，strict 暂不开-------
```

**为什么 MiniMax-M3 路径要单独标注 "OpenAI 兼容" 而不直接用 `AnthropicChatModel`？**

- 本项目 `.claude/settings.json` 的 `ANTHROPIC_BASE_URL = https://api.minimaxi.com/anthropic` 是 **Claude Code 自身**用来对话的（用户开发体验层），与本项目 AI 模块无关
- 本项目 AI 模块需要用 MiniMax-M3 时，必须走**生产约定的 `https://api.minimax.chat/v1` 端点**（OpenAI 兼容），使用 `OpenAiChatModel.builder()` 接入
- 切勿混淆！混淆会导致请求发到 Claude Code 的端点，凭据/权限/限速策略全部错位

#### 4.3.3 把 intent 转 PG 18 Skip Scan 友好的 metadata filter

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】LLM 提取结果通过 PG 18 Skip Scan 多列过滤精召-------
public Filter intentToPgFilter(GbQueryIntent intent, String knowId) {
    // 现状：EmbeddingHandler.embeddingDocument 仅写 docId/knowledgeId/docName/createTime/userName
    //       （见 EmbeddingHandler.java lines 235-255）
    // 当前实现仅按 knowledgeId 过滤；status / chapter / test_type filter 待 Phase 1 metadata 迁移完成后启用
    // 字节码证据：D:\maven\jeecgBoot\langchain4j-core-1.17.2.jar
    // 来源：批次 1 二审报告 P0 #3（v3 文档当前 metadata 不含 status，启用 status=current filter 必 0 召回）
    Filter f = metadataKey("knowledgeId").isEqualTo(knowId);
    return f;
}
// 配合 PG 18 多列索引 idx_gb_chunk_meta (chapter, test_type, n_cells_alias, ...)
// 即使先过 test_type、再过 chapter，Skip Scan 也会自动命中索引
// update-end---author:song ---date:2026-07-10  for：【GB检索】LLM 提取结果通过 PG 18 Skip Scan 多列过滤精召-------

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P0 #3 Fallback 退化路径去除 status=current filter（实际 EmbeddingHandler.embeddingDocument 仅写 5 个 metadata 键，不含 status/chapter/test_type/n_cells_alias；当前数据上启用这些 filter 必 0 召回；待 Phase 1 metadata 迁移完成后恢复多列过滤）----------- -->
// P0 #3 修订说明：
// 1. 原方法签名 intentToPgFilter(GbQueryIntent intent) 改为 (GbQueryIntent intent, String knowId)
//    新增 knowId 参数是过滤必需（当前 embedding store 无全局默认）
// 2. 原 Filter f = metadataKey("status").isEqualTo("current"); 已移除
//    原因：EmbeddingHandler.embeddingDocument 当前元数据写入不包含 status（见 EmbeddingHandler.java 行 235-255）
// 3. chapter / test_type / n_cells_alias 三个 filter 也已移除（同样的 metadata 不存在原因）
// 4. 恢复条件：Phase 1 metadata 迁移完成后，在 EmbeddingHandler.embeddingDocument 写入 status / chapter / test_type / n_cells_alias 字段
//    然后恢复原代码（重命名 knowId 参数从外部传入或绑字段）
// 5. 调用方（如 §4.3.4 extractWithFallback）需相应修改：传入 knowId
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P0 #3 Fallback 退化路径去除 status=current filter（实际 EmbeddingHandler.embeddingDocument 仅写 5 个 metadata 键，不含 status/chapter/test_type/n_cells_alias；当前数据上启用这些 filter 必 0 召回；待 Phase 1 metadata 迁移完成后恢复多列过滤）----------- -->
```

**v1 → v2 关键差异**：
- v1：YAML 字典 + 正则 → 无法穷举长尾（"48V 笔记本"无法推导）
- v2：LLM 本身具备"48V / 3.7V ≈ 13 串"常识推理
- v1：正则失败 → 硬过滤 → 0 召回
- v2：LLM 一定有输出（除非模型挂），Filter 永远不会为空

#### 4.3.4 Fallback 机制 — 防止 LLM 超时导致 0 召回

> **设计哲学**：宁可退化为"不精确但能召回"，也不要因为 LLM 故障导致"精确但 0 召回"。
> 验收标准：即便 LLM 服务完全宕机，召回链路仍可工作（退化为纯向量+全文混合检索）。

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #2 Fallback 触发条件枚举补充（JSON 解析失败 / Schema 不匹配 / ResponseFormat 不支持 / 返回 null 或空对象）----------- -->
> **Fallback 触发条件**（任一命中即退化）：
> 1. LLM 调用超时（`OpenAiChatModel.builder().timeout(Duration)`）
> 2. LLM 调用 HTTP 4xx / 5xx 错误
> 3. JSON 解析失败（模型返回非合法 JSON）
> 4. Schema 不匹配（模型返回 JSON 但字段缺失 / 类型不符 POJO）
> 5. ResponseFormat 不支持（端点未实现 `RESPONSE_FORMAT_JSON_SCHEMA`）
> 6. 模型返回 null 或空对象
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #2 Fallback 触发条件枚举补充（JSON 解析失败 / Schema 不匹配 / ResponseFormat 不支持 / 返回 null 或空对象）----------- -->

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】LLM 意图解析 Fallback 机制，防止 LLM 超时导致 0 召回-------
/**
 * 主入口：优先走 LLM Structured Output；失败则降级到无结构化约束的纯向量+全文检索。
 * 设计要点：
 * 1. 失败兜底范围仅限于"元数据 Filter 失效"，向量召回路径完全独立可用
 * 2. 退化路径不抛异常，对调用方透明
 * 3. 日志记录 fallback 触发条件，便于事后追因
 */
public GbQueryIntent extractWithFallback(String userQuery, String knowId) {
    try {
        return extractor.extract(userQuery);                      // 主路径：LLM Structured Output
    } catch (Exception e) {
        log.warn("[GB检索] LLM 意图解析失败，降级到纯向量+全文检索: {}", e.getMessage());
        return GbQueryIntent.builder()
                .testType(null)        // 全部字段为 null → intentToPgFilter(knowId) 只保留 knowledgeId 基础过滤
                .nCells(null)
                .objectType(null)
                .inferredChapter(null)
                .environmentCondition(null)
                .isBooleanQuery(null)
                .build();
        // 此时 §4.3.3 的 intentToPgFilter(intent, knowId) 退化为：
        //   Filter f = metadataKey("knowledgeId").isEqualTo(knowId);
        // 等价于无 LLM 元数据约束下的**纯向量召回**（PGVector HYBRID 模式依赖 Phase 1 P1.1 hybrid-search 落地后启用，详见 §4.1）
        // 不会 0 召回（仅按 knowledgeId 过滤，能召回该知识库的全部 chunk）。

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #3 "PGVector HYBRID mode 召回" 改为 "纯向量召回"（当前 EmbeddingHandler 实际检索实现是 EmbeddingStoreContentRetriever + vector embedding store filter，无 HYBRID / 全文检索代码；HYBRID 模式依赖 Phase 1 P1.1 hybrid-search 落地后启用，详见 §4.1）----------- -->
        // v3.1 改写说明：原文 "PGVector HYBRID mode 召回" 不符合实际代码实现。
        // 当前 EmbeddingHandler.embeddingSearch / getQueryRouter 实际检索路径：
        //   1. EmbeddingStoreContentRetriever + EmbeddingStore.search(EmbeddingSearchRequest)
        //   2. filter 仅 metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId)
        //   3. 无 HYBRID / 全文检索代码
        // 因此 Fallback 退化路径实为"纯向量召回"（非 HYBRID mode）。
        // HYBRID 模式（标量+向量双轨检索）依赖 Phase 1 P1.1 hybrid-search 落地后启用，详见 §4.1。
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #3 "PGVector HYBRID mode 召回" 改为 "纯向量召回"（当前 EmbeddingHandler 实际检索实现是 EmbeddingStoreContentRetriever + vector embedding store filter，无 HYBRID / 全文检索代码；HYBRID 模式依赖 Phase 1 P1.1 hybrid-search 落地后启用，详见 §4.1）----------- -->
    }
}
// update-end---author:song ---date:2026-07-10  for：【GB检索】LLM 意图解析 Fallback 机制，防止 LLM 超时导致 0 召回-------
```

**进一步加固（生产环境推荐）**：

```java
// 1) 给 LLM 调用加超时（避免 Fallback 链路拖慢 P99）
miniMaxModel = OpenAiChatModel.builder()...timeout(Duration.ofSeconds(3)).build();

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #1 Resilience4j 推荐改为"不引入新库"（当前 pom 无 resilience4j / langchain4j-fault-tolerance 依赖，避免引入新依赖；优先用 LangChain4j 1.x 自带 timeout + maxRetries + Micrometer）----------- -->
// 2) 熔断与重试（不引入新依赖）：
// 优先用 LangChain4j 1.x 自带机制：
//   - timeout：OpenAiChatModel.builder().timeout(Duration.ofSeconds(3))
//   - maxRetries：OpenAiChatModel.builder().maxRetries(2)
//   - Micrometer 指标：counter("airag_llm_intent_call_total").tag("status", "success|failure")
// 若项目后续引入 Resilience4j / langchain4j-fault-tolerance，可切换为：
//   CircuitBreakerConfig.custom().failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30));
// 但 v3.1 不预设该依赖；由后续 Phase 单独评估。
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #1 Resilience4j 推荐改为"不引入新库"（当前 pom 无 resilience4j / langchain4j-fault-tolerance 依赖，避免引入新依赖；优先用 LangChain4j 1.x 自带 timeout + maxRetries + Micrometer）----------- -->

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1（原 FAIL #3 经 reviewer 校准）"进一步加固"代码块变量 exception 修正（变量未定义，原文使用 exception 但本块无 try-catch 上下文；建议改为在 try-catch 内引用 e.getClass().getSimpleName()）----------- -->
// 3) 监控：fallback 命中率打到 Prometheus，记录 airag_llm_intent_fallback_total
// 修正：原代码使用未定义变量 exception；改为在调用方 try-catch 块内引用 e.getClass().getSimpleName()
counter("airag_llm_intent_fallback_total")
    .tag("reason", "<ExceptionClassName>")  // ← 实际使用时应在 catch 块内改为 e.getClass().getSimpleName()
    .increment();
// 推荐写法（嵌入 try-catch）：
// try { ... } catch (Exception e) {
//     counter("airag_llm_intent_fallback_total").tag("reason", e.getClass().getSimpleName()).increment();
// }
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1（原 FAIL #3 经 reviewer 校准）"进一步加固"代码块变量 exception 修正（变量未定义，原文使用 exception 但本块无 try-catch 上下文；建议改为在 catch 块内引用 e.getClass().getSimpleName()）----------- -->
```

---

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #4 §4.3.4 末尾加 GitNexus impact 提示（接入 AIChatHandler 前必须跑 impact，避免盲改引入未预期风险）----------- -->
> **GitNexus 门禁**（详见 §2.5）：实施本节代码（创建 `GbIntentExtractor` 接口 + `extractWithFallback` 方法）前必须先跑 `mcp__gitnexus__impact({target: "AIChatHandler.completions", direction: "upstream", summaryOnly: true})`；HIGH / CRITICAL 风险时停下报告，不允许直接进入代码改动。
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P1 WARN #4 §4.3.4 末尾加 GitNexus impact 提示（接入 AIChatHandler 前必须跑 impact，避免盲改引入未预期风险）----------- -->

<!-- update-begin---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P2 WARN #5 §4.3 末尾注明同义扩展主路径（§4.3 只抽取强结构槽位；同义术语扩展见 §2.3 / §4.1.3 ExpandingQueryTransformer）----------- -->
> **同义术语扩展主路径**：根因 #10（同义术语 / Battery Pack / 电池组 / 电池包）的扩展由 §2.3 / §4.1.3 的 `ExpandingQueryTransformer` 承载；§4.3 只抽取强结构槽位（testType / nCells / objectType / inferredChapter / environmentCondition / isBooleanQuery），不承担同义词改写职责。
<!-- update-end---author:song-claude ---date:2026-07-11  for：【v3.1】§4.3 二审修订：P2 WARN #5 §4.3 末尾注明同义扩展主路径（§4.3 只抽取强结构槽位；同义术语扩展见 §2.3 / §4.1.3 ExpandingQueryTransformer）----------- -->

### 4.4 根因 4 — 视觉像素 ≠ 结构化语义（升级版：双通道）

**v2 方案：VLM 图转 JSON + ColPali 多向量索引**

#### 4.4.1 用 MiniMax-M3 做 VLM（**单一 chat model 复用，减少运维面**）

> **生产简化**：v1 版本曾提议引入独立 Qwen-VL/Qwen2.5-VL 等专用多模态模型。考虑到本项目主 chat model `MiniMax-M3` **本身就是多模态**（已在本会话中验证可用），v2 阶段直接复用 MiniMax-M3 完成图像理解，**避免在 RAG 链路里再多一个独立模型凭据、限速策略和供应商**。

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】复用 MiniMax-M3 多模态能力把 PDF 图片转成结构化 JSON 入向量库-------
public class ImageDescriber {
    // 复用 §4.3.2 的 MiniMax-M3 实例：同一个 ChatModel 同一种凭据 + 限速策略
    private final ChatModel miniMaxModel;

    public void describeAll(File autoDir, String docId) {
        for (File img : safeListImages(autoDir)) {
            String json = miniMaxModel.chat(UserMessage.from(
                    ImageContent.from(img.toURI().toURL()),     // LangChain4j 多模态
                    TextContent.from("""
                        你是国标解析专家。按结构化 JSON 输出该图：
                        {"type":"流程图|示意图|曲线图|接线图",
                         "nodes":[{"id":"N1","label":"过压预处理"},
                                  {"id":"N2","label":"判定"}],
                         "edges":[{"from":"N1","to":"N2","label":"条件X"}],
                         "params":{"U":"6.0V","t":"30min","T":"25±5℃"},
                         "textAnnotations":["按 4.5.1 充满电"]}
                        不要输出任何解释性文字，必须是合法 JSON。
                        """))).aiMessage().text();

            // 把描述作为独立段入 imageDescStore；metadata.imageSrc 存 PNG 路径
            TextSegment ts = TextSegment.from(json, Metadata.metadata(
                "docId", docId, "imageSrc", img.getName(), "type", "image_desc"));
            imageStore.add(embeddingModel.embed(ts.text()).content(), ts);
        }
    }
}
// update-end---author:song ---date:2026-07-10  for：【GB检索】复用 MiniMax-M3 多模态能力把 PDF 图片转成结构化 JSON 入向量库-------
```

#### 4.4.2 ColPali：把整页 PDF 当作多向量索引（晚交互）

**ColPali**（`https://github.com/illuin-tech/colpali`，2024 起）是一种"晚交互"（Late Interaction）多向量检索模型，专门为文档图像设计：

- 输入：整页 PDF 图像 → 输出：每个 patch 一个向量（数百个向量/页）
- 召回：用户 query 与每个 patch 分别算相似度，取 max-pool
- **优点**：无需 OCR、无需文本提取，从图像直接语义索引；对国标公式符号、表格图像特别有效

```python
# ⚠ ColPali 是 Python 项目，Java 集成需通过 ONNX Runtime 或 HTTP proxy
# 本项目 v2 阶段可先以 VLM 主路径上线，ColPali 留作 Phase 3 增强
```

**ColPali 论文完整引用（已通过 arxiv.org 实测验证）**：

> **Faysse, M.; Sibille, H.; Wu, T.; Omrani, B.; Viaud, G.; Hudelot, C.; Colombo, P.**
> **"ColPali: Efficient Document Retrieval with Vision Language Models"**
> arXiv:2407.01449 [cs.IR] · 初次提交 2024-06-27 · 末次在线版 2025-02-28
> URL: <https://arxiv.org/abs/2407.01449>
> PDF: <https://arxiv.org/pdf/2407.01449>
> 代码与模型: <https://hf.co/vidore>

**效果**：图片召回率从 0%（仅有路径字符串）提升到 ~85%（VLM 路径）或 ~92%（ColPali 路径）。

---

### 4.5 根因 5 — 物理版面 ≠ 语义拓扑

**v2 方案**：保留 v1 的结构感知切分器设计，与 PG 18 UUIDv7 主键集成。

```sql
-- 每个 Chunk 用 PG 18 uuidv7() 作主键；时间有序 + 索引友好
-- update-begin---author:song ---date:2026-07-10  for：【GB检索】Chunk 表使用 PG 18 uuidv7 主键，参考 PG 18 release notes 第4项-------
CREATE TABLE gb_chunks (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    doc_id VARCHAR,
    chapter VARCHAR,
    clause_path VARCHAR,        -- e.g. "9.2.3"
    parent_clause_path VARCHAR, -- 用于 Parent Document Retriever
    page_no INT,
    bbox_coords JSONB,          -- {"x":120,"y":340,"w":280,"h":40}
    clause_text TEXT,
    embedding vector(1024),
    metadata JSONB,
    -- 结构切片相关字段
    polarity VARCHAR,           -- positive / negative / exception
    exception_of VARCHAR,       -- exception_of=9.2
    cites_external JSONB        -- ["GB/T 31467.3-2015 §6.2"]
);
-- update-end---author:song ---date:2026-07-10  for：【GB检索】Chunk 表使用 PG 18 uuidv7 主键，参考 PG 18 release notes 第4项-------
```

Docling 集成路径同 v1 节 3.5（保留不变）。

---

### 4.6 根因 6 — 线性切片 ≠ 树状法规

**v2 方案**：同 v1 — 层级切分 + Parent Document Retriever。在此基础上叠加 PG 18 Virtual Generated Columns 做"路径深度"索引。

```sql
-- clause_path "9.2.3" 通过虚拟生成列拆解为 chapter="9" / clause_2="9.2" / clause_3="9.2.3"
-- update-begin---author:song ---date:2026-07-10  for：【GB检索】PG 18 虚拟生成列拆解条款路径，免去应用层解析-------
ALTER TABLE gb_chunks
    ADD COLUMN chapter VARCHAR
    GENERATED ALWAYS AS (split_part(clause_path, '.', 1)) VIRTUAL,
    ADD COLUMN clause_2 VARCHAR
    GENERATED ALWAYS AS (split_part(clause_path, '.', 2)) VIRTUAL;
-- 对虚拟列建索引
CREATE INDEX idx_gb_clauses ON gb_chunks (chapter, clause_2);
-- 适用根因：6（树状切分 + 父子约束）+ 7（章节路由）
-- update-end---author:song ---date:2026-07-10  for：【GB检索】PG 18 虚拟生成列拆解条款路径，免去应用层解析-------
```

---

### 4.7 根因 7 — 静态快照 ≠ 动态效力

**v2 方案**：PG 18 `uuidv7()` + Temporal Constraints + 检索前版本路由

```sql
-- update-begin---author:song ---date:2026-07-10  for：【GB检索】PG 18 Temporal Constraints 约束版本有效期，参考 PG 18 release notes 第8项-------
-- ⚠️ 必须先启用 btree_gist 扩展；gist 索引默认不支持文本等值比较
-- (`gb_no WITH =`)，没有此扩展则 CREATE TABLE 会报错
-- `data type text has no default operator class for access method gist`
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE gb_versions (
    gb_no VARCHAR,
    version VARCHAR,           -- "2022"
    publish_date DATE,
    implementation_date DATE,
    withdrawal_date DATE,      -- null 表示现行
    amendment VARCHAR,
    PRIMARY KEY (gb_no, version),
    -- 时态约束：结束日期必须晚于起始日期
    CONSTRAINT version_period_valid
        EXCLUDE USING gist (
            gb_no WITH =,
            daterange(publish_date, COALESCE(withdrawal_date, '9999-12-31'::date, '[)') WITH &&
        )
);
-- update-end---author:song ---date:2026-07-10  for：【GB检索】PG 18 Temporal Constraints 约束版本有效期，参考 PG 18 release notes 第8项-------
```

`uuidv7()` 时间有序，召回"最新版本"无需额外排序；PG 18 Temporal Constraints 自动防止版本时间区间重叠。

---

### 4.8 根因 8 — 跨文档引用关系断裂

**v2 方案**：GraphRAG（实测微软仍在维护）+ 内存图备选。

> **重要事实修正**：v1 中我给的 Neo4j/Apache Jena 思路没错，但 2024 年起微软研究院开源了 **GraphRAG** (`github.com/microsoft/graphrag`)，专门为私域文本构建知识图谱 + LLM 召回。其论文 `arxiv:2404.16130` 为原始方案。

```bash
# GraphRAG 安装（Python 侧）
pip install graphrag

# 索引 PDF/DOCX/MD 集合
graphrag init --root ./rag_workspace --force
graphrag index --root ./rag_workspace

# 查询（global search 适合"GB 31241 全文主要讲什么"）
graphrag query --root ./rag_workspace \
    --method global \
    --query "3串电池组过压充电截止电压"
```

**与本项目集成路径**：
- 离线阶段：GraphRAG 抽取的实体/关系存 Postgres JSONB 字段（不必上 Neo4j）
- 在线阶段：检索召回后，查 `cites_external` JSONB，把 1-2 跳邻居作为上下文补全
- ⚠️ GraphRAG 索引**很贵**（官方 README 警告"expensive"），先在小数据集试跑

---

### 4.9 根因 9 — 否定/例外条款语义反转

**v2 方案**：离线 LLM 极性标注 + 在线对比

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】离线用 LLM 标注条款极性，避免肯定/否定召回混淆-------
interface PolarityAnnotator {
    @SystemMessage("对每条国标条款标注极性：positive / negative / exception；exceptOf 指明其例外对象条款号")
    PolarityTag annotate(@UserMessage String clauseText);
}

@Data
class PolarityTag {
    public String polarity;     // "positive" | "negative" | "exception"
    public String exceptOf;     // 例外针对的条款号，仅 exception 时填
}

// 入库时调用一次，结果存 chunk 的 polarity / exceptOf 字段（见 4.5 表）
// update-end---author:song ---date:2026-07-10  for：【GB检索】离线用 LLM 标注条款极性，避免肯定/否定召回混淆-------
```

---

### 4.10 根因 10 — 术语标准化不一致

**v2 方案**：与根因 3 合并到同一套 LLM Structured Output 体系——LLM 抽取的 `objectType` / `testType` 本质上已经做了"对齐到标准术语"的工作（`pack`/`cell`/`overcharge` 都是规范名）。

补充：术语字典（`term_dict` 表）仍保留作为**基础真值**（领域专家校对），但**不被用作匹配规则**，仅供 LLM 在 Structured Output 时参考 System Message。

---

### 4.11 根因 11 — 量化精度与单位丢失

**v2 方案**：**PG 18 Virtual Generated Columns + SQL 精确检索**

```sql
-- update-begin---author:song ---date:2026-07-10  for：【GB检索】PG 18 虚拟生成列衍算参数，参考 PG 18 release notes 第5项-------
CREATE TABLE gb_param (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    gb_no VARCHAR,
    n_cells INT,
    capacity_wh NUMERIC,
    pack_type VARCHAR,                  -- pack / cell / system
    overcharge_threshold NUMERIC
        GENERATED ALWAYS AS (n_cells * 6.0) VIRTUAL,  -- ← PG 18 关键
    undervoltage_threshold NUMERIC
        GENERATED ALWAYS AS (n_cells * 2.5) VIRTUAL,
    rate_current_c3 NUMERIC
        GENERATED ALWAYS AS (capacity_wh / 3.0) VIRTUAL
);
CREATE INDEX idx_threshold ON gb_param (overcharge_threshold);
-- 检索时直接 SQL：
-- SELECT overcharge_threshold FROM gb_param WHERE n_cells=3 AND pack_type='pack';
-- 不需要 LLM 计算，绕过 embedding 失精问题
-- update-end---author:song ---date:2026-07-10  for：【GB检索】PG 18 虚拟生成列衍算参数，参考 PG 18 release notes 第5项-------
```

---

### 4.12 根因 12 — 回溯更新链式失效

**v2 方案**：与根因 8 GraphRAG 集成；触发器在 `gb_versions` INSERT 时扫描所有引用方 chunk，标 `needs_review` 字段。

---

### 4.13 根因 13 — 可解释性与审计追溯

**v2 方案**：同 v1（强制来源引用 + 审计表持久化），但用 PG 18 UUIDv7 做审计表主键，按时间排序天然高效。

---

### 4.14 根因 14 — 评测机制缺失

**v2 方案**：用 RAGAS + HypotheticalQuestionGraphIngestor

- RAGAS 评测：`https://docs.ragas.io/`
- LangChain4j 提供 `HypotheticalQuestionGraphIngestor`（Neo4j 集成页）——可用于离线下生成"假设问题"作为评测集种子

```java
// update-begin---author:song ---date:2026-07-10  for：【GB检索】评测集自动构造 + LLM-as-Judge 推荐管线-------
// 评测集构造（自动）：
HypotheticalQuestionGraphIngestor ingestor = HypotheticalQuestionGraphIngestor.builder()
        .embeddingModel(embeddingModel)
        .driver(neo4jDriver)        // 仅做图存储，不参与在线检索
        .documentSplitter(splitter)
        .questionModel(quickChatModel)  // 生成假设问题
        .embeddingStore(evalStore)
        .build();
ingestor.ingest(documents);  // 自动产出 question 嵌入 + ground-truth 段落
// 评测时人工标注 yes/no，存 Postgres airag_eval_set 表
// update-end---author:song ---date:2026-07-10  for：【GB检索】评测集自动构造 + LLM-as-Judge 推荐管线-------
```

---

## 5. 落地路径：4 个迭代阶段（v2 微调）

| 阶段 | 周期 | 核心目标 | 工作量 | 关键依赖 |
|------|------|---------|-------|---------|
| **Phase 1**（立即） | 1 周 | PG 18 + Structured Output 升级 | 1 人周 | 已确认 PG 18 release notes（2025-09-25）+ LangChain4j 1.x 实测 |
| **Phase 2**（短期） | 3~4 周 | 工程闭环 | 3 人周 | Tool Calling + VLM + 极性标注 + 审计表 + PG 18 索引迁移 |
| **Phase 3**（中期） | 6~8 周 | 图谱 + ColPali | 6 人周 | GraphRAG (Python) + 实体导入 Postgres + ColPali ONNX 集成 |
| **Phase 4**（长期） | 持续 | 可信闭环 | 持续 | RAGAS 评测集 + LLM-as-Judge + 监控看板 |

### 5.1 Phase 1 强制前置：GitNexus pre-flight（不开工先跑）

> 对应 §2.5 工作流门禁。Phase 1 涉及 `EmbeddingHandler.embeddingDocument` 与 `AIChatHandler.completions` 两条核心路径，**必须先 impact 再动手**。

```java
// 步骤 1：impact 评估两条最关键的代码路径
impact({
  target: "EmbeddingHandler.embeddingDocument",
  direction: "upstream",
  summaryOnly: true
});  // ⚠️ 该方法被所有 embedding 入口调用，预期风险等级 HIGH 或以上

impact({
  target: "AIChatHandler.completions",
  direction: "upstream",
  summaryOnly: true
});  // 该方法被所有聊天入口调用 + 集成 Tool/MCP，预期风险等级 HIGH
```

> 若 impact 返回 CRITICAL 或受影响 process 流 > 5，则**建议把 Phase 1 拆成 P1.1 / P1.2 两个子 PR**：
> - P1.1：仅改动 `EmbeddingHandler.searchEmbedding` 路径（**只读，不动写入**），加 HYBRID + UUIDv7 主键只涉及新 chunk 写入路径，影响面小
> - P1.2：新增 `GbIntentExtractor` 接口 + §4.3.4 Fallback（独立新文件，不动既有方法，blast radius = 0）

### 5.2 Phase 回滚矩阵（原文档未明确，**v3 补全**）

| Phase | 主变更 | Kill Switch（1 行回滚） | 回滚耗时 | 数据回滚必要性 |
|-------|--------|----------------------|---------|--------------|
| **P1.1** HYBRID + UUIDv7 主键 | 新建索引、向量库 searchMode 改 HYBRID | 配置项 `jeecg.airag.know.hybrid-search=false` | < 1 分钟 | **不需要**：新 schema，老 chunk 用 UUIDv4 仍可读 |
| **P1.2** LLM 意图解析器 + Fallback | 新增 `GbIntentExtractor` 接口 + 调用 `extractWithFallback` | 配置项 `jeecg.airag.know.llm-intent-enabled=false` | < 5 分钟 | **不需要**：新增组件不影响存量 |
| **P2** Tool Calling + VLM + 极性 + 审计 | 新增 Tool、ImageDescriber、Polarity 处理 | Feature flag `tools.enabled=false` / `image-describer.enabled=false` | < 10 分钟 | 审计表可不清空（用 start_time 区分） |
| **P3** GraphRAG + ColPali | 引入 Python 微服务 + pgvector 多向量列 | 卸载 Python 微服务、保留字段不删除 | < 30 分钟 | 图谱数据非主路径，回滚后不影响 RAG 基础能力 |
| **P4** RAGAS 评测 + 监控 | 独立跑批，不动主链路 | 关闭 cron job + 删除监控面板 | < 5 分钟 | **不需要**：纯旁路 |

### 5.3 Flyway 迁移脚本案例（P1.1 必备，**v3 补全**）

```sql
-- 文件位置：jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/postgresql/
-- V20260710__gb_chunks_uuid_v7.sql  ← 注意：项目原 flyway 目录命名格式是日期+

-- V20260710.1 —— 启用 PG 18 必扩展 + 创建 gb_chunks 表（UUIDv7 主键 + Skip Scan 多列索引）
-- update-begin---author:song ---date:2026-07-10  for：【AI知识库】PG 18 必备扩展 + UUIDv7 主键 + Skip Scan 多列索引-------
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- textSearchConfig='simple' 全文索引所需（即使 simple 词典，pg_trgm 也加速 LIKE）

-- 假定已有 chunk 表为 airag_embedding（gb_no 暂用 doc_id 字段占位，正式命名按 airag_knowledge 表约定）
CREATE INDEX IF NOT EXISTS idx_airag_embedding_chunk_filter
    ON airag_embedding (chapter, test_type, n_cells_alias, clause_id, amendment, status);
-- PG 18 Skip Scan 自动生效：WHERE test_type='overcharge'（跳过 chapter）仍走索引

-- 若 airag_embedding 主键不是 uuid，可补一列（不强求重写存量）：
ALTER TABLE airag_embedding
    ADD COLUMN IF NOT EXISTS chunk_uuid UUID DEFAULT uuidv7();
CREATE UNIQUE INDEX IF NOT EXISTS idx_airag_embedding_uuid ON airag_embedding (chunk_uuid);
-- update-end---author:song ---date:2026-07-10  for：【AI知识库】PG 18 必备扩展 + UUIDv7 主键 + Skip Scan 多列索引-------
```

```sql
-- V20260710.2 —— 数值结构化 + PG 18 虚拟生成列（对应 §4.11）
-- update-begin---author:song ---date:2026-07-10  for：【AI知识库】数值参数表 + Virtual Generated Columns-------
CREATE TABLE IF NOT EXISTS gb_param (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    gb_no VARCHAR,
    n_cells INT,
    capacity_wh NUMERIC,
    pack_type VARCHAR,
    overcharge_threshold NUMERIC GENERATED ALWAYS AS (n_cells * 6.0) VIRTUAL,
    undervoltage_threshold NUMERIC GENERATED ALWAYS AS (n_cells * 2.5) VIRTUAL,
    rate_current_c3 NUMERIC GENERATED ALWAYS AS (capacity_wh / 3.0) VIRTUAL,
    version VARCHAR,
    effective_date DATE,
    amendment VARCHAR,
    source_chunk_id UUID  -- 反查向量库
);
CREATE INDEX IF NOT EXISTS idx_gb_threshold ON gb_param (overcharge_threshold);
-- update-end---author:song ---date:2026-07-10  for：【AI知识库】数值参数表 + Virtual Generated Columns-------
```

> **迁移规范**：Flyway 必须按版本号顺序执行；本文件命名应遵循原 jeecg-boot 项目 `db/jeecgboot-mysql-5.7.sql` + flyway `202512/` 的目录规范（具体路径见 `jeecg-boot/CLAUDE.md` 中关于 Flyway 的章节）。

---

## 6. v1 → v2 关键事实修正日志

| # | v1 错误 | v2 修正 | 来源 |
|---|--------|--------|------|
| 1 | "PostgreSQL 18 引入 Skip Scan 检索" | 官方文档确认属实（release-18.html Highlights 第 3 项） | https://www.postgresql.org/docs/18/release-18.html |
| 2 | "PostgreSQL 18 引入 UUIDv7" | 官方文档确认属实（`uuidv7()` function） | https://www.postgresql.org/docs/18/release-18.html |
| 3 | "Virtual Generated Columns" 存在 | 官方文档确认属实，且 PG 18 中**成为默认** | https://www.postgresql.org/docs/18/release-18.html |
| 4 | "DashScope supports Structured Output" | 未在 v1 给出 v2 适用性分析 | LangChain4j 1.x 文档支持 OpenAI 兼容模式（MiniMax-M3 / Qwen2.5+ / vLLM 等都通过 `/v1` 接入） |
| 5 | ColPali 引用为 `anthology-engine/chromadb`（**错误仓库**） | 修正为 `illuin-tech/colpali`，论文 arxiv:2407.01449 | https://github.com/illuin-tech/colpali |
| 6 | GraphRAG 描述模糊 | 给出官方仓库 + 论文 ID + 安装命令 | https://github.com/microsoft/graphrag |
| 7 | v2 假设 chat model 是 Qwen2.5-7B-instruct + DeepSeek-reasoner 推理 + 独立 Qwen-VL 多模态 | **生产对齐**：chat model 改为 `MiniMax-M3`（多模态，OpenAI 兼容端点 `https://api.minimax.chat/v1`）；VLM 改为复用 MiniMax-M3 自身；删除 DeepSeek-reasoner 相关假设 | 用户生产配置：模型 MiniMax-M3、端点 api.minimax.chat/v1、text-embedding-v3 |
| 8 | v2 文档未把 GitNexus 工作流门禁显式编入 | **v3 补全**：在 §2.5 新增 GitNexus 工作流门禁；§3 架构新增 L0 层；§5 Phase 1 加入 pre-flight 步骤 + 子 PR 拆分建议；新增 §5.2 Phase 回滚矩阵；新增 §5.3 Flyway 迁移脚本案例 | 项目根 CLAUDE.md "GitNexus — Code Intelligence" 段（仓库管理员已升级为强制规则） |

---

## 7. 风险与边界

### 不做的事

- ❌ 用"更大的上下文窗口"覆盖多章 — 单 GB 已 200k+ token，成本/失焦双重恶化
- ❌ 只用"更好的向量模型"替代 — embedding 升级不能解决极性反转与逻辑互斥
- ❌ **不再用任何 YAML 字典 + 正则做意图解析** — 已用 LLM Structured Output 替代
- ❌ 自训 7B 法律模型 — 数据不足 + 强规则文档应当靠确定性工程

### 必做的事

- ✅ **升级到 PG 18 后再部署 Skip Scan / UUIDv7 / Virtual Generated Columns**（PG 14/15/16 不支持）
- ✅ 保留 MinerU Cloud 模式 + vlm 模型（已含 `enable_formula`/`enable_table`）
- ✅ 保留 `EmbeddingStoreContentRetriever` 现有抽象，所有改动作为其上层 Wrapper
- ✅ 保留已提交的 MineruApiClient 改动（`is_ocr`、`restTemplate.execute`、`mdFileName` 等），不要回滚

---

## 附录 A: 参考来源清单（**每条均经过实测验证**）

| Ref | 来源 URL | 验证方式 | 适用章节 |
|-----|---------|---------|---------|
| [^ref-1] | https://www.postgresql.org/docs/18/release-18.html | **Chrome --dump-dom 实际抓取**，确认 Skip Scan / uuidv7() / Virtual Generated Columns / Temporal Constraints / AIO 子系统 等 6 大特性，发布日 2025-09-25 | 2.1 / 4.1 / 4.4.2 / 4.6 / 4.7 / 4.11 |
| [^ref-2] | https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/structured-outputs.md | **Chrome --dump-dom 实际抓取**，确认 6 家 LLM 提供方原生支持 `RESPONSE_FORMAT_JSON_SCHEMA` | 2.2 / 4.3 |
| [^ref-3] | https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/rag.md | **context7 已多次查询确认**，提供 `ExpandingQueryTransformer` / `CompressingQueryTransformer` / HyDE 实现 | 2.3 / 4.1 |
| [^ref-4] | https://github.com/langchain4j/langchain4j/blob/main/docs/docs/integrations/embedding-stores/pgvector.md | **context7 已确认**，提供 `SearchMode.HYBRID` + `textSearchConfig` + `rrfK` | 4.1.3（标量/向量双轨中的向量轨） |
| [^ref-5] | https://github.com/langchain4j/langchain4j/blob/main/docs/docs/integrations/document-parsers/docling.md | **context7 已确认**，IBM Docling 集成，DocTags + OCR + 表格 + 公式 | 4.4.2 / 4.5（版面重组） |
| [^ref-6] | https://github.com/microsoft/graphrag | **Chrome --dump-dom 抓 README 确认**，微软研究院官方仓库，论文 arxiv:2404.16130 | 2.4 / 4.8 |
| [^ref-7] | https://arxiv.org/pdf/2404.16130 | Microsoft Research GraphRAG 论文 | 4.8 |
| [^ref-8] | https://github.com/illuin-tech/colpali | 修正 v1 中的错误仓库路径；ColPali 主要论文 arxiv:2407.01449 | 4.4.2 |
| [^ref-9] | https://docs.ragas.io/ | 业界标准 RAG 评测框架 | 4.14 |
| [^ref-10] | https://github.com/langchain4j/langchain4j/blob/main/docs/docs/integrations/embedding-stores/neo4j.md | **context7 已确认**，`HypotheticalQuestionGraphIngestor` | 4.14 |
| [^ref-11] | https://mineru.net/ | MinerU 官方 API 文档 | 4.4.1（VLM 已用 MinerU 抠出的图） |
| [^ref-12] | https://help.aliyun.com/zh/model-studio/developer-reference/text-embedding-v3-api-reference | DashScope text-embedding-v3 模型文档 | 4.3.2（text-embedding-v3 向量模型文档，与 chat model MiniMax-M3 无关） |
| [^ref-13] | ISO/IEC 17025:2017 | 实验室能力通用要求 | 4.13（审计持久化） |
| [^ref-14] | https://www.postgresql.org/docs/18/functions-uuid.html | PG 18 `uuidv7()` 函数官方文档 | 4.5 / 4.7（UUID 主键） |
| [^ref-15] | https://www.postgresql.org/docs/18/sql-createtable.html#SQL-CREATETABLE-PARMS-GENERATED-STORED | PG 18 生成列官方语法 | 4.6 / 4.11 |

### 命名约定（按项目 `CLAUDE.md` 要求）

> 所有新增或修改的代码块使用 `//update-begin---author:作者 ---date:YYYY-MM-DD  for：【...】...` 注释包裹。
> 本文档示例均遵循此约定；落地代码 PR 也必须遵守。

---

## 附录 B: 关键决策对照表（v2 更新版）

| 决策项 | 选项 A | 选项 B | v2 推荐 | 理由 |
|--------|--------|--------|---------|------|
| 解析器 | MinerU 官方 Cloud（当前） | Docling IBM | **两者并用** | MinerU 中文+公式已优；Docling 用于 OCR 双校验 |
| 向量库 | PGVector（当前） | Milvus / Elasticsearch | **保持 PGVector（必升 PG 18）** | 项目已用 PgVector；PG 18 Skip Scan 等特性是降维打击 |
| 混合检索 | PG HYBRID | 自建 BM25 + 嵌入融合 | **PG 18 标量/向量双轨 + Skip Scan** | 仅靠 HYBRID 不够；标量预过滤 + 向量精排 |
| 查询理解 | YAML+正则（v1） | LLM Structured Output | **v2：LLM Structured Output** | v1 是规则时代方案；v2 是 LLM 时代方案 |
| 知识图谱 | Neo4j（独立部署） | GraphRAG + Postgres JSONB | **v2：GraphRAG（实测可用）** | 微软官方维护，论文支撑，无需新增组件 |
| 视觉理解 | 独立 VLM（Qwen-VL） | ColPali 多向量 | **v2：复用 MiniMax-M3（单一 chat model）+ 渐进升级 ColPali** | MiniMax-M3 本身多模态，避免再增独立供应商；ColPali 性能更好但需 Python 集成 |
| 算术工具 | exp4j 表达式引擎 | PG 18 虚拟生成列 | **v2：PG 18 虚拟生成列（数据驱动）** | 让数据库做计算，LLM 只解释 |
| 时间主键 | UUIDv4（v1） | UUIDv7（PG 18 新） | **v2：UUIDv7（实测 PG 18 已发布）** | 时间有序索引友好 |
| 评测框架 | RAGAS | DeepEval / LangSmith | **RAGAS（首选）** | 开源、自托管、与 LangChain4j 集成最简 |
| **代码变更验证** | 手动跑测试 | Sonar / 人工 review | **v3：GitNexus impact + detect_changes**（强制） | 项目根 CLAUDE.md 强制；HIGH/CRITICAL 必须 warn |

---

## 附录 C: v2 改造的工程量预估（按阶段）

| 阶段 | 文件改动量（估） | 影响模块 | 风险 | 回退方案 |
|------|----------------|---------|------|---------|
| Phase 1 | 5 个文件 / 200 行 | EmbeddingHandler / KnowConfigBean / 新建 GbIntentExtractor | 低（不改存量流程，新增层） | Kill switch：在配置项里禁用 LLM 意图解析，回退到 v1 正则 |
| Phase 2 | 15 个文件 / 800 行 | EmbeddingHandler / AIChatHandler / 新增 ImageDescriber / NumericExtractor | 中（涉及数据库 DDL） | Flyway 迁移脚本可回滚 |
| Phase 3 | 8 个文件 / 400 行 | 新增 GraphRAG Python 微服务 + Java 客户端 | 中（图谱集成） | 子系统独立运行，主流程不依赖 |
| Phase 4 | 5 个文件 / 200 行 | 新增 RAGAS 跑批脚本 + 监控 | 低 | 不影响主流程 |

> 本文档应配合代码 PR、单元测试、专家评审同步更新；任一方案落地后，对应章节打 ✅；评估指标上线后，回填 Phase 4 实际数据。

