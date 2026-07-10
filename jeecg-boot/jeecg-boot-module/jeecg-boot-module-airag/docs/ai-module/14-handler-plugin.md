# 14 · 插件、解析器、分段器 · 完整解读

> 本文档合并 `PluginToolBuilder` / `JeecgToolsProvider` / `CommandExecUtil` / `TikaDocumentParser` / `WebPageParser` / `CustomDocumentSplitter` 六个工具类。

---

## 一、`PluginToolBuilder` —— 插件 HTTP 调用构建器

文件 `llm/handler/PluginToolBuilder.java`（578 行）。功能：把 `airag_mcp.tools` 字段的 JSON 配置转成 langchain4j 的 `Map<ToolSpecification, ToolExecutor>`，让 LLM 调用。

### 1.1 `buildTools(airagMcp, currentHttpRequest)` 主入口

```java
public static Map<ToolSpecification, ToolExecutor> buildTools(AiragMcp airagMcp, HttpServletRequest currentHttpRequest) {
    Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();
    if (airagMcp == null || oConvertUtils.isEmpty(airagMcp.getTools())) {
        return tools;
    }

    try {
        JSONArray toolsArray = JSONArray.parseArray(airagMcp.getTools());
        if (toolsArray == null || toolsArray.isEmpty()) return tools;
        
        String baseUrl = airagMcp.getEndpoint();
        boolean isEmptyBaseUrl = oConvertUtils.isEmpty(baseUrl);
        if (isEmptyBaseUrl && currentHttpRequest != null) {
            baseUrl = CommonUtils.getBaseUrl(currentHttpRequest);   // 用当前系统地址
        } else if (isEmptyBaseUrl) {
            return tools;
        }
        
        Map<String, String> headersMap = parseHeaders(airagMcp.getHeaders());
        // ★ 鉴权：是否需要加签
        boolean isNeedSign = isEmptyBaseUrl && ToolsNode.Helper.checkNeedSign(headersMap);
        applyAuthConfig(headersMap, airagMcp.getMetadata(), currentHttpRequest);
        
        for (int i = 0; i < toolsArray.size(); i++) {
            JSONObject toolConfig = toolsArray.getJSONObject(i);
            try {
                ToolSpecification spec = buildToolSpecification(toolConfig);
                ToolExecutor executor = buildToolExecutor(toolConfig, baseUrl, headersMap, isNeedSign);
                if (spec != null && executor != null) {
                    tools.put(spec, executor);
                }
            } catch (Exception e) {
                log.error("构建插件工具失败，工具配置: {}", toolConfig.toJSONString(), e);
            }
        }
    } catch (Exception e) {
        log.error("解析插件工具配置失败，插件: {}", airagMcp.getName(), e);
    }
    return tools;
}
```

### 1.2 `buildToolSpecification` —— JSON → langchain4j 工具描述

```java
private static ToolSpecification buildToolSpecification(JSONObject toolConfig) {
    String name = toolConfig.getString("name");
    String description = toolConfig.getString("description");
    if (oConvertUtils.isEmpty(name) || oConvertUtils.isEmpty(description)) return null;

    // ★ description 拼接返回值说明（response.responses）
    StringBuilder fullDescription = new StringBuilder(description);
    JSONArray responses = toolConfig.getJSONArray("responses");
    if (responses != null && !responses.isEmpty()) {
        fullDescription.append("\n\n返回值说明：");
        for (int i = 0; i < responses.size(); i++) {
            JSONObject responseParam = responses.getJSONObject(i);
            if (responseParam == null) continue;
            String paramName = responseParam.getString("name");
            String paramDesc = responseParam.getString("description");
            String paramType = responseParam.getString("type");
            if (oConvertUtils.isEmpty(paramName)) continue;
            fullDescription.append("\n- ").append(paramName);
            if (paramType != null) fullDescription.append(" (").append(paramType).append(")");
            if (paramDesc != null) fullDescription.append(": ").append(paramDesc);
        }
    }

    // ★ 构造 JSON Schema
    JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
    JSONArray parameters = toolConfig.getJSONArray("parameters");
    if (parameters != null && !parameters.isEmpty()) {
        List<String> requiredParams = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            JSONObject param = parameters.getJSONObject(i);
            if (param == null) continue;
            String paramName = param.getString("name");
            String paramDesc = param.getString("description");
            String paramType = param.getString("type");
            if (oConvertUtils.isEmpty(paramName)) continue;
            
            // ★ 按类型分发
            if ("String".equalsIgnoreCase(paramType) || "string".equalsIgnoreCase(paramType)) {
                schemaBuilder.addStringProperty(paramName, paramDesc != null ? paramDesc : "");
            } else if (Number/Integer) {
                schemaBuilder.addNumberProperty(paramName, paramDesc != null ? paramDesc : "");
            } else if (Boolean) {
                schemaBuilder.addBooleanProperty(paramName, paramDesc != null ? paramDesc : "");
            } else {
                schemaBuilder.addStringProperty(paramName, paramDesc != null ? paramDesc : "");
            }
            if (Boolean.TRUE.equals(param.getBooleanValue("required"))) {
                requiredParams.add(paramName);
            }
        }
        if (!requiredParams.isEmpty()) schemaBuilder.required(requiredParams.toArray(new String[0]));
    }
    return ToolSpecification.builder()
        .name(name).description(fullDescription.toString())
        .parameters(schemaBuilder.build())
        .build();
}
```

**逐行解读**：把前端保存的 `airag_mcp.tools` JSON 转成 langchain4j 的 `ToolSpecification`。**5 种 location**：
- `Path` / `Query` / `Header` / `Body` / `Form-Data`

### 1.3 `buildToolExecutor` —— 工具执行器

```java
private static ToolExecutor buildToolExecutor(JSONObject toolConfig, String baseUrl, Map<String, String> defaultHeaders, boolean isNeedSign) {
    String path = toolConfig.getString("path");
    String method = toolConfig.getString("method");
    JSONArray parameters = toolConfig.getJSONArray("parameters");
    if (oConvertUtils.isEmpty(path) || oConvertUtils.isEmpty(method)) return null;

    return (toolExecutionRequest, memoryId) -> {
        try {
            JSONObject args = JSONObject.parseObject(toolExecutionRequest.arguments());
            String url = buildUrl(baseUrl, path, parameters, args);
            HttpMethod httpMethod = parseHttpMethod(method);
            HttpHeaders httpHeaders = buildHttpHeaders(parameters, args, defaultHeaders);
            JSONObject urlVariables = buildUrlVariables(parameters, args);
            Object body = buildRequestBody(parameters, args, httpHeaders);
            
            if (isNeedSign) {
                ToolsNode.Helper.applySignature(url, httpHeaders, urlVariables, body);   // ★ JeecgBoot 内部系统的加签
            }
            
            // ★ 调 RestUtil 发起 HTTP
            ResponseEntity<String> response = RestUtil.request(url, httpMethod, httpHeaders, urlVariables, body, String.class, AiragConsts.DEFAULT_TIMEOUT * 1000);

            return response.getBody() != null ? response.getBody() : "";
        } catch (HttpClientErrorException e) {
            log.error("插件工具HTTP请求失败: {}", e.getMessage(), e);
            // ★ #QQYUN-14577 失败给 LLM 友好提示，让对话继续
            return "插件调用失败（HTTP " + e.getStatusCode() + "）：" + e.getResponseBodyAsString()
                    + "。请继续完成剩余任务。";
        } catch (Exception e) {
            log.error("插件工具执行失败: {}", e.getMessage(), e);
            return "插件工具执行失败：" + e.getMessage() + "。请继续完成剩余任务。";
        }
    };
}
```

### 1.4 `buildUrl` —— URL 构造（路径遍历防护）

```java
private static String buildUrl(baseUrl, path, parameters, args) {
    String fullPath = path;
    if (!path.startsWith("/")) fullPath = "/" + path;
    if (baseUrl.endsWith("/") && fullPath.startsWith("/")) fullPath = fullPath.substring(1);
    String url = baseUrl + fullPath;
    
    if (parameters != null && args != null) {
        for (JSONObject param : parameters) {
            String paramName = param.getString("name");
            String paramLocation = param.getString("location");
            if (!"Path".equalsIgnoreCase(paramLocation)) continue;
            
            Object value = args.get(paramName);
            if (value != null) {
                // ★ #issues/9421 路径遍历防护
                String paramValue = value.toString();
                if (paramValue.contains("..") || paramValue.contains("/") || paramValue.contains("\\")
                        || paramValue.toLowerCase().contains("%2e") || paramValue.toLowerCase().contains("%2f")) {
                    throw new IllegalArgumentException("Path参数包含非法字符: " + paramName);
                }
                url = url.replace("{" + paramName + "}", paramValue);
            }
        }
    }
    return url;
}
```

### 1.5 `applyAuthConfig` —— 鉴权注入

```java
private static void applyAuthConfig(headersMap, metadataStr, currentHttpRequest) {
    if (oConvertUtils.isEmpty(metadataStr)) return;
    try {
        JSONObject metadata = JSONObject.parseObject(metadataStr);
        String authType = metadata.getString("authType");
        if (!"token".equalsIgnoreCase(authType)) return;   // 仅支持 token 鉴权
        
        String tokenParamName = metadata.getString("tokenParamName");
        String tokenParamValue = metadata.getString("tokenParamValue");
        
        // ★ 当前请求 token 自动填
        if (oConvertUtils.isNotEmpty(tokenParamName) && oConvertUtils.isEmpty(tokenParamValue)) {
            try {
                String currentToken = TokenUtils.getTokenByRequest();
                if (oConvertUtils.isEmpty(currentToken) && currentHttpRequest != null) {
                    currentToken = TokenUtils.getTokenByRequest(currentHttpRequest);
                }
                if (oConvertUtils.isNotEmpty(currentToken)) {
                    tokenParamValue = currentToken;
                }
            } catch (Exception e) {
                log.warn("从TokenUtils获取token失败: {}", e.getMessage());
            }
        }
        if (oConvertUtils.isNotEmpty(tokenParamName) && oConvertUtils.isNotEmpty(tokenParamValue)) {
            headersMap.put(tokenParamName, tokenParamValue);
        }
    } catch (Exception e) {
        log.warn("解析授权配置失败: {}", metadataStr, e);
    }
}
```

**用法**：插件（如"调用当前系统用户查询接口"）需要 JWT 透传时，把 `metadataStr` 配成：
```json
{"authType":"token", "tokenParamName":"X-Access-Token"}
```
留空 `tokenParamValue`，代码自动从 `TokenUtils` 取当前请求的 token。

---

## 二、`JeecgToolsProvider` —— JeecgBizToolsProvider 接口

```java
public interface JeecgToolsProvider {
    public Map<ToolSpecification, ToolExecutor> getDefaultTools();
    
    @Getter
    class JeecgLlmTools {
        ToolSpecification toolSpecification;
        ToolExecutor toolExecutor;
        public JeecgLlmTools(ToolSpecification toolSpecification, ToolExecutor toolExecutor) {
            this.toolSpecification = toolSpecification;
            this.toolExecutor = toolExecutor;
        }
    }
}
```

**实现**：`org.jeecg.modules.airag.JeecgBizToolsProvider` 在 `jeecg-module-system/jeecg-system-biz` 模块中（gitnexus 索引可见）——`implements JeecgToolsProvider`：

提供 JeecgBoot 业务系统的默认工具集：
- `create_user(username, realname)` —— 创建用户（#QQYUN-13565）
- `query_user(name)` —— 查询用户
- `query_dept(name)` —— 查询部门
- `query_role(name)` —— 查询角色

注册方式：被 `AIChatHandler.chat()` 调 `JeecgBizToolsProvider.getDefaultTools()`，把工具注入 `params.tools`。

---

## 三、`CommandExecUtil` —— Shell 命令安全执行

文件 `llm/handler/CommandExecUtil.java`（169 行）。用于 `EmbeddingHandler.parseFileByMinerU` 调 magic-pdf。

### 3.1 Shell 注入字符黑名单

```java
private static final Pattern SHELL_INJECTION_PATTERN = 
    Pattern.compile("[&|;<>`$!\\\\\\r\\n]");      // 元字符
private static final Pattern FILENAME_INJECTION_PATTERN = 
    Pattern.compile("[&|;<>`$!\"'\\r\\n]");    // 文件名更严（去掉了 \）

public static void validateArg(String arg) {
    if (arg != null && SHELL_INJECTION_PATTERN.matcher(arg).find()) {
        throw new IllegalArgumentException("命令参数包含非法字符，已拒绝执行: " + arg);
    }
}

public static void validateFilePath(String filePath) {
    if (filePath != null && FILENAME_INJECTION_PATTERN.matcher(filePath).find()) {
        throw new IllegalArgumentException("文件路径包含非法字符，已拒绝处理: " + filePath);
    }
}
```

### 3.2 `execCommand(command[], args[])`

```java
public static String execCommand(String[] command, String[] args) throws IOException {
    if (null == command || command.length == 0) {
        throw new IllegalArgumentException("命令不能为空");
    }
    if (null != args && args.length > 0) {
        // ★ 校验每一个参数
        for (String arg : args) {
            validateArg(arg);
        }
        command = (String[]) ArrayUtils.addAll(command, args);
    }
    
    // ★ 不经过系统 Shell，直接传命令数组
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(false);
    
    Process process = null;
    try {
        process = Runtime.getRuntime().exec(command);
        try (ByteArrayOutputStream resultOutStream = new ByteArrayOutputStream();
             InputStream processInStream = new BufferedInputStream(process.getInputStream())) {
            new Thread(new InputStreamRunnable(process.getErrorStream(), "ErrorStream")).start();
            int num;
            byte[] bs = new byte[1024];
            while ((num = processInStream.read(bs)) != -1) {
                resultOutStream.write(bs, 0, num);
                String stepMsg = new String(bs);
                // ★ 检测 magic-pdf 等待输入的 prompt
                if (stepMsg.contains("input any key to continue...")) {
                    process.destroy();
                }
            }
            return resultOutStream.toString();
        }
    } catch (IOException e) {
        throw e;
    } finally {
        if (process != null) process.destroy();
    }
}
```

**安全设计**：
1. **数组直接 exec，不经 `cmd.exe /c` 或 `sh -c`** —— 这是 Java 防御命令注入的标准做法
2. **每个 arg 都要过 `validateArg`** 黑名单
3. **超时守护**：进程 destroy + finally block

---

## 四、`TikaDocumentParser` —— Apache Tika + POI 自研适配器

文件 `llm/document/TikaDocumentParser.java`（294 行，反编译自 .class）。

**为什么自研**：注释里说了——"jeecgboot 目前不支持 poi 5.x，langchain4j 内置的 TikaDocumentParser 用的是 poi 5.x，所以自己实现。"

### 4.1 主入口 `parse(File file)`

```java
public Document parse(File file) {
    AssertUtils.assertNotEmpty("请选择文件", file);
    String fileName = file.getName().toLowerCase();
    String ext = FilenameUtils.getExtension(fileName);
    
    if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".pdf")) {
        // ★ Tika 路径
        try (InputStream isForParsing = new FileInputStream(file)) {
            return extractByTika(isForParsing);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    } else if (FILE_SUFFIX.contains(ext.toLowerCase())) {
        // ★ #QQYUN-14261 POI 路径
        return parseDocExcelPdfUsingApachePoi(file);
    } else {
        throw new IllegalArgumentException("不支持的文件格式: " + FilenameUtils.getExtension(fileName));
    }
}
```

### 4.2 支持的文件格式

**Tika 路径**：`txt` / `md` / `pdf`  
**POI 路径**（`FILE_SUFFIX = {docx, doc, pptx, ppt, xlsx, xls}`）：Office 全家桶

### 4.3 `extractByTika(InputStream)` 内部分发

```java
private Document extractByTika(InputStream inputStream) {
    try {
        Parser parser = (Parser) this.parserSupplier.get();          // AutoDetectParser
        ContentHandler contentHandler = (ContentHandler) this.contentHandlerSupplier.get();   // BodyContentHandler(-1)
        Metadata metadata = (Metadata) this.metadataSupplier.get();
        ParseContext parseContext = (ParseContext) this.parseContextSupplier.get();
        parser.parse(inputStream, contentHandler, metadata, parseContext);
        String text = contentHandler.toString();
        if (Utils.isNullOrBlank(text)) {
            throw new BlankDocumentException();
        } else {
            return Document.from(text);
        }
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

`BodyContentHandler(-1)` —— 写入限制设成 -1（无限制），因为某些大 PDF 默认 10 万字符会截断。

### 4.4 `parseDocExcelPdfUsingApachePoi`

```java
public Document parseDocExcelPdfUsingApachePoi(File file) {
    try (InputStream inputStream = new FileInputStream(file)) {
        ApachePoiDocumentParser parser = new ApachePoiDocumentParser();
        Document document = parser.parse(inputStream);
        if (document == null || Utils.isNullOrBlank(document.text())) return null;
        return document;
    } catch (BlankDocumentException e) {
        return null;   // 空文档不算错
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}
```

直接用 langchain4j 的 `ApachePoiDocumentParser`（与最新 langchain4j 版本兼容，因为它只用 POI 的低阶 API）。

---

## 五、`WebPageParser` —— Jsoup 网页抓取

文件 `llm/document/WebPageParser.java`。

```java
public class WebPageParser {
    public String parseToMarkdown(String url) {
        // 1. Jsoup 连 URL，抓 HTML
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...")
            .timeout(10 * 1000)
            .get();
        // 2. 保留主要 HTML 结构
        // 3. 转为 Markdown (用 turndown 等类)
        return markdown;
    }
}
```

被 `EmbeddingHandler.parseWebPage` 调用。

---

## 六、`CustomDocumentSplitter` —— 自定义分段器

文件 `llm/splitter/CustomDocumentSplitter.java`。继承 langchain4j 的 `DocumentSplitter`。

### 6.1 构造

```java
public class CustomDocumentSplitter extends DocumentSplitter {
    private final String splitChar;            // 分隔符
    private final int maxSegmentSize;          // 最大段长
    private final int overlapSize;             // 重叠
    private final String textRules;            // 文本预处理规则
    
    public CustomDocumentSplitter(String textRules, String splitChar, int maxSegmentSize, int overlapSize) {
        super(maxSegmentSize, overlapSize);   // 调父类
        this.splitChar = splitChar;
        this.maxSegmentSize = maxSegmentSize;
        this.overlapSize = overlapSize;
        this.textRules = textRules;
    }
    
    @Override
    public List<TextSegment> split(Document document) {
        // 1. 文本预处理（cleanSpaces / removeUrlsEmails）
        String text = preprocess(document.text(), textRules);
        // 2. 按 splitChar 切
        // 3. 超过 maxSegmentSize 的段递归切
        // 4. 保证 overlap 大小
        // 5. 返回 List<TextSegment>
    }
}
```

### 6.2 文本预处理（textRules）

| `textRules` 值 | 行为 |
|---------------|------|
| `cleanSpaces` | 替换连续空格 / 换行 / 制表符为单个 |
| `removeUrlsEmails` | 删除所有 URL 和邮箱地址 |

被 `EmbeddingHandler.createDocumentSplitter` 调用。

---

## 七、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/handler/PluginToolBuilder.java` | 578 | 插件 HTTP 调用构造 |
| `llm/handler/JeecgToolsProvider.java` | 43 | JeecgBizToolsProvider 接口 |
| `JeecgBizToolsProvider.java` (system-biz) | — | 默认工具实现（用户/部门/角色查询） |
| `llm/handler/CommandExecUtil.java` | 169 | Shell 命令安全执行 |
| `llm/document/TikaDocumentParser.java` | 294 | Tika + POI 解析 |
| `llm/document/WebPageParser.java` | — | Jsoup |
| `llm/splitter/CustomDocumentSplitter.java` | — | 自定义分段 |
| `common/consts/AiragConsts.java` | — | `DEFAULT_TIMEOUT` |
| `config/AiRagConfigBean.java` | — | yml 配置 |

---

## 八、下一章

[16-data-entities.md](16-data-entities.md) — 实体、Mapper、字段完整梳理。
