# GB-RAG v4 Phase 4 (L4 Tool Calling + L5 评测 + I2 元数据回填) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 GB 国标知识库的聊天流加 `GbCalculationTool`（安全参数计算）+ GB 合规 System Prompt 增强 + 审计埋点与 L5 看板，并修复 I2（向量库 metadata 4 槽位键写入缺失，让 P3 的精排真正生效）。

**Architecture:** ⚠️ **关键修正：项目不用 LangChain4j `@Tool`/`AiServices`**（grep 全仓库 0 处）。Tool 必须用项目既有模式：`Map<ToolSpecification, ToolExecutor>` + `ToolSpecification.builder()` + `JsonObjectSchema` + lambda executor，通过 `AIChatParams.setTools()` 注入，由 `LLMHandler`（外部 jar）消费。参考 `BraveSearchToolBuilder`（条件注入）+ `JeecgBizToolsProvider`（spec+executor 构造）。System Prompt 注入点在 `AiragChatServiceImpl:1247`（app.prompt 之后，无现成 hook，新建）。

**Tech Stack:** Java 17 / Spring Boot / LangChain4j 1.17.2（ToolSpecification/ToolExecutor/JsonObjectSchema）/ MyBatis-Plus / Lombok / JUnit 5 + Mockito。

**依据设计文档：** `gb-rag-v4/05-L4-Tool-Calling层.md`（**Tool 注册方式部分已过时，以本 plan Global Constraints 为准**）+ `07-分阶段实施路线.md` §5

## Global Constraints

- **Tool 注册方式（红线）：** 用 `Map<ToolSpecification, ToolExecutor>`，**不用 `@Tool` 注解、不用 `AiServices.builder().tools()`**。每个 Tool = `ToolSpecification.builder().name().description().parameters(JsonObjectSchema.builder()...).build()` + `ToolExecutor` lambda（`(toolExecutionRequest, memoryId) -> { ... return resultString; }`）。参考 `JeecgBizToolsProvider`。
- **条件注入：** 仅当知识库含 GB 国标时注入 GbCalculationTool（参考 BraveSearch 的 `if (...)` 守卫）。Kill Switch：`jeecg.airag.gb-standard.tool.calc-enabled`。
- **入参 standardNo：** Tool 接收 `standardNo`（如 GB 31241），内部先查 `gb_standard` 转 `standardId`，再查 `gb_parameter`。
- **不 eval LLM 字符串：** `formula` 字段仅供展示。优先返回 `param_value`（静态值）；需变量代入时用安全四则运算（白名单 `+-*/()`，手写递归下降解析器，禁止任意函数调用）。
- **System Prompt 增强：** 在 `AiragChatServiceImpl` 现有 `appendMessage(... SystemMessage(prompt) ...)` 之后，检测 knowIds 是否含 GB 国标，追加 GB 合规约束（引用条款号、禁自算数值、注意极性）。
- **聊天侧审计写入：** 在 `AiragChatServiceImpl` 聊天调用周围记录 `gb_audit_log`（userQuery/llmResponse/latencyMs/toolCalls）。入库侧已有写入（P2 GbIngestionPipeline），本 plan 补聊天侧。
- **I2 元数据回填：** 扩展 `GbMetadata` 加 `standardNo/primaryType/secondaryType/clausePath` 字段 + `applyTo` 写入；改 `GbMetadataExtractor` 从文本抽取这些键（或从 LLM 槽位注入）；改 `EmbeddingHandler.embeddingDocument` 写入路径。注意：**现有已嵌入的文档需重新嵌入才生效**（运维操作，非代码）。
- **领域无关红线：** GbCalculationTool 的 Tool description / System Prompt 不得硬编码电池/钢铁词。Tool 是通用的"查参数+算公式"。
- **变更标记：** `//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】... ---` ... `//update-end---`。
- **测试：** `@ExtendWith(MockitoExtension.class)` 纯单元测试，Mock 掉 Mapper/Repository/LLM，AssertJ。Tool executor 测试用 mock Repository 返回固定 GbParameter。
- **每 Task 编译+测试通过才进下一个。**

---

## File Structure

| 文件 | 职责 | 状态 |
|------|------|------|
| `gbstandard/tool/GbCalculationToolBuilder.java` | 构建 GbCalculationTool 的 `Map<ToolSpecification,ToolExecutor>` | 新建 |
| `gbstandard/tool/SafeArithmeticEvaluator.java` | 安全四则运算解析器（白名单 `+-*/()`） | 新建 |
| `gbstandard/config/GbStandardProperties.java` | 加 `Tool` 内部类（calc-enabled） | 改 |
| `gbstandard/service/GbStandardResolver.java` | standardNo → standardId 解析 | 新建 |
| `app/service/impl/AiragChatServiceImpl.java` | 注入 Tool + System Prompt + 审计写入 | 改（核心集成点） |
| `llm/vo/GbMetadata.java` | 加 4 个新键字段 + applyTo | 改（I2） |
| `llm/extractor/GbMetadataExtractor.java` | 抽取新键（standardNo/clausePath/primaryType/secondaryType） | 改（I2） |
| `llm/handler/EmbeddingHandler.java` | embeddingDocument 写入新键（若需） | 改（I2，核对） |
| `gbstandard/repository/GbAuditLogRepository.java` + impl | 加查询方法（by session/time/success 聚合） | 改 |
| `gbstandard/controller/GbAuditLogController.java` | L5 看板 API | 新建 |
| `gbstandard/vo/AuditStatsVO.java` | 看板聚合 VO | 新建 |

---

## Task 1: SafeArithmeticEvaluator（安全四则运算）

**Files:**
- Create: `jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/gbstandard/tool/SafeArithmeticEvaluator.java`
- Test: `.../src/test/java/.../gbstandard/tool/SafeArithmeticEvaluatorTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `Optional<BigDecimal> evaluate(String expr, Map<String,BigDecimal> variables)` —— 仅支持 `+-*/()`，白名单运算符，未知变量报错（返回 empty）。Task 2 GbCalculationTool 调它。

- [ ] **Step 1: 写失败测试**

Create `SafeArithmeticEvaluatorTest.java`：

```java
package org.jeecg.modules.airag.llm.gbstandard.tool;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】SafeArithmeticEvaluator 安全四则运算测试-----------
class SafeArithmeticEvaluatorTest {

    private final SafeArithmeticEvaluator evaluator = new SafeArithmeticEvaluator();

    @Test
    void shouldEvaluateBasicArithmetic() {
        assertThat(evaluator.evaluate("3 * 6.0", Map.of())).contains(new BigDecimal("18.0"));
        assertThat(evaluator.evaluate("2 + 3 * 4", Map.of())).contains(new BigDecimal("14"));
        assertThat(evaluator.evaluate("(2 + 3) * 4", Map.of())).contains(new BigDecimal("20"));
    }

    @Test
    void shouldSubstituteVariables() {
        Optional<BigDecimal> r = evaluator.evaluate("n * 6.0", Map.of("n", new BigDecimal("3")));
        assertThat(r).contains(new BigDecimal("18.0"));
    }

    @Test
    void shouldRejectUnknownVariable() {
        // 未知变量 → empty（不幻觉）
        assertThat(evaluator.evaluate("x * 2", Map.of())).isEmpty();
    }

    @Test
    void shouldRejectFunctionsAndInjection() {
        // 禁止函数调用 / 非法字符 → 抛异常或 empty
        assertThat(evaluator.evaluate("Runtime.getRuntime()", Map.of())).isEmpty();
        assertThat(evaluator.evaluate("1; DROP TABLE", Map.of())).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】SafeArithmeticEvaluator 安全四则运算测试-----------
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag && mvn -q test -Dtest=SafeArithmeticEvaluatorTest`
Expected: 编译失败——类不存在。

- [ ] **Step 3: 实现 SafeArithmeticEvaluator**

Create `SafeArithmeticEvaluator.java`。**手写递归下降解析器**，不引入 exp4j（pom 无），不 eval。白名单字符：数字、`+-*/()`、空白、变量名（字母数字下划线）。任何其他字符（如 `;`、字母组合非变量、括号外的关键字）→ 拒绝。

```java
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】安全四则运算解析器（白名单 +−*/()，不 eval LLM 字符串）-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 安全四则运算解析器。
 * <p>仅支持 + - * / ( ) 和数字、变量代入。禁止函数调用、禁止非法字符。
 * 用于 GbCalculationTool 对公式做变量代入计算，绝不全信任 LLM 字符串 eval。
 * 失败返回 Optional.empty()，不抛异常到调用方（Tool 退化为返回静态 paramValue）。
 * </p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class SafeArithmeticEvaluator {

    /** 合法字符：数字、点、+-*/()、空白、字母数字下划线（变量名） */
    private static final java.util.regex.Pattern SAFE_CHARS =
            java.util.regex.Pattern.compile("^[0-9.+\\-*/()\\sA-Za-z_]+$");

    public Optional<BigDecimal> evaluate(String expr, Map<String, BigDecimal> variables) {
        if (expr == null || expr.isBlank()) return Optional.empty();
        // 1. 字符白名单预筛（拒绝 ; 中文 关键字等）
        if (!SAFE_CHARS.matcher(expr).matches()) return Optional.empty();
        try {
            // 2. 变量代入（未知变量 → 整体失败）
            String substituted = substituteVariables(expr, variables);
            if (substituted == null) return Optional.empty(); // 含未知变量
            // 3. 递归下降解析
            java.text.ParsePosition pos = new java.text.ParsePosition(0);
            BigDecimal result = new Parser(substituted, pos).parseExpression();
            if (pos.getIndex() != substituted.length() || result == null) return Optional.empty();
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 把变量名替换为数值；遇未知变量返回 null（触发整体失败） */
    private String substituteVariables(String expr, Map<String, BigDecimal> variables) {
        if (variables == null || variables.isEmpty()) {
            // 无变量表时，表达式不应含字母（纯数字算式）
            if (expr.chars().anyMatch(Character::isLetter)) return null;
            return expr;
        }
        String result = expr;
        for (Map.Entry<String, BigDecimal> e : variables.entrySet()) {
            result = result.replace(e.getKey(), e.getValue().toPlainString());
        }
        // 代入后若仍有字母 → 未知变量
        if (result.chars().anyMatch(Character::isLetter)) return null;
        return result;
    }

    /** 简单递归下降解析器（expression: term (('+'|'-') term)*; term: factor (('*'|'/') factor)*; factor: number | '(' expression ')'） */
    private static class Parser {
        private final String s;
        private final java.text.ParsePosition pos;
        Parser(String s, java.text.ParsePosition pos) { this.s = s; this.pos = pos; }

        BigDecimal parseExpression() {
            BigDecimal left = parseTerm();
            while (left != null) {
                skipWs();
                char op = peek();
                if (op == '+') { pos.setIndex(pos.getIndex()+1); left = left.add(parseTerm()); }
                else if (op == '-') { pos.setIndex(pos.getIndex()+1); left = left.subtract(parseTerm()); }
                else break;
            }
            return left;
        }

        BigDecimal parseTerm() {
            BigDecimal left = parseFactor();
            while (left != null) {
                skipWs();
                char op = peek();
                if (op == '*') { pos.setIndex(pos.getIndex()+1); left = left.multiply(parseFactor()); }
                else if (op == '/') {
                    pos.setIndex(pos.getIndex()+1);
                    BigDecimal d = parseFactor();
                    if (d == null || d.signum() == 0) return null;
                    left = left.divide(d, 10, java.math.RoundingMode.HALF_UP);
                }
                else break;
            }
            return left;
        }

        BigDecimal parseFactor() {
            skipWs();
            char c = peek();
            if (c == '(') {
                pos.setIndex(pos.getIndex()+1);
                BigDecimal inner = parseExpression();
                skipWs();
                if (peek() == ')') pos.setIndex(pos.getIndex()+1);
                else return null;
                return inner;
            }
            if (c == '-') { pos.setIndex(pos.getIndex()+1); BigDecimal f = parseFactor(); return f == null ? null : f.negate(); }
            return parseNumber();
        }

        BigDecimal parseNumber() {
            skipWs();
            int start = pos.getIndex();
            while (pos.getIndex() < s.length()) {
                char c = s.charAt(pos.getIndex());
                if (Character.isDigit(c) || c == '.') pos.setIndex(pos.getIndex()+1); else break;
            }
            if (pos.getIndex() == start) return null;
            try { return new BigDecimal(s.substring(start, pos.getIndex())); }
            catch (NumberFormatException e) { return null; }
        }

        void skipWs() { while (pos.getIndex() < s.length() && Character.isWhitespace(s.charAt(pos.getIndex()))) pos.setIndex(pos.getIndex()+1); }
        char peek() { return pos.getIndex() < s.length() ? s.charAt(pos.getIndex()) : '\0'; }
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】安全四则运算解析器-----------
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=SafeArithmeticEvaluatorTest`
Expected: PASS（4 测试：基础算术、变量代入、未知变量拒绝、注入拒绝）。

- [ ] **Step 5: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P4 新增 SafeArithmeticEvaluator（安全四则运算，白名单 +−*/()，不 eval LLM 字符串）"
```

---

## Task 2: GbStandardResolver（standardNo → standardId）

**Files:**
- Create: `.../gbstandard/service/GbStandardResolver.java`
- Test: `.../src/test/java/.../gbstandard/service/GbStandardResolverTest.java`

**Interfaces:**
- Consumes: `GbStandardMapper`（查 gb_standard）
- Produces: `Optional<String> resolveStandardId(String standardNo)` —— 按 standardNo 查 gb_standard.id（优先 current 状态）。Task 3 GbCalculationTool 调它。

- [ ] **Step 1: 写失败测试**（Mockito，mock GbStandardMapper）

```java
package org.jeecg.modules.airag.llm.gbstandard.service;

// mock GbStandardMapper 返回 GbStandard，验证 resolveStandardId 返回 id；
// 测 standardNo 不存在返回 empty；测多版本时优先 current 状态
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现 GbStandardResolver**

```java
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】standardNo→standardId 解析器-----------
package org.jeecg.modules.airag.llm.gbstandard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * GB 标准号 → standardId 解析器。
 * <p>Tool 入参用 standardNo（LLM 可见），内部转 standardId 查 gb_parameter。
 * 多版本时优先 current 状态。</p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class GbStandardResolver {

    @Autowired
    private GbStandardMapper gbStandardMapper;

    public Optional<String> resolveStandardId(String standardNo) {
        if (standardNo == null || standardNo.isBlank()) return Optional.empty();
        List<GbStandard> list = gbStandardMapper.selectList(
                new LambdaQueryWrapper<GbStandard>().eq(GbStandard::getStandardNo, standardNo.trim()));
        if (list == null || list.isEmpty()) return Optional.empty();
        // 优先 current 状态
        return list.stream()
                .filter(s -> "current".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .or(() -> list.stream().findFirst())
                .map(GbStandard::getId);
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】standardNo→standardId 解析器-----------
```

- [ ] **Step 4: 运行测试确认通过**

- [ ] **Step 5: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P4 新增 GbStandardResolver（standardNo→standardId，优先 current）"
```

---

## Task 3: GbCalculationToolBuilder（ToolSpecification 模式）

**Files:**
- Create: `.../gbstandard/tool/GbCalculationToolBuilder.java`
- Test: `.../src/test/java/.../gbstandard/tool/GbCalculationToolBuilderTest.java`

**Interfaces:**
- Consumes: `GbStandardResolver`（Task 2）、`GbParameterRepository`（findByName）、`SafeArithmeticEvaluator`（Task 1）、`ObjectMapper`（解析 Tool 入参 JSON）。
- Produces: `Map<ToolSpecification, ToolExecutor> buildTools()` —— 返回含一个 `query_gb_parameter` 工具的 Map。Task 4 AiragChatServiceImpl 注入它。

**关键：用 ToolSpecification.builder() + JsonObjectSchema + ToolExecutor lambda，不用 @Tool。参考 JeecgBizToolsProvider。**

- [ ] **Step 1: 写失败测试**（Mockito，mock resolver/repository/evaluator，构造 builder，调 buildTools()，对返回的 executor 喂入参 JSON 验证输出）

```java
package org.jeecg.modules.airag.llm.gbstandard.tool;

// mock GbStandardResolver.resolveStandardId("GB 31241") → Optional.of("std-1")
// mock GbParameterRepository.findByName("std-1","overcharge_threshold") → GbParameter(paramValue=18.0, unit="V")
// 调 buildTools() 取 executor，喂 {"standardNo":"GB 31241","paramName":"overcharge_threshold"}
// 断言输出含 "18" 和 "V"
// 测 paramValue 为空但有 formula+variables 时走 SafeArithmeticEvaluator
// 测 standardNo 不存在时返回"未找到"
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现 GbCalculationToolBuilder**

```java
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationTool 构建（ToolSpecification 模式，不用 @Tool）-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.service.GbStandardResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GbCalculationTool 构建器。
 * <p>用项目既有 ToolSpecification + ToolExecutor 模式（非 @Tool）。
 * 工具 query_gb_parameter：查 gb_parameter 表，优先返回静态 param_value，
 * 需变量代入时用 SafeArithmeticEvaluator 安全计算。领域无关（通用查参数+算公式）。
 * </p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class GbCalculationToolBuilder {

    @Autowired private GbStandardResolver standardResolver;
    @Autowired private GbParameterRepository parameterRepository;
    @Autowired private SafeArithmeticEvaluator arithmeticEvaluator;
    @Autowired private ObjectMapper objectMapper;

    public Map<ToolSpecification, ToolExecutor> buildTools() {
        Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();

        ToolSpecification spec = ToolSpecification.builder()
                .name("query_gb_parameter")
                .description("查询国标中的技术参数值并按公式计算。优先返回静态参数值；需变量代入时用安全算术计算。禁止自行估算数值。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("standardNo", "标准号，如 GB 31241")
                        .addStringProperty("paramName", "参数名，如 overcharge_threshold")
                        .addStringProperty("variables", "变量值 JSON，如 {\"n\":3}；无变量传空")
                        .required("standardNo", "paramName")
                        .build())
                .build();

        ToolExecutor executor = (toolExecutionRequest, memoryId) -> {
            try {
                JsonNode args = objectMapper.readTree(toolExecutionRequest.arguments());
                String standardNo = args.path("standardNo").asText(null);
                String paramName = args.path("paramName").asText(null);
                String variablesJson = args.path("variables").asText("");

                Optional<String> stdIdOpt = standardResolver.resolveStandardId(standardNo);
                if (stdIdOpt.isEmpty()) return "未找到标准: " + standardNo;
                GbParameter param = parameterRepository.findByName(stdIdOpt.get(), paramName);
                if (param == null) return "未找到参数: " + paramName;

                // 1. 优先返回静态值
                if (param.getParamValue() != null) {
                    return formatResult(param, param.getParamValue().toPlainString());
                }
                // 2. 需变量代入 → 安全四则运算
                if (param.getFormula() != null && !param.getFormula().isBlank()) {
                    Map<String, BigDecimal> vars = parseVariables(variablesJson);
                    Optional<BigDecimal> calc = arithmeticEvaluator.evaluate(param.getFormula(), vars);
                    if (calc.isPresent()) return formatResult(param, calc.get().toPlainString());
                    return "公式计算失败（变量不足或公式非法）: " + param.getFormula();
                }
                return "参数无值且无公式: " + paramName;
            } catch (Exception e) {
                return "工具执行异常: " + e.getMessage();
            }
        };

        tools.put(spec, executor);
        return tools;
    }

    private Map<String, BigDecimal> parseVariables(String json) {
        Map<String, BigDecimal> vars = new HashMap<>();
        if (json == null || json.isBlank()) return vars;
        try {
            JsonNode node = objectMapper.readTree(json);
            node.fields().forEachRemaining(e -> {
                try { vars.put(e.getKey(), new BigDecimal(e.getValue().asText())); }
                catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return vars;
    }

    private String formatResult(GbParameter param, String value) {
        String unit = param.getUnit() != null ? param.getUnit() : "";
        String formulaNote = param.getFormula() != null && !param.getFormula().isBlank()
                ? "（公式: " + param.getFormula() + "）" : "（静态值）";
        return value + " " + unit + formulaNote + "（来源：gb_parameter）";
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationTool 构建-----------
```

- [ ] **Step 4: 运行测试确认通过**

- [ ] **Step 5: 领域无关验证**

Run: `grep -ic "overcharge\|battery\|电池\|steel\|钢铁" GbCalculationToolBuilder.java`（description 不应有领域词；测试数据可有）
Expected: 0（生产代码 description）

- [ ] **Step 6: Commit**

```bash
git add <两个文件>
git commit -m "feat(airag): GB-RAG v4 P4 新增 GbCalculationToolBuilder（ToolSpecification 模式，standardNo 入参，安全算术）"
```

---

## Task 4: GbStandardProperties 加 Tool 子配置

**Files:**
- Modify: `.../gbstandard/config/GbStandardProperties.java`

- [ ] **Step 1: 加 Tool 内部类**

```java
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】tool 子配置（calc-enabled Kill Switch）-----------
    @Data
    public static class Tool {
        /** 是否启用 GbCalculationTool（默认 false，需显式开启） */
        private boolean calcEnabled = false;
    }

    private Tool tool = new Tool();
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】tool 子配置-----------
```

- [ ] **Step 2: 写测试 + 通过**

```java
@Test
void toolShouldHaveCalcEnabledDefaultFalse() {
    assertThat(new GbStandardProperties.Tool().isCalcEnabled()).isFalse();
}
```
加到 `GbStandardPropertiesTest`，运行 `mvn -q test -Dtest=GbStandardPropertiesTest` → PASS。

- [ ] **Step 3: Commit**

```bash
git add GbStandardProperties.java GbStandardPropertiesTest.java
git commit -m "feat(airag): GB-RAG v4 P4 GbStandardProperties 加 Tool 子配置（calc-enabled Kill Switch）"
```

---

## Task 5: AiragChatServiceImpl 注入 Tool + System Prompt + 审计

**⚠️ 核心集成点，最复杂的 Task。** 改 `AiragChatServiceImpl`（聊天主流程）。

**Files:**
- Modify: `.../app/service/impl/AiragChatServiceImpl.java`（3 处：Tool 注入 ~1447 后、System Prompt ~1247 后、审计写入聊天调用周围）

**关键：先读 AiragChatServiceImpl 的相关方法（sendWithAppChat / sendWithDefault 附近），确认注入点和 knowIds 获取方式（`chatConversation.getApp().getKnowIds()`）。**

- [ ] **Step 1: 读 AiragChatServiceImpl 相关段**

读 line 1232-1260（System Prompt）、1440-1465（Tool 注入）、以及聊天调用 `aiChatHandler.chat/completions` 的位置（确认审计写入插入点）。

- [ ] **Step 2: 判断知识库是否含 GB 国标**

新建辅助方法 `private boolean isGbStandardKnowledge(List<String> knowIds)`：用 `GbStandardMapper` 查 knowIds 是否有对应的 gb_standard 记录（knowledgeId 字段匹配）。注入 `GbStandardMapper` + `GbStandardProperties` + `GbCalculationToolBuilder` + `GbAuditLogRepository`。

- [ ] **Step 3: Tool 注入（参考 BraveSearch 模式）**

在 `aiChatParams.setTools(jeecgToolsProvider.getDefaultTools())` 和 BraveSearch 块之后（~1457 后）加：

```java
        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GB 国标知识库时注入 GbCalculationTool-----------
        if (gbStandardProperties.getTool().isCalcEnabled()
                && isGbStandardKnowledge(aiChatParams.getKnowIds())) {
            Map<ToolSpecification, ToolExecutor> gbTools = gbCalculationToolBuilder.buildTools();
            if (!gbTools.isEmpty()) {
                Map<ToolSpecification, ToolExecutor> existing = aiChatParams.getTools();
                if (existing == null) existing = new HashMap<>();
                existing.putAll(gbTools);
                aiChatParams.setTools(existing);
            }
        }
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】-----------
```

- [ ] **Step 4: System Prompt 增强（~1247 后）**

在 `appendMessage(messages, new SystemMessage(prompt), ...)` 之后加：检测 knowIds 含 GB → 追加 GB 合规 SystemMessage（引用条款号、禁自算数值、注意极性条款、多标准差异）。

- [ ] **Step 5: 审计写入（聊天调用周围）**

在 `aiChatHandler.chat/completions` 调用周围（try-finally），记录 `GbAuditLog`：userQuery、llmResponse、latencyMs、success、sessionId（会话 ID）、toolCalls（从结果提取）。失败也记。

- [ ] **Step 6: 编译 + 既有测试**

Run: `mvn -q compile && mvn -q test`
Expected: BUILD SUCCESS（若 AiragChatServiceImpl 有测试，更新 mock）。若 AiragChatServiceImpl 太重难单测，至少保证编译 + 现有测试不破。

- [ ] **Step 7: Commit**

```bash
git add AiragChatServiceImpl.java
git commit -m "feat(airag): GB-RAG v4 P4 AiragChatServiceImpl 注入 GbCalculationTool + GB System Prompt + 聊天审计"
```

---

## Task 6: I2 元数据回填（GbMetadata + GbMetadataExtractor + EmbeddingHandler）

**Files:**
- Modify: `.../llm/vo/GbMetadata.java`（加 standardNo/primaryType/secondaryType/clausePath 字段 + applyTo）
- Modify: `.../llm/extractor/GbMetadataExtractor.java`（抽取新键；regex 抽 standardNo/clausePath，槽位由调用方注入）
- Modify: `.../llm/handler/EmbeddingHandler.java`（embeddingDocument 写入路径，核对 GbMetadata 已含新键则自动写入）
- Modify: `.../llm/extractor/GbMetadataExtractorTest.java` + `.../vo/GbMetadataTest.java`

- [ ] **Step 1: 读 GbMetadata + GbMetadataExtractor + EmbeddingHandler.embeddingDocument 现状**

确认 applyTo 当前写 6 键，需加 4 键（standardNo→standard_no, primaryType→primary_type, secondaryType→secondary_type, clausePath→clause_path）。

- [ ] **Step 2: GbMetadata 加字段 + applyTo**

```java
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】GbMetadata 加 4 键（修复 P3 buildMetadataFilter 读但未写的降级）-----------
    public static final String KEY_STANDARD_NO = "standard_no";
    public static final String KEY_PRIMARY_TYPE = "primary_type";
    public static final String KEY_SECONDARY_TYPE = "secondary_type";
    public static final String KEY_CLAUSE_PATH = "clause_path";

    private String standardNo;
    private String primaryType;
    private String secondaryType;
    private String clausePath;
    // applyTo 里加 4 个 putIfNotEmpty
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------
```

- [ ] **Step 3: GbMetadataExtractor 抽取 standardNo + clausePath**

regex 抽 standardNo（GB_PATTERN 已有）、clausePath（条款号正则）。primaryType/secondaryType 是 LLM 槽位概念，regex 抽不出——**这些键由 P2 的 GbBatchExtractor 抽取后，在 EmbeddingHandler.embeddingDocument 写入时从 GbMetadata 传入**（需 EmbeddingHandler 能拿到当前 chunk 的 clause 槽位）。

- [ ] **Step 4: 更新测试**

GbMetadataTest：验证 applyTo 写新键。GbMetadataExtractorTest：验证抽 standardNo/clausePath。

- [ ] **Step 5: 运行测试 + 编译**

Run: `mvn -q test -Dtest=GbMetadata*Test,GbMetadataExtractor*Test,EmbeddingHandler*Test`

- [ ] **Step 6: Commit**

```bash
git add <相关文件>
git commit -m "fix(airag): GB-RAG v4 P4 I2 元数据回填（GbMetadata 加 4 键，修复 P3 buildMetadataFilter 读写不匹配）"
```

> ⚠️ **运维提醒：现有已嵌入的文档需重新嵌入（重新跑入库）才生效，本 Task 只修写入路径。**

---

## Task 7: GbAuditLogRepository 查询方法 + L5 看板 Controller

**Files:**
- Modify: `.../gbstandard/repository/GbAuditLogRepository.java` + impl（加查询方法）
- Create: `.../gbstandard/controller/GbAuditLogController.java`
- Create: `.../gbstandard/vo/AuditStatsVO.java`
- Test: repository + controller 测试

- [ ] **Step 1: Repository 加查询方法**

```java
    List<GbAuditLog> findBySessionId(String sessionId);
    List<GbAuditLog> findByTimeRange(Date start, Date end);
    long countBySuccess(boolean success, Date start, Date end);
    Map<String, Long> countByRoutingIntent(Date start, Date end);
```

impl 用 LambdaQueryWrapper。

- [ ] **Step 2: AuditStatsVO**

```java
@Data
public class AuditStatsVO {
    private long totalQueries;
    private long successCount;
    private double successRate;
    private double avgLatencyMs;
    private Map<String, Long> byRoutingIntent;
    private long fallbackCount; // extractedIntent 含 null 的数量
}
```

- [ ] **Step 3: GbAuditLogController**

`@RestController @RequestMapping("/airag/gb-standard/audit")`：
- `GET /stats?start=&end=` → AuditStatsVO
- `GET /session/{sessionId}` → List<GbAuditLog>
- `GET /recent?limit=` → List<GbAuditLog>

- [ ] **Step 4: 测试 + 编译**

- [ ] **Step 5: Commit**

```bash
git add <相关文件>
git commit -m "feat(airag): GB-RAG v4 P4 L5 评测看板（GbAuditLogRepository 查询 + Controller + Stats VO）"
```

---

## Task 8: 全模块编译 + 全测试 + 验收

- [ ] **Step 1: 全测试**

Run: `cd jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag && mvn test`
Expected: BUILD SUCCESS（P4 新增测试全过；Mineru 既有失败不算回归）。

- [ ] **Step 2: 领域无关验证**

grep P4 新增生产代码（GbCalculationToolBuilder/SafeArithmeticEvaluator/Controller）无领域词。

- [ ] **Step 3: 范围验证**

确认改动覆盖：`gbstandard/tool/`、`gbstandard/service/`、`gbstandard/controller/`、`AiragChatServiceImpl`、`GbMetadata`、`GbMetadataExtractor`、`EmbeddingHandler`。

- [ ] **Step 4: 验收 commit**

```bash
git commit --allow-empty -m "chore(airag): GB-RAG v4 P4 验收（Tool Calling + L5 看板 + I2 元数据回填）"
```

---

## P4 验收标准（对照 07-分阶段路线 §5）

| 验收点 | 验证方式 |
|--------|---------|
| SafeArithmeticEvaluator 安全（拒注入） | Task 1 测试 PASS |
| GbCalculationTool 用 ToolSpecification 模式 | Task 3 测试 PASS + 无 @Tool 注解 |
| Tool 条件注入（GB 国标 + calc-enabled） | Task 5 代码 + 编译 |
| System Prompt GB 合规增强 | Task 5 代码 |
| 聊天侧审计写入 | Task 5 代码 |
| I2 metadata 回填（4 键写入） | Task 6 GbMetadata 测试 |
| L5 看板 API | Task 7 Controller 测试 |
| 领域无关 | Task 8 grep |
