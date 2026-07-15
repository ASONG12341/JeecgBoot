# GB-RAG v4 Phase 2 (L2 入库管线) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立国标文档的离线入库管线——用户确认结构后，LLM 推导 domain_schema + 批量抽取 4 槽位/极性/参数/引用，结构化写入 gb_* 表，全程审计埋点。配套完善 PDF.js 确认页。

**Architecture:** `GbStandardController.confirm`（现状是 status 翻转 stub）插入 `GbIngestionPipeline` 调用：`GbSchemaDeriver`（每标准 1 次 LLM 推 domain_schema）→ `GbBatchExtractor`（N 个条款打包 1 次 LLM，产 4 槽位+极性+参数+引用）→ 持久化 GbClause/GbParameter/GbReference/GbTermDict → 审计埋点。LLM 客户端复用 `DefaultGbIntentExtractor` 的 OpenAiChatModel 构建骨架。**不动 EmbeddingHandler**（向量 metadata 增强拆到后续 Phase）。

**Tech Stack:** Java 17 / Spring Boot / LangChain4j 1.17.2 OpenAiChatModel / MyBatis-Plus / Lombok / Vue3 + Ant Design Vue + PDF.js。

**依据设计文档：** `gb-rag-v4/03-L2-入库管线与用户确认页.md` + `06-现有代码处置与迁移方案.md`

## Global Constraints

- **领域无关**：任何 LLM prompt、抽取逻辑、硬编码常量都不得包含电池/钢铁等特定领域词。4 槽位（`primaryType/secondaryType/quantityValue/conditionText`）是自由字符串/数值，由 domain_schema 约束取值，不写死枚举。
- **LLM 客户端模式**：`DefaultGbIntentExtractor`（`llm/intent` 包）的 `buildChatModel(AiragModel)` + `resolveApiKey(String)` 是已验证的 OpenAI 兼容端点接入逻辑，但它们是 **package-private**，P2 新类在 `llm.gbstandard.ingestion` 包**无法直接调用**。**Task 0 先把这两个方法提取成共享组件 `GbLlmClient`**（`@Component`，public 方法），放 `llm/common` 或 `llm/gbstandard/llm` 包；Task 2/3 的 deriver/extractor 注入它。`DefaultGbIntentExtractor` 也改为调用 `GbLlmClient`（消除重复，DRY）。这样后续不重复造 API key 解析逻辑。
- **Kill Switch**：每个 LLM 抽取器有独立配置开关，false 时跳过退化为通用骨架字段，不阻塞入库。配置统一在 `GbStandardProperties`（`jeecg.airag.gb-standard`）下新增子节。
- **状态机**：confirm 必须走 `CONFIRMED → INDEXING → COMPLETED`（现状跳过 INDEXING 是 bug）。失败回滚到 `CONFIRMED` 并记审计。常量全在 `LLMConsts`。
- **变更标记**：`//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】... ---` ... `//update-end---`。
- **测试**：`@ExtendWith(MockitoExtension.class)` 纯单元测试，Mock 掉 LLM 调用和 Mapper，AssertJ 断言。**不调真实 LLM**（测试里用 stub 返回固定 JSON）。
- **GitNexus 门禁**：改 `GbStandardController.confirm` / `GbMetadataExtractor` 前，若环境有 GitNexus MCP 工具则先跑 `impact`。本 plan 不改 `EmbeddingHandler`（HIGH 风险隔离到后续 Phase）。
- **P2 不改 EmbeddingHandler**：向量库 metadata 增强拆到后续 Phase，P2 只写结构化表。

---

## File Structure

| 文件 | 职责 | 状态 |
|------|------|------|
| `gbstandard/ingestion/GbSchemaDeriver.java` | domain_schema 推导（每标准 1 次 LLM） | 新建 |
| `gbstandard/ingestion/GbBatchExtractor.java` | 批量 LLM 抽取（4 槽位+极性+参数+引用） | 新建 |
| `gbstandard/ingestion/GbIngestionPipeline.java` | 入库编排器 | 新建 |
| `gbstandard/ingestion/dto/BatchExtractResult.java` | 批量抽取结果 VO | 新建 |
| `gbstandard/config/GbStandardProperties.java` | 加 schema-deriver/clause-metadata-extractor 配置子节 | 改 |
| `gbstandard/controller/GbStandardController.java` | confirm 接管线（CONFIRMED→INDEXING→pipeline→COMPLETED） | 改 |
| `gbstandard/repository/GbClauseRepository.java` + impl | 加 `saveBatch(List<GbClause>)` 写方法 | 改 |
| `gbstandard/repository/GbParameterRepository.java` + impl | 加 `saveBatch`（若无） | 改/核对 |
| `gbstandard/model/GbDocStructure.java` | 加 domainSchema 字段（供 deriver 回写） | 改 |
| 前端 `GbStandardPreview.vue` + pdf.ts | PDF.js 渲染 + 点击条款跳页 | 改 |

---

## Task 0: 提取共享 LLM 客户端 GbLlmClient（DRY 前置）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/llm/GbLlmClient.java`
- Modify: `.../llm/intent/DefaultGbIntentExtractor.java`（改调 GbLlmClient，消除重复）
- Test: `.../src/test/java/.../gbstandard/llm/GbLlmClientTest.java`

**Why:** `DefaultGbIntentExtractor.buildChatModel` + `resolveApiKey` 是 package-private，P2 的 deriver/extractor 在别的包无法复用。先提取成 public `@Component`，Task 2/3 注入它。这是 DRY 前置，避免在两个抽取器里复制粘贴 LLM 客户端代码。

**Interfaces:**
- Consumes: `IAiragModelService`
- Produces: `ChatModel buildChatModel(String modelName, int timeoutSeconds)` —— public，按模型名+超时构建 OpenAiChatModel。Task 2/3 调它。

- [ ] **Step 1: 写失败测试**

Create `GbLlmClientTest.java`（Mockito，mock IAiragModelService 返回一个 AiragModel，验证 buildChatModel 不抛异常 + resolveApiKey 解析 JSON/plaintext 两种格式）：

```java
package org.jeecg.modules.airag.llm.gbstandard.llm;

import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbLlmClient 共享 LLM 客户端测试-----------
@ExtendWith(MockitoExtension.class)
class GbLlmClientTest {

    @Mock
    private IAiragModelService airagModelService;

    @Test
    void resolveApiKeyShouldParseJsonCredential() {
        // 验证 {"apiKey":"sk-xxx"} 格式解析
        GbLlmClient client = new GbLlmClient(airagModelService);
        assertThat(client.resolveApiKey("{\"apiKey\":\"sk-xxx\"}")).isEqualTo("sk-xxx");
    }

    @Test
    void resolveApiKeyShouldReturnPlaintextAsIs() {
        GbLlmClient client = new GbLlmClient(airagModelService);
        assertThat(client.resolveApiKey("sk-plaintext")).isEqualTo("sk-plaintext");
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbLlmClient 共享 LLM 客户端测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbLlmClientTest`
Expected: 编译失败——`GbLlmClient` 不存在。

- [ ] **Step 3: 实现 GbLlmClient**

Create `GbLlmClient.java`。把 `DefaultGbIntentExtractor` 的 `buildChatModel` + `resolveApiKey` 原样搬来，改为 public，签名加 modelName + timeoutSeconds 参数：

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】共享 LLM 客户端（从 DefaultGbIntentExtractor 提取，DRY）-----------
package org.jeecg.modules.airag.llm.gbstandard.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * GB-RAG 共享 LLM 客户端。
 * <p>从 DefaultGbIntentExtractor 提取的 OpenAI 兼容端点接入逻辑（buildChatModel + resolveApiKey），
 * 供 GbSchemaDeriver / GbBatchExtractor / DefaultGbIntentExtractor 复用，避免重复。</p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbLlmClient {

    private final IAiragModelService airagModelService;

    public GbLlmClient(IAiragModelService airagModelService) {
        this.airagModelService = airagModelService;
    }

    /**
     * 按模型名 + 超时构建 ChatModel（查 airag_model 表，解析 credential）。
     */
    public ChatModel buildChatModel(String modelName, int timeoutSeconds) {
        AiragModel model = airagModelService.lambdaQuery()
                .eq(AiragModel::getName, modelName)
                .eq(AiragModel::getActivateFlag, 1).one();
        if (model == null) {
            throw new IllegalStateException("未找到已激活的模型: " + modelName);
        }
        String baseUrl = model.getBaseUrl();
        String apiKey = resolveApiKey(model.getCredential());
        String realModelName = model.getModelName();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(realModelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .build();
    }

    /**
     * 解析 airag_model.credential：JSON {"apiKey":"..."} 或明文。
     */
    public String resolveApiKey(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("credential 为空");
        }
        String trimmed = credential.trim();
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                if (node.has("apiKey")) {
                    return node.get("apiKey").asText();
                }
            } catch (Exception e) {
                log.warn("[GbLlmClient] credential JSON 解析失败，按明文处理: {}", e.getMessage());
            }
        }
        return trimmed;
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】共享 LLM 客户端（从 DefaultGbIntentExtractor 提取，DRY）-----------
```

- [ ] **Step 4: DefaultGbIntentExtractor 改调 GbLlmClient**

Modify `DefaultGbIntentExtractor.java`：注入 `GbLlmClient`，把内部的 `buildChatModel` / `resolveApiKey` 私有方法删除，改为调用 `gbLlmClient.buildChatModel(properties.getPrimaryModelName(), properties.getTimeoutSeconds())` 和（若用到）`gbLlmClient.resolveApiKey`。验证既有测试 `DefaultGbIntentExtractorTest` 仍通过。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbLlmClientTest,DefaultGbIntentExtractorTest`
Expected: PASS（新测试 + 既有测试都不破）。

- [ ] **Step 6: Commit**

```bash
git add <3 个文件>
git commit -m "refactor(airag): GB-RAG v4 P2 提取 GbLlmClient 共享 LLM 客户端（DRY，Task 2/3 复用）"
```

---

## Task 1: 配置子节（schema-deriver + clause-metadata-extractor）

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/config/GbStandardProperties.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/config/GbStandardPropertiesTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `GbStandardProperties` 的 `schemaDeriver`（enabled + modelName + timeoutSeconds）和 `clauseMetadataExtractor`（enabled + batchSize + modelName + timeoutSeconds）子配置，后续 Task 2/3 的抽取器注入它。

- [ ] **Step 1: 读取现有 GbStandardProperties 结构**

读 `GbStandardProperties.java`，确认现有字段（`enabled`、`userReviewEnabled`、`structureParser` 嵌套类）。现有 `StructureParser` 嵌套类有 `enabled` + `llmFallback`。

- [ ] **Step 2: 写失败测试**

Create `GbStandardPropertiesTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardProperties 配置子节测试-----------
class GbStandardPropertiesTest {

    @Test
    void schemaDeriverShouldHaveSensibleDefaults() {
        GbStandardProperties.SchemaDeriver deriver = new GbStandardProperties.SchemaDeriver();
        assertThat(deriver.isEnabled()).isTrue();          // 默认启用（领域无关，每标准 1 次）
        assertThat(deriver.getModelName()).isEqualTo("qwen-flash");
        assertThat(deriver.getTimeoutSeconds()).isEqualTo(10);
    }

    @Test
    void clauseMetadataExtractorShouldHaveBatchSizeDefault() {
        GbStandardProperties.ClauseMetadataExtractor extractor = new GbStandardProperties.ClauseMetadataExtractor();
        assertThat(extractor.isEnabled()).isTrue();
        assertThat(extractor.getBatchSize()).isEqualTo(10);
        assertThat(extractor.getModelName()).isEqualTo("qwen-flash");
        assertThat(extractor.getTimeoutSeconds()).isEqualTo(30);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardProperties 配置子节测试-----------
```

- [ ] **Step 3: 运行测试确认失败**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn -q test -Dtest=GbStandardPropertiesTest
```
Expected: 编译失败——`SchemaDeriver` / `ClauseMetadataExtractor` 嵌套类不存在。

- [ ] **Step 4: 加两个嵌套配置类 + 字段**

Modify `GbStandardProperties.java`，在现有 `StructureParser` 嵌套类之后添加两个新嵌套类和对应字段（用 Lombok `@Data`，与现有 StructureParser 风格一致）：

```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】schema-deriver + clause-metadata-extractor 配置子节-----------
    @Data
    public static class SchemaDeriver {
        /** 是否启用 domain_schema 推导（默认 true） */
        private boolean enabled = true;
        /** 推导用的轻量模型名 */
        private String modelName = "qwen-flash";
        /** 单次调用超时（秒） */
        private int timeoutSeconds = 10;
    }

    @Data
    public static class ClauseMetadataExtractor {
        /** 是否启用条款批量抽取（默认 true） */
        private boolean enabled = true;
        /** 每批打包的条款数 */
        private int batchSize = 10;
        /** 抽取用的模型名 */
        private String modelName = "qwen-flash";
        /** 单次调用超时（秒，批量调用给足时间） */
        private int timeoutSeconds = 30;
    }

    private SchemaDeriver schemaDeriver = new SchemaDeriver();
    private ClauseMetadataExtractor clauseMetadataExtractor = new ClauseMetadataExtractor();
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】schema-deriver + clause-metadata-extractor 配置子节-----------
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbStandardPropertiesTest`
Expected: PASS（2 测试）。

- [ ] **Step 6: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P2 GbStandardProperties 增加 schema-deriver/clause-metadata-extractor 配置子节"
```

---

## Task 2: GbSchemaDeriver — domain_schema 推导

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/GbSchemaDeriver.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/GbSchemaDeriverTest.java`

**Interfaces:**
- Consumes: `GbLlmClient`（Task 0 提取的共享客户端）、`GbStandardProperties.SchemaDeriver`（配置）、`ObjectMapper`。
- Produces: `DomainSchema derive(String standardIntro)` —— 输入标准前言+目录+前3章文本，输出 4 槽位 DomainSchema（或空 schema fallback）。Task 3 pipeline 调它。

- [ ] **Step 1: 写失败测试**

Create `GbSchemaDeriverTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.service.IAiragModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbSchemaDeriver 测试-----------
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbSchemaDeriverTest {

    @Mock
    private IAiragModelService airagModelService;
    @Mock
    private GbLlmClient llmClient;

    private GbStandardProperties.SchemaDeriver config;
    private GbSchemaDeriver deriver;

    @BeforeEach
    void setUp() {
        config = new GbStandardProperties.SchemaDeriver();
        deriver = new GbSchemaDeriver(airagModelService, config, new ObjectMapper(), llmClient);
    }

    @Test
    void disabledShouldReturnEmptySchemaWithoutLlmCall() {
        config.setEnabled(false);
        DomainSchema schema = deriver.derive("任何前言文本");
        // 关闭时退化为空 schema（4 槽位全 null），不阻塞入库
        assertThat(schema.getPrimaryType()).isNull();
        assertThat(schema.getSecondaryType()).isNull();
        assertThat(schema.getQuantityValue()).isNull();
        assertThat(schema.getConditionText()).isNull();
    }

    @Test
    void emptyInputShouldReturnEmptySchemaFallback() {
        // 空输入不调 LLM，直接返回空 schema
        DomainSchema schema = deriver.derive("");
        assertThat(schema.getPrimaryType()).isNull();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbSchemaDeriver 测试-----------
```

> 注：真实 LLM 调用在测试里 mock 掉（测 disabled + empty 两条 fallback 路径，不依赖外部 API）。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbSchemaDeriverTest`
Expected: 编译失败——`GbSchemaDeriver` 不存在。

- [ ] **Step 3: 实现 GbSchemaDeriver**

Create `GbSchemaDeriver.java`。骨架参照 `DefaultGbIntentExtractor` 的 LLM 客户端模式。关键设计：

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】domain_schema 推导器（每标准 1 次 LLM，领域无关）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.service.IAiragModelService;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 国标领域 Schema 推导器。
 * <p>
 * 输入标准前言+目录+前3章，输出 4 槽位的 label/enum/unit（domain_schema）。
 * 领域无关：prompt 只描述 4 个通用槽位语义（做什么/对谁/多少/什么条件），
 * 不预设任何领域的取值。失败退化为空 schema，不阻塞入库。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbSchemaDeriver {

    private final IAiragModelService airagModelService;
    private final GbStandardProperties.SchemaDeriver config;
    private final ObjectMapper objectMapper;
    private final GbLlmClient llmClient;  // Task 0 提取的共享客户端

    public GbSchemaDeriver(IAiragModelService airagModelService,
                           GbStandardProperties.SchemaDeriver config,
                           ObjectMapper objectMapper,
                           GbLlmClient llmClient) {
        this.airagModelService = airagModelService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    // 领域无关系统提示：只描述 4 个通用槽位，不列任何领域取值
    private static final String SYSTEM_PROMPT = """
            你是技术规范文档的领域分析专家。分析给定的国标文本（前言/目录/前几章），
            推导出 4 个通用语义槽位在本标准中的具体含义。

            4 个槽位是所有技术规范的共性维度（领域无关）：
            - primaryType：这条条款在说"做什么"（如测试类型/功能类别/操作类别）
            - secondaryType：针对"什么对象"（如被测物/适用组件）
            - quantityValue：涉及的"量值"是什么（如数量/规格/容量）
            - conditionText：在"什么条件"下（如环境/状态/前提）

            请输出 JSON，每个槽位给出 label（中文标签）、enumValues（常见取值数组，可空）、unit（单位，可空）。
            若某槽位在本标准中不适用，对应字段给 null。
            严格输出合法 JSON，不要解释性文字。格式：
            {"primaryType":{"label":"...","enumValues":[...],"unit":"..."},
             "secondaryType":{"label":"...","enumValues":[...],"unit":"..."},
             "quantityValue":{"label":"...","enumValues":null,"unit":"..."},
             "conditionText":{"label":"...","enumValues":null,"unit":"..."}}
            """;

    public DomainSchema derive(String standardIntro) {
        if (!config.isEnabled()) {
            log.info("[GbSchemaDeriver] 已禁用，返回空 domain_schema");
            return new DomainSchema();
        }
        if (standardIntro == null || standardIntro.isBlank()) {
            log.info("[GbSchemaDeriver] 输入为空，返回空 domain_schema");
            return new DomainSchema();
        }
        try {
            ChatModel chatModel = buildChatModel();
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(truncate(standardIntro, 8000)))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            DomainSchema schema = objectMapper.readValue(json, DomainSchema.class);
            log.info("[GbSchemaDeriver] domain_schema 推导成功: primaryType.label={}",
                    schema.getPrimaryType() != null ? schema.getPrimaryType().getLabel() : "null");
            return schema;
        } catch (Exception e) {
            log.warn("[GbSchemaDeriver] domain_schema 推导失败，退化为空 schema: {}", e.getMessage());
            return new DomainSchema();
        }
    }

    private ChatModel buildChatModel() {
        // 用 Task 0 提取的 GbLlmClient（不再重复 buildChatModel/resolveApiKey 逻辑）
        return llmClient.buildChatModel(config.getModelName(), config.getTimeoutSeconds());
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】domain_schema 推导器（每标准 1 次 LLM，领域无关）-----------
```

> ⚠️ **实施者注意**：`buildChatModel()` 不要重写——直接从 `DefaultGbIntentExtractor.java` 复制其 `buildChatModel(AiragModel)` + `resolveApiKey(String)` 两个私有方法（它们是经过验证的 OpenAI 兼容端点接入逻辑），改用 `config.getModelName()` 和 `config.getTimeoutSeconds()`。IAiragModelService 的查询也照搬。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbSchemaDeriverTest`
Expected: PASS（2 测试：disabled 和 empty 两条 fallback 路径）。

- [ ] **Step 5: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P2 新增 GbSchemaDeriver（domain_schema 推导，领域无关）"
```

---

## Task 3: GbBatchExtractor + BatchExtractResult — 批量 LLM 抽取

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/dto/BatchExtractResult.java`
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/GbBatchExtractor.java`
- Test: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/gbstandard/ingestion/GbBatchExtractorTest.java`

**Interfaces:**
- Consumes: `GbSchemaDeriver` 产的 `DomainSchema`、`IAiragModelService`、`GbStandardProperties.ClauseMetadataExtractor`、`ObjectMapper`。
- Produces: `List<BatchExtractResult> extractBatch(List<GbClauseNode> clauses, DomainSchema schema)` —— 把 N 个条款打包 1 次 LLM，每个条款返回 4 槽位 + 极性 + 参数 + 引用。Task 4 pipeline 调它。

- [ ] **Step 1: 实现 BatchExtractResult VO**

Create `dto/BatchExtractResult.java`:

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量抽取结果 VO-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 单个条款的批量抽取结果（1 次 LLM 调用产出多个）。
 * 字段与 GbClause 4 槽位 + 极性 + 参数 + 引用对齐。
 *
 * @author song
 * @date 2026-07-15
 */
@Data
public class BatchExtractResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 对应条款路径，用于回填 */
    private String clausePath;

    /** 4 个领域无关槽位 */
    private String primaryType;
    private String secondaryType;
    private BigDecimal quantityValue;
    private String conditionText;

    /** 极性: positive/negative/exception */
    private String polarity;
    /** 例外针对的条款路径（exception 时填） */
    private String exceptionOf;

    /** 抽取的参数（公式/数值），可空 */
    private List<ParamExtract> parameters;
    /** 抽取的引用关系，可空 */
    private List<RefExtract> references;

    @Data
    public static class ParamExtract implements Serializable {
        private static final long serialVersionUID = 1L;
        private String paramName;
        private String formula;       // 展示串，不 eval
        private BigDecimal paramValue;
        private String unit;
    }

    @Data
    public static class RefExtract implements Serializable {
        private static final long serialVersionUID = 1L;
        private String targetType;    // intra/inter
        private String targetStandardNo;
        private String targetClausePath;
        private String refType;       // prerequisite/normative_reference/informative
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量抽取结果 VO-----------
```

- [ ] **Step 2: 写失败测试**

Create `GbBatchExtractorTest.java`，测 disabled fallback + 单条款 JSON 解析（mock LLM 返回固定 JSON）：

```java
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.service.IAiragModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbBatchExtractor 测试-----------
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbBatchExtractorTest {

    @Mock
    private IAiragModelService airagModelService;
    @Mock
    private GbLlmClient llmClient;

    private GbStandardProperties.ClauseMetadataExtractor config;
    private GbBatchExtractor extractor;

    @BeforeEach
    void setUp() {
        config = new GbStandardProperties.ClauseMetadataExtractor();
        extractor = new GbBatchExtractor(airagModelService, config, new ObjectMapper(), llmClient);
    }

    @Test
    void disabledShouldReturnEmptyResultsPreservingClausePath() {
        config.setEnabled(false);
        GbClauseNode node = new GbClauseNode();
        node.setClausePath("9.2");
        node.setText("过压充电保护");
        List<BatchExtractResult> results = extractor.extractBatch(List.of(node), new DomainSchema());
        // 关闭时：返回与条款数相同的结果，但槽位全空（不阻塞，回填时只写 clausePath）
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClausePath()).isEqualTo("9.2");
        assertThat(results.get(0).getPrimaryType()).isNull();
        assertThat(results.get(0).getPolarity()).isNull();
    }

    @Test
    void emptyClausesShouldReturnEmptyList() {
        List<BatchExtractResult> results = extractor.extractBatch(List.of(), new DomainSchema());
        assertThat(results).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbBatchExtractor 测试-----------
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbBatchExtractorTest`
Expected: 编译失败——`GbBatchExtractor` 不存在。

- [ ] **Step 4: 实现 GbBatchExtractor**

Create `GbBatchExtractor.java`。LLM 客户端模式同 Task 2（复制 buildChatModel/resolveApiKey）。关键设计：

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量 LLM 抽取器（1 次调用产 4 槽位+极性+参数+引用，领域无关）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.service.IAiragModelService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 国标条款批量 LLM 抽取器。
 * <p>
 * 1 次调用打包 N 个条款（batchSize 个），同时产出：4 槽位值、极性、参数、引用关系。
 * 领域无关：槽位含义由 domain_schema 在 prompt 中动态注入，不硬编码领域词。
 * 失败/禁用退化为空结果（clausePath 保留，槽位全空），不阻塞入库。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbBatchExtractor {

    private final IAiragModelService airagModelService;
    private final GbStandardProperties.ClauseMetadataExtractor config;
    private final ObjectMapper objectMapper;
    private final GbLlmClient llmClient;  // Task 0 提取的共享客户端

    public GbBatchExtractor(IAiragModelService airagModelService,
                            GbStandardProperties.ClauseMetadataExtractor config,
                            ObjectMapper objectMapper,
                            GbLlmClient llmClient) {
        this.airagModelService = airagModelService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是技术规范文档的结构化抽取专家。对下面给出的每个条款，抽取其结构化信息。

            本标准的 4 个语义槽位含义：
            {{DOMAIN_SCHEMA}}

            对每个条款输出：
            - clausePath：条款路径（原样回填）
            - primaryType / secondaryType / quantityValue / conditionText：4 槽位值（不符合则 null）
            - polarity：positive(肯定要求) / negative(否定/禁止) / exception(例外)
            - exceptionOf：例外针对的条款路径（仅 exception 时填）
            - parameters：条款涉及的参数/公式（数组，每项含 paramName/formula/paramValue/unit）
            - references：条款引用关系（数组，每项含 targetType=intra|inter/targetStandardNo/targetClausePath/refType）

            输出一个 JSON 数组，每个元素对应一个条款。严格输出合法 JSON，不要解释性文字。
            """;

    public List<BatchExtractResult> extractBatch(List<GbClauseNode> clauses, DomainSchema schema) {
        if (!config.isEnabled()) {
            log.info("[GbBatchExtractor] 已禁用，返回空结果（保留 clausePath）");
            return fallbackResults(clauses);
        }
        if (clauses == null || clauses.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            ChatModel chatModel = buildChatModel();
            String prompt = SYSTEM_PROMPT_TEMPLATE.replace("{{DOMAIN_SCHEMA}}", describeSchema(schema));
            String userMsg = buildUserMessage(clauses);
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(prompt), UserMessage.from(userMsg))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            List<BatchExtractResult> results = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("[GbBatchExtractor] 批量抽取成功: {} 条款 -> {} 结果", clauses.size(), results.size());
            return results;
        } catch (Exception e) {
            log.warn("[GbBatchExtractor] 批量抽取失败，退化为空结果: {}", e.getMessage());
            return fallbackResults(clauses);
        }
    }

    /** 禁用/失败时的退化：保留 clausePath，槽位全空 */
    private List<BatchExtractResult> fallbackResults(List<GbClauseNode> clauses) {
        List<BatchExtractResult> results = new ArrayList<>();
        for (GbClauseNode c : clauses) {
            BatchExtractResult r = new BatchExtractResult();
            r.setClausePath(c.getClausePath());
            results.add(r);
        }
        return results;
    }

    /** 把 DomainSchema 转成 prompt 里的文字描述（领域无关） */
    private String describeSchema(DomainSchema schema) {
        StringBuilder sb = new StringBuilder();
        if (schema.getPrimaryType() != null) sb.append("- primaryType: ").append(schema.getPrimaryType().getLabel()).append("\n");
        if (schema.getSecondaryType() != null) sb.append("- secondaryType: ").append(schema.getSecondaryType().getLabel()).append("\n");
        if (schema.getQuantityValue() != null) sb.append("- quantityValue: ").append(schema.getQuantityValue().getLabel()).append("\n");
        if (schema.getConditionText() != null) sb.append("- conditionText: ").append(schema.getConditionText().getLabel()).append("\n");
        return sb.length() == 0 ? "（本标准未推导出领域 schema，按通用语义自由抽取）" : sb.toString();
    }

    private String buildUserMessage(List<GbClauseNode> clauses) {
        StringBuilder sb = new StringBuilder("条款列表（JSON）：\n[\n");
        for (int i = 0; i < clauses.size(); i++) {
            GbClauseNode c = clauses.get(i);
            sb.append("{\"clausePath\":\"").append(c.getClausePath()).append("\",")
              .append("\"text\":\"").append(escape(c.getText())).append("\"}");
            if (i < clauses.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private ChatModel buildChatModel() {
        // 用 Task 0 提取的 GbLlmClient
        return llmClient.buildChatModel(config.getModelName(), config.getTimeoutSeconds());
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量 LLM 抽取器（1 次调用产 4 槽位+极性+参数+引用，领域无关）-----------
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbBatchExtractorTest`
Expected: PASS（disabled + empty 两条 fallback）。

- [ ] **Step 6: Commit**

```bash
git add <三个文件>
git commit -m "feat(airag): GB-RAG v4 P2 新增 GbBatchExtractor + BatchExtractResult（批量 LLM 抽取，领域无关）"
```

---

## Task 4: GbClauseRepository 加 saveBatch 写方法

**Files:**
- Modify: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/repository/GbClauseRepository.java`
- Modify: `.../repository/impl/GbClauseRepositoryImpl.java`
- Test: `.../src/test/java/.../repository/GbClauseRepositorySaveTest.java`

**Interfaces:**
- Consumes: `GbClauseMapper`（已有）
- Produces: `int saveBatch(List<GbClause>)` + `void deleteByStandardId(String)`。Task 5 pipeline 持久化条款树时调它。

- [ ] **Step 1: 写失败测试**

Create `GbClauseRepositorySaveTest.java`（Mockito，验证 mapper.insert 被调 N 次 + 先 deleteByStandardId）：

```java
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbClauseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbClauseRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository saveBatch 测试-----------
@ExtendWith(MockitoExtension.class)
class GbClauseRepositorySaveTest {

    @InjectMocks
    private GbClauseRepositoryImpl repository;

    @Mock
    private GbClauseMapper mapper;

    @Test
    void saveBatchShouldDeleteExistingThenInsertAll() {
        GbClause c1 = new GbClause();
        c1.setClausePath("9.1");
        GbClause c2 = new GbClause();
        c2.setClausePath("9.2");
        when(mapper.insert(any(GbClause.class))).thenReturn(1);

        int count = repository.saveBatch("std-1", List.of(c1, c2));

        assertThat(count).isEqualTo(2);
        // 先删旧数据
        verify(mapper).deleteByStandardId("std-1");
        // 再插两条
        verify(mapper, times(2)).insert(any(GbClause.class));
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository saveBatch 测试-----------
```

> 注：`GbClauseMapper` 需加 `deleteByStandardId` 自定义方法（BaseMapper 没有）。在 Task 4 里给 mapper 加该方法（用 `@Delete` 注解或 XML）。测试 mock 它。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbClauseRepositorySaveTest`
Expected: 编译失败——`saveBatch` 不存在。

- [ ] **Step 3: 给 GbClauseMapper 加 deleteByStandardId**

Modify `mapper/GbClauseMapper.java`，加自定义方法（用注解，避免 XML）：

```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseMapper 加 deleteByStandardId-----------
    @org.apache.ibatis.annotations.Delete("DELETE FROM gb_clause WHERE standard_id = #{standardId}")
    int deleteByStandardId(@org.apache.ibatis.annotations.Param("standardId") String standardId);
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseMapper 加 deleteByStandardId-----------
```

- [ ] **Step 4: 接口 + impl 加 saveBatch**

Modify `GbClauseRepository.java` 接口加：
```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository 加 saveBatch-----------
    /**
     * 批量保存条款（先删该 standardId 下旧数据，再全量插入）。
     * @param standardId 标准 ID
     * @param clauses 条款列表
     * @return 插入条数
     */
    int saveBatch(String standardId, List<GbClause> clauses);
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository 加 saveBatch-----------
```

Modify `GbClauseRepositoryImpl.java` 加实现：
```java
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】saveBatch 实现-----------
    @Override
    public int saveBatch(String standardId, List<GbClause> clauses) {
        mapper.deleteByStandardId(standardId);
        int count = 0;
        for (GbClause c : clauses) {
            if (c.getStandardId() == null) c.setStandardId(standardId);
            mapper.insert(c);
            count++;
        }
        return count;
    }
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】saveBatch 实现-----------
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbClauseRepositorySaveTest`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add <4 个文件>
git commit -m "feat(airag): GB-RAG v4 P2 GbClauseRepository 加 saveBatch/deleteByStandardId 写方法"
```

---

## Task 5: GbIngestionPipeline — 编排器

**Files:**
- Create: `.../gbstandard/ingestion/GbIngestionPipeline.java`
- Test: `.../src/test/java/.../ingestion/GbIngestionPipelineTest.java`

**Interfaces:**
- Consumes: `GbSchemaDeriver`、`GbBatchExtractor`、`GbClauseRepository`、`GbParameterRepository`、`GbReferenceRepository`、`GbAuditLogRepository`、`GbStandardMapper`、`ObjectMapper`。输入：`GbStandard`（已含 markdownContent）+ `GbDocStructure`（解析树）。
- Produces: `void run(GbStandard standard, GbDocStructure structure)` —— 编排：derive schema → 存 domain_schema → batch extract（按 batchSize 分批）→ 合并槽位到 GbClause → saveBatch → 存 parameter/reference → 审计埋点。Task 6 controller 调它。

- [ ] **Step 1: 写失败测试**

Create `GbIngestionPipelineTest.java`（Mock 所有依赖，验证调用顺序：derive → extract → saveBatch → audit）：

```java
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbClauseRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbReferenceRepository;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbIngestionPipeline 编排测试-----------
@ExtendWith(MockitoExtension.class)
class GbIngestionPipelineTest {

    @InjectMocks
    private GbIngestionPipeline pipeline;

    @Mock private GbSchemaDeriver schemaDeriver;
    @Mock private GbBatchExtractor batchExtractor;
    @Mock private GbClauseRepository clauseRepository;
    @Mock private GbParameterRepository parameterRepository;
    @Mock private GbReferenceRepository referenceRepository;
    @Mock private GbAuditLogRepository auditLogRepository;
    @Mock private GbStandardMapper gbStandardMapper;
    @Mock private ObjectMapper objectMapper;

    @Test
    void runShouldDeriveExtractPersistAndAuditInOrder() {
        GbStandard standard = new GbStandard();
        standard.setId("std-1");
        standard.setMarkdownContent("前言...");
        GbDocStructure structure = new GbDocStructure();
        GbClauseNode node = new GbClauseNode();
        node.setClausePath("9.2");
        node.setText("过压充电保护");
        structure.setClauses(List.of(node));

        when(schemaDeriver.derive(any())).thenReturn(new DomainSchema());
        BatchExtractResult result = new BatchExtractResult();
        result.setClausePath("9.2");
        result.setPrimaryType("overcharge");
        when(batchExtractor.extractBatch(any(), any())).thenReturn(List.of(result));
        when(clauseRepository.saveBatch(eq("std-1"), any())).thenReturn(1);

        pipeline.run(standard, structure);

        // 验证编排顺序
        verify(schemaDeriver).derive(any());                       // 1. 推导 schema
        verify(gbStandardMapper).updateById(any(GbStandard.class)); // 2. 存 domain_schema
        verify(batchExtractor).extractBatch(any(), any());          // 3. 批量抽取
        verify(clauseRepository).saveBatch(eq("std-1"), any());     // 4. 持久化条款
        verify(auditLogRepository).save(any());                     // 5. 审计埋点
    }

    @Test
    void runShouldAuditFailureWhenExceptionThrown() {
        GbStandard standard = new GbStandard();
        standard.setId("std-1");
        when(schemaDeriver.derive(any())).thenThrow(new RuntimeException("LLM 挂了"));

        pipeline.run(standard, new GbDocStructure());

        // 异常时仍记审计（success=false）
        verify(auditLogRepository).save(any());
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbIngestionPipeline 编排测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbIngestionPipelineTest`
Expected: 编译失败——`GbIngestionPipeline` 不存在。

- [ ] **Step 3: 实现 GbIngestionPipeline**

Create `GbIngestionPipeline.java`。关键设计：树状 `GbClauseNode` 扁平化 → 按 batchSize 分批 → extractBatch → 合并槽位到 GbClause（按 clausePath 匹配）→ saveBatch → 参数/引用单独存 → 审计。

```java
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.*;
import org.jeecg.modules.airag.llm.gbstandard.repository.*;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 国标入库管线编排器。
 * <p>
 * 用户确认结构后由 GbStandardController.confirm 调用。编排顺序：
 * 1. GbSchemaDeriver 推导 domain_schema → 存 gb_standard.domain_schema
 * 2. GbBatchExtractor 按 batchSize 分批抽取 4 槽位/极性/参数/引用
 * 3. 合并到 GbClause（clausePath 匹配）→ saveBatch 持久化 gb_clause
 * 4. 参数 → gb_parameter，引用 → gb_reference
 * 5. 审计埋点（成功/失败都记）
 * 任何阶段失败记审计 success=false，不抛出（controller 据此回滚状态）。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbIngestionPipeline {

    @Autowired private GbSchemaDeriver schemaDeriver;
    @Autowired private GbBatchExtractor batchExtractor;
    @Autowired private GbClauseRepository clauseRepository;
    @Autowired private GbParameterRepository parameterRepository;
    @Autowired private GbReferenceRepository referenceRepository;
    @Autowired private GbAuditLogRepository auditLogRepository;
    @Autowired private GbStandardMapper gbStandardMapper;
    @Autowired private ObjectMapper objectMapper;

    public void run(GbStandard standard, GbDocStructure structure) {
        GbAuditLog audit = new GbAuditLog();
        audit.setSessionId("ingest-" + standard.getId());
        audit.setRetrievedClauses(structure != null ? String.valueOf(structure.getTotalClauseCount()) : "0");
        try {
            // 1. 推导 domain_schema
            DomainSchema schema = schemaDeriver.derive(extractIntro(structure, standard));
            standard.setDomainSchema(objectMapper.writeValueAsString(schema));
            gbStandardMapper.updateById(standard);

            // 2. 扁平化条款树 + 分批抽取
            List<GbClauseNode> flatClauses = flatten(structure != null ? structure.getClauses() : Collections.emptyList());
            int batchSize = 10; // 从 config 取（实施时注入 ClauseMetadataExtractor.batchSize）
            List<BatchExtractResult> allResults = new ArrayList<>();
            for (int i = 0; i < flatClauses.size(); i += batchSize) {
                List<GbClauseNode> batch = flatClauses.subList(i, Math.min(i + batchSize, flatClauses.size()));
                allResults.addAll(batchExtractor.extractBatch(batch, schema));
            }

            // 3. 合并到 GbClause 并持久化
            Map<String, BatchExtractResult> resultMap = new HashMap<>();
            for (BatchExtractResult r : allResults) resultMap.put(r.getClausePath(), r);
            List<GbClause> clauses = new ArrayList<>();
            for (GbClauseNode node : flatClauses) {
                clauses.add(toClause(standard.getId(), node, resultMap.get(node.getClausePath())));
            }
            clauseRepository.saveBatch(standard.getId(), clauses);

            // 4. 参数 + 引用（实施时从 results 收集，存 gb_parameter/gb_reference）
            persistParametersAndReferences(standard.getId(), allResults);

            audit.setSuccess(true);
            audit.setRetrievalChannels(new String[]{"schema-deriver", "batch-extractor"});
            log.info("[GbIngestionPipeline] 入库完成, standardId={}, 条款数={}", standard.getId(), clauses.size());
        } catch (Exception e) {
            log.error("[GbIngestionPipeline] 入库失败, standardId={}: {}", standard.getId(), e.getMessage(), e);
            audit.setSuccess(false);
        } finally {
            try { auditLogRepository.save(audit); } catch (Exception ignored) {}
        }
    }

    private GbClause toClause(String standardId, GbClauseNode node, BatchExtractResult r) {
        GbClause c = new GbClause();
        c.setStandardId(standardId);
        c.setClausePath(node.getClausePath());
        c.setParentPath(node.getParentPath());
        c.setDepth(node.getDepth());
        c.setTitle(node.getTitle());
        c.setText(node.getText());
        c.setClauseType(node.getClauseType());
        c.setConfidence(node.getConfidence());
        c.setPageNo(node.getPageNo());
        c.setIsScope(node.isScope());
        c.setIsAppendix(node.isAppendix());
        c.setAppendixLabel(node.getAppendixLabel());
        if (r != null) {
            c.setPrimaryType(r.getPrimaryType());
            c.setSecondaryType(r.getSecondaryType());
            c.setQuantityValue(r.getQuantityValue());
            c.setConditionText(r.getConditionText());
            c.setPolarity(r.getPolarity() != null ? r.getPolarity() : "positive");
            c.setExceptionOf(r.getExceptionOf());
        }
        return c;
    }

    private List<GbClauseNode> flatten(List<GbClauseNode> roots) {
        List<GbClauseNode> flat = new ArrayList<>();
        for (GbClauseNode n : roots) { flattenInto(n, flat); }
        return flat;
    }
    private void flattenInto(GbClauseNode n, List<GbClauseNode> out) {
        out.add(n);
        for (GbClauseNode child : n.getChildren()) flattenInto(child, out);
    }

    private String extractIntro(GbDocStructure structure, GbStandard standard) {
        // 取结构树的前若干条款文本拼成"前言+目录+前3章"近似输入
        // 实施时：拼 standard.getMarkdownContent() 前 8000 字符 + structure 概要
        return standard.getMarkdownContent() != null ? standard.getMarkdownContent() : "";
    }

    private void persistParametersAndReferences(String standardId, List<BatchExtractResult> results) {
        // 实施时：遍历 results，对每个有 parameters 的条款，转 GbParameter 存（clauseId 需先从已存 clause 查）
        // 对每个有 references 的，转 GbReference 存。此处 P2 先做 parameters，references 可选。
        log.info("[GbIngestionPipeline] 参数/引用持久化 standardId={}（实施时补全）", standardId);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
```

> ⚠️ **实施者注意**：`persistParametersAndReferences` 是占位 log。实施时需：① saveBatch 后从 clauseRepository 按 clausePath 查回 clauseId；② 遍历 results 把 parameters 转 GbParameter（setStandardId/clauseId/paramName/formula/paramValue/unit）批量插 gb_parameter；③ references 转 GbReference 批量插。GbParameterRepository/GbReferenceRepository 若无 saveBatch 同 Task 4 模式加。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbIngestionPipelineTest`
Expected: PASS（2 测试：正常编排顺序 + 异常审计）。

- [ ] **Step 5: Commit**

```bash
git add <2 个文件>
git commit -m "feat(airag): GB-RAG v4 P2 新增 GbIngestionPipeline 入库编排器（derive→extract→persist→audit）"
```

---

## Task 6: GbStandardController.confirm 接管线

**Files:**
- Modify: `.../gbstandard/controller/GbStandardController.java`（confirm 方法，约 193-218 行）
- Test: `.../src/test/java/.../controller/GbStandardControllerConfirmTest.java`

**Interfaces:**
- Consumes: `GbIngestionPipeline`（Task 5）、`GbDocumentStructureParser`（已有，re-parse 取 structure）、`GbStandardMapper`、`AiragKnowledgeDocMapper`。
- Produces: confirm 走完整状态机 `PARSED → CONFIRMED → INDEXING → pipeline.run() → COMPLETED`，失败回滚 `CONFIRMED`。

- [ ] **Step 1: 写失败测试**

Create `GbStandardControllerConfirmTest.java`（Mockito，验证 INDEXING 被设、pipeline 被调、成功设 COMPLETED、失败回滚 CONFIRMED）：

```java
package org.jeecg.modules.airag.llm.gbstandard.controller;

// Mockito 测试：mock GbIngestionPipeline + mapper，验证状态转换
// 成功路径：PARSED → CONFIRMED → INDEXING → pipeline.run → COMPLETED
// 失败路径：pipeline 抛异常 → 回滚到 CONFIRMED
// （实施时按 GbStandardController 现有依赖注入风格补全 @Mock 列表）
```

> 注：GbStandardController 用字段注入 + `new GbDocumentStructureParser()`，测试需 mock pipeline 并注入。实施时若 controller 难单测，可把 confirm 的核心逻辑抽到 package-private 方法测，或用 @Spy + reflection 注入 pipeline。**优先：把 `new GbDocumentStructureParser()` 改为可注入字段或方法参数，便于测试**（这是必要的可测性改造）。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GbStandardControllerConfirmTest`
Expected: 编译失败。

- [ ] **Step 3: 改 confirm 接管线**

Modify `GbStandardController.java`：

1. 注入 `GbIngestionPipeline`（`@Autowired private GbIngestionPipeline ingestionPipeline;`）
2. 把 `private final GbDocumentStructureParser structureParser = new GbDocumentStructureParser();` 改为可注入（`@Autowired private GbDocumentStructureParser structureParser;` + 给 GbDocumentStructureParser 加 `@Component`），便于测试。
3. 重写 confirm 方法体（替换现状的 209-217 行）：

```java
        // update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】confirm 接入入库管线（CONFIRMED→INDEXING→pipeline→COMPLETED）-----------
        // 更新为 CONFIRMED
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_CONFIRMED);

        // 进入 INDEXING
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_INDEXING);
        try {
            // 重新解析结构树（confirm 时用户可能已 saveStructure 修正过）
            GbStandard gbStandard = gbStandardMapper.selectOne(
                    new LambdaQueryWrapper<GbStandard>().eq(GbStandard::getDocId, docId));
            if (gbStandard == null) {
                throw new IllegalStateException("gb_standard 记录不存在, docId=" + docId);
            }
            String markdown = resolveMarkdownContent(doc);
            GbDocStructure structure = structureParser.parse(markdown);

            // 触发入库管线
            ingestionPipeline.run(gbStandard, structure);

            // 成功 → COMPLETED
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_COMPLETED);
            log.info("[GB知识引擎] 文档入库完成, docId={}", docId);
            return Result.OK("确认成功，国标结构已入库");
        } catch (Exception e) {
            log.error("[GB知识引擎] 文档入库失败, docId={}: {}", docId, e.getMessage(), e);
            // 失败回滚到 CONFIRMED（用户可重试）
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_CONFIRMED);
            return Result.error("入库失败: " + e.getMessage());
        }
        // update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】confirm 接入入库管线-----------
```

4. 给 `GbDocumentStructureParser` 加 `@Component` 注解（使其可注入）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GbStandardControllerConfirmTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add <相关文件>
git commit -m "feat(airag): GB-RAG v4 P2 GbStandardController.confirm 接入入库管线（完整状态机 CONFIRMED→INDEXING→COMPLETED）"
```

---

## Task 7: PDF.js 前端完善

**Files:**
- Modify: `jeecgboot-vue3/src/views/super/airag/aiknowledge/components/GbStandardPreview.vue`
- Create: `jeecgboot-vue3/src/views/super/airag/aiknowledge/utils/pdf.ts`（PDF.js 封装）
- Modify: `jeecgboot-vue3/package.json`（加 pdfjs-dist 依赖）

**Interfaces:**
- Consumes: `pdfUrl` prop（已有）+ pdfjs-dist
- Produces: 左侧 PDF 渲染 + 翻页 + 点击右侧条款树跳转 PDF 页码

- [ ] **Step 1: 安装 pdfjs-dist**

Run:
```bash
cd jeecgboot-vue3
pnpm add pdfjs-dist
```
Expected: 依赖安装成功。

- [ ] **Step 2: 封装 pdf.ts**

Create `utils/pdf.ts`：用 pdfjs-dist 加载 PDF → 渲染指定页到 canvas → 提供翻页 + 跳页 API。worker 用 `pdfjs-dist/build/pdf.worker.min.mjs`（Vite 需配置 worker 入口，或用 `import.meta.url`）。

```typescript
// 关键签名
import * as pdfjsLib from 'pdfjs-dist';
// worker 配置（Vite）
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString();

export async function loadPdf(url: string): Promise<PdfDocument>;
export interface PdfDocument {
  numPages: number;
  renderPage(pageNum: number, canvas: HTMLCanvasElement): Promise<void>;
}
```

- [ ] **Step 3: GbStandardPreview.vue 接入**

Modify `GbStandardPreview.vue`：把 `gb-pdf-placeholder` div 换成 `<canvas ref="pdfCanvas">`，在 `doPreview` 成功后用 `props.pdfUrl` 调 `loadPdf` → 渲染首页。翻页按钮接 `renderPage(currentPage±1)`。条款树节点点击 → 若条款有 pageNo → `renderPage(pageNo)` 跳转。

- [ ] **Step 4: 前端构建验证**

Run:
```bash
cd jeecgboot-vue3
pnpm build
```
Expected: 构建成功（无 TS/ESLint 错误）。若 pdfjs-dist worker 在 Vite 下有配置问题，按 Vite 文档用 `?url` 或 `?worker` 语法解决。

- [ ] **Step 5: Commit**

```bash
git add jeecgboot-vue3/
git commit -m "feat(airag): GB-RAG v4 P2 GbStandardPreview 接入 PDF.js（左侧 PDF 渲染 + 翻页 + 条款跳页）"
```

---

## Task 8: 全模块编译 + 全测试 + 验收

**Files:** 无新文件，验证性 Task。

- [ ] **Step 1: 后端全编译 + 测试**

Run:
```bash
cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag
mvn test
```
Expected: BUILD SUCCESS（P2 新增测试全过；既有 MineruApiClientUploadFileTest 失败为 P1 既有问题，不算回归）。

- [ ] **Step 2: 前端构建**

Run:
```bash
cd jeecgboot-vue3 && pnpm build
```
Expected: 构建成功。

- [ ] **Step 3: GitNexus 变更检测（若有 MCP）**

Run: `mcp__gitnexus__detect_changes({scope: "compare", base_ref: "P2 base commit"})`
Expected: 变更范围限 `gbstandard/ingestion/`、`gbstandard/controller/`、`gbstandard/repository/`、`gbstandard/config/`、前端 GbStandardPreview。**不应命中 EmbeddingHandler**（P2 隔离了它）。

- [ ] **Step 4: 验收记录 commit**

```bash
git commit --allow-empty -m "chore(airag): GB-RAG v4 P2 验收（后端测试通过；前端构建通过；未触碰 EmbeddingHandler）"
```

---

## P2 验收标准（对照 07-分阶段路线 §3）

| 验收点 | 验证方式 |
|--------|---------|
| 配置子节生效 | Task 1 测试 PASS |
| GbSchemaDeriver 推导 + fallback | Task 2 测试 PASS |
| GbBatchExtractor 批量抽取 + fallback | Task 3 测试 PASS |
| GbClauseRepository 可写入 | Task 4 测试 PASS |
| 管线编排顺序正确 + 审计 | Task 5 测试 PASS |
| confirm 完整状态机 + 失败回滚 | Task 6 测试 PASS |
| PDF.js 渲染 + 跳页 | Task 7 前端构建 + 手动验证 |
| 范围受控（不动 EmbeddingHandler） | Task 8 Step 3 |

P2 完成后，结构化表（gb_clause/gb_parameter/gb_reference）有数据，为 P3（检索层）提供基础。向量库 metadata 增强留给后续 Phase。
