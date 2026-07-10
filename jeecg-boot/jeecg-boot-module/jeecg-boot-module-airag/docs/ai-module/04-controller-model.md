# 04 · `/airag/airagModel/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/airagModel/test`**（实测连接 + 自动激活）为主线，从 HTTP 请求到 `AIChatHandler.completions` / `EmbeddingModel.embed` / `AIChatHandler.imageGenerate` 完整解读。其他端点在末尾第 5 节列出。

---

## 一、入口控制器（`AiragModelController.java`）

继承 `JeecgController<AiragModel, IAiragModelService>`，路径 `/airag/airagModel`。

| 方法 | 路径 | 鉴权 |
|------|------|------|
| `GET` | `/airag/airagModel/list` | 无 |
| `POST` | `/airag/airagModel/add` | `airag:model:add` |
| `PUT,POST` | `/airag/airagModel/edit` | `airag:model:edit` |
| `DELETE` | `/airag/airagModel/delete` | `airag:model:delete` |
| `GET` | `/airag/airagModel/queryById` | 无 |
| `GET` | `/airag/airagModel/exportXls` | 无 |
| `POST` | `/airag/airagModel/importExcel` | 无 |
| `POST` | `/airag/airagModel/test` | 无（关键接口） |

---

## 二、`POST /airag/airagModel/test` 实测激活（主线）

### 2.1 控制器方法（AiragModelController.java:168-206）

```java
@PostMapping(value = "/test")
public Result<?> test(@RequestBody AiragModel airagModel) {
    // 1. ★ 必填校验
    AssertUtils.assertNotEmpty("模型名称不能为空", airagModel.getName());
    AssertUtils.assertNotEmpty("模型类型不能为空", airagModel.getModelType());
    AssertUtils.assertNotEmpty("基础模型不能为空", airagModel.getModelName());
    
    // 2. ★ 测试连接默认为已激活状态
    airagModel.setActivateFlag(1);
    try {
        if (LLMConsts.MODEL_TYPE_LLM.equals(airagModel.getModelType())) {
            // 3a. LLM：调一次 completions
            aiChatHandler.completions(
                airagModel,
                Collections.singletonList(UserMessage.from("To test whether it can be successfully called, simply return success")),
                null
            );
        } else if (LLMConsts.MODEL_TYPE_EMBED.equals(airagModel.getModelType())) {
            // 3b. EMBED：试一次 embed
            AiModelOptions aiModelOptions = EmbeddingHandler.buildModelOptions(airagModel);
            EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(aiModelOptions);
            embeddingModel.embed("test text");
        } else if (LLMConsts.MODEL_TYPE_IMAGE.equals(airagModel.getModelType())) {
            // 3c. IMAGE：试一次 imageGenerate
            AIChatParams aiChatParams = new AIChatParams();
            String modelName = airagModel.getModelName();
            // ★ 图生图 vs 文生图分流（#QQYUN-12145 + DATE:2026-03-02）
            if(ImageEditEnum.isImageEditModel(modelName)) {
                List<String> images = new ArrayList<>();
                images.add("https://jeecgdev.oss-cn-beijing.aliyuncs.com/upload/test/jeecg_1772268161540.jpg");
                aiChatHandler.imageEdit(airagModel, "Generate a picture of a cartoon cat", images, aiChatParams);
            } else {
                aiChatHandler.imageGenerate(airagModel, "Generate a picture of a cartoon cat", aiChatParams);
            }
        }
    } catch (Exception e) {
        log.error("测试模型连接失败", e);
        return Result.error(e.getMessage());
    }
    // 4. 测试成功 → 自动激活
    airagModel.setActivateFlag(1);
    airagModelService.updateById(airagModel);
    return Result.OK("");
}
```

**逐行解读**：

| 行 | 行为 | 说明 |
|---|------|------|
| 171-173 | 必填校验 | name/type/modelName 三件套 |
| 175 | `setActivateFlag(1)` | 测试连接本身默认"激活"，但测试失败的 catch 会跳过 updateById |
| 177-180 | LLM 测试 | 调一次 `completions`，prompt 让模型返回 success 即可 |
| 181-184 | EMBED 测试 | 构造 `EmbeddingModel` 然后 `embed("test text")` |
| 185-194 | IMAGE 测试 | **用 `ImageEditEnum.isImageEditModel(modelName)` 判断是图生图（如 SD inpaint）还是文生图（如 DALL-E）**。图生图还要传一张测试图 |
| 198-199 | 任意异常 → `Result.error` + **不更新** | 失败不会写库 |
| 203-204 | 成功 → `setActivateFlag(1)` + `updateById` | 测试通过自动持久化激活状态 |

### 2.2 Service 层

`IAiragModelService extends IService<AiragModel>` —— 空接口，全部用 MyBatis-Plus 默认 CRUD（`page` / `getById` / `save` / `updateById` / `removeById`）。

`AiragModelServiceImpl` 是框架生成的 ServiceImpl，只做 mapper 注入。

---

## 三、实体：`AiragModel`

文件 `llm/entity/AiragModel.java`（132 行）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (ASSIGN_ID) | 主键 |
| `name` | String | 模型显示名 |
| `provider` | String | 字典 `model_provider`：`openai`、`zhipu`、`qianfan`、`tongyi`、`ollama`、`deepseek` 等 |
| `modelType` | String | 字典 `model_type`：`LLM` / `EMBED` / `IMAGE` |
| `modelName` | String | 基础模型名（如 `gpt-4o`、`bge-large-zh`、`qwen-vl-max`） |
| `baseUrl` | String | API 域名 |
| `credential` | String | **JSON 字符串**，可包含：`apiKey`、`secretKey`、`httpVersionOne`（百度千帆需要 http/1） |
| `modelParams` | String | **JSON 字符串**，可包含：`temperature`、`topP`、`presencePenalty`、`frequencyPenalty`、`maxTokens`、`timeout`、`enableSearch`、`extraParams` |
| `activateFlag` | Integer (0/1) | 是否激活 |
| 标准审计字段 | | createBy/createTime/updateBy/updateTime/sysOrgCode/tenantId |

**`credential` 字段反序列化**（`AIChatHandler.mergeParams`）：

```java
if (oConvertUtils.isObjectNotEmpty(airagModel.getCredential())) {
    JSONObject modelCredential = JSONObject.parseObject(airagModel.getCredential());
    params.setApiKey(oConvertUtils.getString(modelCredential.getString("apiKey"), null));
    params.setSecretKey(oConvertUtils.getString(modelCredential.getString("secretKey"), null));
    if(modelCredential.containsKey("httpVersionOne")){
        params.setIzHttpVersionOne(modelCredential.getInteger("httpVersionOne") == 1);
    }
}
```

---

## 四、`AiragModelController.test` 三种 modelType 分支详解

### 4.1 LLM 分支：调 `completions`

```java
aiChatHandler.completions(
    airagModel,    // 临时构造的（前端刚填的），不是 DB 里存的
    Collections.singletonList(UserMessage.from("To test whether it can be successfully called, simply return success")),
    null          // params 传 null → AIChatHandler.mergeParams 会自己 new 一个
);
```

进入 `AIChatHandler.completions(AiragModel, List<ChatMessage>, AIChatParams)`：

```java
public String completions(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params) {
    params = mergeParams(airagModel, params);   // ★ 与聊天复用同一个 mergeParams
    messages = injectThinkingPlaceholderIfNeeded(messages, airagModel.getModelName());  // DeepSeek 推理模型占位
    String resp = null;
    try {
        resp = llmHandler.completions(messages, params);    // ★ 实际调用 base-core
    } catch (ToolExecutionException e) {
        String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        causeMsg = matchErrorMsg(causeMsg, causeMsg);
        log.error("AI工具执行异常 - {}", causeMsg, e);
        return "";
    } catch (Exception e) {
        throw translateLlmException(e, "调用大模型接口失败，详情请查看后台日志。");
    }
    // ★ 推理模型剥离 思考块
    if (resp != null && resp.contains("think") && (null == params.getNoThinking() || params.getNoThinking())) {
        String[] thinkSplit = resp.split("/think");
        resp = thinkSplit[thinkSplit.length - 1];
    }
    return resp;
}
```

**逐行解读**：

| 行 | 行为 | 说明 |
|---|------|------|
| 121 | `mergeParams` | 复用聊天时的合并逻辑：注入 credential、temperature、模型参数、RAG、插件 |
| 122 | DeepSeek 占位 | issues/9585：推理模型历史消息注 reasoning_content |
| 127 | `llmHandler.completions(messages, params)` | 实际调大模型 |
| 128-133 | `ToolExecutionException` 单独捕获 | 翻译工具调用失败的错误信息 |
| 134-136 | 其他异常统一翻译 | `translateLlmException` 4 级降级（见 [00-overview.md#六](00-overview.md)） |
| 138-142 | 剥离 `think.../think` | 兼容推理模型输出（注意：当前代码 `"think"` 是简化匹配，实际是精确匹配 `think`） |

**测试响应解析**：只要不抛异常，就算测试成功。即"我向模型发了一条消息，模型给了我响应"。

### 4.2 EMBED 分支：调 `embed`

```java
AiModelOptions aiModelOptions = EmbeddingHandler.buildModelOptions(airagModel);
EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(aiModelOptions);
embeddingModel.embed("test text");
```

`EmbeddingHandler.buildModelOptions` 静态方法（L739-755）：

```java
public static AiModelOptions buildModelOptions(AiragModel model) {
    AiModelOptions.AiModelOptionsBuilder modelOpBuilder = AiModelOptions.builder()
        .provider(model.getProvider())
        .modelName(model.getModelName())
        .baseUrl(model.getBaseUrl());
    if (oConvertUtils.isObjectNotEmpty(model.getCredential())) {
        JSONObject modelCredential = JSONObject.parseObject(model.getCredential());
        modelOpBuilder.apiKey(oConvertUtils.getString(modelCredential.getString("apiKey"), null));
        modelOpBuilder.secretKey(oConvertUtils.getString(modelCredential.getString("secretKey"), null));
        if(modelCredential.containsKey("httpVersionOne")){
            modelOpBuilder.izHttpVersionOne(modelCredential.getInteger("httpVersionOne") == 1);
        }
    }
    modelOpBuilder.topNumber(5);          // 默认 top5
    modelOpBuilder.similarity(0.75);      // 默认 0.75 相似度
    return modelOpBuilder.build();
}
```

**逐行解读**：
- `provider/modelName/baseUrl` 三件套 → `AiModelOptions`
- `credential` JSON → `apiKey`、`secretKey`、`httpVersionOne`
- 默认 `topNumber=5`、`similarity=0.75` —— 这些是 RAG 检索参数，向量化模型本身不用，但 AIChatHandler 也读这个对象

### 4.3 IMAGE 分支：文生图 vs 图生图分流

```java
AIChatParams aiChatParams = new AIChatParams();
String modelName = airagModel.getModelName();
if(ImageEditEnum.isImageEditModel(modelName)){
    List<String> images = new ArrayList<>();
    images.add("https://jeecgdev.oss-cn-beijing.aliyuncs.com/upload/test/jeecg_1772268161540.jpg");
    aiChatHandler.imageEdit(airagModel, "Generate a picture of a cartoon cat", images, aiChatParams);
} else {
    aiChatHandler.imageGenerate(airagModel, "Generate a picture of a cartoon cat", aiChatParams);
}
```

**`ImageEditEnum.isImageEditModel(name)`**：判断模型名是否含"inpaint"、"img2img"、"edit" 等关键字，识别是图生图模型（如 SD inpaint）还是文生图模型。

**`imageGenerate` 与 `imageEdit`** 都在 `AIChatHandler` 中：

```java
public List<Map<String, Object>> imageGenerate(AiragModel airagModel, String messages, AIChatParams params) {
    if(airagModel == null || (airagModel.getActivateFlag() != null && airagModel.getActivateFlag() == 0)){
        // 模型未激活 → 用 yml 默认文生图模型
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

`imageEdit` 几乎一样，只是用 `aiChatConfig.getAiModelPicDraw()` 的默认图生图模型。

---

## 五、其它 `/airag/airagModel/*` 端点

### 5.1 `queryPageList`（分页）

```java
@GetMapping(value = "/list")
public Result<IPage<AiragModel>> queryPageList(AiragModel airagModel, @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize, HttpServletRequest req) {
    QueryWrapper<AiragModel> queryWrapper = QueryGenerator.initQueryWrapper(airagModel, req.getParameterMap());
    Page<AiragModel> page = new Page<AiragModel>(pageNo, pageSize);
    IPage<AiragModel> pageList = airagModelService.page(page, queryWrapper);
    return Result.OK(pageList);
}
```

`QueryGenerator.initQueryWrapper(entity, paramMap)` —— JeecgBoot 通用查询构造器，根据请求参数 `?modelType=LLM&activateFlag=1` 等自动构建 `QueryWrapper`。

### 5.2 `add` / `edit` / `delete`

继承 `JeecgController` 基类（无自动 CRUD 写死）。这 3 个是手写：

```java
@PostMapping(value = "/add")
@RequiresPermissions("airag:model:add")
public Result<String> add(@RequestBody AiragModel airagModel) {
    AssertUtils.assertNotEmpty("模型名称不能为空", airagModel.getName());
    AssertUtils.assertNotEmpty("模型类型不能为空", airagModel.getModelType());
    AssertUtils.assertNotEmpty("基础模型不能为空", airagModel.getModelName());
    if(oConvertUtils.isObjectEmpty(airagModel.getActivateFlag())){
        airagModel.setActivateFlag(0);   // ★ 新增默认未激活
    }
    airagModelService.save(airagModel);
    return Result.OK("添加成功！");
}

@RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
@RequiresPermissions("airag:model:edit")
public Result<String> edit(@RequestBody AiragModel airagModel) {
    airagModelService.updateById(airagModel);
    return Result.OK("编辑成功!");
}

@DeleteMapping(value = "/delete")
@RequiresPermissions("airag:model:delete")
public Result<String> delete(HttpServletRequest request, @RequestParam(name = "id", required = true) String id) {
    if (MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL) {
        AiragModel model = airagModelService.getById(id);
        String currentTenantId = TokenUtils.getTenantIdByRequest(request);
        if (null == model || !model.getTenantId().equals(currentTenantId)) {
            return Result.error("删除AI模型失败，不能删除其他租户的AI模型！");
        }
    }
    airagModelService.removeById(id);
    return Result.OK("删除成功!");
}
```

**关键点**：`add` 默认 `activateFlag=0`（未激活），需要前端点 `/test` 测试通过后才会被改成 1。
`delete` 含租户校验 [issues/8337]。

### 5.3 `exportXls` / `importExcel`

继承 `JeecgController` 基类的标准导出导入：

```java
@GetMapping(value = "/exportXls")
public ModelAndView exportXls(HttpServletRequest request, AiragModel airagModel) {
    return super.exportXls(request, airagModel, AiragModel.class, "AiRag模型配置");
}

@PostMapping(value = "/importExcel", method = RequestMethod.POST)
public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
    return super.importExcel(request, response, AiragModel.class);
}
```

`autoPoi` 框架自动按 Excel 注解导出/导入。第一行中文表头如 "模型名称" 来自 `@Excel(name = "模型名称", width = 15)`。

### 5.4 `queryById`

```java
@GetMapping(value = "/queryById")
public Result<AiragModel> queryById(@RequestParam(name = "id", required = true) String id) {
    AiragModel airagModel = airagModelService.getById(id);
    if (airagModel == null) {
        return Result.error("未找到对应数据");
    }
    return Result.OK(airagModel);
}
```

直接 `getById`，无特殊逻辑。

---

## 六、完整调用链：`POST /airag/airagModel/test` 调通到底

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 前端模型列表 → 点"测试连接"                                              │
│    POST /airag/airagModel/test                                            │
│    Body: AiragModel (含 provider/modelName/baseUrl/credential)            │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. AiragModelController.test(airagModel)                                  │
│    ├─ 3 条 assertNotEmpty                                                  │
│    ├─ setActivateFlag(1)                                                  │
│    ├─ switch (modelType):                                                │
│    │     LLM   → aiChatHandler.completions(airagModel, msg, null)        │
│    │     EMBED → buildModelOptions + createEmbeddingModel + embed()      │
│    │     IMAGE → imageGenerate 或 imageEdit（按 ImageEditEnum）           │
│    └─ success: setActivateFlag(1) + updateById(airagModel)                 │
│           failure: return Result.error + 不 update                        │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. AIChatHandler.completions(airagModel, messages, params=null)           │
│    ├─ params = mergeParams(airagModel, params)    ← 复用聊天逻辑         │
│    ├─ injectThinkingPlaceholderIfNeeded(...) ← DeepSeek 推理占位        │
│    ├─ llmHandler.completions(messages, params)   ← 实际调用              │
│    └─ 异常分类处理                                                      │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. base-core LL MHandler.completions                                      │
│    根据 provider 选择 Langchain4j 适配器（OpenAI/Zhipu/Qianfan/...）        │
│    ├─ 注入 system message（mergeParams 已构造）                          │
│    ├─ 调用 langchain4j ChatModel.chat                                    │
│    └─ 解析响应：剥离 思考块、注入工具执行结果                              │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. 大模型 API（OpenAI / DeepSeek / Qwen / 智普 / 千帆 / Ollama ...）       │
│    HTTP POST {baseUrl}/v1/chat/completions                                │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 七、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/controller/AiragModelController.java` | 209 | 8 个端点 |
| `llm/service/IAiragModelService.java` | 18 | 空接口 |
| `llm/service/impl/AiragModelServiceImpl.java` | — | `ServiceImpl` 默认实现 |
| `llm/entity/AiragModel.java` | 132 | 模型实体 |
| `llm/handler/AIChatHandler.java` | ~700 | 被 `/test` 调用的核心 |
| `llm/handler/EmbeddingHandler.java` | 988 | EMBED 测试路径的 `buildModelOptions` |
| `app/enums/ImageEditEnum.java` | — | 文生图/图生图分流 |
| `app/enums/ImageSizeEnum.java` | — | 图片尺寸选项 |
| `llm/consts/LLMConsts.java` | 222 | `MODEL_TYPE_LLM` / `_EMBED` / `_IMAGE` 常量 |
| `common/handler/AIChatParams.java` | — | params POJO（base-core） |
| `llm/mapper/AiragModelMapper.java` | — | 含 `getByIdIgnoreTenant` |

---

## 八、下一章

[05-controller-mcp.md](05-controller-mcp.md) — `/airag/airagMcp/*` 控制器详解，包括 MCP 同步（`sync`）、插件保存（`saveTools`）、三种传输协议（sse / http / stdio）。
