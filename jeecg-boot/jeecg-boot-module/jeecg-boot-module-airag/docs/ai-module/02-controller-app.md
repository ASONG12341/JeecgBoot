# 02 · `/airag/app/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/app/debug`** 为主线，从 HTTP 请求落到 `AiragAppServiceImpl.generatePrompt` → `AIChatHandler` → 大模型，**整条调用链不断裂**。
> 其他端点（CRUD、写作版本、提示词生成）在末尾第 7 节单独列出。

---

## 一、路由与控制器方法

| 方法 | 路径 | 鉴权 | 来源 |
|------|------|------|------|
| `GET` | `/airag/app/list` | 无 | `AiragAppController#queryPageList` |
| `GET` | `/airag/app/listDict` | 无 | `listDict` |
| `PUT,POST` | `/airag/app/edit` | `airag:app:edit` | `edit` |
| `POST` | `/airag/app/release` | 无 | `release` |
| `DELETE` | `/airag/app/delete` | `airag:app:delete` | `delete` |
| `GET` | `/airag/app/queryById` | `@IgnoreAuth` | `queryById` |
| `POST` | `/airag/app/debug` | 无 | `debugApp` |
| `GET` | `/airag/app/prompt/generate` | 无 | `generatePrompt(blocking=true)` |
| `POST` | `/airag/app/prompt/generate` | 无 | `generatePromptSse(blocking=false)` |
| `POST` | `/airag/app/prompt/generateMemoryByAppId` | 无 | `generatePromptByAppIdSse` |
| `POST` | `/airag/app/save/article/write` | 无 | `saveArticleWrite` |
| `DELETE` | `/airag/app/delete/article/write` | 无 | `deleteArticleWrite` |
| `GET` | `/airag/app/list/article/write` | 无 | `listArticleWrite` |

文件：`AiragAppController.java`（259 行）。基类 `JeecgController<AiragApp, IAiragAppService>`，提供 `exportXls` / `importExcel` 标准导出导入能力。

---

## 二、`POST /airag/app/debug` — 应用调试主入口

### 2.1 入口方法（AiragAppController.java:188-191）

```java
@PostMapping(value = "/debug")
public SseEmitter debugApp(@RequestBody AppDebugParams appDebugParams) {
    return airagChatService.debugApp(appDebugParams);
}
```

**逐行解读**：
- `@RequestBody` 反序列化 `AppDebugParams`，继承自 `ChatSendParams`，多一个 `app` 字段（`AppDebugParams.java`）
- 直接透传给 `IAiragChatService.debugApp`
- 与正式聊天的区别：`debugApp` 用 `app.setId("__DEBUG_APP")` 作为虚拟 ID，避免污染真实会话列表

### 2.2 Service 入口（`AiragChatServiceImpl.java:178-204`）

```java
@Override
public SseEmitter debugApp(AppDebugParams appDebugParams) {
    AssertUtils.assertNotEmpty("参数异常", appDebugParams);
    String userMessage = appDebugParams.getContent();
    AssertUtils.assertNotEmpty("至少发送一条消息", userMessage);
    AssertUtils.assertNotEmpty("应用信息不能为空", appDebugParams.getApp());
    
    String topicId = oConvertUtils.getString(appDebugParams.getTopicId(), UUIDGenerator.generate());
    AiragApp app = appDebugParams.getApp();
    app.setId("__DEBUG_APP");   // 调试模式：固定虚拟 ID
    
    ChatConversation chatConversation = getOrCreateChatConversation(app, topicId, "");
    if (oConvertUtils.isObjectNotEmpty(appDebugParams.getFlowInputs())) {
        chatConversation.setFlowInputs(appDebugParams.getFlowInputs());
    }
    SseEmitter emitter = doChat(chatConversation, topicId, appDebugParams);
    saveChatConversation(chatConversation, true, null, "");   // ★ 立即持久化
    return emitter;
}
```

**逐行解读**：
- `app.setId("__DEBUG_APP")` — 调试模式下使用 `"__DEBUG_APP"` 作为虚拟 ID，让 Redis Key 落到 `airag:chat:conversation:__DEBUG_APP:{topicId}` 这一独立命名空间，避免调试会话污染应用的生产会话缓存
- `getOrCreateChatConversation(app, topicId, "")` —— 同 [01-controller-chat.md#3.2](01-controller-chat.md)，但 topicId 必然是新生成的（前端不传）
- `doChat(...)` —— **复用聊天主流程**，与 `/airag/chat/send` 完全相同的下游逻辑（`AIRagChatServiceImpl.doChat`，参见 [01-controller-chat.md#3.4](01-controller-chat.md)）
- 与 `send()` 不同点：`debugApp` 立即 `saveChatConversation` 而不延迟，调试模式需要立刻存盘以便前端读取

---

## 三、AI 应用主实体：`AiragApp`

文件 `app/entity/AiragApp.java`（221 行）。

### 3.1 字段表

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `id` | String | `ASSIGN_ID` | 主键 |
| `createBy` / `createTime` / `updateBy` / `updateTime` | String / Date | 标准审计 | 标准字段 |
| `sysOrgCode` | String | | 所属部门 |
| `tenantId` | String | | 租户 ID |
| `name` | String | | 应用名称 |
| `descr` | String | | 应用描述 |
| `icon` | String | | 图标 URL |
| `type` | String | 字典 `ai_app_type` | 应用类型（`chatSimple` / `chatFLow`） |
| `prologue` | String | | 开场白 |
| `presetQuestion` | String | | 预设问题（JSON 数组） |
| `prompt` | String | | 提示词主体 |
| `modelId` | String | 字典 `airag_model where model_type='LLM'` | 模型 ID |
| `msgNum` | Integer | | 历史消息数量（用于上下文窗口控制） |
| `knowledgeIds` | String | 字典 `airag_knowledge where status='enable'` | 知识库 IDs，逗号分隔 |
| `flowId` | String | 字典 `airag_flow where status='enable'` | 工作流 ID（高级编排） |
| `quickCommand` | String | | 快捷指令 JSON |
| `status` | String | `enable`/`disable`/`release` | 状态 |
| `metadata` | String | | 元数据 JSON（用于扩展字段） |
| `plugins` | String | | 插件数组 JSON，例：`[{pluginId:'xxx',pluginName:'xxx',category:'mcp'}]` |
| `izOpenMemory` | Integer | `0`/`1` | 是否开启记忆 |
| `memoryId` | String | | 记忆库（AiragKnowledge.type=memory）的 ID |
| `variables` | String | | 变量 JSON 列表 |
| `memoryPrompt` | String | | 记忆与变量的提示词（与 `prompt` 拼接） |
| `knowIds` | `List<String>` | `@TableField(exist=false)` | 转换器：从 `knowledgeIds` 字符串拆出 |

### 3.2 计算字段 `getKnowIds()`（L212-219）

```java
public List<String> getKnowIds() {
    if (oConvertUtils.isNotEmpty(knowledgeIds)) {
        String[] knowIds = knowledgeIds.split(",");
        return Arrays.asList(knowIds);
    } else {
        return new ArrayList<>(0);
    }
}
```

注：MyBatis-Plus 默认不持久化（`@TableField(exist=false)`），但调用 `getKnowIds()` 触发拆字段逻辑。

---

## 四、`POST /airag/app/edit`（保存应用）

### 4.1 控制器（AiragAppController.java:92-115）

```java
@RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
@RequiresPermissions("airag:app:edit")
public Result<String> edit(@RequestBody AiragApp airagApp, HttpServletRequest request) {
    AssertUtils.assertNotEmpty("参数异常", airagApp);
    AssertUtils.assertNotEmpty("请输入应用名称", airagApp.getName());
    AssertUtils.assertNotEmpty("请选择应用类型", airagApp.getType());
    
    // ★ issues/9462 AI应用edit接口跨租户数据写入漏洞
    if (MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL) {
        String currentTenantId = TokenUtils.getTenantIdByRequest(request);
        if (airagApp.getId() != null && !airagApp.getId().isEmpty()) {
            AiragApp dbApp = airagAppService.getById(airagApp.getId());
            if (dbApp == null || !dbApp.getTenantId().equals(currentTenantId)) {
                return Result.error("保存AI应用失败，不能修改其他租户的AI应用！");
            }
        }
        // 强制使用当前登录租户，忽略客户端传入值
        airagApp.setTenantId(currentTenantId);
    }
    
    airagApp.setStatus(AiAppConsts.STATUS_ENABLE);
    airagAppService.saveOrUpdate(airagApp);
    return Result.OK("保存完成!", airagApp.getId());
}
```

**逐行解读**：

| 行 | 行为 | 为什么 |
|---|------|------|
| 96-98 | `assertNotEmpty` 校验 | 防止空 name / 空 type 入库 |
| 100-110 | SaaS 多租户隔离 | 当 `OPEN_SYSTEM_TENANT_CONTROL=true` 时：编辑前查 DB 比对 `tenantId`，覆盖请求体中的 `tenantId` 防止伪造 |
| 112 | `setStatus(STATUS_ENABLE)` | 强制设为启用 |
| 113 | `saveOrUpdate` | MyBatis-Plus：`id` 为空时 `INSERT`，否则 `UPDATE` |

**安全修复 #9462**：原版只校验"编辑时属于自己租户"，但攻击者可绕过 `id` 直接 POST 创建一条任意 `tenantId` 的应用。现在无论新增还是编辑，都强制以当前 JWT 中租户 ID 为准。

### 4.2 Service 层

`IAiragAppService extends IService<AiragApp>`，自带 CRUD（`saveOrUpdate` / `removeById` 等）。本 controller 不写自定义业务，由 MyBatis-Plus 默认实现。

---

## 五、`POST /airag/app/release` — 发布应用

```java
@RequestMapping(value = "/release", method = RequestMethod.POST)
public Result<String> release(@RequestParam(name = "id") String id, 
                              @RequestParam(name = "release") Boolean release) {
    AssertUtils.assertNotEmpty("id必须填写", id);
    if (release == null) {
        release = true;
    }
    AiragApp airagApp = new AiragApp();
    airagApp.setId(id);
    if (release) {
        airagApp.setStatus(AiAppConsts.STATUS_RELEASE);    // "release"
    } else {
        airagApp.setStatus(AiAppConsts.STATUS_ENABLE);     // "enable"
    }
    airagAppService.updateById(airagApp);
    return Result.OK(release ? "发布成功" : "取消发布成功");
}
```

**逻辑**：仅更新 `status` 字段（MyBatis-Plus 默认 `updateById` 仅更新非 null 字段）。`release=true` → 状态置 `release`（已发布到应用市场），`release=false` → 回到 `enable`（草稿状态）。

---

## 六、`POST /airag/app/prompt/generate` — 提示词生成（同步 + SSE）

### 6.1 控制器

```java
@GetMapping(value = "/prompt/generate")
public Result<?> generatePrompt(@RequestParam(name = "prompt", required = true) String prompt) {
    return (Result<?>) airagAppService.generatePrompt(prompt, true);    // blocking=true
}

@PostMapping(value = "/prompt/generate")
public SseEmitter generatePromptSse(@RequestParam(name = "prompt", required = true) String prompt) {
    return (SseEmitter) airagAppService.generatePrompt(prompt, false);  // blocking=false → SSE
}
```

两版本：GET 同步阻塞，POST 流式 SSE。

### 6.2 Service 实现（`AiragAppServiceImpl.java:70-91`）

```java
@Override
public Object generatePrompt(String prompt, boolean blocking) {
    AssertUtils.assertNotEmpty("请输入提示词", prompt);
    // 1. 系统提示词 + 用户需求
    List<ChatMessage> messages = Arrays.asList(
        new SystemMessage(Prompts.GENERATE_LLM_PROMPT),
        new UserMessage(prompt)
    );
    
    // 2. 配置参数（调优大模型生成行为）
    AIChatParams params = new AIChatParams();
    params.setTemperature(0.8);     // 较高温度保证多样性
    params.setTopP(0.9);
    params.setPresencePenalty(0.1);
    params.setFrequencyPenalty(0.1);
    
    if (blocking) {
        String promptValue = aiChatHandler.completionsByDefaultModel(messages, params);
        if (promptValue == null || promptValue.isEmpty()) {
            return Result.error("生成失败");
        }
        return Result.OK("success", promptValue);
    } else {
        return startSseChat(messages, params);   // 流式
    }
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 73 | `Prompts.GENERATE_LLM_PROMPT` —— 系统提示词常量，定义在 `app/consts/Prompts.java` |
| 75 | `Arrays.asList(SystemMessage, UserMessage)` —— 两轮对话结构 |
| 77-81 | 调优参数：T=0.8, TopP=0.9, penalty=0.1（轻微鼓励多样性，避免 LLM 重复） |
| 83 | blocking：`aiChatHandler.completionsByDefaultModel(...)` 返回完整文本（走默认模型） |
| 86 | 空响应 → `Result.error` |

### 6.3 SSE 路径 `startSseChat(...)`（`AiragAppServiceImpl.java:172-200`）

```java
private SseEmitter startSseChat(List<ChatMessage> messages, AIChatParams params) {
    SseEmitter emitter = new SseEmitter(-0L);   // -0L = Long.MIN_VALUE = 永不过期
    TokenStream tokenStream = aiChatHandler.chatByDefaultModel(messages, params);
    
    AtomicBoolean isThinking = new AtomicBoolean(false);
    String requestId = UUIDGenerator.generate();
    
    tokenStream.onPartialResponse((String resMessage) -> {
        // 兼容推理模型（DeepSeek / Qwen）的 思考过程渲染
        if ("think".equals(resMessage)) {     // 简化示意：实际检测 "think" 开头
            isThinking.set(true);
            resMessage = "> ";   // markdown 引用块
        }
        if ("/think".equals(resMessage)) {
            isThinking.set(false);
            resMessage = "\n\n";
        }
        if (isThinking.get()) {
            if (null != resMessage && resMessage.contains("\n")) {
                resMessage = "\n> ";
            }
        }
        EventData eventData = new EventData(requestId, null, EventData.EVENT_MESSAGE);
        EventMessageData messageEventData = EventMessageData.builder().message(resMessage).build();
        eventData.setMessageData(messageEventData);
        emitter.send(SseEmitter.event().data(eventData));
    });
    
    tokenStream.onCompleteResponse(chatResponse -> {
        // 完成事件
        emitter.send(SseEmitter.event().data(new EventData(requestId, null, EventData.EVENT_MESSAGE_END)));
        emitter.complete();
    });
    
    tokenStream.onError(throwable -> {
        emitter.completeWithError(throwable);
    });
    
    return emitter;
}
```

**关键设计**：
- `SseEmitter(-0L)` 永不过期，AI 提示词生成可能耗时几十秒
- `AtomicBoolean isThinking` —— 用于在客户端正确渲染 DeepSeek 推理模型的 `think.../think` 块：在 `think` 后追加 `> `前缀让 markdown 渲染成引用块
- 三种事件回调：`onPartialResponse` / `onCompleteResponse` / `onError` —— 对应 SSE 事件 `EVENT_MESSAGE` / `EVENT_MESSAGE_END` / 自动异常

### 6.4 `generateMemoryByAppId`（`AiragAppServiceImpl.java:95-164`）

```java
@Override
public Object generateMemoryByAppId(String variables, String memoryId, boolean blocking) {
    if(oConvertUtils.isEmpty(variables) && oConvertUtils.isEmpty(memoryId)){
        throw new JeecgBootBizTipException("请先添加变量或者记忆后再次重试！");
    }
    
    // 1. 构建变量描述：把 AppVariableVo 列表转成自然语言
    StringBuilder variablesDesc = new StringBuilder();
    if (oConvertUtils.isNotEmpty(variables)) {
        List<AppVariableVo> variableList = JSONArray.parseArray(variables, AppVariableVo.class);
        if (variableList != null && !variableList.isEmpty()) {
            for (AppVariableVo var : variableList) {
                if (var.getEnable() != null && !var.getEnable()) continue;
                String name = var.getName();
                if (oConvertUtils.isNotEmpty(var.getAction())) {
                    // action 字段：未用 {{}} 包裹的变量名自动补 {{}}
                    String regex = "(?<!\\{\\{)\\b" + Pattern.quote(name) + "\\b(?!\\}\\})";
                    String action = var.getAction().replaceAll(regex, "{{" + name + "}}");
                    variablesDesc.append(action).append("\n");
                } else {
                    variablesDesc.append("- {{").append(name).append("}}");
                    if (oConvertUtils.isNotEmpty(var.getDescription())) {
                        variablesDesc.append(": ").append(var.getDescription());
                    }
                    variablesDesc.append("\n");
                }
            }
        }
    }
    
    // 2. 构建完整 prompt
    StringBuilder promptBuilder = new StringBuilder(Prompts.GENERATE_GUIDE_HEADER);
    if (!variablesDesc.isEmpty()) {
        promptBuilder.append(String.format(Prompts.GENERATE_VAR_PART, variablesDesc.toString()));
    }
    if (oConvertUtils.isNotEmpty(memoryId)) {
        String memoryDescr = "";
        AiragKnowledge memory = airagKnowledgeService.getById(memoryId);
        if (memory != null && oConvertUtils.isNotEmpty(memory.getDescr())) {
            memoryDescr += "记忆库描述：" + memory.getDescr();
        }
        promptBuilder.append(String.format(Prompts.GENERATE_MEMORY_PART, memoryDescr));
    }
    
    List<ChatMessage> messages = List.of(new UserMessage(promptBuilder.toString()));
    AIChatParams params = new AIChatParams();
    params.setTemperature(0.7);
    
    if(blocking){
        String promptValue = aiChatHandler.completionsByDefaultModel(messages, params);
        if (promptValue == null || promptValue.isEmpty()) {
            return Result.error("生成失败");
        }
        return Result.OK("success", promptValue);
    } else {
        return startSseChat(messages, params);
    }
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 101-129 | 变量 → 自然语言：把 `AppVariableVo` 列表转成 `{{name}}: {{description}}` 格式 |
| 112-117 | `action` 字段特殊处理：变量名未用 `{{}}` 包裹时，正则补上（防止用户写 `"用户姓名"` 而漏掉 `{{}}`） |
| 133-137 | 拼装 prompt：header + 变量部分 + 记忆部分 |
| 141-144 | 把 `memoryId` 转换为 `memory.getDescr()` 拼接进 prompt |
| 152 | `temperature=0.7`（略低于 generatePrompt 的 0.8，因为此处输出格式要求更精确） |
| 156 | `completionsByDefaultModel` —— 走默认 LLM |

**调用入口**（控制器）：
```java
@PostMapping(value = "/prompt/generateMemoryByAppId")
public SseEmitter generatePromptByAppIdSse(@RequestParam(name = "variables") String variables,
                                           @RequestParam(name = "memoryId") String memoryId) {
    return (SseEmitter) airagAppService.generateMemoryByAppId(variables, memoryId, false);
}
```

---

## 七、`POST /airag/app/save/article/write` — AI 写作版本管理

### 7.1 控制器

```java
@PostMapping("/save/article/write")
public Result<String> saveArticleWrite(@RequestBody AiArticleWriteVersionVo aiWriteVersionVo) {
    airagAppService.saveArticleWrite(aiWriteVersionVo);
    return Result.OK("保存成功！");
}

@DeleteMapping("/delete/article/write")
public Result<String> deleteArticleWrite(@RequestParam(name = "version") String version) {
    AssertUtils.assertNotEmpty("版本号不能为空", version);
    airagAppService.deleteArticleWrite(version);
    return Result.OK("删除成功！");
}

@GetMapping("/list/article/write")
public Result<List<AiArticleWriteVersionVo>> listArticleWrite() {
    List<AiArticleWriteVersionVo> list = airagAppService.listArticleWrite();
    return Result.OK(list);
}
```

### 7.2 Service（`AiragAppServiceImpl`）

```java
@Override
public void saveArticleWrite(AiArticleWriteVersionVo aiWriteVersionVo) {
    AssertUtils.assertNotEmpty("请输入版本号", aiWriteVersionVo.getVersion());
    String key = AiAppConsts.ARTICLE_WRITER_KEY.replace("{}", aiWriteVersionVo.getVersion());
    // 形如 airag:chat:article:write:v1.0
    redisTemplate.opsForValue().set(key, aiWriteVersionVo);
}
```

**Redis Key**：`airag:chat:article:write:{version}`，value 是 `AiArticleWriteVersionVo`（无 TTL，长期保留）。

写入与删除都用相同的 key pattern。

---

## 八、`DELETE /airag/app/delete` — 删除（带租户校验）

```java
@DeleteMapping(value = "/delete")
@RequiresPermissions("airag:app:delete")
public Result<String> delete(HttpServletRequest request, @RequestParam(name = "id", required = true) String id) {
    // ★ issues/8337 AI工作列表的数据权限问题
    if (MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL) {
        AiragApp app = airagAppService.getById(id);
        String currentTenantId = TokenUtils.getTenantIdByRequest(request);
        if (null == app || !app.getTenantId().equals(currentTenantId)) {
            return Result.error("删除AI应用失败，不能删除其他租户的AI应用！");
        }
    }
    airagAppService.removeById(id);
    return Result.OK("删除成功!");
}
```

`issues/8337` 修复方案：先从 DB 查 app 的 `tenantId`，比对当前 JWT 中租户，不匹配则拒绝删除。

---

## 九、变量服务：`AiragVariableService`

文件 `app/service/impl/AiragVariableServiceImpl.java`（252 行）。

### 9.1 Redis Hash 存储模式

```java
private static final String CACHE_PREFIX = "airag:app:var:";

// Key: airag:app:var:{appId}:{username}
// Field: variableName
// Value: variableValue (字符串)
```

每个用户每个应用一个 Hash，多个变量名作为 Hash 的 field。

### 9.2 `initVariable` 与 `getVariable`

```java
public void initVariable(String username, String appId, String name, String defaultValue) {
    String key = CACHE_PREFIX + appId + ":" + username;
    redisTemplate.opsForHash().putIfAbsent(key, name, defaultValue != null ? defaultValue : "");
    // putIfAbsent: 仅在 field 不存在时设置
}

public String getVariable(String username, String appId, String name) {
    String key = CACHE_PREFIX + appId + ":" + username;
    Object value = redisTemplate.opsForHash().get(key, name);
    return value != null ? String.valueOf(value) : null;
}
```

### 9.3 `additionalPrompt` —— 把变量值填进 `{{var}}` 占位符

```java
public String additionalPrompt(String username, AiragApp app) {
    String memoryPrompt = app.getMemoryPrompt();
    String prompt = app.getPrompt();
    
    if (oConvertUtils.isEmpty(memoryPrompt)) return prompt;
    String variablesStr = app.getVariables();
    if (oConvertUtils.isEmpty(variablesStr)) return prompt;
    
    List<AppVariableVo> variableList = JSONArray.parseArray(variablesStr, AppVariableVo.class);
    if (variableList == null || variableList.isEmpty()) return prompt;
    
    String key = CACHE_PREFIX + app.getId() + ":" + username;
    Map<Object, Object> savedValues = redisTemplate.opsForHash().entries(key);  // ★ 一次拿全部
    
    for (AppVariableVo variable : variableList) {
        if (variable.getEnable() != null && !variable.getEnable()) continue;
        String name = variable.getName();
        String value = variable.getDefaultValue();
        if (savedValues.containsKey(name)) {
            Object savedVal = savedValues.get(name);
            if (savedVal != null) value = String.valueOf(savedVal);
        }
        if (value == null) value = "";
        memoryPrompt = memoryPrompt.replace("{{" + name + "}}", value);    // 模板替换
    }
    return prompt + "\n" + memoryPrompt;
}
```

**逐行解读**：
- L102-104：Redis Hash 的 `entries(key)` 一次性拿所有变量（HGETALL）。避免多次 RPC
- L108-113：优先用 Redis 中的用户值，回退到 `defaultValue`
- L116-118：`String.replace("{{name}}", value)` 模板替换。**注意**：如果 value 中含 `$` 或 `\`，`String.replace` 不解析，不会冲突

### 9.4 `addUpdateVariableTool` —— 注入"更新变量"工具

```java
public void addUpdateVariableTool(AiragApp aiApp, String username, AIChatParams params) {
    if (params.getTools() == null) params.setTools(new HashMap<>());
    if (!AiAppConsts.IZ_OPEN_MEMORY.equals(aiApp.getIzOpenMemory())) return;
    
    // 构造工具描述
    String variablesStr = aiApp.getVariables();
    List<AppVariableVo> variableList = null;
    if (oConvertUtils.isNotEmpty(variablesStr)) {
        variableList = JSONArray.parseArray(variablesStr, AppVariableVo.class);
    }
    
    StringBuilder descriptionBuilder = new StringBuilder(
        "批量更新应用变量的值。请将本次对话中所有需要更新的变量一次性传入updates数组，"
        + "无需多次调用。仅当变量新值与当前值确实不同时才调用本工具。"
    );
    if (variableList != null && !variableList.isEmpty()) {
        descriptionBuilder.append("\n\n可用变量列表：");
        for (AppVariableVo var : variableList) {
            if (var.getEnable() != null && !var.getEnable()) continue;
            descriptionBuilder.append("\n- ").append(var.getName());
            if (oConvertUtils.isNotEmpty(var.getDescription())) {
                descriptionBuilder.append(": ").append(var.getDescription());
            }
        }
        descriptionBuilder.append("\n\n注意：variableName必须是上述列表中的名称之一，且本工具每轮对话只需调用一次。");
    }
    
    // 构造 JSON Schema（批量数组）
    JsonObjectSchema itemSchema = JsonObjectSchema.builder()
        .addStringProperty("variableName", "变量名称（必须是可用变量列表中的名称之一）")
        .addStringProperty("value", "变量新值")
        .required("variableName", "value")
        .build();
    
    ToolSpecification spec = ToolSpecification.builder()
        .name("update_variable")
        .description(descriptionBuilder.toString())
        .parameters(JsonObjectSchema.builder()
            .addProperty("updates", JsonArraySchema.builder()
                .description("需要更新的变量列表，可包含多个变量")
                .items(itemSchema)
                .build())
            .required("updates")
            .build())
        .build();
    
    // 工具执行器
    ToolExecutor executor = (toolExecutionRequest, memoryId) -> {
        try {
            JSONObject args = JSONObject.parseObject(toolExecutionRequest.arguments());
            JSONArray updates = args.getJSONArray("updates");
            IAiragVariableService variableService = SpringContextUtils.getBean(IAiragVariableService.class);
            
            JSONObject updatedMap = new JSONObject();
            if (updates != null) {
                for (int i = 0; i < updates.size(); i++) {
                    JSONObject item = updates.getJSONObject(i);
                    String name = item.getString("variableName");
                    String value = item.getString("value");
                    if (oConvertUtils.isNotEmpty(name)) {
                        variableService.updateVariable(username, aiApp.getId(), name, value);
                        updatedMap.put(name, value);
                    }
                }
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("updated", updatedMap);
            result.put("count", updatedMap.size());
            result.put("message", "已成功更新 " + updatedMap.size() + " 个变量，无需再次调用");
            return result.toJSONString();
        } catch (Exception e) {
            log.error("更新变量失败", e);
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("message", "更新变量失败: " + e.getMessage());
            return error.toJSONString();
        }
    };
    
    params.getTools().put(spec, executor);
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 158 | 仅当 `izOpenMemory == 1` 时注入工具 |
| 167-185 | 工具描述：列出可用变量名 + 触发条件（避免 LLM 多次调用） |
| 189-193 | JSON Schema：`updates: [{variableName, value}, ...]`，每轮调用可批量改多个变量 |
| 196-207 | ToolSpecification + JSON Schema |
| 211-237 | ToolExecutor：解析 `updates` 数组，调用 `updateVariable` 逐个写入 Redis Hash，返回结构化 JSON |
| 246 | 把 `(spec, executor)` 注入 `params.tools`，langchain4j 在 chat 时会自动让 LLM 选 tool |

**关键设计点**：这个 `update_variable` 工具让 LLM 在对话过程中能够**主动识别变量值变化**（如用户告知"我叫张三"），自动调用工具更新变量，省去前端 UI 操作。

---

## 十、完整调用链图：`POST /airag/app/debug` 调通到底

```
┌──────────────────────────────────────────┐
│ 前端点"调试"按钮                           │
│ Body: { content, app:{...}, topicId? }   │
└─────────┬────────────────────────────────┘
          ▼
┌──────────────────────────────────────────┐
│ AiragAppController.debugApp               │
│ @PostMapping("/debug")                   │
│ return airagChatService.debugApp(.)       │
└─────────┬────────────────────────────────┘
          ▼
┌──────────────────────────────────────────┐
│ AiragChatServiceImpl.debugApp             │
│ ├─ app.setId("__DEBUG_APP")              │  ★ 调试虚拟 ID
│ ├─ getOrCreateChatConversation(.)         │
│ ├─ doChat(chatConversation, topicId, .)  │
│ └─ saveChatConversation(...) 立即保存     │
└─────────┬────────────────────────────────┘
          ▼
（同 [01-controller-chat.md#3.4 doChat]，最终落到 aiChatHandler.mergeParams → llmHandler.chat → 大模型）
```

---

## 十一、对应源码文件列表

| 文件 | 行数 | 作用 |
|------|------|------|
| `app/controller/AiragAppController.java` | 259 | 全部 13 个 `/airag/app/*` 端点 |
| `app/service/IAiragAppService.java` | 58 | 接口（生成提示词 / 写作版本） |
| `app/service/impl/AiragAppServiceImpl.java` | ~700 | 提示词 SSE 编排 + 写作版本 |
| `app/service/IAiragVariableService.java` | 55 | 变量接口 |
| `app/service/impl/AiragVariableServiceImpl.java` | 252 | 变量 Redis Hash + 工具注入 |
| `app/entity/AiragApp.java` | 221 | 主实体 |
| `app/vo/ChatSendParams.java` | 102 | （继承依赖） |
| `app/vo/AppDebugParams.java` | 20 | 调试入参 |
| `app/vo/AppVariableVo.java` | — | 变量定义 |
| `app/vo/AiArticleWriteVersionVo.java` | — | 写作版本 |
| `app/vo/AiDrawGenerateVo.java` | — | 海报生成 |
| `app/vo/AiWriteGenerateVo.java` | — | AI 写作 |
| `app/consts/AiAppConsts.java` | 91 | 常量（POSTER_TASK_PREFIX、ARTICLE_WRITER_FLOW_ID） |
| `app/consts/Prompts.java` | — | 系统提示词 |
| `app/enums/ImageEditEnum.java` | — | 图生图模型识别 |
| `app/enums/ImageSizeEnum.java` | — | 图片尺寸枚举 |
| `app/mapper/AiragAppMapper.java` | — | mapper |
| `llm/handler/AIChatHandler.java` | ~700 | （被调） |

---

## 十二、下一章

[03-controller-knowledge.md](03-controller-knowledge.md) — `/airag/knowledge/*` 控制器详解，特别是 `doc/edit`、`doc/import/zip`、`embedding/search`、`embedding/hitTest` 等 RAG 核心接口。
