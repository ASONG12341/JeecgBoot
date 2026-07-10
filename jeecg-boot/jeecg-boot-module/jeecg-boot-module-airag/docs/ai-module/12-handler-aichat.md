# 12 · `AIChatHandler` 完整解读

> 本文档按 `AIChatHandler.java`（约 700 行）的全部公开方法逐个深度解读。这是 LLM 调用的核心适配层。

---

## 一、类概览

```java
@Slf4j
@Component
public class AIChatHandler implements IAIChatHandler {
    
    @Autowired AiragModelMapper airagModelMapper;        // 模型表查
    @Autowired AiragMcpMapper airagMcpMapper;            // MCP 查
    @Autowired EmbeddingHandler embeddingHandler;          // 注入 RAG 路由器
    @Autowired LLMHandler llmHandler;                     // base-core 实际调大模型
    @Autowired AiRagConfigBean aiRagConfigBean;            // 安全白名单 + 通用配置
    @Autowired AiChatConfig aiChatConfig;                  // yml 默认模型
    
    @Value(value = "${jeecg.path.upload:}")
    private String uploadpath;
}
```

**5 个核心方法**（接口 `IAIChatHandler`）：
- `completions(modelId, messages, params)` —— 阻塞问答
- `chat(modelId, messages, params)` —— 流式聊天
- `completionsByDefaultModel(messages, params)` —— 默认模型问答
- `chatByDefaultModel(messages, params)` —— 默认模型流式
- `imageGenerate / imageEdit` —— 绘画

私有工具方法：`mergeParams`（最核心）、`buildPlugins`、`buildImageContents`、`getFirstImageBase64`、`translateLlmException`、`injectThinkingPlaceholderIfNeeded`。

---

## 二、`completions`（阻塞问答）

### 2.1 重载 1：`completions(String modelId, List<ChatMessage> messages)`

```java
@Override
public String completions(String modelId, List<ChatMessage> messages) {
    AssertUtils.assertNotEmpty("至少发送一条消息", messages);
    AssertUtils.assertNotEmpty("请选择模型", modelId);
    return completions(modelId, messages, null);    // 调三参重载
}
```

### 2.2 重载 2：`completions(String modelId, List<ChatMessage> messages, AIChatParams params)`

```java
@Override
public String completions(String modelId, List<ChatMessage> messages, AIChatParams params) {
    AssertUtils.assertNotEmpty("至少发送一条消息", messages);
    AssertUtils.assertNotEmpty("请选择模型", modelId);
    AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);   // ★ ignoreTenant
    return completions(airagModel, messages, params);
}
```

### 2.3 重载 3：`completions(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params)` —— 实际业务方法

```java
public String completions(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params) {
    params = mergeParams(airagModel, params);   // ★ 见 §三
    
    // ★ DeepSeek 推理模型占位 issues/9585
    messages = injectThinkingPlaceholderIfNeeded(messages, airagModel.getModelName());
    
    String resp = null;
    try {
        resp = llmHandler.completions(messages, params);    // ★ 调底层 LLMHandler
    } catch (ToolExecutionException e) {
        // 工具调用执行失败：翻译 cause 后抛友好提示
        String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        causeMsg = matchErrorMsg(causeMsg, causeMsg);
        log.error("AI工具执行异常 - {}", causeMsg, e);
        return "";
    } catch (Exception e) {
        throw translateLlmException(e, "调用大模型接口失败，详情请查看后台日志。");
    }
    
    // ★ 剥离 think.../think 思考块（当 noThinking=true）
    if (resp != null && resp.contains("think")
        && (null == params.getNoThinking() || params.getNoThinking())) {
        String[] thinkSplit = resp.split("/think");
        resp = thinkSplit[thinkSplit.length - 1];
    }
    return resp;
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 121 | `mergeParams` —— 把模型 ID 转换成 `AiragModel` 后合并所有参数 |
| 123 | **DeepSeek 推理模型**：补历史消息的 `thinking` 占位（`AiMessage.thinking` 字段），避免 API 校验失败 |
| 127 | `llmHandler.completions` —— base-core 调用 langchain4j |
| 128-133 | `ToolExecutionException` 单独捕获：工具调用错误（langchain4j 抛出） |
| 134-136 | 其他异常 → `translateLlmException` 4 级降级 |
| 138-142 | 推理模型输出可能含 `think.../think`，剥离掉 |

---

## 三、`mergeParams(AiragModel, AIChatParams)` —— 核心（§四 单独详解）

最核心的方法。在 [01-controller-chat.md#4.2](01-controller-chat.md) 有概述，下面完整展开。

---

## 四、`chat(modelId, messages, params)` —— 流式聊天

```java
@Override
public TokenStream chat(String modelId, List<ChatMessage> messages, AIChatParams params) {
    AssertUtils.assertNotEmpty("至少发送一条消息", messages);
    AssertUtils.assertNotEmpty("请选择模型", modelId);
    AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
    
    // ★ 未激活走默认模型（QQYUN-14781）
    if(null == airagModel || airagModel.getActivateFlag() == 0){
        log.warn("模型未激活,采用默认模型");
        return chatByDefaultModel(messages, params);
    }
    return chat(airagModel, messages, params);
}

private TokenStream chat(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params) {
    params = mergeParams(airagModel, params);
    messages = injectThinkingPlaceholderIfNeeded(messages, airagModel.getModelName());
    return llmHandler.chat(messages, params);   // ★ 返回 langchain4j TokenStream（增量流）
}
```

`TokenStream` 是 langchain4j 的流式接口，回调触发 `onPartialResponse`（增量回复）、`onCompleteResponse`（完成）、`onError`（错误）。

---

## 五、`completionsByDefaultModel` 与 `chatByDefaultModel`

```java
@Override
public String completionsByDefaultModel(List<ChatMessage> messages, AIChatParams params) {
    return completions(new AiragModel(), messages, params);   // ★ 空 AiragModel
}

@Override
public TokenStream chatByDefaultModel(List<ChatMessage> messages, AIChatParams params) {
    return chat(new AiragModel(), messages, params);
}
```

`new AiragModel()` —— **空模型**。传给 `mergeParams` 时，因 `airagModel == null` 触发 `mergeParams` 第一段返回 `params` 的逻辑，且后续 `airagModel.getCredential()` / `getModelParams()` 都是 null，导致 `mergeParams` 内部所有 `if (oConvertUtils.isObjectNotEmpty(...))` 全部跳过——**参数完全来自外部传入**。

最终落到 `llmHandler.completions/chat(messages, params)`，params 内的 provider/modelName/baseUrl/apiKey 由调用方前置设置（一般是 `AiragAppServiceImpl.generatePrompt` 这种场景，从 `yml` 的 `aiChatConfig` 读）。

---

## 六、`buildPlugins(AIChatParams params)` —— 构造 MCP / plugin 工具

完整代码在 [01-controller-chat.md#4.3](01-controller-chat.md)。这里只关注 plugins 数据如何被注入：

```java
private void buildPlugins(AIChatParams params) {
    List<String> pluginIds = params.getPluginIds();
    if(oConvertUtils.isObjectNotEmpty(pluginIds)){
        List<McpToolProvider> mcpToolProviders = new ArrayList<>();
        List<McpToolProviderWrapper> mcpToolProviderWrappers = new ArrayList<>();
        Map<ToolSpecification, ToolExecutor> pluginTools = new HashMap<>();

        for (String pluginId : pluginIds.stream().distinct().collect(Collectors.toList())) {
            AiragMcp airagMcp = airagMcpMapper.selectById(pluginId);
            if (airagMcp == null) continue;
            String category = airagMcp.getCategory();
            if (oConvertUtils.isEmpty(category)) category = "mcp";

            if ("mcp".equalsIgnoreCase(category)) {
                McpToolProviderWrapper wrapper = buildMcpToolProviderWrapper(
                        airagMcp.getName(),
                        airagMcp.getType(),        // sse / http / stdio
                        airagMcp.getEndpoint(),
                        airagMcp.getHeaders(),
                        aiRagConfigBean.getAllowSensitiveNodes()    // ★ 安全白名单
                );
                if (wrapper != null) {
                    mcpToolProviders.add(wrapper.getMcpToolProvider());
                    mcpToolProviderWrappers.add(wrapper);
                }
            } else if ("plugin".equalsIgnoreCase(category)) {
                Map<ToolSpecification, ToolExecutor> tools = PluginToolBuilder.buildTools(airagMcp, params.getCurrentHttpRequest());
                if (tools != null && !tools.isEmpty()) pluginTools.putAll(tools);
            }
        }
        if (!mcpToolProviders.isEmpty()) params.setMcpToolProviders(mcpToolProviders);
        if (!mcpToolProviderWrappers.isEmpty()) params.setMcpToolProviderWrappers(mcpToolProviderWrappers);  // ★ 包装器保连接引用
        if (!pluginTools.isEmpty()) {
            if (params.getTools() == null) params.setTools(new HashMap<>());
            params.getTools().putAll(pluginTools);
        }
    }
}
```

详见 [05-controller-mcp.md#九](05-controller-mcp.md)。

---

## 七、`mergeParams` 完整方法（约 100 行）

```java
private AIChatParams mergeParams(AiragModel airagModel, AIChatParams params) {
    if (null == airagModel) return params;        // ★ 空 AiragModel 时直接返回
    if (params == null) params = new AIChatParams();
    
    // 1. 凭证注入
    params.setProvider(airagModel.getProvider());
    params.setModelName(airagModel.getModelName());
    params.setBaseUrl(airagModel.getBaseUrl());
    if (oConvertUtils.isObjectNotEmpty(airagModel.getCredential())) {
        JSONObject modelCredential = JSONObject.parseObject(airagModel.getCredential());
        params.setApiKey(oConvertUtils.getString(modelCredential.getString("apiKey"), null));
        params.setSecretKey(oConvertUtils.getString(modelCredential.getString("secretKey"), null));
        if(modelCredential.containsKey("httpVersionOne")){
            params.setIzHttpVersionOne(modelCredential.getInteger("httpVersionOne") == 1);
        }
    }
    
    // 2. 模型参数（取 params 优先，找不到用 AiragModel 默认）
    if (oConvertUtils.isObjectNotEmpty(airagModel.getModelParams())) {
        JSONObject modelParams = JSONObject.parseObject(airagModel.getModelParams());
        if (oConvertUtils.isObjectEmpty(params.getTemperature()))   params.setTemperature(modelParams.getDouble("temperature"));
        if (oConvertUtils.isObjectEmpty(params.getTopP()))          params.setTopP(modelParams.getDouble("topP"));
        if (oConvertUtils.isObjectEmpty(params.getPresencePenalty()))  params.setPresencePenalty(modelParams.getDouble("presencePenalty"));
        if (oConvertUtils.isObjectEmpty(params.getFrequencyPenalty())) params.setFrequencyPenalty(modelParams.getDouble("frequencyPenalty"));
        if (oConvertUtils.isObjectEmpty(params.getMaxTokens()))     params.setMaxTokens(modelParams.getInteger("maxTokens"));
        if (oConvertUtils.isObjectEmpty(params.getTimeout()))       params.setTimeout(modelParams.getInteger("timeout"));
        if (oConvertUtils.isObjectEmpty(params.getEnableSearch()))  params.setEnableSearch(modelParams.getBoolean("enableSearch"));
        if (oConvertUtils.isObjectEmpty(params.getExtraParams()) && modelParams.containsKey("extraParams")) {
            params.setExtraParams(modelParams.getObject("extraParams", Map.class));   // ★ qwen-vl-ocr 修复
        }
    }
    
    // 3. RAG 注入
    List<String> knowIds = params.getKnowIds();
    if (oConvertUtils.isObjectNotEmpty(knowIds)) {
        QueryRouter queryRouter = embeddingHandler.getQueryRouter(knowIds, params.getTopNumber(), params.getSimilarity());
        params.setQueryRouter(queryRouter);   // ★ langchain4j 会自动用 router 检索
    }
    
    // 4. 防御：maxTokens ≤ 0 视为 null
    if (oConvertUtils.isObjectNotEmpty(params.getMaxTokens()) && params.getMaxTokens() <= 0) {
        params.setMaxTokens(null);
    }
    
    // 5. 默认 timeout
    if(oConvertUtils.isObjectEmpty(params.getTimeout())){
        params.setTimeout(AiragConsts.DEFAULT_TIMEOUT);
    }
    
    // 6. ★ 插件/MCP（DeepSeek 推理模型跳过）
    String modelName = airagModel.getModelName();
    if(!LLMConsts.DEEPSEEK_REASONER.equals(modelName)){
        buildPlugins(params);
    }
    
    // 7. ★★ DeepSeek 推理模型补丁 issues/9585/#9607
    boolean isDsThinking = LLMConsts.isDeepSeekThinkingModel(modelName);
    log.info("[AI-CHAT][issues/9585] mergeParams provider={}, modelName={}, isDeepSeekThinkingModel={}, ...",
            airagModel.getProvider(), modelName, isDsThinking, params.getReturnThinking(), params.getSendThinking());
    if (isDsThinking) {
        params.setReturnThinking(true);    // 响应中解析 reasoning_content
        params.setSendThinking(true);     // 下一轮请求带 reasoning_content
        log.info("[AI-CHAT][issues/9585][issues/9607] mergeParams after-fix returnThinking={}, sendThinking={}",
                params.getReturnThinking(), params.getSendThinking());
    }
    
    return params;
}
```

**逐行解读（按 7 个职责区块）**：

| 区块 | 行 | 行为 |
|------|----|------|
| 边界 | 293-299 | 空 `airagModel`（默认模型调用）或空 `params` 的兜底 |
| 凭证 | 301-313 | credential JSON → apiKey/secretKey/httpVersionOne |
| 参数 | 315-341 | 模型默认参数 + 调用方可覆盖 |
| RAG | 344-348 | 关键！把 `EmbeddingHandler.getQueryRouter(...)` 注入 params，langchain4j 在 chat 时按 router 检索 |
| 防御 | 351-353 | maxTokens 异常值兜底 |
| 超时 | 356-358 | 默认 timeout（通常 60s） |
| 插件 | 362-365 | DeepSeek 推理模型 `deepseek-reasoner` 不支持 tool calling，要跳过 |
| ★DeepSeek 补丁 | 368-385 | 推理模型：强制 `returnThinking` 和 `sendThinking`，让 AiMessage 的 thinking ↔ reasoning_content 自动转换 |

---

## 八、`injectThinkingPlaceholderIfNeeded` —— DeepSeek 历史消息占位（issues/9585）

```java
private static List<ChatMessage> injectThinkingPlaceholderIfNeeded(List<ChatMessage> messages, String modelName) {
    if (messages == null || messages.isEmpty()
        || !LLMConsts.isDeepSeekThinkingModel(modelName)) {
        return messages;   // 非推理模型直接返回
    }
    List<ChatMessage> result = new ArrayList<>(messages.size());
    int injected = 0;
    for (ChatMessage msg : messages) {
        if (msg instanceof AiMessage) {
            AiMessage aiMsg = (AiMessage) msg;
            if (oConvertUtils.isEmpty(aiMsg.thinking())) {
                // ★ 临时方案：推理模型要求 assistant 历史消息携带 reasoning_content，
                //   langchain4j 的 sendThinking 仅在 thinking 非空时才注入
                AiMessage rebuilt = AiMessage.builder()
                    .text(aiMsg.text())
                    .thinking("...")            // ★ 占位字符串 "..."
                    .toolExecutionRequests(aiMsg.toolExecutionRequests())
                    .attributes(aiMsg.attributes())
                    .build();
                result.add(rebuilt);
                injected++;
                continue;
            }
        }
        result.add(msg);
    }
    if (injected > 0) {
        log.info("[AI-CHAT][issues/9585] 为 DeepSeek 推理模型[{}]的 {} 条历史 AI 消息注入了占位 thinking",
                modelName, injected);
    }
    return result;
}
```

**修复动机**：DeepSeek v4-flash / v4-pro 等推理模型 API 校验：assistant 历史消息必须有 `reasoning_content`，否则返回 `"The reasoning_content in the thinking mode must be passed back to the API."`

langchain4j 1.x 行为：当 `AiMessage.thinking()` 非空时才会调 `sendThinking=true` 把 reasoning_content 加进请求。但历史持久化层（`MessageHistory`）当前不存 reasoning_content，所以重建出来的 `AiMessage.thinking()` 始终是 null —— 触发 API 报错。

**临时方案**：重建历史 AI 消息，注入占位字符串 `"..."` 让 thinking 非 null，从而带上 reasoning_content 字段。**后续 MessageHistory 升级后会去掉该兜底**。

---

## 九、`imageGenerate`（绘画，文生图）

```java
public List<Map<String, Object>> imageGenerate(AiragModel airagModel, String messages, AIChatParams params) {
    if(airagModel == null || (airagModel.getActivateFlag() != null && airagModel.getActivateFlag() == 0)){
        if (airagModel != null && oConvertUtils.isNotEmpty(airagModel.getId())) {
            log.warn("模型未激活,采用默认文生图模型");
        }
        if(aiChatConfig == null || oConvertUtils.isEmpty(aiChatConfig.getAiModelDraw().getApiKey())){
            throw new JeecgBootBizTipException("当前系统未配置默认图像模型，请前往yml中配置默认模型");
        }
        airagModel = this.getDefaultDrawModel(aiChatConfig.getAiModelDraw());
    }
    params = mergeParams(airagModel, params);
    try {
        return llmHandler.imageGenerate(messages, params);
    } catch (Exception e) {
        throw translateLlmException(e, "调用绘画AI接口失败，详情请查看后台日志。");
    }
}
```

**关键点**：当 `airagModel` 为空或未激活，**强制从 yml `jeecg.ai-chat.ai-model-draw` 取默认文生图模型**。`getDefaultDrawModel` 临时构造一个 `AiragModel` 副本。

`imageEdit` 几乎一样，只是用 `aiChatConfig.getAiModelPicDraw()` 的默认图生图模型。

---

## 十、`translateLlmException` —— 异常 4 级降级

```java
private JeecgBootException translateLlmException(Exception e, String defaultMsg) {
    String exceptionMsg = e.getMessage();
    String errMsg = defaultMsg;

    if (oConvertUtils.isNotEmpty(exceptionMsg)) {
        // 1. 工具调用消息序列不完整
        if (exceptionMsg.contains("messages with role 'tool' must be a response to a preceeding message with 'tool_calls'")) {
            errMsg = "消息序列不完整，可能是因为历史消息数量设置过小导致工具调用上下文丢失。建议增加历史消息数量后重试。";
            log.error("AI模型调用异常: 工具调用消息序列不完整，建议增加历史消息数量。异常详情: {}", exceptionMsg, e);
            return new JeecgBootException(errMsg);
        }
        // 2. 关键字匹配（MODEL_ERROR_MAP）
        errMsg = matchErrorMsg(exceptionMsg, errMsg);
    }

    log.error("AI模型调用异常: {}", errMsg, e);
    return new JeecgBootException(errMsg);
}
```

降级优先级：
1. **超时**：未配关键字；走 `matchErrorMsg` 关键字匹配（具体关键字 map 在 `IAIChatHandler.MODEL_ERROR_MAP`）
2. **工具上下文丢失**：`messages with role 'tool'...` → 友好提示
3. **`MODEL_ERROR_MAP` 关键字匹配**：中文具体提示
4. **兜底**：`defaultMsg` 参数

---

## 十一、`buildUserMessage` + `buildImageContents` + `getFirstImageBase64`

```java
@Override
public UserMessage buildUserMessage(String content, List<String> images) {
    List<Content> contents = new ArrayList<>();
    contents.add(TextContent.from(content));
    if (oConvertUtils.isObjectNotEmpty(images)) {
        List<ImageContent> imageContents = buildImageContents(images);
        contents.addAll(imageContents);
    }
    return UserMessage.from(contents);
}

@Override
public List<ImageContent> buildImageContents(List<String> images) {
    List<ImageContent> imageContents = new ArrayList<>();
    for (String imageUrl : images) {
        Matcher matcher = LLMConsts.WEB_PATTERN.matcher(imageUrl);
        if (matcher.matches()) {
            imageContents.add(ImageContent.from(imageUrl));    // ★ 网络图直接传 URL
        } else {
            // ★ 本地图 → Base64 编码（让大模型能识别）
            String filePath = uploadpath + File.separator + imageUrl;
            SsrfFileTypeFilter.checkPathTraversal(filePath);   // ★ 路径遍历防护
            Path path = Paths.get(filePath);
            byte[] fileContent = Files.readAllBytes(path);
            String base64Data = Base64.getEncoder().encodeToString(fileContent);
            String mimeType = Files.probeContentType(path);
            imageContents.add(ImageContent.from(base64Data, mimeType));
        }
    }
    return imageContents;
}
```

**逐行解读**：
- **网络图片** → 直接传 URL 给大模型，让大模型自己去取
- **本地图片** → 必须读成 Base64（多数大模型 API 不接受 multipart）
- `SsrfFileTypeFilter.checkPathTraversal` —— 即使在图片读取路径也做安全校验

`getFirstImageBase64` 是 `imageEdit` 的辅助方法，更严格：`canonicalFile` 对比 uploadpath 确认不路径遍历。

---

## 十二、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/handler/AIChatHandler.java` | ~700 | LLM 调用适配层（本文档） |
| `common/handler/IAIChatHandler.java` | — | 接口（base-core 中） |
| `common/handler/AIChatParams.java` | — | 参数 POJO（base-core） |
| `common/handler/McpToolProviderWrapper.java` | — | MCP 连接包装 |
| `llm/handler/EmbeddingHandler.java` | 988 | RAG 路由器构造 |
| `llm/handler/JeecgToolsProvider.java` | — | JeecgBizToolsProvider（外部实现） |
| `llm/handler/PluginToolBuilder.java` | 578 | plugin 类型工具构造 |
| `llm/mapper/AiragModelMapper.java` | — | 被 `getByIdIgnoreTenant` 调用 |
| `llm/mapper/AiragMcpMapper.java` | — | MCP 查 |
| `config/AiRagConfigBean.java` | — | yml `jeecg.airag.*` 配置 |
| `config/AiChatConfig.java` | — | yml `jeecg.ai-chat.*` 配置（含默认模型） |
| `common/consts/AiragConsts.java` | — | `DEFAULT_TIMEOUT` 等 |
| `llm/consts/LLMConsts.java` | 222 | `DEEPSEEK_REASONER` / `isDeepSeekThinkingModel` 等 |

---

## 十三、下一章

[13-handler-embedding.md](13-handler-embedding.md) — `EmbeddingHandler` 完整逐方法解读（向量化 + 检索）。
