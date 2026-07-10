# 15 · 数据层 · 实体 / Mapper / Redis Key / 配置 全梳理

> 本文档汇总所有 9 张业务表 + 关键 Redis Key 模式 + yml 配置项。

---

## 一、9 张业务表

### 1.1 `airag_app`（AI 应用）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | `@TableId(ASSIGN_ID)` |
| `name` | String | 应用名称 |
| `descr` | String | 描述 |
| `icon` | String | 图标 URL |
| `type` | String | 字典 `ai_app_type`：chatSimple / chatFLow |
| `prologue` | String | 开场白 |
| `presetQuestion` | String | 预设问题 JSON 数组 |
| `prompt` | String | 提示词 |
| `modelId` | String | 关联 airag_model.id |
| `msgNum` | Integer | 历史消息数量 |
| `knowledgeIds` | String | 关联 airag_knowledge.id，逗号分隔 |
| `flowId` | String | 关联 airag_flow.id |
| `quickCommand` | String | 快捷指令 JSON |
| `status` | String | enable / disable / release |
| `metadata` | String | 元数据 JSON |
| `plugins` | String | 插件 JSON |
| `izOpenMemory` | Integer | 0/1 |
| `memoryId` | String | 关联 airag_knowledge.type='memory' |
| `variables` | String | 变量定义 JSON 列表 |
| `memoryPrompt` | String | 记忆+变量 提示词 |
| `tenantId/createBy/...` | 标准审计 | |

**Mapper**：`AiragAppMapper extends BaseMapper<AiragApp>`，自定义 `getByIdIgnoreTenant(id)`。

### 1.2 `airag_knowledge`（知识库）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (ASSIGN_ID) | |
| `name` | String | |
| `embedId` | String | 关联 airag_model.id (modelType='EMBED') |
| `descr` | String | |
| `status` | String | enable / disable |
| `type` | String | knowledge / **memory**（记忆库） |
| `metadata` | String | 元数据（含分段策略） |
| 审计 | | |

### 1.3 `airag_knowledge_doc`（知识库文档）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | |
| `knowledgeId` | String | 所属知识库 |
| `title` | String | |
| `type` | String | text / file / web |
| `content` | String | 解析后内容 |
| `metadata` | String | JSON：{filePath, sourcesPath, website, useKnowledgeDefault, failedReason} |
| `status` | String | draft / building / complete / failed |

**Mapper**：`AirragKnowledgeDocMapper` 含 `deleteByMainId(knowId)`（按知识库 ID 删全部文档，AirragKnowledgeDocServiceImpl.removeByKnowIds 调用）。

### 1.4 `airag_model`（模型配置）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | |
| `name` | String | |
| `provider` | String | openai / zhipu / qianfan / tongyi / ollama / deepseek |
| `modelType` | String | **LLM / EMBED / IMAGE** |
| `modelName` | String | 基础模型名 |
| `baseUrl` | String | API 域名 |
| `credential` | String | JSON: {apiKey, secretKey, httpVersionOne} |
| `modelParams` | String | JSON: {temperature, topP, maxTokens, enableSearch, extraParams} |
| `activateFlag` | Integer | 0/1 |

### 1.5 `airag_mcp`（MCP / 插件）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | |
| `name` | String | |
| `descr` | String | |
| **`category`** | String | **mcp / plugin** |
| **`type`** | String | 当 category=mcp：sse / http / stdio |
| `endpoint` | String | sse→URL，stdio→命令 |
| `headers` | String | 请求头 JSON / 环境变量 |
| **`tools`** | String | **JSON 数组：[{name, description, path, method, parameters, responses, enabled}]** |
| `status` | String | enable / disable |
| `synced` | Integer | 0=未同步 1=已同步 |
| `metadata` | String | JSON：{tool_count} |

### 1.6 `airag_prompts`（提示词）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` / `name` / `delFlag` | | `@TableLogic delFlag` |
| `promptKey` | String | 唯一标识（代码中引用） |
| `description` | String | |
| `content` | String | 模板，`{{variable}}` 占位 |
| `category` / `tags` | String | |
| `modelId` | String | |
| `modelParam` | String | |
| `status` | String | 0=未发布 1=已发布 |
| `version` | String | "0.0.1" |

### 1.7 `airag_ext_data`（评估器 / 轨迹）

`biz_type` 区分多业务：

| bizType | 用途 | 关键字段 |
|---------|------|---------|
| `evaluator` | 评估器（实验场景） | dataValue: 评分规则 JSON |
| `track` | 调用轨迹 | metadata: parentId |

字段：
| 字段 | 类型 |
|------|------|
| `id` | String |
| `bizType` | String（evaluator / track） |
| `name` / `descr` / `tags` | String |
| `dataValue` | JSON 实际业务数据 |
| `metadata` | JSON 元数据 |
| `datasetValue` | 评测集数据 |
| `status` | String（run / completed / failed） |
| `version` | Integer |

### 1.8 `aigc_word_template`（Word 模板）

| 字段 | 类型 |
|------|------|
| `id` / `name` / `code` (唯一) | |
| `header` / `footer` / `main` | String |
| `margins` | String JSON |
| `width` / `height` | Integer |
| `paperDirection` | String（vertical / horizontal） |
| `watermark` | String |

### 1.9 `AiOcr`（Redis POJO，不在数据库）

```java
@Data
public class AiOcr {
    private String id, title, prompt;
}
```

---

## 二、关键 Redis Key 模式

| Key | TTL | 内容 | 用途 |
|-----|-----|------|------|
| `airag:chat:conversation:{appId}:{topicId}:{username}` | 永久 | `ChatConversation`（含 messages） | 会话状态保存 |
| `airag:chat:conversation:{appId}:{topicId}:{username}:portal` | 永久 | `ChatConversation` | 应用门户场景（#QQYUN-14127） |
| `airag:app:var:{appId}:{username}` | 永久 | Hash：`{varName: varValue}` | 变量存储 |
| `airag:chat:article:write:{version}` | 永久 | `AiArticleWriteVersionVo` | 写作版本 |
| `airag:poster:task:{taskId}` | 1 小时 | 海报任务结果（pending/success/failed） | 海报异步 (#14568) |
| `airag:voice:task:{taskId}` | 1 小时 | `VoiceResultVo` | 语音异步 (#14568) |
| `airag:ocr` | 永久 | List<AiOcr> JSON | OCR 模型列表（唯一 Redis 模式） |

**本地内存缓存**（`AiragLocalCache`，`ConcurrentHashMap`）：
- `CACHE_TYPE_FLOW_CONTEXT` —— 流程执行上下文（stop 用）
- `CACHE_TYPE_SSE` —— `requestId → SseEmitter`
- `CACHE_TYPE_SSE_SEND_TIME` —— `requestId → 起始时间戳`
- `CACHE_TYPE_SSE_HISTORY_MSG` —— `requestId → CopyOnWriteArrayList<MessageHistory>`

**EmbeddingHandler 内部**（静态 `EMBED_STORE_CACHE`）：
- key = `modelId + host:port:database`
- value = `PgVectorEmbeddingStore`
- 进程级缓存，减少重复构造

---

## 三、关键 yml 配置项

### 3.1 `jeecg.ai-chat.*`（base-core 中 `AiChatConfig`）

```yaml
jeecg:
  ai-chat:
    ai-model-llm:           # 默认聊天模型
      provider: deepseek
      model: deepseek-chat
      api-host: https://api.deepseek.com
      api-key: sk-xxx
    ai-model-embed:         # 默认向量模型（#QQYUN-14645）
      provider: openai
      model: text-embedding-3-small
      api-host: https://api.openai.com
      api-key: sk-xxx
    ai-model-draw:          # 默认文生图模型
      provider: openai
      model: dall-e-3
      api-host: https://api.openai.com
      api-key: sk-xxx
    ai-model-pic-draw:      # 默认图生图模型
      provider: openai
      model: dall-e-2
      api-host: https://api.openai.com
      api-key: sk-xxx
```

### 3.2 `jeecg.airag.*`（base-core 中 `AiRagConfigBean` + airag 中的 `EmbedStoreConfigBean` / `KnowConfigBean`）

```yaml
jeecg:
  airag:
    allow-sensitive-nodes: "stdio"    # yml 白名单控制 stdio MCP（QQYUN-14242）
    embed-store:
      host: 127.0.0.1
      port: 5432
      database: postgres
      user: postgres
      password: postgres
      table: embeddings            # 表名（维度变化时自动加 _维度 后缀）
```

`KnowConfigBean`：

```java
@Data
@Component
public class KnowConfigBean {
    private boolean isEnableMinerU;     // 是否启用 magic-pdf
    private String condaEnv;            // conda 环境名
}
```

---

## 四、Mapper 列表

| Mapper | 自定义方法 |
|--------|-----------|
| `AiragAppMapper` | `getByIdIgnoreTenant(id)` |
| `AirragKnowledgeMapper` | `getByIdIgnoreTenant(id)` |
| `AirragKnowledgeDocMapper` | `deleteByMainId(knowId)` |
| `AiragModelMapper` | `getByIdIgnoreTenant(id)` |
| `AiragMcpMapper` | `selectById(id)`（基类方法） |
| `AiragPromptsMapper` | 基类方法 |
| `AiragExtDataMapper` | 基类方法 |
| `AigcWordTemplateMapper` | 基类方法 |

`getByIdIgnoreTenant` —— 故意忽略多租户过滤（AI 模块默认无租户隔离，业务字段例如 `tenantId` 只存不当核心条件）。

---

## 五、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `app/entity/AiragApp.java` | 221 | |
| `app/vo/*.java` | — | 计算字段 getKnowIds()、ChatConversation 等 |
| `llm/entity/AiragKnowledge.java` | 120 | |
| `llm/entity/AiragKnowledgeDoc.java` | 125 | |
| `llm/entity/AiragModel.java` | 132 | |
| `llm/entity/AiragMcp.java` | 139 | |
| `ocr/entity/AiOcr.java` | 29 | |
| `prompts/entity/AiragPrompts.java` | 108 | |
| `prompts/entity/AiragExtData.java` | 99 | |
| `wordtpl/entity/AigcWordTemplate.java` | 127 | |
| 各 `*Mapper.java` | — | |
| `llm/config/EmbedStoreConfigBean.java` | 48 | |
| `llm/config/KnowConfigBean.java` | — | |

---

## 六、下一章

[18-security.md](18-security.md) — 安全修复与对应 issue 完整对照。
