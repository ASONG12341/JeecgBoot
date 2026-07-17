# GB-RAG v4 Phase 5 (连通管线) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 连通 GB-RAG v4 的端到端数据流——把 4 个 LLM 槽位真正写入向量库（airag_embedding），持久化 gb_parameter/gb_reference，让聊天流/检索页的 metadata 精排、GbCalculationTool、ContextAssembler 引用增强真正生效。修复端到端追踪发现的 4 个 CRITICAL 断点。

**根因：** `GbIngestionPipeline` 写 gb_clause 但**从不碰 airag_embedding**；`persistParametersAndReferences` 是 log-only 占位；`GbParameterRepository`/`GbReferenceRepository` 无写方法；VectorChannel 不设 standardId。两条数据流（regex→embedding / LLM→clause）从未连通。

**Architecture:**
- 在 `EmbeddingHandler` 新建 `embedClauses(knowId, List<GbClause>)`：per-clause 嵌入，每个 clause 的 Metadata 注入 4 槽位 + standard_no/clause_id/clause_path/standard_id，复用 batchEmbedAll（DashScope 10 段限制）+ embeddingStore.addAll。删除旧 clause 嵌入按 `standard_id` 键（独立于 doc chunk）。
- `GbIngestionPipeline.run` 在 saveBatch 后调 embedClauses + 真实 persistParametersAndReferences（用 ASSIGN_ID 保证的 clausePath→clauseId 映射，无需回查）。
- `GbParameterRepository`/`GbReferenceRepository` 加 saveBatch（仿 GbClauseRepository 模式）。
- `VectorChannel` 注入 GbStandardResolver 设 standardId（让 ContextAssembler 能工作）。

**Tech Stack:** Java 17 / Spring Boot / LangChain4j 1.17.2（EmbeddingStore/EmbeddingModel/TextSegment/Metadata）/ MyBatis-Plus（ASSIGN_ID）/ Lombok / JUnit 5 + Mockito。

**依据：** 端到端追踪报告（4 CRITICAL 断点）+ P5 可行性深挖（字节码级确认）。

## Global Constraints

- **只修写入侧**：buildMetadataFilter 不动（langchain4j IsEqualTo 排除缺键文档是严格语义，旧数据靠重新嵌入修复，不加降级开关）。
- **per-clause embedding**：每个 clause = 一个 TextSegment（text = clause.text），Metadata 注入 4 槽位 + standard_no/clause_id/clause_path/standard_id/amendment/polarity。不复用 chunk 分割（chunk↔clause 映射有损）。
- **clauseId 无需回查**：MyBatis-Plus ASSIGN_ID 在 insert 前生成 id 到实体 → saveBatch 后 clauses 列表的 id 已填充，直接建 `clausePath→clauseId` 映射。
- **删除键用 standard_id**：新增 `EMBED_STORE_METADATA_STANDARD_ID` 常量，clause 嵌入按 standard_id 删除-重插（独立于 doc chunk 的 docId 删除，避免误删 doc chunk）。
- **复用既有 embedding 基建**：`getEmbedStore`/`getEmbedModelData`/`buildModelOptions`/`batchEmbedAll`（都是 EmbeddingHandler 的方法，部分 private 需在同类内调用或开放）。embedClauses 必须建在 EmbeddingHandler 内（复用 store 缓存 + model 解析）。
- **DashScope 10 段限制**：用 `batchEmbedAll(embeddingModel, segments, 10)` 分批嵌入。
- **领域无关红线**：embedClauses 是通用的"把 clause 带槽位嵌入"，不含领域词。
- **变更标记**：`//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】... ---`。
- **测试**：`@ExtendWith(MockitoExtension.class)` 纯单元测试。关键：GbIngestionPipelineTest 要新增断言验证 embedClauses + parameterRepository.saveBatch 被调（之前的测试没验证这些，导致断点没被发现）。
- **每 Task 编译+测试通过才进下一个。**

---

## File Structure

| 文件 | 职责 | 状态 |
|------|------|------|
| `llm/handler/EmbeddingHandler.java` | 新建 `embedClauses(knowId, List<GbClause>)` + `EMBED_STORE_METADATA_STANDARD_ID` 常量 | 改（核心） |
| `gbstandard/ingestion/GbIngestionPipeline.java` | run 接 embedClauses + 真实 persistParametersAndReferences | 改（核心） |
| `gbstandard/repository/GbParameterRepository.java` + impl + mapper | 加 saveBatch + deleteByStandardId | 改 |
| `gbstandard/repository/GbReferenceRepository.java` + impl + mapper | 加 saveBatch + deleteByStandardId | 改 |
| `gbstandard/retrieval/channel/VectorChannel.java` | 注入 GbStandardResolver 设 standardId | 改 |

---

## Task 1: GbParameterRepository 加 saveBatch

**Files:**
- Modify: `.../gbstandard/mapper/GbParameterMapper.java`（加 deleteByStandardId）
- Modify: `.../gbstandard/repository/GbParameterRepository.java`（加 saveBatch 接口）
- Modify: `.../gbstandard/repository/impl/GbParameterRepositoryImpl.java`（加 saveBatch 实现）
- Test: `.../src/test/java/.../repository/GbParameterRepositorySaveTest.java`

**Interfaces:**
- Consumes: `GbParameterMapper`（已有）
- Produces: `int saveBatch(String standardId, List<GbParameter>)` —— 先 deleteByStandardId 再 loop insert。仿 GbClauseRepository.saveBatch。

- [ ] **Step 1: 写失败测试**（仿 GbClauseRepositorySaveTest：mock mapper，验证 deleteByStandardId 被调 + insert 被调 N 次）

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: GbParameterMapper 加 deleteByStandardId**

```java
@org.apache.ibatis.annotations.Delete("DELETE FROM gb_parameter WHERE standard_id = #{standardId}")
int deleteByStandardId(@org.apache.ibatis.annotations.Param("standardId") String standardId);
```

- [ ] **Step 4: 接口 + impl 加 saveBatch**（仿 GbClauseRepositoryImpl.saveBatch：delete then loop insert）

- [ ] **Step 5: 运行测试确认通过**

- [ ] **Step 6: Commit**

---

## Task 2: GbReferenceRepository 加 saveBatch

**Files:** 同 Task 1 模式，但 GbReferenceRepository + GbReferenceMapper + impl。

**注意：** GbReference 的删除键是 `source_standard_id`（不是 standard_id）。

- [ ] **Step 1-6**: 同 Task 1 模式。GbReferenceMapper.deleteByStandardId 用 `DELETE FROM gb_reference WHERE source_standard_id = #{standardId}`。

---

## Task 3: EmbeddingHandler.embedClauses（per-clause 向量写入）

**⚠️ 核心 Task。** 这是连通 Path A/B 的关键。

**Files:**
- Modify: `.../llm/handler/EmbeddingHandler.java`（加常量 + embedClauses 方法）

**Interfaces:**
- Consumes: `AiragKnowledgeService`（查 knowId→embedId）、`getEmbedModelData`/`getEmbedStore`（既有 private，同类内调）、`batchEmbedAll`（既有 public static）、`embeddingStore.add`/`addAll`。
- Produces: `void embedClauses(String knowId, List<GbClause> clauses)` —— 按 knowId 解析 embedding model + store，删除该 standardId 的旧 clause 嵌入，把每个 clause 嵌入（Metadata 注入 4 槽位 + standard_no/clause_id/clause_path/standard_id/amendment/polarity）。

**关键设计：**
- 加常量 `public static final String EMBED_STORE_METADATA_STANDARD_ID = "standard_id";`
- embedClauses 流程：
  1. `AiragKnowledge k = airagKnowledgeService.getById(knowId)` → `getEmbedModelData(k.getEmbedId())` → `buildModelOptions` + `createEmbeddingModel` → embeddingModel
  2. `getEmbedStore(model)` → embeddingStore
  3. 按 standard_id 分组删除（每个 distinct standardId 调 `embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_STANDARD_ID).isEqualTo(sid))`）
  4. 对每个 GbClause：构造 `TextSegment`（text=clause.text，Metadata 注入 standard_no/standard_id/clause_id/clause_path/amendment/primary_type/secondary_type/polarity/condition_text/docId/knowledgeId）
  5. `batchEmbedAll(embeddingModel, segments, 10)` → embeddings
  6. `embeddingStore.addAll(embeddings, segments)`
- 防御：knowId 空/clauses 空/model 解析失败 → 记日志跳过，不抛异常（不阻塞入库）

- [ ] **Step 1: 读 EmbeddingHandler 的 embeddingDocument + getEmbedStore + getEmbedModelData + batchEmbedAll + 常量区**，确认可复用的方法签名。

- [ ] **Step 2: 写失败测试**（mock EmbeddingHandler 的依赖，验证 embedClauses 不抛 + 调 store。注意：embedClauses 在 EmbeddingHandler 内部调 private 方法，测试用 @Spy 或测公开行为。可简化：测 embedClauses 对空输入防御性返回不抛异常）

- [ ] **Step 3: 实现 embedClauses**

```java
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】embedClauses：per-clause 向量写入，注入 4 槽位到 airag_embedding metadata（连通 LLM→向量库）-----------
public static final String EMBED_STORE_METADATA_STANDARD_ID = "standard_id";

public void embedClauses(String knowId, List<GbClause> clauses) {
    if (knowId == null || knowId.isBlank() || clauses == null || clauses.isEmpty()) {
        log.info("[EmbeddingHandler][embedClauses] knowId 或 clauses 为空，跳过 clause 嵌入");
        return;
    }
    try {
        AiragKnowledge airagKnowledge = airagKnowledgeService.getById(knowId);
        if (airagKnowledge == null || airagKnowledge.getEmbedId() == null) {
            log.warn("[EmbeddingHandler][embedClauses] 未找到知识库或 embedId, knowId={}", knowId);
            return;
        }
        AiragModel model = getEmbedModelData(airagKnowledge.getEmbedId());
        EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(buildModelOptions(model));
        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);

        // 删除该批 clauses 涉及的各 standardId 的旧 clause 嵌入
        clauses.stream().map(GbClause::getStandardId).filter(StringUtils::hasText).distinct()
                .forEach(sid -> embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_STANDARD_ID).isEqualTo(sid)));

        // 每个 clause 一个 TextSegment，Metadata 注入 4 槽位 + 定位键
        List<TextSegment> segments = new ArrayList<>();
        for (GbClause c : clauses) {
            if (c.getText() == null || c.getText().isBlank()) continue;
            Metadata md = Metadata.metadata(EMBED_STORE_METADATA_STANDARD_ID, c.getStandardId())
                    .put(EMBED_STORE_METADATA_KNOWLEDGEID, knowId);
            putIfPresent(md, "standard_no", /* 从 GbStandard 取或 clause 无此字段 */ null); // 见下方注意
            putIfPresent(md, "clause_id", c.getClausePath());
            putIfPresent(md, "clause_path", c.getClausePath());
            putIfPresent(md, "primary_type", c.getPrimaryType());
            putIfPresent(md, "secondary_type", c.getSecondaryType());
            putIfPresent(md, "polarity", c.getPolarity());
            if (c.getConditionText() != null) md.put("condition_text", c.getConditionText());
            segments.add(TextSegment.from(c.getText(), md));
        }
        if (segments.isEmpty()) return;
        List<Embedding> embeddings = batchEmbedAll(embeddingModel, segments, 10);
        embeddingStore.addAll(embeddings, segments);
        log.info("[EmbeddingHandler][embedClauses] clause 嵌入完成, knowId={}, 条数={}", knowId, segments.size());
    } catch (Exception e) {
        log.error("[EmbeddingHandler][embedClauses] clause 嵌入失败, knowId={}: {}", knowId, e.getMessage(), e);
        // 不抛，不阻塞入库主管线
    }
}

private void putIfPresent(Metadata md, String key, String value) {
    if (value != null && !value.isEmpty()) md.put(key, value);
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
```

> ⚠️ **注意 standard_no**：GbClause 没有 standardNo 字段（只有 standardId）。要么在 embedClauses 签名加 standardNo 参数（从 GbStandard 传入），要么从 GbStandard 解析。**建议 embedClauses 签名改为 `embedClauses(String knowId, String standardNo, List<GbClause> clauses)`**，由 pipeline 从 GbStandard.standardNo 传入。buildMetadataFilter 读 standard_no，所以这个键要写。

- [ ] **Step 4: 运行测试确认通过**（防御性测试 + 编译）

- [ ] **Step 5: Commit**

---

## Task 4: GbIngestionPipeline 接入 embedClauses + 真实 persist

**⚠️ 核心 Task。** 连通管线的总开关。

**Files:**
- Modify: `.../gbstandard/ingestion/GbIngestionPipeline.java`

**改动：**
1. 注入 `@Autowired private EmbeddingHandler embeddingHandler;`（新建依赖）
2. `run()` 在 `clauseRepository.saveBatch(...)` 后加：
   - `embeddingHandler.embedClauses(standard.getKnowledgeId(), standard.getStandardNo(), clauses);`
3. 把 `persistParametersAndReferences` 从 log-only 改为真实实现：
   - 建 `Map<String,String> clausePathToId`（从 clauses 列表，ASSIGN_ID 已填 id）
   - 遍历 allResults，对每个有 parameters 的：ParamExtract→GbParameter（clauseId=clausePathToId.get(result.clausePath)），收集成 List<GbParameter>
   - 对每个有 references 的：RefExtract→GbReference（sourceClausePath=result.clausePath，inter 时 targetStandardId=gbStandardResolver.resolve(targetStandardNo)），收集成 List<GbReference>
   - `parameterRepository.saveBatch(standardId, paramList)` + `referenceRepository.saveBatch(standardId, refList)`
4. 注入 GbStandardResolver（用于 inter ref 的 targetStandardId 解析）

- [ ] **Step 1: 读 GbIngestionPipeline.run + persistParametersAndReferences 现状 + toClause**

- [ ] **Step 2: 更新 GbIngestionPipelineTest** —— **这是发现断点的关键**。新增断言：
   - `verify(embeddingHandler).embedClauses(eq(knowId), eq(standardNo), any());`（验证向量写入被调）
   - `verify(parameterRepository).saveBatch(eq(standardId), any());`（验证参数持久化被调）
   - 这些断言之前不存在，正是断点没被发现的原因。@Mock embeddingHandler + parameterRepository + referenceRepository。

- [ ] **Step 3: 实现 run 接入 + 真实 persist**

- [ ] **Step 4: 运行测试确认通过**（含新断言）

- [ ] **Step 5: Commit**

---

## Task 5: VectorChannel 设 standardId（让 ContextAssembler 工作）

**Files:**
- Modify: `.../gbstandard/retrieval/channel/VectorChannel.java`

**改动：**
1. 注入 `@Autowired private GbStandardResolver gbStandardResolver;`
2. `convertToRetrievalResult` 在读 standardNo 后加：
   ```java
   if (StringUtils.hasText(standardNo)) {
       result.setStandardId(gbStandardResolver.resolveStandardId(standardNo).orElse(null));
   }
   ```
3. 防御：standardNo 空/resolver 返回 empty → standardId 保持 null（ContextAssembler 已有 bail-out）

- [ ] **Step 1-4**: 改 + 测试（VectorChannel 测试 mock GbStandardResolver）+ 编译

- [ ] **Step 5: Commit**

---

## Task 6: 全模块编译 + 全测试 + 端到端验证清单

- [ ] **Step 1: 全测试**

Run: `mvn test`
Expected: BUILD SUCCESS（P5 新增测试全过；Mineru 既有失败不算回归）。

- [ ] **Step 2: 端到端断点复查（静态）**

确认 4 个 CRITICAL 断点已连通：
1. embedClauses 被 pipeline 调用 → 4 槽位进 airag_embedding ✓（Task 4 测试断言）
2. persistParametersAndReferences 真实持久化 gb_parameter/gb_reference ✓（Task 4 测试断言）
3. VectorChannel 设 standardId → ContextAssembler 能查引用 ✓（Task 5）
4. 聊天流 buildMetadataFilter 现在有数据可过滤（依赖 Task 3 写入）✓

- [ ] **Step 3: 验收 commit**

```bash
git commit --allow-empty -m "chore(airag): GB-RAG v4 P5 验收（连通管线，4 CRITICAL 断点已修）"
```

---

## P5 验收标准

| 验收点 | 验证方式 |
|--------|---------|
| embedClauses 写 4 槽位到向量库 | Task 3 + Task 4 测试断言 embedClauses 被调 |
| gb_parameter 持久化 | Task 1 + Task 4 测试断言 saveBatch 被调 |
| gb_reference 持久化 | Task 2 + Task 4 测试断言 saveBatch 被调 |
| VectorChannel 设 standardId | Task 5 测试 |
| 端到端不再归零 | Task 6 静态复查 4 断点 |
| 领域无关 | embedClauses/persist 无领域词 |

## 运维操作（部署时，非代码）
- 跑 PG DDL（V1 + V2）
- **重新确认（confirm）现有 GB 文档**：让 pipeline 重新跑 embedClauses + persist，把 4 槽位写进向量库 + 填 gb_parameter/gb_reference
- 旧 doc chunk 嵌入与 clause 嵌入共存于 airag_embedding（按 standard_id 键区分），互不影响
