# GB-RAG v4 Phase 3 (L3 检索层重构) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 GB-RAG 的意图系统为领域无关的 `QueryIntent`，修复 VectorChannel 数据流断裂，启用 weightedFuse，新建 ParamChannel + ContextAssembler，让聊天流和检索页共享同一套检索能力。

**Architecture:** 新建 `QueryIntent`（通用骨架 + 4 槽位）+ `RoutingIntentClassifier`（规则路由，领域无关词）+ `QueryIntentExtractor`（LLM 槽位抽取，复用 GbLlmClient）。废弃 `GbQueryIntent`/`AdaptiveIntentExtractor`/`IntentContext`（先切换再删）。聊天流保留 `getQueryRouter` 路径但传 `QueryIntent`（废弃 ThreadLocal）。`buildMetadataFilter` 改读 4 槽位。VectorChannel 改调带 intent 的 searchEmbedding 并修数据流。编排器改调 weightedFuse。

**Tech Stack:** Java 17 / Spring Boot / LangChain4j 1.17.2 / MyBatis-Plus / Lombok / JUnit 5 + Mockito。

**依据设计文档：** `gb-rag-v4/04-L3-在线检索层.md` + `06-现有代码处置与迁移方案.md`

**⚠️ HIGH RISK 阶段：** 本 phase 改动 `AIChatHandler`（全量 LLM 调用入口）、`EmbeddingHandler`（全检索链路）。改前每个符号须确认 blast radius；每 Task 编译+测试通过后才进下一个。

## Global Constraints

- **领域无关（核心红线）：** 任何 prompt、抽取逻辑、硬编码常量、关键词列表都不得包含领域词（overcharge/battery/电池/steel/钢铁/crush/nCells/testType 等）。4 槽位是自由字符串/数值，由 domain_schema 约束。每 Task 的实现代码须 grep 验证无领域词。
- **先切换再删（安全废弃）：** 旧类（GbQueryIntent/AdaptiveIntentExtractor/IntentContext）在所有调用方切换到新 QueryIntent 之前**保留可编译**；切换全部完成 + 测试通过后，在专门的"删除 Task"里一次性删除。中间状态必须可编译、可测试、可回滚。
- **意图两层分离：** `RoutingIntentClassifier`（规则，<1ms，领域无关词）产路由意图（CLAUSE_LOOKUP/PARAM_QUERY/SEMANTIC_SEARCH）；`QueryIntentExtractor`（LLM + 动态 domain_schema，可异步/缓存/fallback）产 4 槽位。两层独立。
- **LLM 客户端复用 GbLlmClient**（P2 Task 0）。不重复 buildChatModel/resolveApiKey。
- **废弃 IntentContext（ThreadLocal）：** 聊天流改显式参数传递 QueryIntent，不靠 ThreadLocal。保留 `extractAndCacheIntent` 在 `mergeParams` **之前**调用的顺序不变式（这是已知的 load-bearing 顺序，曾出过 bug）。
- **buildMetadataFilter 改读 4 槽位：** 过滤键改为 `primary_type/secondary_type/clause_id/chapter/standard_no/status`（与 P1 gb_clause 表 + airag_embedding metadata 对齐）。废弃旧电池键 `test_type/n_cells_alias`。
- **VectorChannel 数据流修复：** 改调 5 参数 searchEmbedding（带 QueryIntent），且修复 convertToRetrievalResult 读不到 standardId/clauseId/clausePath 的问题（EmbeddingHandler 输出 map 补这些键）。
- **weightedFuse 真正生效：** 编排器改调 weightedFuse（Vector 0.4/Structure 0.4/Param 0.2）。
- **变更标记：** `//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】... ---` ... `//update-end---`。
- **测试：** `@ExtendWith(MockitoExtension.class)` 纯单元测试，Mock 掉 LLM/Mapper，AssertJ。不调真实 LLM。
- **每 Task 编译+测试通过后才进下一个**（HIGH RISK 阶段强制）。

---

## File Structure

| 文件 | 职责 | 状态 |
|------|------|------|
| `gbstandard/query/QueryIntent.java` | 领域无关意图 POJO（通用骨架 + 4 槽位） | 新建 |
| `gbstandard/query/RoutingIntent.java` | 路由意图枚举（CLAUSE/PARAM/SEMANTIC） | 新建 |
| `gbstandard/query/RoutingIntentClassifier.java` | 规则路由（领域无关词） | 新建 |
| `gbstandard/query/QueryIntentExtractor.java` | LLM 槽位抽取（复用 GbLlmClient + domain_schema） | 新建 |
| `gbstandard/retrieval/channel/ParamChannel.java` | 结构化参数查询通道 | 新建 |
| `gbstandard/retrieval/ContextAssembler.java` | 引用上下文增强 + 引用强制注入 | 新建 |
| `llm/handler/EmbeddingHandler.java` | buildMetadataFilter 改读 4 槽位；searchEmbedding 输出补键 | 改（HIGH） |
| `llm/handler/AIChatHandler.java` | 废弃 IntentContext，传 QueryIntent | 改（HIGH） |
| `gbstandard/retrieval/channel/VectorChannel.java` | 改调 5 参数 + 修数据流 | 改 |
| `gbstandard/retrieval/orchestrator/GbRetrievalOrchestrator.java` | 改调 weightedFuse + @PreDestroy | 改 |
| `gbstandard/controller/GbRetrievalController.java` | 用新意图系统 | 改 |

---

## Task 1: 新建 QueryIntent + RoutingIntent（领域无关意图 POJO）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/query/QueryIntent.java`
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/query/RoutingIntent.java`
- Test: `.../src/test/java/.../gbstandard/query/QueryIntentTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `QueryIntent`（通用骨架 standardNo/clauseId/version/objectType/isBooleanQuery + 4 槽位 primaryType/secondaryType/quantityValue/conditionText）+ `RoutingIntent` 枚举。后续所有 Task 用它替代 GbQueryIntent。

- [ ] **Step 1: 写失败测试**

Create `QueryIntentTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntent 领域无关意图 POJO 测试-----------
class QueryIntentTest {

    @Test
    void shouldHoldGenericSkeletonAndFourSlots() {
        QueryIntent intent = QueryIntent.builder()
                .standardNo("GB 31241")
                .clauseId("9.2")
                .version("2022")
                .objectType("pack")
                .isBooleanQuery(false)
                .primaryType("overcharge")      // 测试数据用电池词，但字段本身领域无关
                .secondaryType("pack")
                .quantityValue(new BigDecimal("3"))
                .conditionText("25±5℃")
                .build();

        assertThat(intent.getStandardNo()).isEqualTo("GB 31241");
        assertThat(intent.getClauseId()).isEqualTo("9.2");
        assertThat(intent.getPrimaryType()).isEqualTo("overcharge");
        assertThat(intent.getQuantityValue()).isEqualByComparingTo("3");
    }

    @Test
    void emptyBuilderShouldHaveAllNullSlots() {
        QueryIntent intent = QueryIntent.builder().build();
        assertThat(intent.getPrimaryType()).isNull();
        assertThat(intent.getSecondaryType()).isNull();
        assertThat(intent.getQuantityValue()).isNull();
        assertThat(intent.getConditionText()).isNull();
        assertThat(intent.getStandardNo()).isNull();
    }

    @Test
    void routingIntentShouldHaveThreeValues() {
        assertThat(RoutingIntent.values())
                .containsExactly(RoutingIntent.CLAUSE_LOOKUP, RoutingIntent.PARAM_QUERY, RoutingIntent.SEMANTIC_SEARCH);
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntent 领域无关意图 POJO 测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag && mvn -q test -Dtest=QueryIntentTest`
Expected: 编译失败——QueryIntent/RoutingIntent 不存在。

- [ ] **Step 3: 实现 RoutingIntent 枚举**

Create `RoutingIntent.java`:

```java
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】路由意图枚举（领域无关，规则可判）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

/**
 * 路由意图（规则判断，<1ms，领域无关）。
 * 决定激活哪些检索通道，与 LLM 槽位抽取分离。
 *
 * @author song
 * @date 2026-07-16
 */
public enum RoutingIntent {
    /** 条款查询：用户提到具体条款号（4.1.2 / 第4章 / 附录A） */
    CLAUSE_LOOKUP,
    /** 参数查询：涉及数值/公式/计算（领域无关词） */
    PARAM_QUERY,
    /** 语义搜索：默认 */
    SEMANTIC_SEARCH
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】路由意图枚举（领域无关，规则可判）-----------
```

- [ ] **Step 4: 实现 QueryIntent**

Create `QueryIntent.java`:

```java
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】领域无关意图 POJO（通用骨架 + 4 槽位）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import dev.langchain4j.model.output.structured.Description;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 国标检索意图（领域无关）。
 * <p>
 * 通用骨架字段：所有技术规范共享（standardNo/clauseId/version/objectType/isBooleanQuery）。
 * 4 个固定语义槽位：做什么/对谁/多少/什么条件，值由 LLM 按 domain_schema 填充。
 * 替代旧 GbQueryIntent（电池专属 POJO）。领域差异全部下沉到 domain_schema 数据。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntent implements Serializable {
    private static final long serialVersionUID = 1L;

    // ===== 通用骨架字段（所有标准共享）=====

    @Description("GB 标准号，如 GB 31241 / GB/T 31467.3；未提及则 null")
    private String standardNo;

    @Description("条款号，如 '9.2' / '8.3.1'；未提及则 null")
    private String clauseId;

    @Description("标准版次/年份，如 '2022'；未提及则 null")
    private String version;

    @Description("对象类型（领域无关，具体含义由 domain_schema 定义）；未提及则 null")
    private String objectType;

    @Description("极性：true=用户问'能否/是否'；false=用户问'如何/怎么'；未提及则 null")
    private Boolean isBooleanQuery;

    // ===== 4 个固定语义槽位（领域无关）=====

    @Description("槽位1-做什么：测试类型/功能类别/操作类别（领域无关，具体取值由 domain_schema 约束）")
    private String primaryType;

    @Description("槽位2-对谁：对象/适用物/被测组件（领域无关）")
    private String secondaryType;

    @Description("槽位3-多少：量值（领域无关）")
    private BigDecimal quantityValue;

    @Description("槽位4-什么条件：环境/状态/前提条件（领域无关）")
    private String conditionText;
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】领域无关意图 POJO（通用骨架 + 4 槽位）-----------
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=QueryIntentTest`
Expected: PASS（3 测试）。

- [ ] **Step 6: 领域无关验证**

Run: `grep -iE "overcharge|battery|电池|steel|钢铁|crush|nail" QueryIntent.java`
Expected: 0 匹配（`@Description` 里不能有领域词——上面代码已确保）。

- [ ] **Step 7: Commit**

```bash
git add <两个主文件 + 测试>
git commit -m "feat(airag): GB-RAG v4 P3 新增 QueryIntent + RoutingIntent（领域无关意图，替代 GbQueryIntent）"
```

---

## Task 2: RoutingIntentClassifier — 规则路由（领域无关词）

**Files:**
- Create: `.../gbstandard/query/RoutingIntentClassifier.java`
- Test: `.../src/test/java/.../gbstandard/query/RoutingIntentClassifierTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `RoutingIntent classify(String query)` —— 规则判断（条款号正则 / 领域无关参数词），<1ms。Task 4/5 用它。

- [ ] **Step 1: 写失败测试**

Create `RoutingIntentClassifierTest.java`:

```java
package org.jeecg.modules.airag.llm.gbstandard.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RoutingIntentClassifier 规则路由测试-----------
class RoutingIntentClassifierTest {

    private RoutingIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RoutingIntentClassifier();
    }

    @ParameterizedTest
    @CsvSource({
            "4.1.2 条是什么, CLAUSE_LOOKUP",
            "第4章的要求, CLAUSE_LOOKUP",
            "附录A的内容, CLAUSE_LOOKUP",
            "9.2.3, CLAUSE_LOOKUP"
    })
    void shouldClassifyClauseLookup(String query, RoutingIntent expected) {
        assertThat(classifier.classify(query)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "这个参数的数值是多少, PARAM_QUERY",
            "公式怎么计算, PARAM_QUERY",
            "大于多少算合格, PARAM_QUERY"
    })
    void shouldClassifyParamQuery(String query, RoutingIntent expected) {
        assertThat(classifier.classify(query)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "电池组的一般要求",
            "如何进行安全测试",
            "这个标准主要讲什么"
    })
    void shouldClassifySemanticSearchByDefault(String query) {
        assertThat(classifier.classify(query)).isEqualTo(RoutingIntent.SEMANTIC_SEARCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emptyQueryShouldDefaultToSemantic(String query) {
        assertThat(classifier.classify(query)).isEqualTo(RoutingIntent.SEMANTIC_SEARCH);
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RoutingIntentClassifier 规则路由测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=RoutingIntentClassifierTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 RoutingIntentClassifier**

Create `RoutingIntentClassifier.java`。**关键：关键词必须是领域无关的**（数值/公式/计算/等于/大于/小于/单位），绝不能用 强度/屈服/overcharge 等领域词。

```java
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】规则路由分类器（领域无关词，<1ms）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 路由意图分类器（规则，<1ms，领域无关）。
 * <p>
 * 第一层意图：决定激活哪些检索通道。与 LLM 槽位抽取（第二层）分离。
 * 关键约束：关键词必须领域无关（数值/公式/计算等跨领域通用词），
 * 不得包含 电池/钢铁/overcharge/强度/屈服 等领域词。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component
public class RoutingIntentClassifier {

    /** 条款号正则：4.1.2 / 第4章 / 附录A（领域无关的规范文档通用格式） */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "(第[一二三四五六七八九十百千万]+[章节条])|" +
            "([\\d]+\\.[\\d]+(\\.[\\d]+)?条?)|" +
            "(附录[A-Z])",
            Pattern.CASE_INSENSITIVE
    );

    /** 参数/数值关键词（领域无关：任何技术规范都有数值/公式/计算） */
    private static final List<String> PARAM_KEYWORDS = List.of(
            "数值", "公式", "计算", "等于", "大于", "小于", "范围", "单位", "阈值", "系数", "倍率"
    );

    public RoutingIntent classify(String query) {
        if (query == null || query.trim().isEmpty()) {
            return RoutingIntent.SEMANTIC_SEARCH;
        }
        String normalized = query.trim();
        // 1. 条款查询（正则）
        if (CLAUSE_PATTERN.matcher(normalized).find()) {
            log.debug("[RoutingIntent] CLAUSE_LOOKUP: {}", query);
            return RoutingIntent.CLAUSE_LOOKUP;
        }
        // 2. 参数查询（领域无关关键词）
        for (String kw : PARAM_KEYWORDS) {
            if (normalized.contains(kw)) {
                log.debug("[RoutingIntent] PARAM_QUERY: {}", query);
                return RoutingIntent.PARAM_QUERY;
            }
        }
        // 3. 默认语义搜索
        return RoutingIntent.SEMANTIC_SEARCH;
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】规则路由分类器（领域无关词，<1ms）-----------
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=RoutingIntentClassifierTest`
Expected: PASS。

- [ ] **Step 5: 领域无关验证**

Run: `grep -iE "overcharge|battery|电池|steel|钢铁|crush|nail|强度|屈服|抗拉" RoutingIntentClassifier.java`
Expected: 0 匹配。

- [ ] **Step 6: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P3 新增 RoutingIntentClassifier（规则路由，领域无关词）"
```

---

## Task 3: QueryIntentExtractor — LLM 槽位抽取

**Files:**
- Create: `.../gbstandard/query/QueryIntentExtractor.java`
- Test: `.../src/test/java/.../gbstandard/query/QueryIntentExtractorTest.java`

**Interfaces:**
- Consumes: `GbLlmClient`（P2）、`ObjectMapper`。可选 `GbStandardMapper`（查 domain_schema）。
- Produces: `QueryIntent extractWithFallback(String userQuery, List<String> knowIds)` —— LLM 抽 4 槽位 + 通用骨架，带 fallback（失败返回空 QueryIntent，不阻塞）。替代 `DefaultGbIntentExtractor.extractWithFallback`（P3 末尾删旧类时，聊天流切到这个）。

- [ ] **Step 1: 写失败测试**

Create `QueryIntentExtractorTest.java`（Mockito，测 disabled fallback + empty fallback，不调真实 LLM）：

```java
package org.jeecg.modules.airag.llm.gbstandard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntentExtractor LLM 槽位抽取测试-----------
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryIntentExtractorTest {

    @Mock
    private GbLlmClient gbLlmClient;

    private QueryIntentExtractor extractor;

    @BeforeEach
    void setUp() {
        // enabled=true（默认），测试 fallback 路径
        extractor = new QueryIntentExtractor(gbLlmClient, new ObjectMapper(), true);
    }

    @Test
    void emptyQueryShouldReturnEmptyIntent() {
        QueryIntent intent = extractor.extractWithFallback("", List.of("know-1"));
        assertThat(intent).isNotNull();
        assertThat(intent.getPrimaryType()).isNull();
        assertThat(intent.getStandardNo()).isNull();
    }

    @Test
    void nullKnowIdsShouldReturnEmptyIntent() {
        QueryIntent intent = extractor.extractWithFallback("某 query", null);
        assertThat(intent).isNotNull();
        assertThat(intent.getPrimaryType()).isNull();
    }

    @Test
    void disabledShouldReturnEmptyIntentWithoutLlmCall() {
        QueryIntentExtractor disabled = new QueryIntentExtractor(gbLlmClient, new ObjectMapper(), false);
        QueryIntent intent = disabled.extractWithFallback("3S 电池包过充", List.of("know-1"));
        assertThat(intent.getPrimaryType()).isNull();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntentExtractor LLM 槽位抽取测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=QueryIntentExtractorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现 QueryIntentExtractor**

Create `QueryIntentExtractor.java`。复用 GbLlmClient，prompt 领域无关（只描述 4 槽位 + 通用骨架，不列领域 enum）。

```java
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】LLM 槽位抽取器（领域无关，复用 GbLlmClient，带 fallback）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 国标检索意图 LLM 槽位抽取器（领域无关）。
 * <p>
 * 第二层意图：LLM 抽取 4 槽位 + 通用骨架字段。prompt 领域无关，
 * 槽位含义由 domain_schema 在运行时注入（当前 P3 先用通用描述，
 * domain_schema 注入留待标准多挂载时增强）。
 * 失败/禁用返回空 QueryIntent（全 null），不阻塞检索（退化为纯向量召回）。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component
public class QueryIntentExtractor {

    private final GbLlmClient gbLlmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    @Autowired
    public QueryIntentExtractor(GbLlmClient gbLlmClient,
                                ObjectMapper objectMapper,
                                @Value("${jeecg.airag.gb-standard.query-intent-enabled:true}") boolean enabled) {
        this.gbLlmClient = gbLlmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** 测试用构造器（直接传 enabled） */
    QueryIntentExtractor(GbLlmClient gbLlmClient, ObjectMapper objectMapper, boolean enabled) {
        this.gbLlmClient = gbLlmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // 领域无关系统提示：只描述 4 通用槽位 + 骨架字段，不列任何领域 enum
    private static final String SYSTEM_PROMPT = """
            你是技术规范文档的检索意图分析专家。把用户的口语化提问转为结构化 JSON。
            
            输出字段（全部领域无关）：
            - standardNo：提到的标准号（如 GB 31241），未提及则 null
            - clauseId：条款号（如 9.2），未提及则 null
            - version：版次/年份，未提及则 null
            - objectType：对象类型（通用，未提及则 null）
            - isBooleanQuery：true=用户问能否/是否；false=问如何/怎么
            - primaryType：这条问题在问"做什么"（测试类型/功能类别等），无法判断则 null
            - secondaryType：针对"什么对象"，无法判断则 null
            - quantityValue：涉及的量值（数字），无法判断则 null
            - conditionText：条件（如温度/环境），无法判断则 null
            
            严格输出合法 JSON，不要解释性文字。
            """;

    public QueryIntent extractWithFallback(String userQuery, List<String> knowIds) {
        if (!enabled) {
            log.debug("[QueryIntentExtractor] 已禁用，返回空 intent");
            return QueryIntent.builder().build();
        }
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return QueryIntent.builder().build();
        }
        if (knowIds == null || knowIds.isEmpty()) {
            return QueryIntent.builder().build();
        }
        try {
            ChatModel chatModel = gbLlmClient.buildChatModel("qwen-flash", 5);
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userQuery))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            QueryIntent intent = objectMapper.readValue(json, QueryIntent.class);
            log.info("[QueryIntentExtractor] 抽取成功: standardNo={}, primaryType={}",
                    intent.getStandardNo(), intent.getPrimaryType());
            return intent;
        } catch (Exception e) {
            log.warn("[QueryIntentExtractor] 抽取失败，返回空 intent: {}", e.getMessage());
            return QueryIntent.builder().build();
        }
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】LLM 槽位抽取器（领域无关，复用 GbLlmClient，带 fallback）-----------
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=QueryIntentExtractorTest`
Expected: PASS（3 测试：empty + nullKnowIds + disabled）。

- [ ] **Step 5: 领域无关验证**

Run: `grep -iE "overcharge|battery|电池|steel|钢铁|crush|nail" QueryIntentExtractor.java`
Expected: 0 匹配（prompt 里不能有领域词）。

- [ ] **Step 6: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P3 新增 QueryIntentExtractor（LLM 槽位抽取，领域无关，复用 GbLlmClient）"
```

---

## Task 4: EmbeddingHandler.buildMetadataFilter 改读 4 槽位（HIGH RISK）

**Files:**
- Modify: `.../llm/handler/EmbeddingHandler.java`（`buildMetadataFilter` 方法 + `searchEmbedding` 5 参数版输出 map 补键）
- Test: `.../src/test/java/.../handler/EmbeddingHandlerMetadataFilterTest.java`（新建，或扩展现有 EmbeddingHandlerMetadataTest）

**Interfaces:**
- Consumes: `QueryIntent`（Task 1，替代 GbQueryIntent）
- Produces: `buildMetadataFilter(Filter, QueryIntent)` —— 过滤键改为 4 槽位 + 通用骨架；`searchEmbedding` 输出 map 补 standardNo/clauseId/clausePath（修 VectorChannel 数据流断裂）。

**⚠️ HIGH RISK：** EmbeddingHandler 被全检索链路调用。本 Task 既要改 `buildMetadataFilter`（签名从 GbQueryIntent 改 QueryIntent），又要改 `getQueryRouter` 4 参数版（同样接 QueryIntent）。改前确认 blast radius：所有调用方。

- [ ] **Step 1: 勘察调用方**

grep 全模块 `buildMetadataFilter` / `getQueryRouter.*intent` / `searchEmbedding.*intent` 的调用方。确认：
- `buildMetadataFilter`：仅 EmbeddingHandler 内部调用（searchEmbedding + getQueryRouter）。
- `getQueryRouter(knowIds, top, sim, GbQueryIntent)`：仅 `AIChatHandler.mergeParams:398`。
- `searchEmbedding(...,GbQueryIntent)`：**无调用方**（勘察已确认）。

- [ ] **Step 2: 写失败测试**

Create `EmbeddingHandlerMetadataFilterTest.java`（Mockito，验证新过滤键 primary_type/secondary_type/clause_id 等）：

```java
package org.jeecg.modules.airag.llm.handler;

import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位测试-----------
@ExtendWith(MockitoExtension.class)
class EmbeddingHandlerMetadataFilterTest {

    @InjectMocks
    private EmbeddingHandler embeddingHandler;

    /** 反射调 private buildMetadataFilter(Filter, QueryIntent) 验证不抛异常 + 接受 QueryIntent */
    @Test
    void buildMetadataFilterShouldAcceptQueryIntentWithSlots() throws Exception {
        Method m = EmbeddingHandler.class.getDeclaredMethod("buildMetadataFilter",
                dev.langchain4j.store.embedding.filter.Filter.class, QueryIntent.class);
        m.setAccessible(true);

        QueryIntent intent = QueryIntent.builder()
                .primaryType("overcharge")
                .secondaryType("pack")
                .clauseId("9.2")
                .quantityValue(new BigDecimal("3"))
                .build();

        Object result = m.invoke(embeddingHandler,
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("knowledgeId").isEqualTo("k1"),
                intent);
        assertThat(result).isNotNull();
    }

    @Test
    void buildMetadataFilterShouldHandleNullIntent() throws Exception {
        Method m = EmbeddingHandler.class.getDeclaredMethod("buildMetadataFilter",
                dev.langchain4j.store.embedding.filter.Filter.class, QueryIntent.class);
        m.setAccessible(true);
        Object result = m.invoke(embeddingHandler,
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("knowledgeId").isEqualTo("k1"),
                null);
        assertThat(result).isNotNull();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位测试-----------
```

> 注：反射调 private 方法是因为它内部方法。若项目已有 EmbeddingHandlerMetadataTest，可扩展它。Filter 构造用 `metadataKey(...).isEqualTo(...)`（langchain4j 1.17.2 API）。

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q test -Dtest=EmbeddingHandlerMetadataFilterTest`
Expected: 编译失败——buildMetadataFilter 第二参数还是 GbQueryIntent。

- [ ] **Step 4: 改 buildMetadataFilter + getQueryRouter + searchEmbedding 签名**

Modify `EmbeddingHandler.java`：

1. **`buildMetadataFilter(Filter base, QueryIntent intent)`**（替换原 GbQueryIntent 版）：

```java
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位 + 通用骨架（废弃电池键 test_type/n_cells_alias）-----------
    private Filter buildMetadataFilter(Filter base, QueryIntent intent) {
        Filter result = base;
        if (intent == null) return result;
        if (isNotEmpty(intent.getClauseId()))       result = new And(result, metadataKey("clause_id").isEqualTo(intent.getClauseId()));
        if (isNotEmpty(intent.getStandardNo()))     result = new And(result, metadataKey("standard_no").isEqualTo(intent.getStandardNo()));
        if (isNotEmpty(intent.getVersion()))        result = new And(result, metadataKey("amendment").isEqualTo(intent.getVersion()));
        if (isNotEmpty(intent.getPrimaryType()))    result = new And(result, metadataKey("primary_type").isEqualTo(intent.getPrimaryType()));
        if (isNotEmpty(intent.getSecondaryType()))  result = new And(result, metadataKey("secondary_type").isEqualTo(intent.getSecondaryType()));
        return result;
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位 + 通用骨架-----------
```

2. **`getQueryRouter(List<String>, Integer, Double, QueryIntent)`**（4 参数版，第二参数类型 GbQueryIntent→QueryIntent）。

3. **`searchEmbedding(String, String, Integer, Double, QueryIntent)`**（5 参数版，同上）。

4. **searchEmbedding 输出 map 补键**（修 VectorChannel 数据流断裂）：在构造结果 map 时，若 embeddingStore 的 match 携带 metadata，从 metadata 读 standardNo/clauseId/clausePath/primaryType 写入 map（供 VectorChannel 读取）。具体：在 `matches` → map 转换处，加 `item.put("standardNo", match.metadata().get("standard_no")...)` 等。

> ⚠️ 实施者须先读 EmbeddingHandler 的 searchEmbedding + getQueryRouter 全文，确认 import（GbQueryIntent→QueryIntent）、所有内部 `intent.getTestType()/getNCells()/getInferredChapter()` 调用点全部改成新字段。grep `intent.get` 确保无遗漏。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=EmbeddingHandlerMetadataFilterTest,EmbeddingHandlerSearchFilterTest,EmbeddingHandlerMetadataTest`
Expected: PASS（新测试 + 既有测试。既有测试若引用 GbQueryIntent 须改为 QueryIntent）。

- [ ] **Step 6: 编译全模块确认无遗漏**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（grep 确认 EmbeddingHandler 内无 GbQueryIntent 残留引用）。

- [ ] **Step 7: Commit**

```bash
git add EmbeddingHandler.java + 测试
git commit -m "feat(airag): GB-RAG v4 P3 EmbeddingHandler buildMetadataFilter 改读 4 槽位（HIGH RISK，废弃电池键）"
```

---

## Task 5: AIChatHandler 切换到 QueryIntent（废弃 IntentContext）（HIGH RISK）

**Files:**
- Modify: `.../llm/handler/AIChatHandler.java`（extractAndCacheIntent + mergeParams）
- Test: `.../src/test/java/.../handler/AIChatHandlerIntentTest.java`

**Interfaces:**
- Consumes: `QueryIntentExtractor`（Task 3）、`QueryIntent`（Task 1）。
- Produces: 聊天流用 QueryIntent 显式传参（废弃 IntentContext ThreadLocal）。`extractAndCacheIntent` 改为 `extractIntent` 返回 QueryIntent（不写 ThreadLocal），`mergeParams` 接收它。

**⚠️ HIGH RISK + 顺序不变式：** `extractAndCacheIntent` 必须在 `mergeParams` 之前调用。改为：`completions`/`chat` 里 `QueryIntent intent = extractIntent(...)` → 把 intent 通过参数或 params 字段传给 `mergeParams`。**不破坏 load-bearing 顺序**。

- [ ] **Step 1: 勘察 + 写失败测试**

读 AIChatHandler 的 completions(line 128) + chat(line 236) + mergeParams(line 338) + extractAndCacheIntent(line 505) 全文，确认 ThreadLocal 的 set(521)/get(397)/clear(164,255) 位置。

Create `AIChatHandlerIntentTest.java`（验证：extractIntent 返回 QueryIntent 不写 ThreadLocal；mergeParams 接收 QueryIntent 传给 getQueryRouter）。具体测试设计依赖 controller 可测性——若 AIChatHandler 字段太多难 @InjectMocks，可把意图逻辑抽到 package-private 辅助方法测。**优先：抽取 `buildQueryRouterForKnowIds(knowIds, top, sim, QueryIntent)` 方法测它委托 getQueryRouter。**

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=AIChatHandlerIntentTest`
Expected: 编译失败。

- [ ] **Step 3: 改 AIChatHandler**

1. 注入 `@Autowired private QueryIntentExtractor queryIntentExtractor;`（保留 `gbIntentExtractor` 直到 Task 8 删除旧类——或此时就把 extractAndCacheIntent 改调 queryIntentExtractor，gbIntentExtractor 字段标记 @Deprecated 暂留）。

2. **`extractAndCacheIntent` → 改为 `extractIntent`**（返回 QueryIntent，不写 ThreadLocal）：

```java
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】废弃 IntentContext，extractIntent 返回 QueryIntent 显式传参-----------
    private QueryIntent extractIntent(String caller, List<ChatMessage> messages, AIChatParams params) {
        List<String> knowIds = params.getKnowIds();
        if (knowIds == null || knowIds.isEmpty()) return null;
        String userQuery = extractLastUserQuery(messages);
        if (userQuery == null || userQuery.trim().isEmpty()) return null;
        if (!GB_PATTERN.matcher(userQuery).find()) return null;
        try {
            QueryIntent intent = queryIntentExtractor.extractWithFallback(userQuery, knowIds);
            log.info("[RAG][{}] QueryIntent 抽取成功: {}", caller, intent);
            return intent;
        } catch (Exception e) {
            log.warn("[RAG][{}] QueryIntent 抽取失败: {}", caller, e.getMessage());
            return null;
        }
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】废弃 IntentContext-----------
```

3. **completions / chat**：把 `extractAndCacheIntent(...)` 改为 `QueryIntent intent = extractIntent(...)`，把 intent 传给 `mergeParams`（改签名加 QueryIntent 参数，或存到 params 的临时字段）。

4. **mergeParams**：签名加 `QueryIntent intent`，`getQueryRouter(knowIds, top, sim, intent)`（读参数而非 IntentContext.get()）。

5. 删除 `IntentContext.set/get/clear` 调用（保留 IntentContext 类直到 Task 8）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=AIChatHandlerIntentTest`
Expected: PASS。

- [ ] **Step 5: 全模块编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add AIChatHandler.java + 测试
git commit -m "feat(airag): GB-RAG v4 P3 AIChatHandler 切换 QueryIntent，废弃 IntentContext（HIGH RISK，保留 load-bearing 顺序）"
```

---

## Task 6: VectorChannel 改调 5 参数 + 修数据流

**Files:**
- Modify: `.../gbstandard/retrieval/channel/VectorChannel.java`
- Modify: `.../gbstandard/controller/GbRetrievalController.java`（把 QueryIntent 传进 RetrievalRequest）
- Modify: `.../gbstandard/retrieval/dto/RetrievalRequest.java`（加 `queryIntent: QueryIntent` 字段）

**Interfaces:**
- Consumes: `QueryIntent`（从 RetrievalRequest 取）、EmbeddingHandler 5 参数 searchEmbedding。
- Produces: VectorChannel 调带 intent 的 searchEmbedding，metadata 精排生效；convertToRetrievalResult 能读到 standardNo/clauseId/clausePath（Task 4 已补键）。

- [ ] **Step 1: RetrievalRequest 加 queryIntent 字段**

Modify `RetrievalRequest.java`，加 `private QueryIntent queryIntent;`（与原 `intent: String` 并存——String intent 留给 RoutingIntent 路由用，queryIntent 给槽位精排）。

- [ ] **Step 2: VectorChannel 改调 5 参数**

Modify `VectorChannel.java`：把 4 参数 `searchEmbedding` 改为 5 参数（传 `request.getQueryIntent()`）：

```java
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】VectorChannel 改调带 intent 的 searchEmbedding（启用 metadata 精排）-----------
    List<Map<String, Object>> searchResults = embeddingHandler.searchEmbedding(
            knowledgeIds.get(0),
            request.getQuery(),
            request.getTopK(),
            request.getSimilarityThreshold(),
            request.getQueryIntent()      // 5 参数，启用 4 槽位 metadata 过滤
    );
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】-----------
```

同时修 `convertToRetrievalResult`：从 map 读 standardNo/clauseId/clausePath（Task 4 已补键），不再恒为 null。

- [ ] **Step 3: GbRetrievalController 注入 QueryIntent**

Modify `GbRetrievalController.java`：注入 `QueryIntentExtractor` + `RoutingIntentClassifier`。`/query` 端点里：
1. `RoutingIntent routing = routingIntentClassifier.classify(query)` → `request.setIntent(routing.name())`
2. `QueryIntent qi = queryIntentExtractor.extractWithFallback(query, knowledgeIds)` → `request.setQueryIntent(qi)`

- [ ] **Step 4: 编译 + 既有测试**

Run: `mvn -q compile && mvn -q test -Dtest=*Channel*,*Controller*`
Expected: BUILD SUCCESS + 测试通过（若 VectorChannel 有测试，更新 mock）。

- [ ] **Step 5: Commit**

```bash
git add VectorChannel.java GbRetrievalController.java RetrievalRequest.java
git commit -m "feat(airag): GB-RAG v4 P3 VectorChannel 改调 5 参数 searchEmbedding + 修数据流断裂"
```

---

## Task 7: ParamChannel + ContextAssembler（新建）

**Files:**
- Create: `.../gbstandard/retrieval/channel/ParamChannel.java`
- Create: `.../gbstandard/retrieval/ContextAssembler.java`
- Test: 两个对应的测试

**Interfaces:**
- Consumes: `GbParameterRepository`（ParamChannel）、`GbReferenceRepository`（ContextAssembler）。
- Produces: ParamChannel 查 gb_parameter；ContextAssembler 做引用上下文增强 + 强制注入引用标注。

- [ ] **Step 1: 实现 ParamChannel**

Create `ParamChannel.java`（`@Component("paramChannel")`，实现 RetrievalChannel）：查 `parameterRepository.searchByName(standardId, paramName)` 或按 quantityValue 范围。返回 RetrievalResult（sourceChannel="PARAM"）。`isApplicable`：路由意图=PARAM_QUERY 或 queryIntent.quantityValue 非空。

- [ ] **Step 2: 实现 ContextAssembler**

Create `ContextAssembler.java`：输入 RRF 融合后的 `List<RetrievalResult>`，对每个 result 查 `referenceRepository.findBySourceClause(standardId, clausePath)`，命中的引用条款作为 `[引用上下文]` 追加（不参与打分）。同时格式化引用标注 `[标准号 版本] §条款号 (页码)`。

- [ ] **Step 3: 测试**

Mockito 测 ParamChannel（mock parameterRepository）+ ContextAssembler（mock referenceRepository，验证引用条款被追加为上下文）。

- [ ] **Step 4: Commit**

```bash
git add <4 个文件>
git commit -m "feat(airag): GB-RAG v4 P3 新增 ParamChannel + ContextAssembler（参数查询 + 引用上下文增强）"
```

---

## Task 8: 编排器改调 weightedFuse + 接 ParamChannel + ContextAssembler

**Files:**
- Modify: `.../gbstandard/retrieval/orchestrator/GbRetrievalOrchestrator.java`
- Modify: `.../gbstandard/retrieval/planner/QueryPlanner.java`（selectChannels 加 paramChannel）

**Interfaces:**
- Consumes: ParamChannel（Task 7）、RRFFusionStrategy.weightedFuse、ContextAssembler（Task 7）。
- Produces: 编排器调 weightedFuse（权重生效）+ 接 ParamChannel + ContextAssembler 后处理。

- [ ] **Step 1: 改编排器**

Modify `GbRetrievalOrchestrator.java`：
1. 把 `fusionStrategy.fuse(channelResults, topK)` 改为 `fusionStrategy.weightedFuse(channelResults, plan.getChannelWeights(), topK)`。
2. fuse 后调 `contextAssembler.enhance(fusedResults)`（引用上下文增强）。
3. 加 `@PreDestroy` 关闭固定线程池（修内存泄漏）。

- [ ] **Step 2: QueryPlanner 加 paramChannel**

Modify `QueryPlanner.java`：selectChannels 的 PARAM_QUERY 分支加 paramChannel。

- [ ] **Step 3: 测试 + 编译**

Run: `mvn -q test -Dtest=*Orchestrator*,*Planner*`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add <相关文件>
git commit -m "feat(airag): GB-RAG v4 P3 编排器改调 weightedFuse + 接 ParamChannel/ContextAssembler + @PreDestroy"
```

---

## Task 9: 删除旧意图系统（切换全部完成后的清理）

**Files:**
- Delete: `common/handler/GbQueryIntent.java`
- Delete: `common/handler/IGbIntentExtractor.java`
- Delete: `common/handler/IntentContext.java`
- Delete: `retrieval/intent/AdaptiveIntentExtractor.java`
- 可选 Delete: `llm/intent/DefaultGbIntentExtractor.java`（若聊天流已完全切到 QueryIntentExtractor）+ `GbIntentValidator.java` + `GbIntentExtractorProperties.java`

**前置条件：** Task 1-8 全部完成 + 编译通过 + 全测试通过。grep 确认这些旧类无任何引用残留。

- [ ] **Step 1: grep 确认无引用**

Run: `grep -rn "GbQueryIntent\|IGbIntentExtractor\|IntentContext\|AdaptiveIntentExtractor" src/main`
Expected: 仅在待删文件自身内（或零引用）。

- [ ] **Step 2: 删除文件**

```bash
git rm <上述文件>
```

- [ ] **Step 3: 全编译 + 全测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS（无引用残留）。

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(airag): GB-RAG v4 P3 删除旧意图系统（GbQueryIntent/AdaptiveIntentExtractor/IntentContext，已全部切换到 QueryIntent）"
```

---

## Task 10: 全模块编译 + 全测试 + 验收

- [ ] **Step 1: 后端全测试**

Run: `cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag && mvn test`
Expected: BUILD SUCCESS（P3 新增测试全过；MineruApiClientUploadFileTest 既有失败不算回归）。

- [ ] **Step 2: 范围验证**

确认改动覆盖：`gbstandard/query/*`、`gbstandard/retrieval/*`、`EmbeddingHandler`、`AIChatHandler`、删除的旧类。

- [ ] **Step 3: 验收 commit**

```bash
git commit --allow-empty -m "chore(airag): GB-RAG v4 P3 验收（检索层重构完成，意图统一，weightedFuse 生效）"
```

---

## P3 验收标准（对照 07-分阶段路线 §4）

| 验收点 | 验证方式 |
|--------|---------|
| QueryIntent 领域无关 | Task 1 grep 无领域词 |
| 规则路由领域无关 | Task 2 grep 无领域词 |
| LLM 槽位抽取 + fallback | Task 3 测试 PASS |
| buildMetadataFilter 读 4 槽位 | Task 4 测试 PASS + 全编译 |
| 聊天流废弃 IntentContext | Task 5 测试 PASS + 顺序不变式保留 |
| VectorChannel metadata 精排 | Task 6 改调 5 参数 |
| ParamChannel + ContextAssembler | Task 7 测试 PASS |
| weightedFuse 生效 | Task 8 编排器改调 |
| 旧意图系统删除 | Task 9 grep 零引用 + 全编译 |
