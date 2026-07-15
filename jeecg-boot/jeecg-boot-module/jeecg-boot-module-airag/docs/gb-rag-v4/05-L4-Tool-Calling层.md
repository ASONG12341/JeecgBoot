# 05 · L4 Tool Calling 层

> **日期**：2026-07-15
> **解决根因**：2（LLM 不会计算只会续写）、13（可解释性/证据链）
> **核心设计**：GbCalculationTool（安全计算）+ 引用强制注入（非 Tool）+ System Prompt 约束

---

## 1. 层定位

L4 是**在线推理层**，只在聊天流（AIChatHandler）生效，不参与检索页（检索页只到 L3）。

L4 解决两个根因：
- 根因 2：LLM 续写产生算术幻觉（"n=3, U=n×6.0" → LLM 输出训练语料常见数字）。修正：Tool 计算。
- 根因 13：合规回答必须给来源。修正：引用强制注入 + System Prompt 约束。

## 2. 两个能力，但定位不同

| 能力 | 形态 | 是否核心交付 | 理由 |
|------|------|------------|------|
| 算术计算 | `GbCalculationTool`（LangChain4j Tool） | ✅ 核心 | LLM 算术幻觉无法调参消除，必须外置 |
| 引用注入 | ContextAssembler 强制注入（L3）+ System Prompt | ✅ 核心（但不在 L4） | 引用是合规强制，不该由 LLM 决定是否调 Tool |
| 完整引用格式化 | `GbCitationTool`（可选） | ⚪ 可选降级 | 仅"用户主动问某条款完整引用格式"时才需要 |

> **重要修正**：旧设计把 `GbCitationTool` 列为核心 Tool。但"每次回答必须引用条款号"是合规硬要求，不该由 LLM 决定是否调用 Tool。所以引用降级为 L3 ContextAssembler 的强制注入（见 `04-L3 §5.2`），L4 只保留 `GbCalculationTool` 作为核心 Tool。

## 3. GbCalculationTool — 安全计算

### 3.1 设计原则：不 eval LLM 字符串

公式来源是 LLM 抽取的 `gb_parameter.formula_display` 字符串。直接 eval 等于信任 LLM 输出执行代码——**安全风险**。

**结构化存储（见 `02-L1 §3.3`）**：
- `param_value`：静态值（LLM 抽出的具体数值，**优先返回这个**）
- `formula_display`：仅供展示（"U = n × 6.0 V"），**不 eval**
- `variables`：变量绑定 `[{"name":"n","desc":"电池串数"}]`

### 3.2 接口签名

```java
@Tool("查询国标中的技术参数并计算公式结果；禁止 LLM 自行估算数值")
public class GbCalculationTool {

    @Tool("查指定标准条款的参数值（优先返回静态 param_value；需变量代入时用安全四则运算")
    public String queryParameter(
        @P("标准号，如 GB 31241") String standardNo,
        @P("参数名，如 overcharge_threshold") String paramName,
        @P("变量值映射，如 {n:3}；无变量传空") Map<String, Object> variables
    ) {
        // 1. 查 gb_parameter 表
        // 2. 若 param_value 非空 → 直接返回（数据库值，可审计）
        // 3. 若需变量代入 → 安全四则运算（见 §3.3）
        // 4. 返回格式："过压充电截止电压 = 18.0 V（来源：[GB 31241-2022] §9.2，n=3）"
    }
}
```

### 3.3 安全四则运算（不引入 exp4j，不 eval）

`param_value` 为空、需变量代入时：
- **仅支持四则运算**（`+ - * /` 和括号），白名单运算符。
- 实现：手写递归下降四则运算解析器，或用 `javax.script.ScriptEngine`（Nashorn/SPI）限制版——**禁止任意函数调用**。
- 变量从 `variables` Map 绑定，未知变量报错而非幻觉。
- 运算结果写 audit log（输入公式 + 变量 + 结果），可审计。

> 不引入 exp4j 依赖（pom 当前无）。四则运算手写或 ScriptEngine 限制版即可，避免新依赖。

### 3.4 注册进 AIChatHandler

LangChain4j 1.x 的 Tools 注册机制（`AiServices.builder().tools(gbCalcTool)`）。仅当知识库含国标时注册。

## 4. 引用强制注入（在 L3，不在 L4）

见 `04-L3 §5.2`。要点：
- ContextAssembler 组装上下文时，每条款强制格式化 `[GB 31241-2022] §9.2 (p.15)`。
- System Prompt 追加约束。

## 5. System Prompt 增强

当知识库含国标时，自动追加：

```
你是国标安全合规助手。回答时必须遵守：
1. 引用具体的标准号和条款号（上下文已标注 [标准号 版本] §条款号）。
2. 数值计算必须使用 GbCalculationTool，禁止自行估算或凭语感输出数字。
3. 注意否定/例外条款（polarity=negative/exception 的条款，语义反转）。
4. 多标准有不同要求时，明确指出差异。
5. 优先依据 status=current 的现行版；引用旧版需明确标注版次。
```

## 6. 审计记录（L5 埋点，L4 触发）

每次 Tool 调用写 `gb_audit_log.tool_calls`：
```json
{"tool":"GbCalculationTool","input":{...},"output":"18.0 V","source":"[GB 31241-2022] §9.2"}
```

为 L5 评测提供"计算准确率"数据。

## 7. 配置与 Kill Switch

```yaml
jeecg:
  airag:
    gb-standard:
      tool:
        calc-enabled: true              # GbCalculationTool 注册开关
        citation-tool-enabled: false    # GbCitationTool 可选，默认关
```

Kill Switch：`calc-enabled=false` → 不注册 Tool，LLM 退化为"凭检索到的 param_value 静态值回答"（仍有来源，但复杂变量代入能力丧失）。
