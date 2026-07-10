# 16 · 常量全集

> 本文档汇总 AI 模块用到的所有常量类。

---

## 一、`LLMConsts`（`llm/consts/LLMConsts.java`，222 行）

### 1.1 模型类型

```java
public static final String MODEL_TYPE_LLM    = "LLM";
public static final String MODEL_TYPE_EMBED  = "EMBED";
public static final String MODEL_TYPE_IMAGE  = "IMAGE";

public static final Integer EMBED_MODEL_DEFAULT_DIMENSION = 1536;
```

### 1.2 知识库文档状态

```java
public static final String KNOWLEDGE_DOC_STATUS_DRAFT    = "draft";
public static final String KNOWLEDGE_DOC_STATUS_BUILDING = "building";
public static final String KNOWLEDGE_DOC_STATUS_COMPLETE = "complete";
public static final String KNOWLEDGE_DOC_STATUS_FAILED   = "failed";
```

### 1.3 知识库文档类型

```java
public static final String KNOWLEDGE_DOC_TYPE_TEXT = "text";
public static final String KNOWLEDGE_DOC_TYPE_FILE = "file";
public static final String KNOWLEDGE_DOC_TYPE_WEB  = "web";
```

### 1.4 知识库文档 metadata 键

```java
public static final String KNOWLEDGE_DOC_METADATA_FILEPATH      = "filePath";
public static final String KNOWLEDGE_DOC_METADATA_SOURCES_PATH = "sourcesPath";
public static final String KNOWLEDGE_DOC_METADATA_WEBSITE       = "website";
```

### 1.5 知识库类型

```java
public static final String KNOWLEDGE_TYPE_KNOWLEDGE = "knowledge";
public static final String KNOWLEDGE_TYPE_MEMORY    = "memory";
```

### 1.6 分段策略

```java
public static final String ENABLE_SEGMENT         = "enableSegment";
public static final String USE_KNOWLEDGE_DEFAULT = "useKnowledgeDefault";
public static final String SEGMENT_STRATEGY      = "segmentStrategy";
public static final String SEGMENT_STRATEGY_AUTO  = "auto";
public static final String SEGMENT_STRATEGY_CUSTOM= "custom";
public static final String MAX_SEGMENT           = "maxSegment";
public static final String OVERLAP               = "overlap";
public static final String SEPARATOR              = "separator";
public static final String CUSTOM_SEPARATOR       = "customSeparator";
public static final String TEXT_RULES             = "textRules";
public static final String TEXT_RULES_CLEAN_SPACES = "cleanSpaces";
public static final String TEXT_RULES_REMOVE_URLS_EMAILS = "removeUrlsEmails";
```

### 1.7 DeepSeek 推理模型

```java
public static final String DEEPSEEK_REASONER = "deepseek-reasoner";

public static final Set<String> DEEPSEEK_THINKING_MODELS = new HashSet<>(Arrays.asList(
    "deepseek-reasoner",
    "deepseek-v4-flash",
    "deepseek-v4-pro"
));

public static boolean isDeepSeekThinkingModel(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) return false;
    String name = modelName.trim().toLowerCase();
    if (DEEPSEEK_THINKING_MODELS.contains(name)) return true;
    return name.contains("reasoner")
        || name.contains("v4-flash")
        || name.contains("v4-pro");
}
```

### 1.8 聊天文件

```java
public static final Set<String> CHAT_FILE_EXT_WHITELIST = new HashSet<>(Arrays.asList(
    "txt", "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "md"));

public static final int CHAT_FILE_TEXT_MAX_LENGTH = 20000;
public static final int CHAT_FILE_MAX_COUNT      = 3;
```

### 1.9 网页 URL 正则

```java
public static final Pattern WEB_PATTERN = Pattern.compile("^(http|https)://.*");
```

---

## 二、`AiAppConsts`（`app/consts/AiAppConsts.java`，91 行）

```java
public static final String STATUS_ENABLE  = "enable";
public static final String STATUS_DISABLE = "disable";
public static final String STATUS_RELEASE = "release";

public static final String DEFAULT_APP_ID        = "default";
public static final String APP_TYPE_CHAT_SIMPLE = "chatSimple";
public static final String APP_TYPE_CHAT_FLOW   = "chatFLow";

public static final String APP_METADATA_FLOW_INPUTS = "flowInputs";

public static final Integer IZ_OPEN_MEMORY = 1;
public static final int CONVERSATION_MAX_TITLE_LENGTH = 10;

public static final String ARTICLE_WRITER_FLOW_ID = "2011769909807579138";
public static final String ARTICLE_WRITER_KEY    = "airag:chat:article:write:{}";

public static final String POSTER_TASK_PREFIX = "airag:poster:task:";
public static final long   POSTER_TASK_TTL    = 3600L;     // 1 小时

public static final String AI_DRAW_TYPE_DRAW = "draw";
public static final String AI_DRAW_TYPE_FACE = "face";
public static final String AI_DRAW_TYPE_MIX  = "mix";
```

---

## 三、`AiPromptsConsts`（`prompts/consts/AiPromptsConsts.java`，30 行）

```java
public static final String STATUS_RUNNING   = "run";
public static final String STATUS_COMPLETED = "completed";
public static final String STATUS_FAILED    = "failed";

public static final String BIZ_TYPE_EVALUATOR = "evaluator";
public static final String BIZ_TYPE_TRACK     = "track";
```

---

## 四、`EmbedStoreConfigBean`（`llm/config/EmbedStoreConfigBean.java`，48 行）

```java
@ConfigurationProperties(prefix = "jeecg.airag.embed-store")
public class EmbedStoreConfigBean {
    public static final String PREFIX = "jeecg.airag.embed-store";
    
    private String host = "127.0.0.1";
    private int port = 5432;
    private String database = "postgres";
    private String user = "postgres";
    private String password = "postgres";
    private String table = "embeddings";
}
```

对应 yml（已在 [15-data-entities.md#3.2](15-data-entities.md) 说明）。

---

## 五、SQL 状态转换（`embeddingStore.removeAll`）

```
embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isEqualTo(doc.getId()));
embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId));
embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isIn(docIds));
```

metadata key 定义见 [13-handler-embedding.md#一](13-handler-embedding.md)。

---

## 六、下一章

[18-security.md](18-security.md) — 安全修复。
