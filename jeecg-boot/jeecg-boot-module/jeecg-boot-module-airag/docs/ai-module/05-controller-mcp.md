# 05 · `/airag/airagMcp/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/airagMcp/sync/{id}`**（同步外部 MCP 工具）为主线，从 HTTP 请求落到 `AirragMcpServiceImpl.sync` → `langchain4j McpClient.listTools` → 写回 DB，**整条调用链不断裂**。
> 该接口对 JeecgBoot 集成第三方 MCP 服务（如高德地图 / 飞书 / Notion）至关重要。

---

## 一、入口控制器（`AiragMcpController.java`）

继承 `JeecgController<AiragMcp, IAiragMcpService>`，路径 `/airag/airagMcp`。

| 方法 | 路径 | 鉴权 |
|------|------|------|
| `GET` | `/airag/airagMcp/list` | `airag:mcp:list` |
| `POST` | `/airag/airagMcp/save` | `airag:mcp:save` |
| `POST` | `/airag/airagMcp/saveAndSync` | `airag:mcp:save` |
| `POST` | `/airag/airagMcp/sync/{id}` | `airag:mcp:save` |
| `POST` | `/airag/airagMcp/status/{id}/{action}` | `airag:mcp:save` |
| `POST` | `/airag/airagMcp/saveTools` | `airag:mcp:save` |
| `DELETE` | `/airag/airagMcp/delete` | `airag:mcp:delete` |
| `GET` | `/airag/airagMcp/queryById` | 无 |
| `GET` | `/airag/airagMcp/exportXls` | `airag:mcp:export` |
| `POST` | `/airag/airagMcp/importExcel` | `airag:mcp:import` |

---

## 二、实体：`AiragMcp`

文件 `llm/entity/AiragMcp.java`（139 行）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (ASSIGN_ID) | 主键 |
| `name` | String | MCP 服务显示名 |
| `descr` | String | 描述 |
| **`category`** | String | **`mcp`** / **`plugin`** — 区分 MCP 协议 vs 内部 HTTP 插件 |
| **`type`** | String | 当 category=mcp 时：`sse` / `http` / `stdio` |
| `endpoint` | String | 服务端点：sse→URL；stdio→命令行 |
| `headers` | String | 请求头 JSON（sse/http）或环境变量（stdio） |
| **`tools`** | String | **工具列表 JSON**，结构：`[{name, description, path, method, parameters, responses, enabled}]` |
| `status` | String | `enable` / `disable` |
| `synced` | Integer (0/1) | 是否已同步（即调用 sync 后再标 1） |
| `metadata` | String | 元数据 JSON（含 tool_count 等） |
| 标准审计字段 | | createBy/createTime/.../tenantId |

**`category` 与 `type` 是关键区分字段**：

| category | type | 用途 | 集成方式 |
|---------|------|------|---------|
| `mcp` | `sse` | SSE 长连接的 MCP | HttpMcpTransport |
| `mcp` | `http` | Streamable HTTP MCP | StreamableHttpMcpTransport |
| `mcp` | `stdio` | 本地进程（`npx`/`uvx`） | StdioMcpTransport（受 yml 白名单控制） |
| `plugin` | — | 调用 JeecgBoot 内部 HTTP API | PluginToolBuilder.buildTools |

---

## 三、`POST /airag/airagMcp/sync/{id}` —— 同步工具列表（主线）

### 3.1 控制器（AiragMcpController.java:107-110）

```java
@Operation(summary = "MCP-同步MCP信息")
@RequiresPermissions("airag:mcp:save")
@PostMapping(value = "/sync/{id}")
public Result<?> sync(@PathVariable(name = "id", required = true) String id) {
    return airagMcpService.sync(id);
}
```

### 3.2 Service `sync` 入口（`AirragMcpServiceImpl.java#115-246`）

```java
@Override
public Result<?> sync(String id) {
    AiragMcp mcp = this.getById(id);
    if (mcp == null) {
        return Result.error("未找到对应的MCP对象");
    }
    
    // ★ #QQYUN-12453：插件类型不支持同步
    String category = mcp.getCategory();
    if (oConvertUtils.isEmpty(category)) {
        category = "mcp";  // 兼容旧数据
    }
    if (!"mcp".equalsIgnoreCase(category)) {
        return Result.error("只有MCP类型才支持同步操作");
    }
    
    String type = mcp.getType();
    String endpoint = mcp.getEndpoint();
    
    // 解析 headers JSON
    Map<String, String> headers = null;
    if (oConvertUtils.isNotEmpty(mcp.getHeaders())) {
        try {
            headers = JSONObject.parseObject(mcp.getHeaders(), new com.alibaba.fastjson.TypeReference<Map<String, String>>() {});
        } catch (JSONException e) {
            headers = null;
        }
    }
    if (type == null || endpoint == null) {
        return Result.error("MCP类型或端点为空");
    }
    
    McpClient mcpClient = null;
    try {
        // ★ 根据 type 构造对应 Transport
        if ("sse".equalsIgnoreCase(type)) {
            log.info("[MCP]使用SSE协议(HttpMcpTransport), endpoint:{}", endpoint);
            HttpMcpTransport.Builder builder = HttpMcpTransport.builder()
                .sseUrl(endpoint)
                .logRequests(true)
                .logResponses(true);
            if (headers != null && !headers.isEmpty()) {
                builder.customHeaders(headers);
            }
            mcpClient = new DefaultMcpClient.Builder().transport(builder.build()).build();
        } else if ("stdio".equalsIgnoreCase(type)) {
            String openSafe = aiRagConfigBean.getAllowSensitiveNodes();
            // ★ stdio 默认禁用 [QQYUN-14242]
            if(oConvertUtils.isNotEmpty(openSafe) && openSafe.toLowerCase().contains("stdio")) {
                log.info("[MCP]使用STDIO协议(StdioMcpTransport), endpoint:{}", endpoint);
                // ★ 跨平台 shell 包装
                List<String> cmdParts;
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    cmdParts = new ArrayList<>();
                    cmdParts.add("cmd.exe");
                    cmdParts.add("/c");
                    cmdParts.add(endpoint.trim());
                } else {
                    cmdParts = new ArrayList<>();
                    cmdParts.add("sh");
                    cmdParts.add("-c");
                    cmdParts.add(endpoint.trim());
                }
                log.info("[MCP]执行stdio命令: {}", cmdParts);
                StdioMcpTransport.Builder builder = new StdioMcpTransport.Builder()
                    .command(cmdParts)
                    .environment(headers);   // headers 当环境变量用
                mcpClient = new DefaultMcpClient.Builder().transport(builder.build()).build();
            } else {
                String disabledMsg = "stdio 功能已禁用。若需启用，请在 yml 的 jeecg.airag.allow-sensitive-nodes 中加入 stdio。";
                log.warn("[MCP]{}", disabledMsg);
                return Result.error(disabledMsg);
            }
        } else if("http".equalsIgnoreCase(type)){
            log.info("[MCP]使用HTTP协议(StreamableHttpMcpTransport), endpoint:{}", endpoint);
            mcpClient = mcpHttpCreate(endpoint, headers);
        } else {
            return Result.error("不支持的MCP类型:" + type);
        }
        
        // ★ 调 MCP listTools()
        List<ToolSpecification> toolSpecifications = mcpClient.listTools();
        
        // 序列化为 Map 列表（保留 langchain4j 的 ToolSpecification 结构）
        List<Map<String, Object>> specMaps = toolSpecifications.stream()
            .map(spec -> {
                try {
                    // 优先 Jackson 序列化（保留字段）
                    String raw = objectMapper.writeValueAsString(spec);
                    if (raw != null && raw.length() > 2) {
                        return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
                    }
                } catch (Exception ignore) {}
                // 回退：自定义 convertToolSpec
                return convertToolSpec(spec);
            }).collect(Collectors.toList());
        
        // 写回 DB
        String jsonList;
        try {
            jsonList = objectMapper.writeValueAsString(specMaps);
        } catch (JsonProcessingException e) {
            jsonList = JSONObject.toJSONString(specMaps);
        }
        mcp.setTools(jsonList);
        mcp.setSynced(1);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tool_count", toolSpecifications.size());
        mcp.setMetadata(objectMapper.writeValueAsString(metadata));
        this.updateById(mcp);
        
        return Result.OK(specMaps);
    } catch (Exception e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException) {
            message = "，MCP客户端参数错误";
        }
        log.error("同步MCP工具失败 id={}, error={}", id, message, e);
        return Result.error("同步失败" + message);
    } finally {
        // ★ 释放 MCP 客户端连接
        if (mcpClient != null) {
            try {
                Method closeMethod = mcpClient.getClass().getMethod("close");
                closeMethod.invoke(mcpClient);
            } catch (NoSuchMethodException ignore) {
                // langchain4j 版本如果没有 close 方法就跳过
            } catch (Exception ex) {
                log.warn("关闭MCP客户端失败 id={}, error={}", id, ex.getMessage());
            }
        }
    }
}
```

**逐行解读**：

| 行 | 行为 | 说明 |
|---|------|------|
| 116-119 | `getById(id)` | MyBatis-Plus 默认通过主键查 |
| 122-129 | **#QQYUN-12453** | 同步只对 `category=mcp` 有效，plugin 类型是手动配置 |
| 130-139 | 解析 headers JSON | fastjson + TypeReference 解析为 Map |
| 142-145 | **SSE 类型** | 用 `HttpMcpTransport`（langchain4j 1.4.0-beta10 后弃用，推荐 HTTP） |
| 146-187 | **stdio 类型** | 安全敏感：受 yml 白名单 `jeecg.airag.allow-sensitive-nodes` 控制；Windows 用 `cmd.exe /c` 包裹，Linux/Mac 用 `sh -c` 包裹 |
| 188-191 | **HTTP 类型** | `StreamableHttpMcpTransport` 是官方推荐的新协议 |
| 196-211 | `mcpClient.listTools()` → 序列化 | 用 Jackson 序列化为 Map 列表；失败回退到 `convertToolSpec` 手动转 |
| 213-218 | 写回 DB | 把工具列表存到 `tools` 字段 JSON，标记 `synced=1`，metadata 记 tool_count |
| 220 | 返回 Map 列表给前端 | 前端展示工具详情 |
| 234-244 | `finally` 关闭客户端 | 反射调 `close()` 方法，langchain4j 版本差异兼容 |
| 222-231 | 异常处理 | 把异常信息透出给前端（敏感操作错误必须可见） |

### 3.3 `mcpHttpCreate`（L255-269）

```java
private McpClient mcpHttpCreate(String endpoint, Map<String, String> headers) {
    StreamableHttpMcpTransport.Builder builder = new StreamableHttpMcpTransport.builder()
        .url(endpoint)
        .timeout(Duration.ofMinutes(60))
        .logRequests(true)
        .logResponses(true);
    if (headers != null && !headers.isEmpty()) {
        builder.customHeaders(headers);
    }
    return new DefaultMcpClient.Builder()
        .transport(builder.build())
        .build();
}
```

60 分钟超时（视频/异步 MCP 服务可能耗时长）。

### 3.4 `convertToolSpec`（L281-310）

```java
private Map<String, Object> convertToolSpec(ToolSpecification spec) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (spec == null) return map;
    map.put("name", spec.name());
    map.put("description", spec.description());
    try {
        Object params = spec.parameters();
        if (params != null) {
            JsonObjectSchema obj = (JsonObjectSchema) params;
            List<Map<String, Object>> fields = new ArrayList<>();
            if (obj.properties() != null) {
                obj.properties().forEach((fieldName, fieldSchema) -> {
                    Map<String, Object> fieldMap = new LinkedHashMap<>();
                    fieldMap.put("name", fieldName);
                    fieldMap.put("description", extractDescription(fieldSchema));
                    if (obj.required() != null && obj.required().contains(fieldName)) {
                        fieldMap.put("required", true);
                    }
                    fields.add(fieldMap);
                });
            }
            map.put("parameters", fields);
        }
    } catch (Exception ignored) {}
    return map;
}
```

把 langchain4j 的 `ToolSpecification`（record）拆解为扁平 Map，便于序列化到 DB。

---

## 四、`POST /airag/airagMcp/save` —— 保存配置

```java
@PostMapping(value = "/save")
public Result<String> save(@RequestBody AiragMcp airagMcp) {
    return airagMcpService.edit(airagMcp);
}
```

### 4.1 Service `edit`（`AirragMcpServiceImpl.java#57-104`）

```java
@Override
public Result<String> edit(AiragMcp airagMcp) {
    // 1. 必填校验
    if (airagMcp.getName() == null || airagMcp.getName().trim().isEmpty()) {
        return Result.error("名称不能为空");
    }
    
    // 2. #QQYUN-12453 默认 category
    if (oConvertUtils.isEmpty(airagMcp.getCategory())) {
        airagMcp.setCategory("mcp");
    }
    
    // 3. 按 category 校验 type/endpoint
    if ("mcp".equalsIgnoreCase(airagMcp.getCategory())) {
        if (airagMcp.getType() == null || airagMcp.getType().trim().isEmpty()) {
            return Result.error("MCP类型不能为空");
        }
        if (airagMcp.getEndpoint() == null || airagMcp.getEndpoint().trim().isEmpty()) {
            return Result.error("服务端点不能为空");
        }
    } else if ("plugin".equalsIgnoreCase(category)) {
        // 插件：BaseURL 可选，填了用填的，没填用当前系统地址
    } else {
        // 未知类型：按 mcp 处理
        if (airagMcp.getEndpoint() == null || airagMcp.getEndpoint().trim().isEmpty()) {
            return Result.error("服务端点不能为空");
        }
    }
    
    // 4. 新增/编辑
    if (airagMcp.getId() == null || airagMcp.getId().trim().isEmpty()) {
        airagMcp.setStatus("enable");
        // ★ #QQYUN-12453 仅 MCP 类型默认未同步
        if ("mcp".equalsIgnoreCase(airagMcp.getCategory())) {
            airagMcp.setSynced(CommonConstant.STATUS_0_INT);    // 0
        } else {
            airagMcp.setSynced(CommonConstant.STATUS_1_INT);    // 1
        }
        this.save(airagMcp);    // INSERT
    } else {
        this.updateById(airagMcp);    // UPDATE
    }
    return Result.OK("保存成功");
}
```

### 4.2 `POST /airag/airagMcp/saveAndSync` —— 保存并同步

```java
@PostMapping(value = "/saveAndSync")
@RequiresPermissions("airag:mcp:save")
public Result<?> saveAndSync(@RequestBody AiragMcp airagMcp) {
    Result<String> saveResult = airagMcpService.edit(airagMcp);
    if (!saveResult.isSuccess()) {
        return saveResult;
    }
    String id = airagMcp.getId();
    if (id == null || id.trim().isEmpty()) {
        return Result.error("保存失败");
    }
    return airagMcpService.sync(id);   // ★ save 后立即 sync
}
```

`save` 成功后立刻调 `sync`。这样前端填完表单一点就完成"保存+拉取工具列表"。

---

## 五、`POST /airag/airagMcp/status/{id}/{action}` —— 启停

```java
@PostMapping(value = "/status/{id}/{action}")
public Result<?> toggleStatus(@PathVariable(name = "id", required = true) String id,
                              @PathVariable(name = "action", required = true) String action) {
    return airagMcpService.toggleStatus(id, action);
}
```

```java
// AirragMcpServiceImpl.toggleStatus (L336-358)
public Result<?> toggleStatus(String id, String action) {
    if (oConvertUtils.isEmpty(id)) return Result.error("id不能为空");
    if (oConvertUtils.isEmpty(action)) return Result.error("action不能为空");
    String normalized = action.toLowerCase();
    if (!"enable".equals(normalized) && !"disable".equals(normalized)) {
        return Result.error("action只能为enable或disable");
    }
    AiragMcp mcp = this.getById(id);
    if (mcp == null) return Result.error("未找到对应的MCP服务");
    
    // 已经是目标状态就不重复更新
    if (normalized.equalsIgnoreCase(mcp.getStatus())) {
        return Result.OK("操作成功");
    }
    mcp.setStatus(normalized);
    this.updateById(mcp);
    return Result.OK("操作成功");
}
```

**注意**：禁用后，`AIChatHandler.buildPlugins` 还是会构建工具但 langchain4j 调时大模型才知道这个工具不能用——禁用实际意味着"前端不可见"，运行时还是可能被选。要彻底禁用需要更细的判断（目前未实现）。

---

## 六、`POST /airag/airagMcp/saveTools` —— 手动保存工具定义（plugin 类型）

```java
@PostMapping(value = "/saveTools")
public Result<String> saveTools(@RequestBody SaveToolsDTO dto) {
    return airagMcpService.saveTools(dto.getId(), dto.getTools());
}
```

入参 `SaveToolsDTO`：

```java
@Data
public class SaveToolsDTO {
    private String id;       // airag_mcp.id
    private String tools;    // 工具列表 JSON 字符串
}
```

```java
// AirragMcpServiceImpl.saveTools (L370-419)
public Result<String> saveTools(String id, String tools) {
    if (oConvertUtils.isEmpty(id)) return Result.error("插件ID不能为空");
    AiragMcp mcp = this.getById(id);
    if (mcp == null) return Result.error("未找到对应的插件");
    
    String category = mcp.getCategory();
    if (oConvertUtils.isEmpty(category)) category = "mcp";
    if (!"plugin".equalsIgnoreCase(category)) {
        return Result.error("只有插件类型才能保存工具");
    }
    
    mcp.setTools(tools);
    try {
        // 解析 tools JSON 长度，更新 metadata.tool_count
        com.alibaba.fastjson.JSONArray toolsArray = com.alibaba.fastjson.JSONArray.parseArray(tools);
        int toolCount = toolsArray != null ? toolsArray.size() : 0;
        
        JSONObject metadata = new JSONObject();
        if (oConvertUtils.isNotEmpty(mcp.getMetadata())) {
            try {
                JSONObject metadataJson = JSONObject.parseObject(mcp.getMetadata());
                if (metadataJson != null) metadata.putAll(metadataJson);
            } catch (Exception e) {
                log.warn("解析metadata失败，将重新创建: {}", mcp.getMetadata());
            }
        }
        metadata.put("tool_count", toolCount);
        mcp.setMetadata(metadata.toJSONString());
    } catch (Exception e) {
        log.warn("更新工具数量失败: {}", e.getMessage());
    }
    
    this.updateById(mcp);
    return Result.OK("保存成功");
}
```

**注意**：`saveTools` 只对 `category=plugin` 有效（MCP 类型应通过 `sync` 走 `listTools` 自动填充）。它是"插件"类型的 JSON 配置工具，前端可视化编辑工具后调用此接口持久化。

---

## 七、CRUD 其他端点

| 端点 | 行为 |
|------|------|
| `GET /airag/airagMcp/list` | `queryPageList` —— `QueryGenerator.initQueryWrapper` 自动构建 + 分页 |
| `DELETE /airag/airagMcp/delete` | `removeById` —— 直接 MyBatis-Plus 默认 |
| `GET /airag/airagMcp/queryById` | `getById` |
| `GET /airag/airagMcp/exportXls` | 继承 `JeecgController.exportXls` —— autoPoi 按 `@Excel` 注解导出 |
| `POST /airag/airagMcp/importExcel` | 继承 `JeecgController.importExcel` |

---

## 八、完整调用链图：`POST /airag/airagMcp/sync/{id}`

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 前端配置 MCP 信息后点"同步"                                              │
│    POST /airag/airagMcp/sync/{id}                                         │
│    Path: id = airag_mcp 主键                                              │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. AiragMcpController.sync(id)                                            │
│    @RequiresPermissions("airag:mcp:save")                                │
│    return airagMcpService.sync(id)                                        │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. AirragMcpServiceImpl.sync(id)                                          │
│    ├─ getById(id)                                                          │
│    ├─ 校验：category 必须为 mcp                                            │
│    ├─ 解析 headers JSON                                                   │
│    ├─ switch (type):                                                      │
│    │     sse   → HttpMcpTransport.builder().sseUrl(endpoint)...           │
│    │     stdio → aiRagConfigBean.getAllowSensitiveNodes() 检查白名单       │
│    │              Windows: cmd.exe /c  /  Linux/Mac: sh -c                │
│    │              headers 当环境变量                                      │
│    │     http  → StreamableHttpMcpTransport.builder().url(endpoint)...   │
│    ├─ new DefaultMcpClient.Builder().transport(...).build()                │
│    ├─ mcpClient.listTools()        ← ★ 实际连接 MCP 服务器拉工具列表      │
│    ├─ List<ToolSpecification> → Jackson → List<Map<String,Object>>        │
│    ├─ mcp.setTools(jsonList)         ← 写回 airag_mcp.tools                 │
│    ├─ mcp.setSynced(1)              ← 标记已同步                          │
│    ├─ metadata.tool_count = N       ← 写回                                │
│    ├─ updateById(mcp)               ← 持久化                              │
│    └─ finally: mcpClient.close()    ← 关闭连接                            │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. 外部 MCP 服务器 (HTTP/SSE/Stdio)                                         │
│    JSON-RPC over HTTP/SSE/Stdio 协议                                       │
│    GET/POST 请求                                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 九、MCP 在 AI 聊天中的使用

详见 [01-controller-chat.md#四 §4.3 buildPlugins](01-controller-chat.md)。简单总结：

`AIChatHandler.buildPlugins(params)` 从 `params.pluginIds` 列表查 `airag_mcp`：

| category | 处理 |
|---------|------|
| `mcp` | `buildMcpToolProviderWrapper(name, type, endpoint, headers, allowSensitiveNodes)` —— 构造 `McpToolProvider`（包装器保连接引用） |
| `plugin` | `PluginToolBuilder.buildTools(airagMcp, currentHttpRequest)` —— 构造 `ToolSpecification + ToolExecutor`（HTTP 调用型） |

---

## 十、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/controller/AiragMcpController.java` | 200 | 10 个端点 |
| `llm/service/IAirragMcpService.java` | 32 | 接口 |
| `llm/service/impl/AirragMcpServiceImpl.java` | 421 | sync + edit + toggleStatus + saveTools |
| `llm/entity/AiragMcp.java` | 139 | MCP 实体 |
| `llm/handler/AIChatHandler.java` | ~700 | buildPlugins、buildMcpToolProviderWrapper |
| `llm/handler/PluginToolBuilder.java` | 578 | plugin 类型的工具构造 |
| `llm/dto/SaveToolsDTO.java` | — | saveTools 入参 |
| `llm/mapper/AiragMcpMapper.java` | — | mapper |
| `llm/consts/FlowPluginContent.java` | — | 流程插件 key 常量 |
| `common/handler/McpToolProviderWrapper.java` | — | MCP 连接包装器（base-core） |

---

## 十一、下一章

[06-controller-prompts.md](06-controller-prompts.md) — `/airag/prompts/*` 与 `/airag/extData/*` 控制器详解。
