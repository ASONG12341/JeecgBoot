# 00 · 模块全景与调用链

## 一、包结构图

```
jeecg-boot-module-airag/
└── src/main/java/org/jeecg/modules/airag/
    ├── JeecgAiRagApplication.java          # 可独立启动的 SpringBoot 入口
    │
    ├── api/                                 # 跨模块 BaseApi 实现
    │   └── AiragBaseApiImpl.java
    │
    ├── app/
    │   ├── consts/                          # AiAppConsts / Prompts 常量
    │   ├── controller/                      # 应用 + 聊天 控制器
    │   ├── entity/                          # AiragApp + AppVariableVo 等
    │   ├── enums/                           # ImageEditEnum / ImageSizeEnum
    │   ├── mapper/
    │   ├── service/                         # IAiragAppService / IAiragChatService / IAiragVariableService
    │   ├── service/impl/                    # 实现
    │   ├── vo/                              # ChatSendParams / AppDebugParams 等
    │   └── demo/                            # 示例代码
    │
    ├── llm/                                 # ★ 核心子包：模型 / 知识库 / 文档 / 工具
    │   ├── config/                          # EmbedStoreConfigBean / KnowConfigBean
    │   ├── consts/                          # LLMConsts / FlowPluginContent
    │   ├── controller/                      # AiragKnowledgeController / AiragModelController /
    │   │                                   # AiragMcpController / AiragBaseApiController
    │   ├── document/                        # TikaDocumentParser / WebPageParser
    │   ├── dto/
    │   ├── entity/                          # AiragKnowledge / AiragKnowledgeDoc / AiragModel / AiragMcp
    │   ├── handler/                         # ★ AIChatHandler / EmbeddingHandler / JeecgToolsProvider（接口）/
    │   │                                   #   PluginToolBuilder / CommandExecUtil
    │   ├── mapper/
    │   ├── service/                         # I*Service 接口
    │   ├── service/impl/                    # 实现
    │   └── splitter/                        # CustomDocumentSplitter
    │
    ├── ocr/                                 # OCR（Redis 存储）
    ├── prompts/                             # 提示词 + 评估器（airag_ext_data）
    ├── video/                               # 视频生成（JeecgBoot 视频模块）
    ├── voice/                               # 文生语音
    └── wordtpl/                             # Word 模板（docx 生成/解析）
```

**跨模块类（不在本模块内）：**

| 类 | 位置 | 来源 |
|----|------|------|
| `IAiragBaseApi` | `jeecg-module-system/jeecg-system-api/jeecg-system-cloud-api` 与 `jeecg-system-local-api` 两份 | base-system |
| `AiragBaseApiFallback` / `AiragBaseApiFallbackFactory` | 同上 | base-system |
| `AiragFlowDTO` | `jeecg-boot-base-core/src/main/java/org/jeecg/common/api/dto/` | base-core |
| `JeecgBizToolsProvider` | `jeecg-module-system/jeecg-system-biz/src/main/java/org/jeecg/modules/airag/` | system-biz |
| LLM 引擎底层（`LLMHandler` / `AiModelFactory` / `AiModelOptions`） | `org.jeecg.ai` 包 | base-core |

注：本目录下 `airag.flow.*` 与 `airag.common.*` 子包**源码未在本仓库内**，但被 `AiragChatServiceImpl` import——可能后续重构中迁移走了，调用方需要时直接 grep `import org.jeecg.modules.airag.flow`。

---

## 二、调用链全景（5 大典型场景）

### 2.1 场景 A：用户在前端聊天框发送一条消息（流式 SSE）

```
[A] 用户在 chat.vue 点"发送"
    │
    ▼ Axios POST /airag/chat/send
    │   Body: ChatSendParams { content, conversationId?, topicId?, appId?, images?, files?, flowInputs? }
    │
[1] AiragChatController.send                              ── 入参校验后调用 Service
    │   file: AiragChatController.java#55-59
    ▼
[2] AiragChatServiceImpl.send                              ── 建立/获取会话、保存变量、调 doChat
    │   file: AiragChatServiceImpl.java#140-176
    │
    ├─ getOrCreateChatConversation()                        ── Redis 读 / airag:chat:conversation:{appId}:{topicId}
    ├─ saveVariables(app)                                  ── AiragVariableService.initVariable
    │
    ▼
[3] doChat(chatConversation, topicId, params)
    │
    ├─ 加载 app（如果 appId 非空）
    ├─ 加载 prompt、拼接 messages
    │
    ├─ if (app.flowId 非空) → AiragFlowService.run(flow)   ── 走工作流编排
    │      │
    │      ├─ 节点循环：
    │      │   ├─ LLM 节点 → AIChatHandler.chat           ── 流式调大模型
    │      │   ├─ 工具节点 → ToolSpecification.execute     ── HTTP 调用或本地方法
    │      │   ├─ 变量节点 → 用 appId/memoryId 取变量 / 记忆
    │      │   └─ ...
    │      │
    │      └─ 流式输出 (SSE EventData)
    │
    └─ else → 直接 AIChatHandler.chatByDefaultModel(messages, params)
        │
        ├─ AIChatHandler.mergeParams(airagModel, params)
        │      ├─ 注入 provider / modelName / baseUrl / apiKey / secretKey
        │      ├─ 注入 temperature / topP / maxTokens / ...
        │      ├─ if (knowIds 非空) → EmbeddingHandler.getQueryRouter(knowIds, topNumber, similarity)
        │      └─ if (pluginIds 非空) → AIChatHandler.buildPlugins(params)
        │             ├─ "mcp"  → buildMcpToolProviderWrapper (langchain4j McpToolProvider)
        │             └─ "plugin" → PluginToolBuilder.buildTools (HTTP 调用型 ToolExecutor)
        │
        ├─ injectThinkingPlaceholderIfNeeded(messages, modelName)    ── DeepSeek 推理模型占位
        │
        └─ llmHandler.chat(messages, params)                          ── 底层 LLM SDK
               │
               ▼ (langchain4j 调用大模型 + RAG 自动检索 + 工具调用)
               ▼ TokenStream 返回增量
    │
    ▼
[4] saveChatConversation(...)                             ── 写回 Redis
[5] 关闭 SSE emitter                                       ── 从 AiragLocalCache 移除
[6] HTTP 响应：SSE 流（multipart/x-sse 格式，事件类型 EVENT_MESSAGE / EVENT_MESSAGE_TOOL / EVENT_MESSAGE_END）
    │
    ▼
[B] 前端 useChat.ts 解析 SSE 事件，渲染 AI 回复
```

### 2.2 场景 B：上传 PDF → 知识库向量化

```
[C] 用户上传文件
    │
    ▼ POST /airag/knowledge/doc/edit
    │   Body: { knowledgeId, title, type="file", metadata:{filePath:"https://..."} }
    │
[1] AiragKnowledgeController.doc/edit
    │   file: AirragKnowledgeController.java#215-219
    ▼
[2] AirragKnowledgeDocServiceImpl.editDocument
    │   ├─ 校验 docs 类型（text 要 content；file 要 metadata.filePath）
    │   ├─ status=DRAFT → saveOrUpdate(airagKnowledgeDoc)
    │   └─ rebuildDocument(airagKnowledgeDoc.getId())             ── ★ 同一 service
    │         │
    │         ├─ 文档状态 → BUILDING
    │         ├─ updateBatchById(...)                              ── 持久化
    │         ├─ CompletableFuture.runAsync(                       ── 异步向量化
    │         │     new ExecutorService 固定 10 线程
    │         │
    │         ▼ 异步线程内：
    │         ├─ EmbeddingHandler.embeddingDocument(knowId, doc)
    │         │      │
    │         │      ├─ getEmbedModelData(modelId)                ── 模型未激活回退 yml 默认
    │         │      │
    │         │      ├─ parseFile(doc) 或 parseWebPage(doc) 或 原 content
    │         │      │      ├─ TikaDocumentParser
    │         │      │      │     - txt/md/pdf → Tika AutoDetectParser
    │         │      │      │     - doc/x/docx/ppt/x/xlsx/x → Apache POI
    │         │      │      └─ WebPageParser                       ── Jsoup → Markdown
    │         │      │
    │         │      ├─ parseFileByMinerU(doc)                     ── 启用 magic-pdf conda
    │         │      │      └─ CommandExecUtil.execCommand(["conda","run","-n",env,"magic-pdf"], args)
    │         │      │
    │         │      ├─ title + "\n\n" + content
    │         │      │
    │         │      ├─ createDocumentSplitter(doc)                ── 自定义分段
    │         │      │      ├─ if "useKnowledgeDefault" → 读知识库 metadata
    │         │      │      ├─ if "custom" + 自定义分隔符 → CustomDocumentSplitter
    │         │      │      └─ else DocumentSplitters.recursive(maxSegment=1000, overlapSize=50)
    │         │      │
    │         │      ├─ splitDocumentPreservingHtmlTables          ── #9551 表格保留
    │         │      │
    │         │      ├─ embeddingModel.embedAll(segments)           ── 调大模型
    │         │      │
    │         │      └─ embeddingStore.addAll(embeddings, segments) ── PgVector 存储
    │         │             └─ embeddingStore.removeAll(metadataKey(docId).isEqualTo(doc.id))  ── 删旧
    │         │
    │         ├─ status BUILDING → COMPLETE
    │         │
    │         └─ catch (Throwable) → handleDocBuildFailed
    │               └─ status=FAILED + metadata.failedReason
    │
    └─ HTTP 响应：{"success":true, "message":"操作成功"}
```

### 2.3 场景 C：聊天时启用 RAG 检索

```
[D] LLM 调用前
[1] AIChatHandler.mergeParams(airagModel, params)
    │
    ├─ if (params.knowIds 非空)
    ▼
[2] EmbeddingHandler.getQueryRouter(knowIds, topNumber, similarity)
    │      │
    │      ├─ for knowId in knowIds:
    │      │      ├─ read AiragKnowledge.getByIdIgnoreTenant(knowId)
    │      │      ├─ getEmbedModelData(knowId.embedId) → AiragModel
    │      │      ├─ createEmbeddingModel(AiModelOptions)
    │      │      ├─ getEmbedStore(airagModel) → PgVectorEmbeddingStore
    │      │      │
    │      │      ├─ filter:
    │      │      │      ├─ if (knowledge.type == "memory") → username 隔离（QQYUN-14265）
    │      │      │      └─ else 仅 knowledgeId 过滤
    │      │      │
    │      │      └─ EmbeddingStoreContentRetriever.builder()...
    │      │
    │      └─ return DefaultQueryRouter(retrievers)
    │
    ▼ params.queryRouter = router
[3] llmHandler.chat(messages, params)
    │      └─ langchain4j 内部：
    │             ├─ Router 根据 queryRouter 路由到对应 retriever
    │             ├─ Retriever 在向量库检索 topK 个匹配 segment
    │             ├─ 把 segment 拼到 messages 的 system message
    │             └─ 调大模型
    │
    ▼ TokenStream 输出回复
```

### 2.4 场景 D：MCP 工具调用（AI 自动调用外部服务）

```
[E] 聊天请求带 pluginIds=["xxx-mcp-id"]
[1] AIChatHandler.buildPlugins(params)
    │
    ├─ for pluginId in pluginIds:
    │      ├─ airagMcpMapper.selectById(pluginId)               ── AiragMcp
    │      │
    │      ├─ if (mcp.category == "mcp"):
    │      │      └─ buildMcpToolProviderWrapper(name, type, endpoint, headers)
    │      │             ├─ if (type == "sse") → HttpMcpTransport
    │      │             ├─ if (type == "stdio") → StdioMcpTransport (受 yml 白名单控制)
    │      │             └─ if (type == "http") → StreamableHttpMcpTransport
    │      │             └─ return McpToolProvider + McpToolProviderWrapper
    │      │
    │      └─ else if (mcp.category == "plugin"):
    │             └─ PluginToolBuilder.buildTools(airagMcp, currentHttpRequest)
    │                    ├─ tools JSON → ToolSpecification
    │                    │      ├─ parameters 解析为 JsonObjectSchema
    │                    │      ├─ responses 拼接到 description
    │                    │      └─ required 字段
    │                    │
    │                    └─ ToolExecutor：HTTP 调用
    │                           ├─ baseUrl 为空 → 用当前请求 baseUrl
    │                           ├─ applyAuthConfig ── token 自动注入
    │                           ├─ buildUrl ── 路径遍历防护 (#9421)
    │                           ├─ RestUtil.request(method, headers, urlVars, body)
    │                           └─ 失败返回：'请继续完成剩余任务'
    │
    ├─ params.setMcpToolProviders(mcpToolProviders)
    └─ params.setTools(pluginTools)
[2] llmHandler.chat(messages, params) ── langchain4j 自动让大模型选 tool 并执行
```

### 2.5 场景 E：上传 ZIP 文件批量导入知识库

```
POST /airag/knowledge/doc/import/zip  (knowId + file)
    │
    ▼
AirragKnowledgeDocServiceImpl.importDocumentFromZip
    │
    ├─ SsrfFileTypeFilter.checkUploadFileType(zipFile)        ── 防护 1
    ├─ 检查 .zip 后缀
    ├─ CommonUtils.uploadLocal                                   ── 上传到 uploadpath/knowId/uuid.zip
    ├─ 读 knowledge.metadata → 看有没有知识库默认分段策略 (#14932)
    │
    ├─ unzipFile(zipPath, sourcesPath, fileConsumer)             ── ★ ★ ★
    │      │
    │      ├─ entryCount 检查 (≤10000)                           ── 防 zip bomb (#14932 引申)
    │      ├─ shouldSkipZipEntry                                 ── 跳过 .DS_Store / __MACOSX / ._ (#9551)
    │      ├─ safeResolve(targetDir, entryName)                  ── 防 Zip Slip
    │      ├─ copyLimited(stream, output, MAX_FILE_SIZE=150MB)  ── 单文件 150MB 上限
    │      └─ totalUnzippedSize 累加检查 (≤1GB)                 ── 总解压 1GB 上限
    │
    ├─ for each uploadedFile:
    │      ├─ SUPPORT_DOC_TYPE 过滤 ({txt,pdf,docx,doc,pptx,ppt,xlsx,xls,md})
    │      ├─ 构造 AiragKnowledgeDoc(type="file", metadata.filePath=相对路径)
    │      ├─ if (useKnowledgeDefault) → metadata.useKnowledgeDefault=true
    │      └─ docList.add(...)
    │
    ├─ saveBatch(docList)
    └─ rebuildDocument(join docIds)                            ── 走场景 B 的异步向量化流
```

---

## 三、模块依赖图（精简版）

```
┌────────────────────────────────────────────────────────────────────────┐
│                            Frontend (jeecgboot-vue3)                 │
│  src/views/super/airag/                                              │
│  ├── aiapp/chat/chat.vue → useChat.ts (SSE 客户端)                  │
│  ├── aiknowledge/  → AiKnowledgeBaseList.vue                        │
│  ├── aiprompts/    → 评估器 / 数据集                                 │
│  └── ... (12 个子模块)                                                │
└──────────────┬─────────────────────────────────────────────────────────┘
               │ HTTP / SSE
               ▼
┌────────────────────────────────────────────────────────────────────────┐
│   jeecg-boot-module-airag (本模块)                                     │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐               │
│   │Controller│→│  Service │→│  Handler │→│  Mapper  │               │
│   └──────────┘  └──────────┘  └──────────┘  └─────┬────┘               │
│                                                  │ MyBatis-Plus       │
└──────────────────────────────────────────────────┼────────────────────┘
                                                   ▼
                                          ┌─────────────────┐
                                          │ MySQL           │
                                          │ (9 张业务表)     │
                                          └─────────────────┘
                                                   │
                          ┌────────────────────────┼────────────────────────┐
                          │                        │                        │
                          ▼                        ▼                        ▼
              ┌───────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
              │ PostgreSQL        │   │ Redis                 │   │ 文件存储              │
              │ + pgvector         │   │ - 会话                │   │ - uploadpath 本地    │
              │ - embed_store      │   │ - 文章版本            │   │ - MinIO              │
              │ - table=embeddings │   │ - 变量                │   │ - Aliyun OSS         │
              │ + 维度后缀          │   │ - 海报任务 (TTL 1h)   │   │                      │
              └───────────────────┘   │ - OCR 配置            │   └──────────────────────┘
                                       └──────────────────────┘
                                                   ▲
                                                   │
┌──────────────────────────────────────────────────┴────────────────────┐
│   base-core (jeecg-boot-base-core)                                     │
│   ├── dev.langchain4j ── 大模型调用 / 向量 / MCP                       │
│   ├── pgvector ── PostgreSQL 向量支持                                  │
│   ├── IAiragBaseApi 接口定义                                           │
│   └── AiRagConfigBean（敏感节点白名单）                                │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 四、章节文件对应源码范围

| 章节文件 | 涉及源码文件 | 文件数 |
|---------|------------|--------|
| 01-controller-chat | AiragChatController + AiragChatServiceImpl + AiragAppController + AiragAppServiceImpl + AiragVariableServiceImpl + AIChatHandler + llmHandler + ChatSendParams + ChatConversation + ... | ~15 |
| 02-controller-app | AiragAppController + AiragAppServiceImpl + AiragApp + Prompts + AppDebugParams + AppVariableVo + AiArticleWriteVersionVo | ~10 |
| 03-controller-knowledge | AiragKnowledgeController + AirragKnowledgeDocServiceImpl + AirragKnowledgeServiceImpl + EmbeddingHandler + TikaDocumentParser + WebPageParser + CustomDocumentSplitter + AirragKnowledge + AirragKnowledgeDoc + EmbedStoreConfigBean | ~12 |
| 04-controller-model | AiragModelController + AiragModelServiceImpl + AiragModel | ~5 |
| 05-controller-mcp | AiragMcpController + AirragMcpServiceImpl + AirragMcp + LLMConsts + JeecgBizToolsProvider（system-biz） | ~6 |
| 06-controller-prompts | AiragPromptsController + AirragPromptsServiceImpl + AiragExtDataController + AirragExtDataServiceImpl + AiragPrompts + AiragExtData + AiPromptsConsts | ~8 |
| 07-controller-ocr | AiOcrController + AiOcr | 2 |
| 08-controller-video | VideoGenerationController + VideoGenerationServiceImpl + VideoGenerateVo + VideoTaskResultVo | ~4 |
| 09-controller-voice | VoiceController + VoiceServiceImpl + VoiceApiHelper + VoiceGenerateVo + VoiceResultVo | ~5 |
| 10-controller-word | AigcWordTemplateController + AigcWordTemplateServiceImpl + WordTplUtils + WordUtil + 所有 DTO | ~15 |
| 11-controller-baseapi | AiragBaseApiController + AiragBaseApiImpl + IAiragBaseApi（local + cloud）| ~5 |

---

## 五、关键性能与稳定性数据

| 项目 | 数值 |
|------|------|
| 向量化线程池 | `Executors.newFixedThreadPool(10)` |
| ZIP 单文件上限 | 150 MB |
| ZIP 总解压上限 | 1 GB |
| ZIP Entry 数量上限 | 10000 |
| 提示词重试温度 | 0.8 / 0.7（不同场景） |
| TopK 默认 | 5 |
| 相似度默认阈值 | 0.75 |
| 文本截断 | DEFAULT_MAX_OUTPUT_CHARS=4000 |
| 聊天文件大小上限 | 20000 chars |
| 聊天文件数上限 | 3 |
| 海报任务 TTL | 3600 s |
| MCP HTTP 超时 | 60 min（StreamableHttpMcpTransport） |
| HTTP 工具超时 | AiragConsts.DEFAULT_TIMEOUT × 1000 ms |

---

## 六、安全边界

- **路径遍历**：`SsrfFileTypeFilter.checkPathTraversal` + 自实现 `safeResolve` (ZIP) + Path 参数拒绝 `..` `/` `\` `%2e` `%2f`
- **命令注入**：`CommandExecUtil.validateArg` + `validateFilePath` 正则阻断 `&|;<>\`$!`
- **租户隔离**：所有删除 / 编辑接口走 `MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL`，tenantId 取自 JWT
- **Shell 元字符**：`SHELL_INJECTION_PATTERN = [&|;<>\`$!\r\n]`（Windows + Unix 双生效）
- **SSRF**：`SsrfFileTypeFilter.checkUploadFileType`
- **JWT 透传**：插件 HTTP 调用自动注入 `X-Access-Token` 头
- **ZIP bomb**：entryCount + totalUnzippedSize + 单文件大小三层防护

详细对照见 [18-security.md](18-security.md)。

---

## 七、下一章

[01-controller-chat.md](01-controller-chat.md) — 从 `/airag/chat/send` 到 AI 响应的完整调用链逐行解读。
