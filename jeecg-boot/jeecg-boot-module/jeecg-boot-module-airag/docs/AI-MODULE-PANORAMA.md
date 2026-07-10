# JeecgBoot AI 模块全景文档

> 本文档基于 gitnexus 索引与源码全量梳理，覆盖 jeecg-boot-module-airag 全部 34 个 Java 文件、12 个 Controller、9 个 Service 接口、9 个实体，以及前端 vue 页面。

---

## 一、模块总览

**位置**：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/`（独立 Maven 模块，按 13 个子包组织）。

**依赖的核心第三方**：`dev.langchain4j`（LLM / Embedding / MCP）、`com.alibaba.fastjson[2]`、`commons-io`、`tika`、`jsoup`、`pgvector`、`hutool`、`apache poi`。

**应用入口**：`org.jeecg.JeecgAiRagApplication`（独立可启动，与系统单体共享 `jeecg-boot-base-core`）。`org.jeecg.common.airag.api.IAiragBaseApi` 是与 `jeecg-module-system` 解耦的 RPC 接口（通过 `@Primary` 注入）。

**索引元数据**：已建索引 34161 符号、74940 关系、300 执行流；本模块贡献约 90 个 Java 符号。

---

## 二、子领域（13 个包）

| 包 | 职责 | 关键文件 |
|----|------|---------|
| `airag.app` | AI 应用（聊天 / 调试 / 提示词 / 写作 / 海报） | `AiragAppController`、`AiragChatController`、`IAiragAppService`、`IAiragChatService`、`IAiragVariableService` |
| `airag.llm` | 模型配置 / 知识库 / 文档 / MCP / 插件 / 向量化 | `AiragModelController`、`AiragKnowledgeController`、`AiragMcpController`、`AIChatHandler`、`EmbeddingHandler`、`JeecgToolsProvider`、`PluginToolBuilder` |
| `airag.llm.document` | 文档解析器（Tika + Jsoup） | `TikaDocumentParser`、`WebPageParser` |
| `airag.llm.splitter` | 自定义分段器 | `CustomDocumentSplitter` |
| `airag.llm.config` | 向量库 / MinerU 配置 Bean | `EmbedStoreConfigBean`、`KnowConfigBean` |
| `airag.llm.consts` | LLM 常量 | `LLMConsts`、`FlowPluginContent` |
| `airag.llm.handler` | LLM / 嵌入 / 工具 / 命令执行 | `AIChatHandler`、`EmbeddingHandler`、`CommandExecUtil`、`JeecgToolsProvider`、`PluginToolBuilder` |
| `airag.api` | 跨模块 baseApi 实现 | `AiragBaseApiImpl` |
| `airag.common` | 跨包抽象（接口、常量、VO） | `IAIChatHandler`、`IEmbeddingHandler`、`AIChatParams`、`McpToolProviderWrapper`、`KnowledgeSearchResult`、`MessageHistory`、`AiragLocalCache`、`AiragConsts` |
| `airag.flow` | AI 流程引擎 | `AiragFlow`、`IAiragFlowService`、`FlowConsts`、`JeecgFlowContext`、`ToolsNode`、`BraveSearchToolBuilder` |
| `airag.ocr` | OCR 模型配置（Redis 存储） | `AiOcrController`、`AiOcr` |
| `airag.prompts` | 提示词市场 / 评估器 / 轨迹 | `AiragPromptsController`、`AiragExtDataController`、`AiragPrompts`、`AiragExtData` |
| `airag.video` | AI 视频生成（提交 / 查询 / 配音） | `VideoGenerationController`、`IVideoGenerationService` |
| `airag.voice` | 文生语音（同步 / 异步） | `VoiceController`、`IVoiceService`、`VoiceApiHelper` |
| `airag.wordtpl` | Word 模板生成 / 解析 | `AigcWordTemplateController`、`WordTplUtils`、`WordUtil` |
| `airag.app.enums` | 图片尺寸 / 图像编辑枚举 | `ImageSizeEnum`、`ImageEditEnum` |

> `airag.flow.*` 是 AI 流程引擎（langchain4j 之上自研的 DSL 编排器），由 `airag.chat` 调用。

---

## 三、Controller 层（12 个 REST Controller，80+ 端点）

### 3.1 `/airag/app` — AI 应用（`AiragAppController`）

继承 `JeecgController<AiragApp, IAiragAppService>`。

| 方法 / 路径 | 鉴权 | 入参 | 出参 |
|------------|------|------|------|
| `GET /airag/app/list` | 无 | `AiragApp` + `pageNo/pageSize` | `Result<IPage<AiragApp>>` |
| `GET /airag/app/listDict` | 无 | `AiragApp`（过滤 type） | `Result<List<DictModel>>`（按 create_time desc） |
| `PUT,POST /airag/app/edit` | `airag:app:edit` | `AiragApp`（含 tenant 校验，#9462） | `Result<String>` |
| `POST /airag/app/release` | 无 | `id`、`release:Boolean` | 状态置 `release` / `enable` |
| `DELETE /airag/app/delete` | `airag:app:delete` | `id`（带 tenant 校验 #8337） | `Result<String>` |
| `GET /airag/app/queryById` | `@IgnoreAuth` | `id` | `Result<AiragApp>` |
| `POST /airag/app/debug` | 无 | `AppDebugParams`（含完整 `AiragApp` 对象） | `SseEmitter`（流式） |
| `GET /airag/app/prompt/generate` | 无 | `prompt` | `Result<?>`（阻塞） |
| `POST /airag/app/prompt/generate` | 无 | `prompt` | `SseEmitter`（SSE 流式） |
| `POST /airag/app/prompt/generateMemoryByAppId` | 无 | `variables`、`memoryId` | `SseEmitter` |
| `POST /airag/app/save/article/write` | 无 | `AiArticleWriteVersionVo` | `Result<String>` |
| `DELETE /airag/app/delete/article/write` | 无 | `version` | `Result<String>` |
| `GET /airag/app/list/article/write` | 无 | — | `Result<List<AiArticleWriteVersionVo>>` |

### 3.2 `/airag/chat` — AI 对话（`AiragChatController`，全部 `@IgnoreAuth`）

| 方法 / 路径 | 入参 | 出参 |
|------------|------|------|
| `POST /airag/chat/send` | `ChatSendParams` | `SseEmitter` |
| `GET /airag/chat/send` | `content`、`conversationId?`、`topicId?`、`appId?`（旧浏览器兼容） | `SseEmitter` |
| `GET /airag/chat/init` | `id` (appId) | `Result<?>`（忽略租户） |
| `GET /airag/chat/conversations` | `appId?` | `Result<?>` |
| `GET /airag/chat/getConversationsByType` | `sessionType` | `Result<?>` |
| `DELETE /airag/chat/conversation/{id}` | path id | `Result<?>` |
| `DELETE /airag/chat/conversation/{id}/{sessionType}` | path | `Result<?>` |
| `PUT /airag/chat/conversation/update/title` | `ChatConversation` | `Result<?>` |
| `GET /airag/chat/messages` | `conversationId`、`sessionType?` | `Result<?>` |
| `GET /airag/chat/messages/clear/{conversationId}` | path | `Result<?>` |
| `GET /airag/chat/messages/clear/{conversationId}/{sessionType}` | path | `Result<?>` |
| `GET /airag/chat/receive/{requestId}` | path | `SseEmitter`（重连） |
| `GET /airag/chat/stop/{requestId}` | path | `Result<?>`（停止 SSE） |
| `POST /airag/chat/upload` | `file` (multipart) | `Result<?>`（路径走 `jeecg.uploadType`） |
| `POST /airag/chat/genAiPoster` | `AiDrawGenerateVo` | `Result<String>`（同步图片 URL） |
| `POST /airag/chat/genAiPosterAsync` | `AiDrawGenerateVo` | `Result<String>`（返回 taskId） |
| `GET /airag/chat/getAiPosterResult/{taskId}` | path | `Result<?>` (status: pending/success/failed) |
| `POST /airag/chat/genAiWriter` | `AiWriteGenerateVo` | `SseEmitter` |

### 3.3 `/airag/knowledge` — 知识库（`AiragKnowledgeController`）

| 方法 / 路径 | 鉴权 | 说明 |
|------------|------|------|
| `GET /airag/knowledge/list` | 无 | 分页查询 |
| `POST /airag/knowledge/add` | `airag:knowledge:add` | 新增（默认 type=knowledge） |
| `PUT,POST /airag/knowledge/edit` | `airag:knowledge:edit` | 编辑（切换 embedId → 触发文档重建） |
| `PUT /airag/knowledge/rebuild` | `airag:knowledge:rebuild` | 整库重建 |
| `DELETE /airag/knowledge/delete` | `airag:knowledge:delete` | 删除（同时删文档） |
| `GET /airag/knowledge/queryById` | 无 | 查 |
| `GET /airag/knowledge/doc/list` | 无 | 文档分页 |
| `POST /airag/knowledge/doc/edit` | `airag:knowledge:doc:edit` | 文档新增 / 编辑（触发向量化） |
| `POST /airag/knowledge/doc/import/zip` | `airag:knowledge:doc:zip` | ZIP 导入 |
| `GET /airag/knowledge/doc/import/task/list` | 无 | 导入任务列表 |
| `PUT /airag/knowledge/doc/rebuild` | `airag:knowledge:doc:rebuild` | 重建文档 |
| `DELETE /airag/knowledge/doc/deleteBatch` | `airag:knowledge:doc:deleteBatch` | 批量删 |
| `DELETE /airag/knowledge/doc/deleteAll` | `airag:knowledge:doc:deleteAll` | 清空库 |
| `GET /airag/knowledge/embedding/hitTest/{knowId}` | 无 | 命中测试（topNumber/similarity） |
| `GET /airag/knowledge/embedding/search` | 无 | 向量查询（多库） |
| `GET /airag/knowledge/query/batch/byId` | 无 | 批量查 |
| `POST /airag/knowledge/plugin/add` | 无 | 添加记忆 |
| `POST /airag/knowledge/plugin/query` | 无 | 查询记忆 |

### 3.4 `/airag/airagModel` — 模型配置（`AiragModelController`）

| 方法 / 路径 | 鉴权 | 说明 |
|------------|------|------|
| `GET /airag/airagModel/list` | 无 | 分页 |
| `POST /airag/airagModel/add` | `airag:model:add` | 新增 |
| `PUT,POST /airag/airagModel/edit` | `airag:model:edit` | 编辑 |
| `DELETE /airag/airagModel/delete` | `airag:model:delete` | 删除（带 tenant 校验） |
| `GET /airag/airagModel/queryById` | 无 | 查 |
| `GET /airag/airagModel/exportXls` | 无 | 导出 |
| `POST /airag/airagModel/importExcel` | 无 | 导入 |
| `POST /airag/airagModel/test` | 无 | **实测连接 + 自动激活**（支持 LLM/EMBED/IMAGE 三种类型，含图生图） |

### 3.5 `/airag/airagMcp` — MCP / 插件（`AiragMcpController`）

| 方法 / 路径 | 鉴权 | 说明 |
|------------|------|------|
| `GET /airag/airagMcp/list` | `airag:mcp:list` | 分页 |
| `POST /airag/airagMcp/save` | `airag:mcp:save` | 保存 |
| `POST /airag/airagMcp/saveAndSync` | `airag:mcp:save` | 保存后立即同步远程 |
| `POST /airag/airagMcp/sync/{id}` | `airag:mcp:save` | 同步远程工具列表 |
| `POST /airag/airagMcp/status/{id}/{action}` | `airag:mcp:save` | enable / disable |
| `POST /airag/airagMcp/saveTools` | `airag:mcp:save` | 保存插件 tools JSON |
| `DELETE /airag/airagMcp/delete` | `airag:mcp:delete` | 删除 |
| `GET /airag/airagMcp/queryById` | 无 | 查 |
| `GET /airag/airagMcp/exportXls` | `airag:mcp:export` | 导出 |
| `POST /airag/airagMcp/importExcel` | `airag:mcp:import` | 导入 |

### 3.6 `/airag/api` — BaseApi（`AiragBaseApiController`，无鉴权）

跨模块调用入口，实现 `org.jeecg.common.airag.api.IAiragBaseApi`：

| 方法 / 路径 | 说明 |
|------------|------|
| `POST /airag/api/knowledgeWriteTextDocument` | 写入知识库文本文档（带分段配置），返回 docId |
| `POST /airag/api/getChatVariable` | 取会话变量值 |
| `POST /airag/api/setChatVariable` | 写会话变量 |
| `POST /airag/api/getMemoryIdByAppId` | 取应用的记忆库 ID |
| `POST /airag/api/getPromptContent` | 按 ID 取提示词内容 |

### 3.7 `/airag/ocr` — OCR（`AiOcrController`，**Redis 存储**）

| 方法 / 路径 | 说明 |
|------------|------|
| `GET /airag/ocr/list` | 从 Redis key `airag:ocr` 读列表 |
| `POST /airag/ocr/add` | UUID 生成 ID，写入 Redis 列表 |
| `PUT /airag/ocr/edit` | 按 id 复制属性 |
| `DELETE /airag/ocr/deleteById` | 移除 |

### 3.8 `/airag/prompts` — 提示词市场（`AiragPromptsController`）

| 方法 / 路径 | 说明 |
|------------|------|
| `GET /airag/prompts/list` | 分页 |
| `POST /airag/prompts/add` | 新增（delFlag=0, status=0） |
| `PUT,POST /airag/prompts/edit` | 编辑 |
| `DELETE /airag/prompts/delete` | 单删 |
| `DELETE /airag/prompts/deleteBatch` | 批量 |
| `GET /airag/prompts/queryById` | 查 |
| `POST /airag/prompts/experiment` | 构造器调试（Prompt 实验） |
| `GET /airag/prompts/exportXls` | 导出 |
| `POST /airag/prompts/importExcel` | 导入 |

### 3.9 `/airag/extData` — 评估器 / 轨迹（`AiragExtDataController`）

| 方法 / 路径 | 说明 |
|------------|------|
| `GET /airag/extData/list` | bizType=`evaluator` 分页 |
| `GET /airag/extData/getTrackList` | bizType=`track`，按 metadata 过滤 |
| `POST /airag/extData/add` | bizType 强制 evaluator |
| `PUT,POST /airag/extData/edit` | 编辑 |
| `DELETE /airag/extData/delete` | 单删 |
| `DELETE /airag/extData/deleteBatch` | 批量 |
| `GET /airag/extData/queryById` | 查 |
| `GET /airag/extData/queryTrackById` | 查轨迹（status=run 返回"处理中"） |
| `POST /airag/extData/evaluator/debug` | 评估器调试 |
| `GET /airag/extData/exportXls` | 导出 |
| `POST /airag/extData/importExcel` | 导入 |

### 3.10 `/airag/video` — 视频生成（`VideoGenerationController`）

| 方法 / 路径 | 说明 |
|------------|------|
| `POST /airag/video/submit` | 提交任务（返回 `VideoTaskResultVo`） |
| `GET /airag/video/query/{taskId}` | 查询任务 |
| `POST /airag/video/voiceover` | 配音（生成旁白 → TTS → FFmpeg 合并） |
| `GET /airag/video/prompts` | 预设提示词 |
| `GET /airag/video/listByUser` | 用户记录 |
| `DELETE /airag/video/deleteVideoRecord` | 删记录 |

### 3.11 `/airag/voice` — 文生语音（`VoiceController`）

| 方法 / 路径 | 说明 |
|------------|------|
| `POST /airag/voice/generate` | 同步（speed 校验 0.25~4.0） |
| `POST /airag/voice/generateAsync` | 异步返回 taskId |
| `GET /airag/voice/queryTask/{taskId}` | 查异步任务 |
| `GET /airag/voice/listByUser` | 用户记录 |
| `DELETE /airag/voice/deleteVoiceRecord` | 删记录 |

### 3.12 `/airag/word` — Word 模板（`AigcWordTemplateController`）

| 方法 / 路径 | 说明 |
|------------|------|
| `GET /airag/word/list` | 分页 |
| `POST /airag/word/add` | 新增（code 唯一） |
| `PUT,POST /airag/word/edit` | 编辑（code 不允许改） |
| `DELETE /airag/word/delete` | 单删 |
| `DELETE /airag/word/deleteBatch` | 批量 |
| `GET /airag/word/queryById` | 查 |
| `GET /airag/word/download` | 下载 word 模板（`wordTplUtils.generateWordTemplate`） |
| `POST /airag/word/parse/file` | 解析 word 文件 → `AigcWordTemplate` |
| `POST /airag/word/generate/word` | 用模板 + 数据生成 word |

---

## 四、Service 层（9 个接口，11 个实现类）

### 4.1 `IAiragAppService extends IService<AiragApp>`

| 方法 | 说明 |
|------|------|
| `generatePrompt(prompt, blocking)` | 自动生成提示词（`true` 同步、`false` SSE） |
| `generateMemoryByAppId(variables, memoryId, blocking)` | 按应用 ID 生成变量和记忆提示词 |
| `saveArticleWrite(AiArticleWriteVersionVo)` | 写作保存（Redis `airag:chat:article:write:{version}`） |
| `listArticleWrite()` | 列写作版本 |
| `deleteArticleWrite(version)` | 删写作 |

### 4.2 `IAiragChatService`

| 方法 | 返回 | 说明 |
|------|------|------|
| `send(ChatSendParams)` | `SseEmitter` | 发送消息（SSE 流式） |
| `debugApp(AppDebugParams)` | `SseEmitter` | 调试应用（不持久化真实会话） |
| `stop(requestId)` | `Result<?>` | 终止（清 `AiragLocalCache` 中的 `flowContext` 和 `sseEmitter`） |
| `getConversations(appId)` | `Result<?>` | 列会话（Redis `airag:chat:conversation:*`） |
| `getConversationsByType(sessionType)` | `Result<?>` | 按类型列会话 |
| `getMessages(conversationId, sessionType)` | `Result<?>` | 取消息（`mergeToolMessages` 合并工具调用） |
| `deleteConversation(id, sessionType)` | `Result<?>` | 删会话 |
| `updateConversationTitle(ChatConversation)` | `Result<?>` | 改标题 |
| `clearMessage(conversationId, sessionType)` | `Result<?>` | 清消息 |
| `initChat(appId)` | `Result<?>` | 忽略租户初始化（QQYUN-12113 分享场景） |
| `receiveByRequestId(requestId)` | `SseEmitter` | 断线重连 |
| `genAiPoster(AiDrawGenerateVo)` | `String` | 同步生成海报 |
| `genAiPosterAsync(AiDrawGenerateVo)` | `String` (taskId) | 异步海报（`POSTER_TASK_PREFIX`，TTL 1h） |
| `getAiPosterResult(taskId)` | `Result<?>` | 异步海报结果 |
| `genAiWriter(AiWriteGenerateVo)` | `SseEmitter` | AI 写作 |

### 4.3 `IAiragKnowledgeService extends IService<AiragKnowledge>`

| 方法 | 说明 |
|------|------|
| `getPluginMemory(memoryId)` | 把记忆库包装为 langchain4j 工具 |

### 4.4 `IAiragKnowledgeDocService extends IService<AiragKnowledgeDoc>`

| 方法 | 说明 |
|------|------|
| `rebuildDocument(docIds)` | 重建向量化 |
| `editDocument(AiragKnowledgeDoc)` | 新增 / 编辑（解析 + 分段 + 嵌入） |
| `rebuildDocumentByKnowId(knowId)` | 整库重建 |
| `removeByKnowIds(knowIds)` | 按库删 |
| `removeDocByIds(docIds)` | 批量删文档 |
| `deleteAllByKnowId(knowId)` | 清空库 |
| `importDocumentFromZip(knowId, file)` | ZIP 导入 |

### 4.5 `IAiragModelService extends IService<AiragModel>`

空接口，业务用 MyBatis-Plus 默认方法（`page` / `list` / `save` / `updateById` / `removeById`）。

### 4.6 `IAiragMcpService extends IService<AiragMcp>`

| 方法 | 说明 |
|------|------|
| `edit(AiragMcp)` | 保存（校验 type） |
| `sync(id)` | 同步（建立 MCP 连接 / 拉取工具列表） |
| `toggleStatus(id, action)` | 启停 |
| `saveTools(id, tools)` | 仅更新 tools JSON |

### 4.7 `IAiragFlowPluginService`

| 方法 | 说明 |
|------|------|
| `getFlowsToPlugin(flowIds)` | 把流程转换为 MCP 插件配置 |
| `getFlowsToPlugin(flowIds, appId, memoryId)` | 带上下文（变量节点、记忆节点） |

### 4.8 `IAiragVariableService`

| 方法 | 说明 |
|------|------|
| `updateVariable(userId, appId, name, value)` | 写变量 |
| `additionalPrompt(username, app)` | 追加提示词 |
| `initVariable(userId, appId, name, defaultValue)` | 初始化变量 |
| `getVariable(username, appId, name)` | 取变量 |
| `addUpdateVariableTool(AiragApp, username, AIChatParams)` | 注入"更新变量"工具到 langchain4j |

### 4.9 `IAiragPromptsService extends IService<AiragPrompts>`

| 方法 | 说明 |
|------|------|
| `promptExperiment(AiragExperimentVo, HttpServletRequest)` | 提示词实验 |

### 4.10 `IAiragExtDataService extends IService<AiragExtData>`

| 方法 | 说明 |
|------|------|
| `debugEvaluator(AiragDebugVo)` | 评估器调试 |
| `queryTrackById(id)` | 查调用轨迹 |

### 4.11 其他 Service

- `IVoiceService`：`textToSpeech`、`generateAsync`、`getVoiceTaskResult`、`getVoiceRecords`、`deleteVoiceRecord`
- `IVideoGenerationService`：`submitTask`、`queryTask`、`addVoiceover`、`getPresetPrompts`、`getVideoRecords`、`deleteVideoRecord`
- `IAigcWordTemplateService`：`generateWordFromTpl(WordTplGenDTO, OutputStream)` 等

---

## 五、Handler 层（核心处理类）

### 5.1 `AIChatHandler implements IAIChatHandler`（`@Component`）

**注入**：`AiragModelMapper`、`AiragMcpMapper`、`EmbeddingHandler`、`LLMHandler`（base-core）、`AiRagConfigBean`、`AiChatConfig`。

#### 公开方法

| 方法 | 说明 |
|------|------|
| `completions(modelId, messages, params)` | 阻塞问答 |
| `chat(modelId, messages, params)` | 流式聊天（`TokenStream`） |
| `completionsByDefaultModel(messages, params)` | 默认模型问答 |
| `chatByDefaultModel(messages, params)` | 默认模型流式聊天 |
| `imageGenerate(modelId, messages, params)` | 文生图 |
| `imageEdit(modelId, messages, images, params)` | 图生图 |
| `buildUserMessage(content, images)` | 拼装多模态消息 |
| `buildImageContents(images)` | 网络图直传 / 本地图 Base64 |

#### `completions(modelId, messages, params)` 执行步骤

1. `mergeParams(airagModel, params)` 合并凭证 / 模型参数 / RAG 路由器 / MCP 工具 / DeepSeek 推理模型特殊处理
2. `injectThinkingPlaceholderIfNeeded(messages, modelName)` —— DeepSeek 推理模型历史消息补占位
3. `llmHandler.completions(messages, params)` 实际调用
4. 异常分类处理（4 级降级，见 §16）
5. 响应清洗：剥离 `<think>...</think>`（当 `params.noThinking=true`）

#### `mergeParams` 关键逻辑

- 注入 `provider / modelName / baseUrl / apiKey / secretKey / httpVersionOne`
- 注入 `temperature / topP / presencePenalty / frequencyPenalty / maxTokens / timeout / enableSearch / extraParams`
- `params.knowIds` 非空 → 调用 `embeddingHandler.getQueryRouter()` 注入 RAG
- 默认 timeout = `AiragConsts.DEFAULT_TIMEOUT`
- DeepSeek 推理模型：跳过插件（`!DEEPSEEK_REASONER.equals(modelName)`），强制 `returnThinking=true, sendThinking=true`

#### `chat` 流式分支

- 模型未激活 → `chatByDefaultModel` 走 `yml` 中的默认模型（QQYUN-14781）

### 5.2 `EmbeddingHandler implements IEmbeddingHandler`

**注入**：`EmbedStoreConfigBean`、`AiragModelMapper`、`IAiragKnowledgeService`（`@Lazy`）、`AiragKnowledgeMapper`、`KnowConfigBean`、`AiChatConfig`。

#### 公开方法

| 方法 | 说明 |
|------|------|
| `embeddingDocument(knowId, doc)` | 向量化文档主流程 |
| `embeddingSearch(knowIds, queryText, topNumber, similarity)` | 多知识库查询 |
| `searchEmbedding(knowId, queryText, topNumber, similarity)` | 单库查询 |
| `getQueryRouter(knowIds, topNumber, similarity)` | 构造 langchain4j `QueryRouter` |
| `deleteEmbedDocsByKnowId(knowId, modelId)` | 按库删向量 |
| `deleteEmbedDocsByDocIds(docIds, modelId)` | 按文档删向量 |
| `getEmbedModelData(modelId)` | 模型未激活 → 回退 yml 默认向量模型（QQYUN-14645） |
| `getEmbedStore(model)` | 构造 `PgVectorEmbeddingStore` |
| `buildModelOptions(AiragModel)` | 静态工具：AiragModel → AiModelOptions |
| `splitDocumentPreservingHtmlTables(doc, splitter)` | 静态工具：保留 HTML 表格完整块（#9551） |
| `appendSplitText(text, metadata, splitter, result)` | 静态工具 |
| `appendSegment(text, metadata, result)` | 静态工具 |
| `reindexSegments(segments)` | 静态工具：写 `index` 元数据 |

#### `embeddingDocument` 流程

1. 读知识库配置 → 获取 embedding 模型 → 构造 `EmbeddingModel`
2. **TYPE_FILE**：`parseFileByMinerU`（若启用 magic-pdf conda 命令）→ `parseFile`（Tika）
3. **TYPE_WEB**：`parseWebPage`（Jsoup → Markdown）
4. 标题拼接 `title + "\n\n" + content`
5. `createDocumentSplitter(doc)` —— 文档 metadata 中的分段策略
6. `splitDocumentPreservingHtmlTables`（#9551）
7. `embeddingStore.removeAll(docId==doc.id)` 删旧向量
8. `embeddingModel.embedAll(segments)` → `embeddingStore.addAll(...)`

#### 分段策略键（`LLMConsts`）

- `USE_KNOWLEDGE_DEFAULT` — 使用知识库默认分段策略（QQYUN-14932）
- `SEGMENT_STRATEGY`（`auto` / `custom`）
- `MAX_SEGMENT` / `OVERLAP`（百分比）
- `SEPARATOR` / `CUSTOM_SEPARATOR` / `TEXT_RULES`（`cleanSpaces` / `removeUrlsEmails`）

#### 元数据 Key

- `knowledgeId` / `docId` / `docName` / `username` / `createTime` / `index`

#### 缓存

`EMBED_STORE_CACHE: ConcurrentHashMap<String, EmbeddingStore<TextSegment>>`，key = `modelId + host:port:database`。

#### 模型降级

`getEmbedModelData`：`modelId` 未指定 / 不存在 / 未激活（`activateFlag != 1`）→ 回退 `yml` 默认向量模型（`jeecg.ai-chat.ai-model-embed`）。

#### 维度兼容性

维度 ≠ 1536 → 表名加后缀 `_${dimension}`（QQYUN-12345）。

### 5.3 `JeecgToolsProvider`（接口 + `JeecgLlmTools` 内部类）

- `getDefaultTools()` — 返回默认工具集（用户查询 / 创建用户等，QQYUN-13565）

### 5.4 `PluginToolBuilder`（静态工具类）

| 方法 | 说明 |
|------|------|
| `buildTools(airagMcp, currentHttpRequest)` | 从 `airagMcp.tools` JSON 构建 `Map<ToolSpecification, ToolExecutor>` |
| `buildToolSpecification(toolConfig)` | JSON → `ToolSpecification` |
| `buildToolExecutor(toolConfig, baseUrl, headers, isNeedSign)` | JSON → `ToolExecutor` |
| `buildUrl(baseUrl, path, parameters, args)` | 处理 Path 参数（路径遍历防护） |
| `buildHttpHeaders(parameters, args, defaultHeaders)` | 处理 Header 参数 |
| `buildUrlVariables(parameters, args)` | 处理 Query 参数 |
| `buildRequestBody(parameters, args, httpHeaders)` | 处理 Body / Form-Data |
| `parseHeaders(headersStr)` | 解析 headers JSON |
| `applyAuthConfig(headersMap, metadataStr, currentHttpRequest)` | token 授权注入 |

**安全**：
- `baseUrl` 为空 → 用当前请求 baseUrl
- `applyAuthConfig` —— token 授权自动注入请求头
- 路径遍历防护：`..` `/` `\` `%2e` `%2f` 拒绝（#9421）
- HTTP 调用超时 `AiragConsts.DEFAULT_TIMEOUT * 1000`
- 失败返回"插件调用失败（HTTP xxx）：{响应体}。请继续完成剩余任务。"（#14577）

**参数 location**：`Path` / `Query` / `Header` / `Body` / `Form-Data`

### 5.5 `CommandExecUtil`

- `validateFilePath(path)` — 文件名 Shell 注入字符校验
- `execCommand(command, args)` — `ProcessBuilder` 执行（**MinerU magic-pdf** 调用入口）

---

## 六、Document 解析层

### 6.1 `TikaDocumentParser`（自研，覆盖 langchain4j 内置）

**支持文件类型**：

| 类型 | 解析器 |
|------|--------|
| `txt` / `md` / `pdf` | Apache Tika（`AutoDetectParser` + `BodyContentHandler(-1)`） |
| `docx` / `doc` / `pptx` / `ppt` / `xlsx` / `xls` | Apache POI（langchain4j `ApachePoiDocumentParser`） |

**核心方法**：

| 方法 | 说明 |
|------|------|
| `parse(File)` | 主入口，按后缀分发 |
| `parseDocExcelPdfUsingApachePoi(File)` | POI 解析 |
| `extractByTika(InputStream)` | Tika 解析 |

### 6.2 `WebPageParser`

| 方法 | 说明 |
|------|------|
| `parseToMarkdown(url)` | Jsoup 抓取 + 转 Markdown |

### 6.3 `CustomDocumentSplitter extends DocumentSplitter`

- 按指定分隔符（`\n`、句号等）+ 自定义正则规则分段
- `maxSegment` / `overlap` 由 metadata 控制

---

## 七、API 实现（`AiragBaseApiImpl`）

实现 `org.jeecg.common.airag.api.IAiragBaseApi`（base-core 跨模块接口）：

```java
String knowledgeWriteTextDocument(knowledgeId, title, content, segmentConfig)
String getChatVariable(appId, username, name)
void   setChatVariable(appId, username, name, value)
String getMemoryIdByAppId(appId)
String getPromptContent(promptId)
```

**实现细节**：

- `knowledgeWriteTextDocument` → 构造 `AiragKnowledgeDoc`（`type=text`，`metadata=segmentConfig`），调 `airagKnowledgeDocService.editDocument`，返回 `docId`
- `getMemoryIdByAppId` → 查询 `izOpenMemory=1 AND memoryId IS NOT NULL` 的 App
- 业务异常用 `JeecgBootBizTipException` 抛出

---

## 八、实体层（9 个 Entity）

| 实体 | 表名 | 主键 | 关键字段 |
|------|------|------|---------|
| `AiragApp` | `airag_app` | `ASSIGN_ID` (String) | name / descr / icon / type(`chatSimple`/`chatFLow`) / prologue / presetQuestion / prompt / modelId / msgNum / knowledgeIds / flowId / quickCommand / status(`enable`/`disable`/`release`) / metadata / plugins(JSON) / izOpenMemory / memoryId / variables / memoryPrompt |
| `AiragKnowledge` | `airag_knowledge` | `ASSIGN_ID` | name / embedId / descr / status / type(`knowledge`/`memory`) / metadata |
| `AiragKnowledgeDoc` | `airag_knowledge_doc` | `ASSIGN_ID` | knowledgeId / title / type(`text`/`file`/`web`) / content / metadata(JSON: filePath/website/sourcesPath) / status |
| `AiragModel` | `airag_model` | `ASSIGN_ID` | name / provider(`model_provider`字典) / modelType(`LLM`/`EMBED`/`IMAGE`) / modelName / baseUrl / credential(JSON: apiKey/secretKey/httpVersionOne) / modelParams(JSON: temperature/topP/.../extraParams) / activateFlag(0/1) |
| `AiragMcp` | `airag_mcp` | `ASSIGN_ID` | name / descr / category(`plugin`/`mcp`) / type(`sse`/`stdio`) / endpoint / headers / tools(JSON 工具列表) / status(`enable`/`disable`) / synced / metadata |
| `AiragPrompts` | `airag_prompts` | `ASSIGN_ID` + `@TableLogic delFlag` | name / promptKey / description / content(支持 `{{variable}}`) / category / tags / modelId / modelParam / status(0/1) / version |
| `AiragExtData` | `airag_ext_data` | `ASSIGN_ID` | bizType(`evaluator`/`track`) / name / descr / tags / dataValue(JSON) / metadata / datasetValue / status(`run`/`completed`/`failed`) / version |
| `AigcWordTemplate` | `aigc_word_template` | `ASSIGN_ID` | name / code / header / footer / main / margins / width / height / paperDirection(`vertical`/`horizontal`) / watermark |
| `AiOcr` | **仅 Redis 存储** | — | id / title / prompt |

**通用审计字段**（除 `AiOcr`）：`createBy` / `createTime` / `updateBy` / `updateTime` / `sysOrgCode` / `tenantId`（全部带 `@Dict` / `@Excel` 注解）。

---

## 九、关键常量

### 9.1 `LLMConsts`

| 分类 | 常量 | 值 |
|------|------|-----|
| 模型类型 | `MODEL_TYPE_LLM` / `MODEL_TYPE_EMBED` / `MODEL_TYPE_IMAGE` | `LLM` / `EMBED` / `IMAGE` |
| 默认向量维度 | `EMBED_MODEL_DEFAULT_DIMENSION` | `1536` |
| 文档状态 | `KNOWLEDGE_DOC_STATUS_DRAFT` / `_BUILDING` / `_COMPLETE` / `_FAILED` | `draft` / `building` / `complete` / `failed` |
| 文档类型 | `KNOWLEDGE_DOC_TYPE_TEXT` / `_FILE` / `_WEB` | `text` / `file` / `web` |
| 知识库类型 | `KNOWLEDGE_TYPE_KNOWLEDGE` / `_MEMORY` | `knowledge` / `memory` |
| 分段策略 | `SEGMENT_STRATEGY_AUTO` / `_CUSTOM` | `auto` / `custom` |
| 分段键 | `MAX_SEGMENT` / `OVERLAP` / `SEPARATOR` / `CUSTOM_SEPARATOR` / `TEXT_RULES` / `USE_KNOWLEDGE_DEFAULT` / `ENABLE_SEGMENT` | — |
| 文本预处理 | `TEXT_RULES_CLEAN_SPACES` / `TEXT_RULES_REMOVE_URLS_EMAILS` | `cleanSpaces` / `removeUrlsEmails` |
| DeepSeek | `DEEPSEEK_REASONER` | `deepseek-reasoner` |
| DeepSeek 推理模型集 | `DEEPSEEK_THINKING_MODELS` | `{reasoner, v4-flash, v4-pro}` |
| DeepSeek 判断 | `isDeepSeekThinkingModel(name)` | 大小写不敏感 + 关键字匹配 |
| 聊天白名单 | `CHAT_FILE_EXT_WHITELIST` | `{txt, pdf, docx, doc, pptx, ppt, xlsx, xls, md}` |
| 聊天单文件最大 | `CHAT_FILE_TEXT_MAX_LENGTH` | `20000` |
| 聊天最大文件数 | `CHAT_FILE_MAX_COUNT` | `3` |
| 网页 URL 正则 | `WEB_PATTERN` | `^(http\|https)://.*` |

### 9.2 `AiAppConsts`

| 分类 | 常量 | 值 |
|------|------|-----|
| 状态 | `STATUS_ENABLE` / `STATUS_DISABLE` / `STATUS_RELEASE` | `enable` / `disable` / `release` |
| 默认应用 | `DEFAULT_APP_ID` | `default` |
| 应用类型 | `APP_TYPE_CHAT_SIMPLE` / `APP_TYPE_CHAT_FLOW` | `chatSimple` / `chatFLow` |
| 元数据键 | `APP_METADATA_FLOW_INPUTS` | `flowInputs` |
| 记忆开关 | `IZ_OPEN_MEMORY` | `1` |
| 标题最大长度 | `CONVERSATION_MAX_TITLE_LENGTH` | `10` |
| 写作流程 ID | `ARTICLE_WRITER_FLOW_ID` | `2011769909807579138` |
| 写作 Redis Key | `ARTICLE_WRITER_KEY` | `airag:chat:article:write:{}` |
| 海报任务 Key | `POSTER_TASK_PREFIX` | `airag:poster:task:` |
| 海报 TTL | `POSTER_TASK_TTL` | `3600s` |
| 绘画类型 | `AI_DRAW_TYPE_DRAW` / `_FACE` / `_MIX` | `draw` / `face` / `mix` |

### 9.3 `AiPromptsConsts`

| 分类 | 常量 | 值 |
|------|------|-----|
| 状态 | `STATUS_RUNNING` / `STATUS_COMPLETED` / `STATUS_FAILED` | `run` / `completed` / `failed` |
| bizType | `BIZ_TYPE_EVALUATOR` / `BIZ_TYPE_TRACK` | `evaluator` / `track` |

---

## 十、配置 Bean

### 10.1 `EmbedStoreConfigBean`（`jeecg.airag.embed-store` 前缀）

| 字段 | 默认值 |
|------|--------|
| `host` | `127.0.0.1` |
| `port` | `5432` |
| `database` | `postgres` |
| `user` | `postgres` |
| `password` | `postgres` |
| `table` | `embeddings` |

> 对应 PostgreSQL + pgvector 扩展。

### 10.2 `KnowConfigBean`

| 字段 | 说明 |
|------|------|
| `isEnableMinerU` | 是否启用 MinerU PDF 解析 |
| `condaEnv` | conda 环境名（拼到 `magic-pdf` 命令前） |

### 10.3 `AiChatConfig`（`jeecg.ai-chat` 前缀，base-core）

| 字段 | 说明 |
|------|------|
| `aiModelDraw` | 默认文生图模型（QQYUN-12145） |
| `aiModelPicDraw` | 默认图生图模型（QQYUN-12145） |
| `aiModelEmbed` | 默认向量模型（QQYUN-14645） |

---

## 十一、典型执行流

### 11.1 用户发送聊天消息 → AI 响应

```
AiragChatController.send
  → AiragChatServiceImpl.send
      → getOrCreateChatConversation  (Redis airag:chat:conversation:*)
      → saveVariables(app)
      → doChat(chatConversation, topicId, params)
          → 解析 app / 加载 prompt / 拼装 messages
          → AiragFlowService.run  (若 app.flowId 非空)
              → 节点循环：LLM 节点 → AIChatHandler.chat
                          → llmHandler.chat(messages, params)
                          → 流式输出
              → 工具节点：ToolSpecification.execute
                          → PluginToolBuilder 构建的 HTTP 调用
          → SSE 推送给前端
```

### 11.2 上传文档 → 向量化

```
AiragKnowledgeController.doc/edit
  → IAiragKnowledgeDocService.editDocument(doc)
      → 解析 metadata
      → if file → parseFile (Tika/POI) | parseFileByMinerU (magic-pdf)
      → if web  → parseWebPage (Jsoup → MD)
      → EmbeddingHandler.embeddingDocument(knowId, doc)
          → getEmbedModelData (回退到 yml 默认)
          → createDocumentSplitter (auto/custom + HTML 表格保留)
          → EmbeddingStore.removeAll(docId)  // 删旧
          → embeddingModel.embedAll(segments)
          → embeddingStore.addAll(embeddings, segments)  // PgVector
```

### 11.3 聊天 RAG 检索

```
AIChatHandler.mergeParams(airagModel, params)
  → params.knowIds 非空
  → EmbeddingHandler.getQueryRouter(knowIds, topNumber, similarity)
      → 每个 knowId → EmbeddingStoreContentRetriever
      → DefaultQueryRouter(retrievers)
  → llmHandler.chat(messages, params)  // 自动检索 + 拼接上下文
```

### 11.4 MCP 工具集成

```
AiragChatServiceImpl 构建 plugins
  → AIChatHandler.buildPlugins(params)
      → 对每个 pluginId：
          if category=="mcp" → buildMcpToolProviderWrapper → McpToolProvider
          if category=="plugin" → PluginToolBuilder.buildTools → ToolSpecification+ToolExecutor
      → params.setMcpToolProviders / setTools
  → llmHandler.chat 调用时自动传入工具
```

### 11.5 视频配音

```
VideoGenerationController.voiceover
  → IVideoGenerationService.addVoiceover(taskId, prompt)
      → 根据 prompt 生成旁白文案（LLM）
      → TTS 合成语音（VoiceApiHelper）
      → FFmpeg 合并原视频 + 音轨
      → 返回带配音的 VideoTaskResultVo
```

---

## 十二、安全机制（已修复的 issue）

| Issue | 修复位置 | 说明 |
|-------|---------|------|
| `#9462` | `AiragAppController.edit` | 跨租户数据写入漏洞 → 用 `TokenUtils.getTenantIdByRequest` 覆盖 |
| `#8337` | App/Knowledge/Mcp/Model 的 delete | SaaS 隔离：删除前校验 tenantId |
| `#8545` | `AiragChatServiceImpl.send` | 工作流入参 `flowInputs` 持久化到会话 |
| `#9234` | `AIChatHandler.buildPlugins` | MCP 连接包装器：`McpToolProviderWrapper` 保存连接引用 |
| `#9551` | `EmbeddingHandler` | HTML 表格分段完整保留 |
| `#9585` | `AIChatHandler.injectThinkingPlaceholderIfNeeded` | DeepSeek 推理模型多轮工具调用回传 `reasoning_content` |
| `#9607` | `AIChatHandler.mergeParams` | deepseek-v4-flash 联网搜索异常 |
| `#9418` | `EmbeddingHandler.createDocumentSplitter` | 大文件分段失败 |
| `#9421` | `PluginToolBuilder.buildUrl` | 路径遍历（Path 参数拒绝 `..` `/` `\` `%2e` `%2f`） |
| `#9424` / `#9425` | `EmbeddingHandler.ensureFile` | 命令注入 + 路径遍历 |
| `#9431` | `AIChatHandler.getFirstImageBase64` | 本地文件路径遍历 |
| `QQYUN-14568` | Chat / Voice | 海报 / 语音改为异步（taskId + Redis） |
| `QQYUN-14645` | `EmbeddingHandler.getEmbedModelData` | 模型未激活回退到 yml 默认 |
| `QQYUN-14577` | `PluginToolBuilder` 异常分支 | 工具失败返回"请继续完成剩余任务" |
| `QQYUN-12135` | `AiragChatController.upload` | AI 聊天上传图片 token 校验 |

---

## 十三、前端模块（`jeecgboot-vue3/src/views/super/airag/`）

### 目录结构

```
src/views/super/airag/
├── aiapp/                  # AI 应用管理 + 聊天界面
│   ├── AiAppList.vue       # 列表 + 编辑
│   ├── AiApp.api.ts        # API 封装
│   ├── chat/
│   │   ├── chat.vue / AiChat.vue        # 聊天主界面
│   │   ├── chatMessage.vue              # 单条消息
│   │   ├── ThinkText.vue                # 思考过程展示
│   │   ├── presetQuestion.vue           # 预设问题
│   │   ├── slide.vue                    # 幻灯片渲染
│   │   ├── hooks/useChat.ts             # SSE 流式封装
│   │   ├── jeecg-tags/
│   │   │   ├── jeecg-chart/ChartRender.vue       # 图表渲染
│   │   │   └── tool-exec/JeecgToolExec.vue       # 工具执行展示
│   │   └── portal/AppPortal.vue         # 嵌入第三方系统
│   └── components/                      # 弹窗组件
├── aiknowledge/            # 知识库管理
├── aimodel/                # AI 模型配置
├── aimcp/                  # MCP / 插件管理
├── aiposter/               # AI 海报
├── aiccloth/               # AI 换衣（draw / face / mix 三种）
├── aiprompts/              # 提示词管理 + 评估器 + 数据集
├── aivideo/ + aivideo2/    # AI 视频（两个版本）
├── aivoice/                # AI 语音
├── aiwriter/               # AI 写作（左侧目录 + 右侧编辑器）
├── ocr/                    # OCR 模型
└── wordtpl/                # Word 模板
```

**注册方式**：`src/views/super/registerSuper.ts` 通过 `import.meta.glob('./**/register.ts')` 动态发现。

**API 客户端**：每个模块一个 `.api.ts` 封装 axios 请求 + TypeScript 类型。

---

## 十四、与其他模块的耦合点

| 依赖 | 用途 | 来源 |
|------|------|------|
| `org.jeecg.common.airag.api.IAiragBaseApi` | 跨模块 baseApi 接口 | base-core |
| `org.jeecg.ai.handler.LLMHandler` | 底层 LLM 封装 | base-core |
| `org.jeecg.ai.factory.AiModelFactory` | 模型工厂 | base-core |
| `org.jeecg.ai.factory.AiModelOptions` | 模型参数 | base-core |
| `org.jeecg.config.AiChatConfig` | yml 配置 | base-core |
| `org.jeecg.config.AiRagConfigBean` | RAG 全局配置 | base-core |
| `org.jeecg.common.system.api.ISysBaseAPI` | 用户 / 部门 | system |
| `org.jeecg.common.system.util.JwtUtil` | JWT 解析 | base-core |
| `org.jeecg.common.util.filter.SsrfFileTypeFilter` | SSRF + 路径遍历防护 | base-core |
| `dev.langchain4j.*` | LLM 编排 | 第三方 |

---

## 十五、关键运行时缓存

### 15.1 Redis Key 模式

| Key 模式 | 内容 | TTL |
|---------|------|-----|
| `airag:chat:conversation:{appId}:{topicId}[:{sessionType}]` | `ChatConversation`（含 messages） | 永不过期 |
| `airag:chat:article:write:{version}` | 写作版本 | — |
| `airag:poster:task:{taskId}` | 海报任务结果（pending/success/failed） | 1h |
| `airag:ocr` | OCR 模型列表（JSON 数组） | 永不过期 |
| `airag:chat:variable:{appId}:{userId}:{name}` | 变量值 | — |

### 15.2 本地内存缓存（`AiragLocalCache`，`ConcurrentHashMap`）

| Cache Key | 内容 | 用途 |
|-----------|------|------|
| `CACHE_TYPE_FLOW_CONTEXT` | 流程执行上下文 | 支持 `stop` 操作 |
| `CACHE_TYPE_SSE` | SSE emitter | 与 requestId 映射 |
| `EMBED_STORE_CACHE`（EmbeddingHandler 内部） | `PgVectorEmbeddingStore` | 避免重复构造 |

---

## 十六、错误处理与国际化

`AIChatHandler.translateLlmException` —— 4 级降级：

1. **超时（timeout）** → 排队提示
2. **工具上下文丢失**（`messages with role 'tool' must be a response to a preceeding message with 'tool_calls'`） → 友好提示："消息序列不完整，可能是因为历史消息数量设置过小导致工具调用上下文丢失。建议增加历史消息数量后重试。"
3. **`MODEL_ERROR_MAP` 关键字匹配** → 对应中文提示
4. **兜底** → `defaultMsg` 参数

---

## 十七、维护说明

- **数据库初始化**：参考 `db/jeecgboot-mysql-5.7.sql`（基础 schema），本模块的 9 张业务表通过 Flyway 自动迁移（`jeecg-system-start/src/main/resources/flyway/sql/mysql/`）
- **配置文件**：参考 `application-dev.yml` 中的 `jeecg.ai-chat.*` 和 `jeecg.airag.embed-store.*`
- **Docker 部署**：`docker-compose.yml` 已包含 `jeecg-boot-pgvector` 容器
- **依赖升级**：langchain4j 升级到 1.x 时需关注 `dev.langchain4j.service.tool.ToolExecutor` 的 API 变更