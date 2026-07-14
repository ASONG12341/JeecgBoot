# GB-RAG v3.1 落地总路线图

> 本文件是跨会话的**唯一任务入口**。任何新会话必须先读此文件，按“当前阶段”继续，不要擅自跳阶段。
>
> 总依据：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/GB-RAG-14根因复盘与完整落地方案.md` §5「落地路径：4 个迭代阶段」

---

## 1. 项目总目标

把 JeecgBoot airag 模块的 GB 标准 RAG 从 v1（YAML+正则）升级到 v3（LLM 结构化语义 + PG 18 现代数据库特性 + 图谱 + 评测），解决 14 根因中的高优先级问题。

---

## 2. 四阶段总览

| 阶段 | 周期 | 核心目标 | 对应 v3 章节 | 状态 |
|------|------|---------|------------|------|
| **Phase 1** | 1 周 | PG 18 基础 + Structured Output 升级 | §4.1 / §4.3 / §5.3 | **P1.2 ✅ / P1.1 未开始** |
| **Phase 2** | 3~4 周 | 工程闭环：计算、视觉、极性、审计 | §4.2 / §4.4 / §4.9 / §4.13 | 未开始 |
| **Phase 3** | 6~8 周 | 图谱 + ColPali 多向量 | §4.6 / §4.8 / §4.12 | 未开始 |
| **Phase 4** | 持续 | 可信闭环：评测 + 监控 | §4.14 | 未开始 |

---

## 3. Phase 1 详细拆分

Phase 1 按 v3 建议拆成 **P1.1** 和 **P1.2** 两个子阶段（降低 blast radius）。

### 3.1 P1.1 · 检索层升级（HYBRID + UUIDv7 + metadata）

**目标**：让 `EmbeddingHandler` 从纯向量召回升级为**标量预过滤 + 向量精排**，并写入结构化 metadata。

- [x] **P1.1-1 Flyway 迁移脚本**
  - 启用 PG 18 扩展：`btree_gist`、`pg_trgm`
  - 创建 Skip Scan 多列索引：`chapter, test_type, n_cells_alias, clause_id, amendment, status`
  - 新增 `gb_param` 表 + Virtual Generated Columns（`overcharge_threshold`、`undervoltage_threshold`、`rate_current_c3`）
  - 文件位置：`jeecg-boot-module/jeecg-boot-module-airag/docs/pg18-migration.sql`

- [x] **P1.1-2 EmbeddingHandler.embeddingDocument 写 metadata**
  - 在写入 chunk 时提取并写入 6 个 GB 字段
  - 通过 GbMetadataExtractor 混合策略（正则 + LLM fallback）

- [x] **P1.1-3 EmbeddingHandler.searchEmbedding 改造**
  - 支持 `metadataKey(...).isEqualTo(...)` 标量 filter
  - 配置项切换 HYBRID / 纯向量模式：`jeecg.airag.know.hybrid-search`
  - Kill Switch：关闭后回退到原有纯向量召回

- [x] **P1.1-4 ExpandingQueryTransformer 接入**
  - 在检索前对 userQuery 做扩展
  - 多路召回后 RRF 融合
  - HYBRID 模式下 minScore 忽略,仅按 topK 返回

- [x] **P1.1-5 UUIDv7 主键（可选,新 chunk）**
  - 新写入 chunk 使用 `uuidv7()` 作为辅助列
  - DDL 中已包含,老 chunk 用 UUIDv4 不强制迁移

- [x] **P1.1-6 单测 + 回归验证**
  - 14/14 现有测试不回归
  - EmbeddingHandlerSearchFilterTest + EmbeddingHandlerMetadataTest 新增

### 3.2 P1.2 · LLM 意图解析器（GbIntentExtractor）

**目标**：用 LLM Structured Output 替代 YAML+正则，抽取 `GbQueryIntent` 结构化槽位。

- [x] **P1.2-1** `GbQueryIntent` POJO（6 字段 + `@Description`）
- [x] **P1.2-2** `IGbIntentExtractor` 接口
- [x] **P1.2-3** `IntentContext` ThreadLocal 工具类
- [x] **P1.2-4** `DefaultGbIntentExtractor` Qwen-flash 实现 + credential JSON 解析
- [x] **P1.2-5** `AIChatHandler` 注入 + `mergeParams` 调用 + `try-finally` 清理
- [x] **P1.2-6** `DefaultGbIntentExtractorTest` 单元测试（9 个用例通过）
- [x] **P1.2-7** `GbIntentExtractorProperties` 配置类精简
- [x] **P1.2-8** 设计文档 `2026-07-11-gb-intent-extractor-design.md` 更新
- [x] **P1.2-9** v3 §4.3 文档二审修订（8 处 update-begin/end 包裹）
- [x] **P1.2-10** 整体验收 + commit message 草案
- [ ] **P1.2-11** `GbIntentExtractorComparisonTest` 真实模型 A/B 测试（由用户自行处理）

### 3.3 v3 §4.3 文档修订明细（已并入 P1.2-9 / P1.2-10）

- [x] Task 1：P0 #2 `@JsonPropertyDescription` → `@Description`（6 处字段）
- [x] Task 2：P0 #3 Fallback 退化路径去除 `status=current` filter
- [x] Task 3：P1 "进一步加固"代码块补 `update-begin/end` 包裹 + 变量 `exception` 修正为 `e`
- [x] Task 4：P1 WARN #1 Resilience4j 推荐改为"不引入新库"
- [x] Task 5：P1 WARN #2 Fallback 触发条件枚举补充
- [x] Task 6：P1 WARN #3 "PGVector HYBRID mode" → "纯向量召回"
- [x] Task 7：P1 WARN #4 §4.3.4 末尾加 GitNexus impact 提示
- [x] Task 8：P2 WARN #5 §4.3 末尾注明同义扩展主路径
- [x] Task 9：整体验收 + 输出 commit message 草案

---

## 4. Phase 2 详细拆分（待规划）

| 编号 | 任务 | 对应 v3 章节 | 目标 |
|------|------|------------|------|
| P2.1 | Tool Calling / `GbSafeCalculator` | §4.2 | 禁止 LLM 凭语感输出数值，用 PG 18 虚拟生成列实时计算 |
| P2.2 | VLM 图转 JSON（附录流程图） | §4.4 | 解决 MinerU 把图抠成 `![](...)` 导致 embedding 看不见的问题 |
| P2.3 | 极性标注 + 在线对比 | §4.9 | 区分肯定/否定/例外条款，避免语义近似但方向相反的召回 |
| P2.4 | 审计表 + 人审闭环 | §4.13 | 答案必须给出来源页码+条款号+版次，审计表持久化 |
| P2.5 | PG 18 索引迁移收尾 | §5.3 | 把 P1.1 的索引策略推广到所有 GB 相关表 |

---

## 5. Phase 3 详细拆分（待规划）

| 编号 | 任务 | 对应 v3 章节 | 目标 |
|------|------|------------|------|
| P3.1 | GraphRAG Python 微服务 + 实体导入 | §4.8 / §4.12 | 跨文档引用关系、版本依赖图 |
| P3.2 | Parent Document Retriever | §4.6 | 解决线性切片把章-条-款压扁的问题 |
| P3.3 | ColPali 多向量索引 | §4.4 | 整页 PDF 作为多向量，无需 OCR |
| P3.4 | 回溯更新链式失效 | §4.12 | GB A 引用 GB B 修订后联动重审 |

---

## 6. Phase 4 详细拆分（待规划）

| 编号 | 任务 | 对应 v3 章节 | 目标 |
|------|------|------------|------|
| P4.1 | GB 评测集构造 | §4.14 | 覆盖 14 根因的问答对 + ground truth |
| P4.2 | LLM-as-Judge | §4.14 | 自动评判召回率、引用准确率、计算题正确率 |
| P4.3 | RAGAS + 监控看板 | §4.14 | 持续量化 RAG 质量 |
| P4.4 | 反馈闭环 | §4.14 | 把评测结果反哺到检索/排序模型 |

---

## 7. 当前阶段

**Phase 1 P1.1 已完成**：6 个子任务全部 ✅,P1.2 P1.1 主体已全部落地。

**默认下一站**：**Phase 2** 或 **用户提交后**。

**新会话入口动作**：
1. 读取本文件。
2. 确认 P1.1 全部 ✅。
3. **重要**:检查工作区是否提交了 P1.1 全部 7+ 个文件(P1.2 Bug 修复也需提交)。
4. 进入 Phase 2(GB-RAG v3 §4.2/§4.4/§4.9/§4.13)。

---

## 8. 已完成归档

- 2026-07-12:P1.1 全部完成(6 个子任务)+ Task 10 接线就位 + **修复 P1.2 阶段 mergeParams 顺序错位 bug**(注释承诺 vs 实现错位,IntentContext.get() 永远 null)。修改文件 9 个,新增测试 5 个,14+ 测试不回归。
- 2026-07-11：P1.2 全部代码 + 单测 + 设计文档 + v3 §4.3 文档修订完成
- 2026-07-11：P1.1 Task 1 完成 — `GbQueryIntent` 增加 clauseId/amendment/status 字段（commits dd96d672..5bd0ba67，review clean）
- 记忆已保存：`airag-credential-json.md`、`airag-intent-extractor-lessons.md`

---

## 9. 强制约束（贯穿所有阶段）

- 所有代码改动必须用 `update-begin/end` 包裹
- 修改任何 Java 方法前必须先跑 GitNexus `impact`
- 不做 git 操作（add/commit/push 由用户完成）
- 不引入新依赖，除非用户明确要求
- 任何阶段都有 Kill Switch，确保可 1 行回滚
