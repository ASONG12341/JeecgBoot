# 01 · `/airag/chat/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/chat/send`** 为主线，从 HTTP 请求落到 `AiragChatServiceImpl.send` → `doChat` → `AIChatHandler` → `llmHandler` → 大模型，**整条调用链不断裂**。
> 其他端点（`/init`、`/conversations`、`/messages`、`/stop`、`/upload`、`/genAiPoster` 等）在第 6 节单独列出。

---

## 一、路由与控制器方法

| 方法 | 路径 | 来源 |
|------|------|------|
| `POST` | `/airag/chat/send` | `AiragChatController#send` |
| `GET` | `/airag/chat/send`（旧浏览器兼容） | `AiragChatController#sendByGet` |
| `GET` | `/airag/chat/init` | `AiragChatController#initChat` |
| `GET` | `/airag/chat/conversations` | `AiragChatController#getConversations` |
| `GET` | `/airag/chat/getConversationsByType` | `AiragChatController#getConversationsByType` |
| `DELETE` | `/airag/chat/conversation/{id}` | `AiragChatController#deleteConversation` |
| `DELETE` | `/airag/chat/conversation/{id}/{sessionType}` | `AiragChatController#deleteConversationByType` |
| `PUT` | `/airag/chat/conversation/update/title` | `AiragChatController#updateConversationTitle` |
| `GET` | `/airag/chat/messages` | `AiragChatController#getMessages` |
| `GET` | `/airag/chat/messages/clear/{conversationId}[/{sessionType}]` | `clearMessage` / `clearMessageByType` |
| `GET` | `/airag/chat/receive/{requestId}` | `AiragChatController#receiveByRequestId` |
| `GET` | `/airag/chat/stop/{requestId}` | `AiragChatController#stop` |
| `POST` | `/airag/chat/upload` | `AiragChatController#upload` |
| `POST` | `/airag/chat/genAiPoster[Async]` | `genAiPoster` / `genAiPosterAsync` |
| `GET` | `/airag/chat/getAiPosterResult/{taskId}` | `getAiPosterResult` |
| `POST` | `/airag/chat/genAiWriter` | `genAiWriter` |

> 全部 `@IgnoreAuth`，不需 Shiro 鉴权。源头文件：`AiragChatController.java`。

---

## 二、入口：HTTP 请求 → 控制器方法

```http
POST /airag/chat/send HTTP/1.1
Content-Type: application/json
X-Access-Token: eyJhbGciOi...

{
  "content": "介绍一下 JeecgBoot 的 AI 模块",
  "conversationId": "abc123",
  "topicId": "topic-001",
  "appId": "app-crm",
  "images": [],
  "files": [],
  "flowInputs": { "language": "Java" },
  "enableSearch": false,
  "enableThink": true,
  "sessionType": "portal",
  "enableDraw": false,
  "drawModelId": null,
  "imageSize": "1024x1024",
  "imageUrl": null,
  "izSaveSession": true
}
```

字段定义来源：`ChatSendParams.java#21-99`（`@NoArgsConstructor`、`@Data`，继承无父类）。

### 控制器层

```java
// AiragChatController.java:55-59
@IgnoreAuth
@PostMapping(value = "/send")
public SseEmitter send(@RequestBody ChatSendParams chatSendParams) {
    return chatService.send(chatSendParams);
}
```

**逐行解读**：
- L55 `@IgnoreAuth` — 跳过 Shiro 鉴权（见 [shiro/IgnoreAuth.java]）
- L56 `@PostMapping("/send")` — Spring MVC 路由映射
- L57 入参 `@RequestBody` 直接绑定到 `ChatSendParams`，由 Jackson 反序列化（Jackson 3，Spring Boot 4 默认）
- L58 返回 `SseEmitter` — Spring 提供的 Server-Sent Events 异步响应类型
- L59 透传给 `chatService.send(...)` —— 注入的是 `IAiragChatService`，实际类型是 `AiragChatServiceImpl`

**属性注入**（同文件 L35-45）：
```java
@Autowired
IAiragChatService chatService;
@Value(value = "${jeecg.path.upload}")
private String uploadpath;
@Value(value="${jeecg.uploadType}")
private String uploadType;
```

`uploadpath`、`uploadType` 仅 `upload()` 用到，决定上传文件落地路径。

---

## 三、Service 层：`AiragChatServiceImpl.send`

文件 `AiragChatServiceImpl.java`，核心方法 L140-176。

### 3.1 入口校验与会话准备

```java
// AiragChatServiceImpl.java:140-176
@Override
public SseEmitter send(ChatSendParams chatSendParams) {
    AssertUtils.assertNotEmpty("参数异常", chatSendParams);
    String userMessage = chatSendParams.getContent();
    AssertUtils.assertNotEmpty("至少发送一条消息", userMessage);
    
    // 获取会话信息
    String conversationId = chatSendParams.getConversationId();
    String topicId = oConvertUtils.getString(chatSendParams.getTopicId(), UUIDGenerator.generate());
    // 获取app信息
    AiragApp app = null;
    if (oConvertUtils.isNotEmpty(chatSendParams.getAppId())) {
        app = airagAppMapper.getByIdIgnoreTenant(chatSendParams.getAppId());
    }
    // 创建/获取会话
    ChatConversation chatConversation = getOrCreateChatConversation(app, conversationId, chatSendParams.getSessionType());
    // 设置标题
    if (oConvertUtils.isEmpty(chatConversation.getTitle())) {
        int maxLength = AiAppConsts.CONVERSATION_MAX_TITLE_LENGTH; // 10
        chatConversation.setTitle(userMessage.length() > maxLength ? userMessage.substring(0, maxLength) : userMessage);
    }
    // 保存工作流入参
    if (oConvertUtils.isObjectNotEmpty(chatSendParams.getFlowInputs())) {
        chatConversation.setFlowInputs(chatSendParams.getFlowInputs());
    }
    // 是否保存会话
    if(null != chatSendParams.getIzSaveSession()){
        chatConversation.setIzSaveSession(chatSendParams.getIzSaveSession());
    }
    // 保存变量
    saveVariables(app);
    // 发送消息
    return doChat(chatConversation, topicId, chatSendParams);
}
```

**逐行解读**：

| 行 | 行为 | 含义 |
|---|------|------|
| 142-144 | `AssertUtils.assertNotEmpty` | 防 null/空字符串，统一抛 `JeecgBootException` |
| 147 | `topicId` 默认值 | 用 `UUIDGenerator.generate()` 生成——每次新会话一个 topicId |
| 150-153 | `airagAppMapper.getByIdIgnoreTenant` | 忽略多租户的 app 查询（在 airag 模块默认不用 saas 隔离） |
| 155 | `getOrCreateChatConversation(...)` | 见 §3.2 |
| 158-161 | 标题自动生成 | 把用户首条消息前 10 个字符作为会话标题 |
| 164-167 | 工作流入参 | 写到 Redis 中的会话对象（issue/8545） |
| 169-171 | 是否持久化 | 由前端决定是否保留会话 |
| 173 | `saveVariables(app)` | 初始化应用级变量（`AiragVariableService`） |
| 175 | 进入 `doChat` | 真正的对话逻辑 |

### 3.2 会话管理 `getOrCreateChatConversation`（文件 L714-732）

```java
private ChatConversation getOrCreateChatConversation(AiragApp app, String conversationId, String sessionType) {
    if (oConvertUtils.isObjectEmpty(app)) {
        app = new AiragApp();
        app.setId(AiAppConsts.DEFAULT_APP_ID);    // "default"
    }
    String key = getConversationCacheKey(conversationId, null, sessionType);
    ChatConversation chatConversation = null;
    if (oConvertUtils.isNotEmpty(key)) {
        chatConversation = (ChatConversation) redisTemplate.boundValueOps(key).get();
    }
    if (null == chatConversation) {
        chatConversation = createConversation(conversationId);
    }
    chatConversation.setApp(app);
    return chatConversation;
}
```

- **Redis Key 模式**：`airag:chat:conversation:{appId}:{topicId}:{username}:{sessionType}`
- `redisTemplate.boundValueOps(key).get()` —— **同步读 Redis**
- 若不存在 → `createConversation(conversationId)` 生成新对象（id 用 `UUIDGenerator.generate()`）
- 注：应用门户场景下（QQYUN-14127），`sessionType=portal` 时 Redis Key 加上 `portal` 后缀以隔离

### 3.3 变量初始化 `saveVariables(app)`

```java
private void saveVariables(AiragApp app) {
    if (app == null || app.getVariables() == null) {
        return;
    }
    String username = SecurityUtils.getSubject().getPrincipal() != null 
        ? (String) SecurityUtils.getSubject().getPrincipal() : "anonymous";
    List<AppVariableVo> variableList = JSONArray.parseArray(app.getVariables(), AppVariableVo.class);
    if (variableList != null) {
        for (AppVariableVo var : variableList) {
            airagVariableService.initVariable(username, app.getId(), var.getName(), var.getDefaultValue());
        }
    }
}
```

`AiragVariableServiceImpl.initVariable` 内部：
- Redis Key: `airag:app:var:{appId}:{username}`
- 操作：`redisTemplate.opsForHash().putIfAbsent(key, name, defaultValue)` —— 仅不存在时设置

### 3.4 主流程 `doChat`（文件 L971-...）

```java
@NotNull
private SseEmitter doChat(ChatConversation chatConversation, String topicId, ChatSendParams sendParams) {
    // 从历史消息中组装本次的消息列表（ChatMessage 列表，供 langchain4j 用）
    List<ChatMessage> messages = collateMessage(chatConversation, topicId);
    
    AiragApp aiApp = chatConversation.getApp();
    // 每次会话都生成一个新的requestId，用来缓存emitter
    String requestId = UUIDGenerator.generate();
    SseEmitter emitter = createSSE(requestId);
    // 缓存emitter（用于断线重连）
    AiragLocalCache.put(AiragConsts.CACHE_TYPE_SSE, requestId, emitter);
    // 缓存开始发送时间
    AiragLocalCache.put(AiragConsts.CACHE_TYPE_SSE_SEND_TIME, requestId, System.currentTimeMillis());
    // 初始化历史消息缓存
    AiragLocalCache.put(AiragConsts.CACHE_TYPE_SSE_HISTORY_MSG, requestId, new CopyOnWriteArrayList<>());
    
    try {
        // 组装用户消息
        String content = sendParams.getContent();
        if(!CollectionUtils.isEmpty(sendParams.getFiles())){
            content = buildContentWithFiles(content, sendParams.getFiles());
        }
        UserMessage userMessage = aiChatHandler.buildUserMessage(content, sendParams.getImages());
        // 追加消息
        appendMessage(messages, userMessage, chatConversation, topicId, sendParams.getFiles(), sendParams.getContent());
        // 绘画AI逻辑
        if (Boolean.TRUE.equals(sendParams.getEnableDraw())) {
            // 跳到 genImageChat 分支
            return genImageChat(chatConversation, topicId, emitter, requestId, sendParams);
        }
        // 工作流 OR 简单聊天
        if (aiApp.getFlowId() != null && !aiApp.getFlowId().isEmpty()) {
            return sendWithFlow(chatConversation, topicId, emitter, requestId, sendParams);
        }
        // 普通应用聊天
        return sendWithAppChat(chatConversation, topicId, emitter, requestId, sendParams);
    } catch (Exception e) {
        // 出错时关闭 SSE 并发错误事件
        closeSSE(emitter, new EventData(requestId, null, EventData.EVENT_MESSAGE_END));
        throw new JeecgBootException("发送消息失败:" + e.getMessage());
    }
}
```

**关键设计点**：

1. **`AiragLocalCache`**（base-core）—— 基于 `ConcurrentHashMap` 的进程内缓存。`CACHE_TYPE_SSE` 存 `requestId → SseEmitter`，`CACHE_TYPE_SSE_HISTORY_MSG` 存 `requestId → CopyOnWriteArrayList<MessageHistory>`。这两个缓存用于：
   - 客户端断开 → 通过 `GET /airag/chat/receive/{requestId}` 重新订阅 SSE 流（`receiveByRequestId` 反查 `AiragLocalCache` 拿到原 emitter）
   - 调用方发 `GET /airag/chat/stop/{requestId}` → 查缓存关闭 emitter

2. **`collateMessage(chatConversation, topicId)`**（L803-868）—— 把 Redis 中存的 `MessageHistory` 列表反向重建为 langchain4j 的 `ChatMessage` 列表（`UserMessage` / `AiMessage` / `ToolExecutionResultMessage`），并按 `topicId` 过滤（同一个 conversation 下可有多个 topic，分支对话）。`#9539` 修复了 `ToolExecutionResultMessage` 空结果传给通义千问被拒的问题。

3. **`sendWithFlow` vs `sendWithAppChat` vs `sendWithDefault`**（三个分支）：
   - `sendWithFlow(aiApp.flowId 非空)` —— 走自定义工作流编排（`AiragFlowService.run`）
   - `sendWithAppChat` —— 标准应用聊天（带 prompt / 知识库 / 工具）
   - `sendWithDefault` —— 无 app 或 app 为空时使用 yml 默认模型

---

## 四、Handler 层：`AIChatHandler`

`AIChatHandler` 是包装层，接收 `AiragModel` / `IAIChatHandler` 接口的方法签名，转发给 base-core 的 `LLMHandler`。

### 4.1 完整调用链 send → handler → 大模型

```
AiragChatServiceImpl.sendWithAppChat
    └─ aiChatHandler.chat(modelId, messages, params)
         └─ chat(modelId, messages, params)
              ├─ airagModelMapper.getByIdIgnoreTenant(modelId)
              ├─ if 未激活 → chatByDefaultModel
              └─ chat(airagModel, messages, params)
                   ├─ mergeParams(airagModel, params)
                   │    ├─ 注入凭证（apiKey, secretKey, httpVersionOne）
                   │    ├─ 注入模型参数（temperature/topP/maxTokens/...）
                   │    ├─ if (knowIds 非空) → embeddingHandler.getQueryRouter(knowIds, topNumber, similarity)
                   │    ├─ if (pluginIds 非空) → buildPlugins(params)
                   │    ├─ if (isDeepSeekThinkingModel(modelName)) → 强制 returnThinking=true, sendThinking=true
                   │    └─ default timeout = AiragConsts.DEFAULT_TIMEOUT
                   ├─ injectThinkingPlaceholderIfNeeded(messages, modelName)   ── DeepSeek 推理模型历史消息补占位
                   └─ llmHandler.chat(messages, params)  ←── base-core 实际调大模型
                         └─ TokenStream 增量输出
```

### 4.2 `mergeParams` 详解（最核心的一段）

文件 `AIChatHandler.java`，方法 L292-389。

```java
private AIChatParams mergeParams(AiragModel airagModel, AIChatParams params) {
    if (null == airagModel) {
        return params;
    }
    if (params == null) {
        params = new AIChatParams();
    }

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
    
    // 2. 模型参数注入（取 params 优先，找不到用 modelParams 中的）
    if (oConvertUtils.isObjectNotEmpty(airagModel.getModelParams())) {
        JSONObject modelParams = JSONObject.parseObject(airagModel.getModelParams());
        if (oConvertUtils.isObjectEmpty(params.getTemperature())) {
            params.setTemperature(modelParams.getDouble("temperature"));
        }
        // ... 同样方式注入 topP, presencePenalty, frequencyPenalty, maxTokens, timeout, enableSearch, extraParams
    }
    
    // 3. RAG 注入
    List<String> knowIds = params.getKnowIds();
    if (oConvertUtils.isObjectNotEmpty(knowIds)) {
        QueryRouter queryRouter = embeddingHandler.getQueryRouter(knowIds, params.getTopNumber(), params.getSimilarity());
        params.setQueryRouter(queryRouter);
    }
    
    // 4. 纠正 maxTokens 异常值
    if (oConvertUtils.isObjectNotNotEmpty(params.getMaxTokens()) && params.getMaxTokens() <= 0) {
        params.setMaxTokens(null);
    }
    
    // 5. 默认 timeout
    if(oConvertUtils.isObjectEmpty(params.getTimeout())){
        params.setTimeout(AiragConsts.DEFAULT_TIMEOUT);
    }
    
    // 6. 插件/MCP（DeepSeek 推理模型跳过插件：issues/9585）
    String modelName = airagModel.getModelName();
    if(!LLMConsts.DEEPSEEK_REASONER.equals(modelName)){
        buildPlugins(params);
    }
    
    // 7. DeepSeek 推理模型补丁：issues/9585 / issues/9607
    boolean isDsThinking = LLMConsts.isDeepSeekThinkingModel(modelName);
    if (isDsThinking) {
        params.setReturnThinking(true);   // 响应中解析 reasoning_content
        params.setSendThinking(true);    // 下一轮请求带 reasoning_content
    }
    
    return params;
}
```

**逐行解读**：

| 行 | 行为 | 为什么这么写 |
|---|------|---|
| 293-295 | null `airagModel` 直接返回 | 与 `completionsByDefaultModel` 配合使用，传空 AiragModel 走默认模型 |
| 296-299 | null `params` new 一个 | 调用方可能不传 |
| 301-313 | 凭证注入 | `credential` 是 JSON 字符串，可存 apiKey、secretKey、httpVersionOne（百度千帆等需要 http/1） |
| 315-341 | 模型参数按 `params` 优先填充 | 这是"运行时参数覆盖默认参数"的实现 |
| 344-348 | RAG 注入 | 这一步最关键，把 langchain4j 的 `QueryRouter` 装到 params，让 llmHandler 在 chat 时自动检索 |
| 351-353 | maxTokens 防御 | 异常值（≤0）置 null，避免某些大模型 API 报错 |
| 356-358 | 默认 timeout | `AiragConsts.DEFAULT_TIMEOUT` 在 base-core 常量 |
| 362-365 | DeepSeek 推理模型跳过插件 | deepseek-reasoner 不支持 tool calling；新版 v4-flash 已支持 #9585 修复后逻辑会更复杂 |
| 368-385 | DeepSeek 推理模型补丁 | `#9585` + `#9607`：开启 `returnThinking + sendThinking` 让 langchain4j 把 `AiMessage.thinking` 序列化回 reasoning_content |

### 4.3 `buildPlugins` 详解（L399-461）

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
            
            String category = airagMcp.getCategory();  // "mcp" 或 "plugin"
            if (oConvertUtils.isEmpty(category)) category = "mcp";

            if ("mcp".equalsIgnoreCase(category)) {
                McpToolProviderWrapper wrapper = buildMcpToolProviderWrapper(
                        airagMcp.getName(),
                        airagMcp.getType(),       // sse / stdio / http
                        airagMcp.getEndpoint(),
                        airagMcp.getHeaders(),
                        aiRagConfigBean.getAllowSensitiveNodes()    // 用于 yml 白名单
                );
                if (wrapper != null) {
                    mcpToolProviders.add(wrapper.getMcpToolProvider());
                    mcpToolProviderWrappers.add(wrapper);
                }
            } else if ("plugin".equalsIgnoreCase(category)) {
                Map<ToolSpecification, ToolExecutor> tools = PluginToolBuilder.buildTools(airagMcp, params.getCurrentHttpRequest());
                if (tools != null && !tools.isEmpty()) {
                    pluginTools.putAll(tools);
                }
            }
        }
        if (!mcpToolProviders.isEmpty()) params.setMcpToolProviders(mcpToolProviders);
        if (!mcpToolProviderWrappers.isEmpty()) params.setMcpToolProviderWrappers(mcpToolProviderWrappers);
        if (!pluginTools.isEmpty()) {
            if (params.getTools() == null) params.setTools(new HashMap<>());
            params.getTools().putAll(pluginTools);
        }
    }
}
```

两种插件形态：
- **`mcp`** —— 调用外部 MCP 协议服务（JSON-RPC over SSE/HTTP/Stdio）。由 `buildMcpToolProviderWrapper` 构造 langchain4j 的 `McpToolProvider`，**包装器保留连接引用以备关闭**（issues/9234 修复）
- **`plugin`** —— 调用 JeecgBoot 系统的 HTTP API。通过 `PluginToolBuilder.buildTools` 把 `airag_mcp.tools` 字段（JSON 配置）转成 `ToolSpecification + ToolExecutor`，执行时用 `RestUtil.request` 调 HTTP

### 4.4 `chat(modelId, messages, params)` 完整方法（L184-197）

```java
@Override
public TokenStream chat(String modelId, List<ChatMessage> messages, AIChatParams params) {
    AssertUtils.assertNotEmpty("至少发送一条消息", messages);
    AssertUtils.assertNotEmpty("请选择模型", modelId);

    AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
    // 未激活的模型走默认模型（issues/14781）
    if(null == airagModel || airagModel.getActivateFlag() == 0){
        log.warn("模型未激活,采用默认模型");
        return chatByDefaultModel(messages,params);
    }
    return chat(airagModel, messages, params);
}
```

落到私有 chat 方法（L209-215）后，最终调用：
```java
return llmHandler.chat(messages, params);
```

`llmHandler` 是 base-core 的 `org.jeecg.ai.handler.LLMHandler`，是 langchain4j 的薄壳，处理最后的 HTTP 调用到大模型。

---

## 五、流式响应：SSE 事件推送

`SseEmitter` 由 Spring 处理。后端通过 `emitter.send(SseEmitter.event().data(...))` 推送事件，前端 `EventSource` 接收。

事件类型（`EventData`）：

| 事件 | 说明 |
|------|------|
| `EVENT_MESSAGE` | 流式文本片段 |
| `EVENT_MESSAGE_TOOL` | 工具调用结果（前端用来展示"AI 正在调用工具"） |
| `EVENT_MESSAGE_END` | 流结束 |
| `EVENT_ERROR` | 错误 |

`stop(requestId)` 流程（L208-225，`AiragChatServiceImpl`）：

```java
public Result<?> stop(String requestId) {
    // 1. 把流程上下文标记为 stopped
    JeecgFlowContext flowContext = AiragLocalCache.get(AiragConsts.CACHE_TYPE_FLOW_CONTEXT, requestId);
    if (flowContext != null) {
        flowContext.setStopped(true);
        AiragLocalCache.remove(AiragConsts.CACHE_TYPE_FLOW_CONTEXT, requestId);
    }
    // 2. 关闭 SSE emitter
    SseEmitter emitter = AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE, requestId);
    if (emitter != null) {
        closeSSE(emitter, new EventData(requestId, null, EventData.EVENT_MESSAGE_END));
        return Result.ok("会话已成功终止");
    } else {
        return Result.error("未找到对应的会话");
    }
}
```

---

## 六、其他 chat/* 端点逐个解读

### 6.1 `POST /airag/chat/send`（旧浏览器兼容，GET）

```java
@GetMapping(value = "/send")
public SseEmitter sendByGet(@RequestParam("content") String content,
                            @RequestParam(value = "conversationId", required = false) String conversationId,
                            @RequestParam(value = "topicId", required = false) String topicId,
                            @RequestParam(value = "appId", required = false) String appId) {
    ChatSendParams chatSendParams = new ChatSendParams(content, conversationId, topicId, appId);
    return chatService.send(chatSendParams);
}
```

把 query string 转为 `ChatSendParams`，复用同一个 Service。

### 6.2 `GET /airag/chat/init?appId=xxx` — 分享场景

```java
@IgnoreAuth
@GetMapping(value = "/init")
public Result<?> initChat(@RequestParam(name = "id", required = true) String id) {
    return chatService.initChat(id);
}
```

`initChat(appId)` 不带 sessionType，用于 [QQYUN-12113] 分享后免登录访问。会忽略 tenant 限制，从公开的 `AiragApp` 加载配置。

### 6.3 `GET /airag/chat/conversations?appId=xxx`

```java
@IgnoreAuth
@GetMapping(value = "/conversations")
public Result<?> getConversations(@RequestParam(value = "appId", required = false) String appId) {
    return chatService.getConversations(appId);
}
```

内部 `getConversations(appId)` 用 `redis.scan(airag:chat:*:username:)` 找出当前用户在这个 app 下的所有会话，按 `createTime desc` 排序。

### 6.4 `DELETE /airag/chat/conversation/{id}` 和 `DELETE /airag/chat/conversation/{id}/{sessionType}`

```java
@IgnoreAuth
@DeleteMapping(value = "/conversation/{id}")
public Result<?> deleteConversation(@PathVariable("id") String id) {
    return chatService.deleteConversation(id, "");
}

@IgnoreAuth
@DeleteMapping(value = "/conversation/{id}/{sessionType}")
public Result<?> deleteConversationByType(@PathVariable("id") String id,
                                         @PathVariable("sessionType") String sessionType) {
    return chatService.deleteConversation(id, sessionType);
}
```

内部 `deleteConversation(id, sessionType)` 根据 Redis Key 直接删 Redis 中 `airag:chat:conversation:{appId}:{topicId}:{username}[:{sessionType}]`，从 `AiragLocalCache` 删除 SSE emitter（如果存在）。

### 6.5 `PUT /airag/chat/conversation/update/title`

```java
@IgnoreAuth
@PutMapping(value = "/conversation/update/title")
public Result<?> updateConversationTitle(@RequestBody ChatConversation updateTitleParams) {
    return chatService.updateConversationTitle(updateTitleParams);
}
```

只更新 Redis 中 `ChatConversation.title` 字段。

### 6.6 `GET /airag/chat/messages?conversationId=xxx&sessionType=xxx`

```java
@IgnoreAuth
@GetMapping(value = "/messages")
public Result<?> getMessages(@RequestParam(value = "conversationId", required = true) String conversationId,
                             @RequestParam(value = "sessionType", required = false) String sessionType) {
    return chatService.getMessages(conversationId, sessionType);
}
```

内部 `getMessages(conversationId, sessionType)`：
1. 从 Redis 拿 `ChatConversation`
2. 调用 `mergeToolMessages(messages, showToolProcess)` 合并工具调用相关消息（把同一轮 AI 消息和后续 tool 执行折叠为一条消息，前端展示更整齐）
3. 返回 `{messages, flowInputs, appData}`（QQYUN-14127 当 sessionType 非空时附带 app 全量数据）

### 6.7 `GET /airag/chat/messages/clear/{conversationId}[/{sessionType}]`

```java
@IgnoreAuth
@GetMapping(value = "/messages/clear/{conversationId}")
public Result<?> clearMessage(@PathVariable(value = "conversationId") String conversationId) {
    return chatService.clearMessage(conversationId, "");
}

@IgnoreAuth
@GetMapping(value = "messages/clear/{conversationId}/{sessionType}")
public Result<?> clearMessageByType(...) {...}
```

把 Redis 里 `ChatConversation.messages` 置空（保留会话对象，但清空聊天记录）。

### 6.8 `GET /airag/chat/receive/{requestId}` — 断线重连

```java
@IgnoreAuth
@GetMapping(value = "/receive/{requestId}")
public SseEmitter receiveByRequestId(@PathVariable(name = "requestId", required = true) String requestId) {
    return chatService.receiveByRequestId(requestId);
}
```

内部 `receiveByRequestId(requestId)` 从 `AiragLocalCache.CACHE_TYPE_SSE` 取出原 emitter，**重新订阅**。用于浏览器 SSE 自动重连场景（连接断开后客户端用上一帧拿到的 requestId 重新订阅）。

### 6.9 `GET /airag/chat/stop/{requestId}` — 主动停止

见 §5 末尾 stop 流程。

### 6.10 `POST /airag/chat/upload` — 上传文件

```java
@IgnoreAuth
@PostMapping(value = "/upload")
public Result<?> upload(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String bizPath = "airag";
    MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
    MultipartFile file = multipartRequest.getFile("file");
    String savePath;
    if (CommonConstant.UPLOAD_TYPE_LOCAL.equals(uploadType)) {       // local
        savePath = CommonUtils.uploadLocal(file, bizPath, uploadpath);
    } else {                                                          // minio / alioss
        savePath = CommonUtils.upload(file, bizPath, uploadType);
    }
    Result<?> result = new Result<>();
    result.setMessage(savePath);
    result.setSuccess(true);
    return result;
}
```

走系统通用上传（`jeecg.uploadType` 配置：`local` / `minio` / `alioss`）。返回的是文件存储相对路径。

### 6.11 `POST /airag/chat/genAiPoster` 与 `POST /airag/chat/genAiPosterAsync`

```java
@PostMapping("/genAiPoster")
public Result<String> genAiPoster(@RequestBody AiDrawGenerateVo aiDrawGenerateVo){
    String imageUrl = chatService.genAiPoster(aiDrawGenerateVo);
    return Result.OK(imageUrl);
}

@PostMapping("/genAiPosterAsync")
public Result<String> genAiPosterAsync(@RequestBody AiDrawGenerateVo aiDrawGenerateVo) {
    String taskId = chatService.genAiPosterAsync(aiDrawGenerateVo);
    return Result.OK(taskId);
}

@GetMapping("/getAiPosterResult/{taskId}")
public Result<?> getAiPosterResult(@PathVariable String taskId) {
    return chatService.getAiPosterResult(taskId);
}
```

**接口演进**：
- 老版同步：阻塞返回图片 URL，前端要等
- 新版异步（QQYUN-14568）：立即返回 `taskId`，前端轮询 `/getAiPosterResult/{taskId}`。后端 Redis Key `airag:poster:task:{taskId}` 存任务结果，TTL 1 小时（`AiAppConsts.POSTER_TASK_TTL`）

### 6.12 `POST /airag/chat/genAiWriter` — AI 写作

```java
@PostMapping("/genAiWriter")
public SseEmitter genAiWriter(@RequestBody AiWriteGenerateVo aiWriteGenerateVo){
    return chatService.genAiWriter(aiWriteGenerateVo);
}
```

内部调用 `genAiWriter` 跑预定义的写作流程 `ARTICLE_WRITER_FLOW_ID=2011769909807579138`（`AiAppConsts`），流式返回结果。

---

## 七、关键流程图（全链路总结）

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. 前端 chat.vue 的 useChat 发送 POST                                  │
│    Body: ChatSendParams                                                  │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. AiragChatController.send                                             │
│    @IgnoreAuth @PostMapping /send                                       │
│    return chatService.send(chatSendParams);                             │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. AirragChatServiceImpl.send                                            │
│    ├─ 校验 / 取会话（Redis）/ 标题/变量                                  │
│    ├─ saveVariables(app)                                                 │
│    └─ return doChat(chatConversation, topicId, chatSendParams)            │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. AirragChatServiceImpl.doChat                                          │
│    ├─ collateMessage(chatConversation, topicId)                          │
│    │     └─ 从 Redis 读 ChatConversation.messages                         │
│    │           反向重建为 List<ChatMessage>                              │
│    ├─ createSSE(requestId) → emitter                                     │
│    ├─ AiragLocalCache.put(CACHE_TYPE_SSE, requestId, emitter)            │
│    ├─ aiChatHandler.buildUserMessage(content, images)                    │
│    ├─ appendMessage(...)  ← 写入本次用户消息到历史                        │
│    ├─ if (enableDraw) → genImageChat                                     │
│    ├─ else if (flowId != null) → sendWithFlow                            │
│    │       └─ AiragFlowService.run(flow)                                 │
│    │              ├─ 节点循环: LLM/工具/变量/...                          │
│    │              └─ 每节点通过 aiChatHandler.chat(...) 调用大模型         │
│    └─ else → sendWithAppChat → sendWithDefault                           │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 5. AIChatHandler.mergeParams + chat                                     │
│    ├─ 注入凭证/参数                                                      │
│    ├─ if (knowIds) → EmbeddingHandler.getQueryRouter(knowIds)             │
│    │      └─ 每个 knowId → EmbeddingStoreContentRetriever                 │
│    │             + filter (memory 库 + username 隔离)                     │
│    ├─ if (pluginIds) → buildPlugins(params)                              │
│    │      ├─ "mcp" → buildMcpToolProviderWrapper                         │
│    │      │        ├─ HttpMcpTransport / StreamableHttpMcpTransport       │
│    │      │        └─ StdioMcpTransport（yml 白名单控制）                │
│    │      └─ "plugin" → PluginToolBuilder.buildTools                     │
│    │             └─ buildToolSpecification + buildToolExecutor            │
│    │                    └─ RestUtil.request(...)                          │
│    ├─ DeepSeek 推理模型补丁                                               │
│    └─ llmHandler.chat(messages, params) → langchain4j                     │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 6. 大模型 + RAG + 工具调用                                                │
│    ├─ langchain4j Router 根据 queryRouter 检索向量库                     │
│    ├─ 自动调用 ToolExecutor                                              │
│    └─ 返回 TokenStream                                                   │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 7. 流式回写到 SSE（AirragChatServiceImpl.createSSE）                       │
│    ├─ onPartialResponse(s)  → emitter.send(EVENT_MESSAGE)                │
│    ├─ onToolExecuted(s)    → emitter.send(EVENT_MESSAGE_TOOL)            │
│    └─ onComplete()          → emitter.send(EVENT_MESSAGE_END) + close()   │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 8. saveChatConversation(chatConversation, ...)                           │
│    └─ Redis BoundValueOperations.set(...) 写回                          │
│         Key: airag:chat:conversation:{appId}:{topicId}:{username}       │
└────────────────────────┬───────────────────────────────────────────────┘
                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 9. 浏览器 EventSource 收到 SSE 流，逐片渲染 UI                            │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 八、对应源码文件列表

完整覆盖 `/airag/chat/*` 涉及的源码：

| 文件 | 行数 | 作用 |
|------|------|------|
| `app/controller/AiragChatController.java` | 307 | 全部 18 个 `/airag/chat/*` 端点 |
| `app/controller/AiragAppController.java` | 259 | 调试应用 `/airag/app/debug`（与 chat 流程密切相关） |
| `app/service/IAiragChatService.java` | 154 | 接口定义 |
| `app/service/impl/AiragChatServiceImpl.java` | ~2260 | 主服务实现（最大文件） |
| `app/service/impl/AiragAppServiceImpl.java` | ~700 | 调试 / 提示词生成 |
| `app/service/impl/AiragVariableServiceImpl.java` | 252 | 变量 + Redis Hash |
| `app/vo/ChatSendParams.java` | 102 | 发送参数 |
| `app/vo/AppDebugParams.java` | 20 | 调试参数 |
| `app/vo/ChatConversation.java` | — | 会话对象（Redis 序列化） |
| `app/vo/AiDrawGenerateVo.java` | — | 海报生成入参 |
| `app/vo/AiWriteGenerateVo.java` | — | AI 写作入参 |
| `app/consts/AiAppConsts.java` | 91 | 常量（CONVERSATION_MAX_TITLE_LENGTH、POSTER_TASK_PREFIX、ARTICLE_WRITER_FLOW_ID） |
| `app/consts/Prompts.java` | — | 系统提示词（GENERATE_LLM_PROMPT 等） |
| `llm/handler/AIChatHandler.java` | ~700 | LLM 调用编排 |
| `llm/handler/EmbeddingHandler.java` | ~1000 | 向量化与 RAG 查询 |
| `llm/handler/PluginToolBuilder.java` | ~580 | 插件 HTTP 调用构造 |
| `common/handler/AIChatParams.java` | — | llmHandler 参数 POJO（base-core 中） |

---

## 九、下一章

[02-controller-app.md](02-controller-app.md) — `/airag/app/*` 控制器详解，包括调试流程 / 提示词生成 / 海报 / 写作等子接口。
