# 13 · `EmbeddingHandler` 完整解读

> 本文档按 `EmbeddingHandler.java`（988 行）的全部公开 + 关键私有方法逐个深度解读。这是 RAG 检索链路的核心。

---

## 一、类概览

```java
@Slf4j
@Component
public class EmbeddingHandler implements IEmbeddingHandler {
    
    @Autowired EmbedStoreConfigBean embedStoreConfigBean;   // jeecg.airag.embed-store.*
    @Autowired AiragModelMapper airagModelMapper;
    @Autowired @Lazy IAiragKnowledgeService airagKnowledgeService;   // 解决循环依赖
    @Autowired AiragKnowledgeMapper airagKnowledgeMapper;
    @Value("${jeecg.path.upload:}") String uploadpath;
    @Autowired KnowConfigBean knowConfigBean;
    @Autowired(required = false) AiChatConfig aiChatConfig;
    
    public static final String EMBED_STORE_METADATA_DOCID = "docId";
    public static final String EMBED_STORE_METADATA_USER_NAME = "username";
    public static final String EMBED_STORE_METADATA_KNOWLEDGEID = "knowledgeId";
    public static final String EMBED_STORE_METADATA_DOCNAME = "docName";
    public static final String EMBED_STORE_CREATE_TIME = "createTime";
    
    private static final int DEFAULT_SEGMENT_SIZE = 1000;     // 默认分段长度
    private static final int DEFAULT_OVERLAP_SIZE = 50;       // 默认重叠
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 4000;
    
    /** 向量库实例缓存：key = modelId + host:port:database */
    private static final ConcurrentHashMap<String, EmbeddingStore<TextSegment>> EMBED_STORE_CACHE = new ConcurrentHashMap<>();
    
    private static final Pattern PATTERN_MD_IMAGE = Pattern.compile("!\\[(.*?)]\\((.*?)\\)");
    private static final Pattern PATTERN_HTML_TABLE = Pattern.compile("(?is)<table\\b.*?</table>");    // #9551
}
```

---

## 二、`embeddingDocument(knowId, doc)` —— 向量化文档主入口

完整代码 [03-controller-knowledge.md#3.1](03-controller-knowledge.md)。关键流程图：

```
1. 读 AiragKnowledge
2. 按 type 分发解析：file → parseFile; web → parseWebPage; text → 跳过
3. title + "\n\n" + content
4. getEmbedModelData() → EmbeddingModel
5. getEmbedStore() → PgVectorEmbeddingStore
6. embeddingStore.removeAll(docId)  // ★ 删旧
7. createDocumentSplitter(doc)
8. 分段 + 嵌入：
     - HTML 表格：splitDocumentPreservingHtmlTables
     - 普通：EmbeddingStoreIngestor.ingest()
9. 返回 Metadata.toMap()
```

下面解读关键私有方法。

---

## 三、`parseFile(doc)` —— Tika / POI / MinerU 解析文件

```java
private String parseFile(AiragKnowledgeDoc doc) {
    String metadata = doc.getMetadata();
    AssertUtils.assertNotEmpty("请先上传文件", metadata);
    JSONObject metadataJson = JSONObject.parseObject(metadata);
    if (!metadataJson.containsKey(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH)) {
        throw new JeecgBootException("请先上传文件");
    }
    String filePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
    AssertUtils.assertNotEmpty("请先上传文件", filePath);
    filePath = ensureFile(filePath);   // ★ 路径遍历防护
    
    File docFile = new File(filePath);
    if (docFile.exists()) {
        Document document = new TikaDocumentParser(
            AutoDetectParser::new, null, null, null).parse(docFile);   // ★ Tika + POI 自动选
        if (null != document) {
            String content = document.text();
            // ★ MD 文件特殊处理：本地图路径 → 网络路径
            String fileType = FilenameUtils.getExtension(docFile.getName());
            if ("md".contains(fileType)) {
                String baseUrl = "#{domainURL}/sys/common/static/";
                String sourcePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH);
                if (oConvertUtils.isNotEmpty(sourcePath)) {
                    String escapedPath = uploadpath;
                    sourcePath = sourcePath.replaceFirst("^" + escapedPath, "").replace("\\", "/");
                    String docFilePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
                    docFilePath = FilenameUtils.getPath(docFilePath);
                    docFilePath = docFilePath.replace("\\", "/");
                    StringBuffer sb = replaceImageUrl(content, baseUrl + sourcePath + "/", baseUrl + docFilePath);
                    content = sb.toString();
                }
            }
            return content;
        }
    }
    return null;
}
```

详见 `TikaDocumentParser.java` 在 [15-handler-parsers.md](15-handler-parsers.md)。

---

## 四、`createDocumentSplitter(doc)` —— 自定义分段器选择

```java
private DocumentSplitter createDocumentSplitter(AiragKnowledgeDoc doc) {
    DocumentSplitter splitter = null;
    int maxSegment = DEFAULT_SEGMENT_SIZE;     // 1000
    int overlapSize = DEFAULT_OVERLAP_SIZE;     // 50

    if (oConvertUtils.isNotEmpty(doc.getMetadata())) {
        try {
            JSONObject json = JSONObject.parseObject(doc.getMetadata());
            
            // ★ #QQYUN-14932 使用知识库默认分段策略
            Boolean useKnowledgeDefault = json.getBoolean(LLMConsts.USE_KNOWLEDGE_DEFAULT);
            if (Boolean.TRUE.equals(useKnowledgeDefault)) {
                if (oConvertUtils.isNotEmpty(doc.getKnowledgeId())) {
                    AiragKnowledge knowledge = airagKnowledgeMapper.selectById(doc.getKnowledgeId());
                    if (knowledge != null && oConvertUtils.isNotEmpty(knowledge.getMetadata())) {
                        // ★ 用知识库的 metadata 覆盖
                        json = JSONObject.parseObject(knowledge.getMetadata());
                    } else {
                        return DocumentSplitters.recursive(maxSegment, overlapSize);
                    }
                } else {
                    return DocumentSplitters.recursive(maxSegment, overlapSize);
                }
            }
            
            // ★ #issue/9418 优先使用前端传入的分段大小和重叠度
            Object segmentStrategy = json.get(LLMConsts.SEGMENT_STRATEGY);
            Integer sizeObj = json.getInteger(LLMConsts.MAX_SEGMENT);
            if (sizeObj != null && sizeObj > 0) maxSegment = sizeObj;
            Double overlapObj = json.getDouble(LLMConsts.OVERLAP);
            if (overlapObj != null && overlapObj >= 0) {
                double rate = overlapObj / 100;
                overlapSize = (int) (maxSegment * rate);
            }
            
            if (segmentStrategy != null && LLMConsts.SEGMENT_STRATEGY_CUSTOM.equals(segmentStrategy.toString())) {
                // ★ 自定义分段
                String splitChar = json.getString(LLMConsts.SEPARATOR);
                if (oConvertUtils.isNotEmpty(splitChar)) {
                    if (LLMConsts.SEGMENT_STRATEGY_CUSTOM.equals(splitChar)) {
                        // "custom" → 用 CUSTOM_SEPARATOR 字段
                        splitChar = oConvertUtils.getString(json.getString(LLMConsts.CUSTOM_SEPARATOR), "\n");
                    }
                    // ★ 处理转义字符
                    splitChar = splitChar.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
                    String textRules = json.getString(LLMConsts.TEXT_RULES);
                    splitter = new CustomDocumentSplitter(textRules, splitChar, maxSegment, overlapSize);
                }
            }
        } catch (Exception e) {
            log.warn("解析自定义分词配置失败: {}", e.getMessage());
        }
    }

    // 默认：langchain4j recursive
    if (splitter == null) {
        splitter = DocumentSplitters.recursive(maxSegment, overlapSize);
    }
    return splitter;
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 280-294 | **#QQYUN-14932**：如果文档 metadata 标志 `useKnowledgeDefault=true` → 用知识库 metadata 覆盖 |
| 296-307 | **#issue/9418**：用户前端传入的 `maxSegment` / `overlap` 字段优先 |
| 308-321 | **自定义分隔符策略**：用户配置 `"separator": "\n"` 或自定义字符串；带 `\\n` 等转义 |
| 322-326 | 默认 → `DocumentSplitters.recursive(maxSegment, overlapSize)` |

**分段策略参数**（`LLMConsts` 全部常量键）：
- `MAX_SEGMENT` —— 单段最大字符数
- `OVERLAP` —— 重叠率（百分比 0-90）
- `SEPARATOR` —— 分隔符（`\n`、`\n\n`、`。`、`！`、`?`、`.`、`!`、`?`、`custom`）
- `CUSTOM_SEPARATOR` —— 自定义字符串
- `TEXT_RULES` —— 文本预处理（`cleanSpaces` 替换多空格换行；`removeUrlsEmails` 删 URL/邮箱）

---

## 五、`splitDocumentPreservingHtmlTables` —— HTML 表格完整保留（issues/9551）

```java
public static List<TextSegment> splitDocumentPreservingHtmlTables(Document document, DocumentSplitter splitter) {
    String text = document.text();
    Metadata metadata = document.metadata();
    List<TextSegment> result = new ArrayList<>();
    Matcher matcher = PATTERN_HTML_TABLE.matcher(text);   // (?is)<table\b.*?</table>
    int lastEnd = 0;
    while (matcher.find()) {
        String before = text.substring(lastEnd, matcher.start());
        if (!before.isBlank()) {
            appendSplitText(before, metadata, splitter, result);    // 表格前的文本走普通分段
        }
        appendSegment(matcher.group(), metadata, result);         // ★ 表格完整保留为一个段
        lastEnd = matcher.end();
    }
    String remaining = text.substring(lastEnd);
    if (!remaining.isBlank()) {
        appendSplitText(remaining, metadata, splitter, result);
    }
    reindexSegments(result);                                     // ★ 给每个段写 index 元数据
    return result;
}

public static void appendSplitText(String text, Metadata metadata, DocumentSplitter splitter, List<TextSegment> result) {
    List<TextSegment> segments = splitter.split(Document.from(text, metadata));
    result.addAll(segments);
}

public static void appendSegment(String text, Metadata metadata, List<TextSegment> result) {
    result.add(TextSegment.from(text, metadata));
}

public static void reindexSegments(List<TextSegment> segments) {
    for (int i = 0; i < segments.size(); i++) {
        segments.get(i).metadata().put("index", String.valueOf(i));
    }
}
```

**修复动机**：之前 HTML 表格在向量化分段时被 splitter 切开，每行散落在不同段，检索时 LLM 拿到的上下文被切断。修复后表格作为单个完整段保留，前后文本仍走普通分段。

---

## 六、`embeddingSearch(knowIds, queryText, topNumber, similarity)` —— 多知识库检索

完整代码 [03-controller-knowledge.md#三](03-controller-knowledge.md) 引用。核心逻辑：

```java
public KnowledgeSearchResult embeddingSearch(List<String> knowIds, String queryText, Integer topNumber, Double similarity) {
    AssertUtils.assertNotEmpty("请选择知识库", knowIds);
    AssertUtils.assertNotEmpty("请填写查询内容", queryText);

    topNumber = oConvertUtils.getInteger(topNumber, 5);

    List<Map<String, Object>> documents = new ArrayList<>(16);
    for (String knowId : knowIds) {
        List<Map<String, Object>> searchResp = searchEmbedding(knowId, queryText, topNumber, similarity);
        if (oConvertUtils.isObjectNotEmpty(searchResp)) {
            documents.addAll(searchResp);
        }
    }

    StringBuilder data = new StringBuilder();
    // ★ #QQYUN-14479 记忆库按 createTime desc，普通库按 score desc
    boolean memoryMode = false;
    if (knowIds.size() == 1) {
        String firstId = knowIds.get(0);
        if (oConvertUtils.isNotEmpty(firstId)) {
            AiragKnowledge k = airagKnowledgeMapper.getByIdIgnoreTenant(firstId);
            memoryMode = (k != null && LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(k.getType()));
        }
    }
    
    List<Map<String, Object>> prepared = documents.stream()
        .sorted(memoryMode
            ? Comparator.comparingLong((Map<String, Object> doc) -> oConvertUtils.getLong(doc.get(EMBED_STORE_CREATE_TIME), 0L)).reversed()
            : Comparator.comparingDouble((Map<String, Object> doc) -> (Double) doc.get("score")).reversed())
        .collect(Collectors.toList());

    List<Map<String, Object>> limited = new ArrayList<>();
    for (Map<String, Object> doc : prepared) {
        if (limited.size() >= topNumber) break;
        String content = oConvertUtils.getString(doc.get("content"), "");
        int remain = DEFAULT_MAX_OUTPUT_CHARS - data.length();
        if (remain <= 0) break;
        if (content.length() <= remain) {
            data.append(content).append("\n");
            limited.add(doc);
        } else {
            data.append(content, 0, remain);
            limited.add(doc);
            break;
        }
    }
    return new KnowledgeSearchResult(data.toString(), limited);
}
```

**逐行解读**：
- L401-405 默认 topNumber = 5
- L407-414 多知识库：每个库先单独检索，合并结果
- L416-425 **#QQYUN-14479** 重要改动：判断 `knowIds.size()==1 && 单个库的 type=memory` → **记忆库按 `createTime` desc 排序**，否则按 score 倒序
- L427-440 截断：`DEFAULT_MAX_OUTPUT_CHARS=4000`（不要一次返回太多 token，否则塞满 LLM 上下文窗口）

---

## 七、`searchEmbedding(knowId, queryText, topNumber, similarity)` —— 单库检索

```java
public List<Map<String, Object>> searchEmbedding(String knowId, String queryText, Integer topNumber, Double similarity) {
    AssertUtils.assertNotEmpty("请选择知识库", knowId);
    AiragKnowledge knowledge = airagKnowledgeMapper.getByIdIgnoreTenant(knowId);
    AssertUtils.assertNotEmpty("知识库不存在", knowledge);
    AssertUtils.assertNotEmpty("请填写查询内容", queryText);
    AiragModel model = getEmbedModelData(knowledge.getEmbedId());

    AiModelOptions modelOp = buildModelOptions(model);
    EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
    Embedding queryEmbedding = embeddingModel.embed(queryText).content();

    topNumber = oConvertUtils.getInteger(topNumber, modelOp.getTopNumber());
    similarity = oConvertUtils.getDou(similarity, modelOp.getSimilarity());
    
    // ★ 过滤：knowledgeId 必加；如果 memory 类型，加 username 隔离
    Filter filter = metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId);
    if (LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(knowledge.getType())) {
        try {
            HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
            String token = TokenUtils.getTokenByRequest(request);
            String username = JwtUtil.getUsername(token);
            if (oConvertUtils.isNotEmpty(username)) {
                filter = new And(filter, metadataKey(EMBED_STORE_METADATA_USER_NAME).isEqualTo(username));
            }
        } catch (Exception e) {
            log.info("构建过滤器异常,{}", e.getMessage());
        }
    }

    EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(topNumber)
        .minScore(similarity)
        .filter(filter)
        .build();

    EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
    List<EmbeddingMatch<TextSegment>> relevant = embeddingStore.search(embeddingSearchRequest).matches();

    List<Map<String, Object>> result = new ArrayList<>();
    if (oConvertUtils.isObjectNotEmpty(relevant)) {
        result = relevant.stream().map(matchRes -> {
            Map<String, Object> data = new HashMap<>();
            data.put("score", matchRes.score());
            data.put("content", matchRes.embedded().text());
            Metadata metadata = matchRes.embedded().metadata();
            data.put("chunk", metadata.getInteger("index"));    // ★ 段在文档中的位置
            data.put(EMBED_STORE_METADATA_DOCNAME, metadata.getString(EMBED_STORE_METADATA_DOCNAME));
            String ct = metadata.getString(EMBED_STORE_CREATE_TIME);
            data.put(EMBED_STORE_CREATE_TIME, ct);             // #QQYUN-14479 排序用
            return data;
        }).collect(Collectors.toList());
    }
    return result;
}
```

**关键点**：
- **`filter`** — 双重条件：知识库 ID 必加，记忆库（`type=memory`）+ 当前 username 强隔离（QQYUN-14265）
- **`langchain4j EmbeddingMatch`** — 拿到相似度 `score()`、段文本 `embedded().text()`、metadata 中含 `index`（chunk 在原文档中的位置）

---

## 八、`getQueryRouter(knowIds, topNumber, similarity)` —— RAG 路由器构造

```java
@Override
public QueryRouter getQueryRouter(List<String> knowIds, Integer topNumber, Double similarity) {
    AssertUtils.assertNotEmpty("请选择知识库", knowIds);
    List<ContentRetriever> retrievers = Lists.newArrayList();
    for (String knowId : knowIds) {
        if (oConvertUtils.isEmpty(knowId)) continue;
        AiragKnowledge knowledge = airagKnowledgeMapper.getByIdIgnoreTenant(knowId);
        AssertUtils.assertNotEmpty("知识库不存在", knowledge);
        AiragModel model = getEmbedModelData(knowledge.getEmbedId());
        AiModelOptions modelOptions = buildModelOptions(model);
        EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOptions);
        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);

        topNumber = oConvertUtils.getInteger(topNumber, 5);
        similarity = oConvertUtils.getDou(similarity, 0.75);

        // ★ 同样的过滤：knowledgeId + memory 类型加 username
        Filter filter = metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId);
        if (LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(knowledge.getType())) {
            try {
                HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
                String token = TokenUtils.getTokenByRequest(request);
                String username = JwtUtil.getUsername(token);
                if (oConvertUtils.isNotEmpty(username)) {
                    filter = new And(filter, metadataKey(EMBED_STORE_METADATA_USER_NAME).isEqualTo(username));
                }
            } catch (Exception e) {
                log.info("构建过滤器异常,{}", e.getMessage());
            }
        }

        // ★ langchain4j ContentRetriever —— 每次 LLM 调用时自动检索
        EmbeddingStoreContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(topNumber)
            .minScore(similarity)
            .filter(filter)
            .build();
        retrievers.add(contentRetriever);
    }
    if (retrievers.isEmpty()) {
        return null;
    } else {
        return new DefaultQueryRouter(retrievers);    // ★ 多 router 路由
    }
}
```

**关键设计**：
- 每个 knowledgeId 构造一个 `EmbeddingStoreContentRetriever`
- 用 `DefaultQueryRouter(retrievers)` 聚合 —— **聊天时 RAG 自动检索、拼接 system message**
- 这个 router 是被 `AIChatHandler.mergeParams` 注入 `params.queryRouter`

---

## 九、`getEmbedStore(model)` —— PgVector 客户端构造与缓存

完整代码 [03-controller-knowledge.md#3.5](03-controller-knowledge.md)。关键：

```java
private EmbeddingStore<TextSegment> getEmbedStore(AiragModel model) {
    AssertUtils.assertNotEmpty("未配置模型", model);
    String modelId = model.getId();
    String connectionInfo = embedStoreConfigBean.getHost() + embedStoreConfigBean.getPort() + embedStoreConfigBean.getDatabase();
    String key = modelId + connectionInfo;
    if (EMBED_STORE_CACHE.containsKey(key)) {
        return EMBED_STORE_CACHE.get(key);   // ★ 进程级缓存
    }
    
    AiModelOptions modelOp = buildModelOptions(model);
    EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
    
    String tableName = embedStoreConfigBean.getTable();
    
    // ★ #QQYUN-12345 维度不一致表名后缀
    int dimension = embeddingModel.dimension();
    if (!LLMConsts.EMBED_MODEL_DEFAULT_DIMENSION.equals(dimension)) {
        tableName += ("_" + dimension);
    }
    
    EmbeddingStore<TextSegment> embeddingStore = PgVectorEmbeddingStore.builder()
        .host(embedStoreConfigBean.getHost())
        .port(embedStoreConfigBean.getPort())
        .database(embedStoreConfigBean.getDatabase())
        .user(embedStoreConfigBean.getUser())
        .password(embedStoreConfigBean.getPassword())
        .table(tableName)
        .dimension(embeddingModel.dimension())
        .useIndex(true)
        .indexListSize(100)         // IVFFlat lists
        .createTable(true)
        .dropTableFirst(false)       // ★ 不要 drop，避免丢数据
        .build();
    
    EMBED_STORE_CACHE.put(key, embeddingStore);
    return embeddingStore;
}
```

**关键点**：
- **EMBED_STORE_CACHE**：`ConcurrentHashMap<String, EmbeddingStore<TextSegment>>`，**进程级**缓存
  - key = `modelId + host:port:database`
  - 但模型切换维度时不会刷新（缓存命中会跳过维度校验），实际应定期清缓存
- **表名后缀**：维度 ≠ 1536 自动 `_维度` 后缀，**同一 PG 实例可支持多模型**
- **IVFFlat index**：`useIndex(true)`, `indexListSize(100)` —— pgvector 索引

---

## 十、`getEmbedModelData(modelId)` —— 模型降级链路

完整代码 [03-controller-knowledge.md#3.6](03-controller-knowledge.md)。降级链路：
1. 用 modelId 查 AiragModel（必须 `modelType='EMBED'`）
2. **未激活 → 不报错，回退到 `yml` 默认向量模型**（QQYUN-14645）
3. `yml` 也未配置 → 抛 `assertNotEmpty`

---

## 十一、`parseWebPage(doc)` —— Jsoup + Markdown

```java
private String parseWebPage(AiragKnowledgeDoc doc) {
    String metadata = doc.getMetadata();
    AssertUtils.assertNotEmpty("请先配置网页URL", metadata);
    JSONObject metadataJson = JSONObject.parseObject(metadata);
    String website = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_WEBSITE);
    AssertUtils.assertNotEmpty("请先配置网页URL", website);

    // URL 格式校验
    Matcher matcher = LLMConsts.WEB_PATTERN.matcher(website);
    if (!matcher.matches()) {
        throw new JeecgBootException("网页URL格式不正确，请以http://或https://开头");
    }

    try {
        WebPageParser webPageParser = new WebPageParser();
        String content = webPageParser.parseToMarkdown(website);
        if (oConvertUtils.isEmpty(content)) {
            throw new JeecgBootException("网页内容为空，请检查URL是否可访问");
        }
        log.info("网页解析成功, URL: {}, 内容长度: {}", website, content.length());
        return content;
    } catch (JeecgBootException e) {
        throw e;
    } catch (Exception e) {
        log.error("网页解析失败, URL: {}, 错误: {}", website, e.getMessage(), e);
        throw new JeecgBootException("网页解析失败: " + e.getMessage());
    }
}
```

---

## 十二、`parseFileByMinerU(doc)` —— magic-pdf 高质量 PDF 解析

完整代码 [03-controller-knowledge.md#3.3](03-controller-knowledge.md)。要点：
- 仅在 `knowConfigBean.isEnableMinerU()=true` 时启用
- `CommandExecUtil.execCommand(["conda", "run", "-n", env, "magic-pdf"], args)`
- 失败降级到普通 Tika

---

## 十三、`ensureFile(filePath)` —— 路径遍历防护

完整代码 [03-controller-knowledge.md#四](03-controller-knowledge.md) + [18-security.md](18-security.md)。

---

## 十四、`deleteEmbedDocsByKnowId` / `deleteEmbedDocsByDocIds`

```java
public void deleteEmbedDocsByKnowId(String knowId, String modelId) {
    AssertUtils.assertNotEmpty("选择知识库", knowId);
    AiragModel model = getEmbedModelData(modelId);
    EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
    embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId));
}

public void deleteEmbedDocsByDocIds(List<String> docIds, String modelId) {
    AssertUtils.assertNotEmpty("选择文档", docIds);
    AiragModel model = getEmbedModelData(modelId);
    EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
    embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isIn(docIds));   // ★ IN 列表
}
```

被 `AirragKnowledgeDocServiceImpl.removeByKnowIds` / `removeDocByIds` 异步调用（删除知识库或文档时同步删向量）。

---

## 十五、`buildModelOptions(AiragModel)` —— 静态工具

完整代码 [04-controller-model.md#四 §4.2](04-controller-model.md)。

`mergeParams` / `completions` / `imageGenerate` 等都调用此方法构造 `AiModelOptions`。

---

## 十六、`replaceImageUrl` 静态工具

`parseFile` 中处理 Markdown 图片的本地路径 → 网络路径替换。正则匹配 `!\[alt](url)` 语法：

```java
private static StringBuffer replaceImageUrl(String content, String abstractBaseUrl, String relativeBaseUrl) {
    Matcher matcher = PATTERN_MD_IMAGE.matcher(content);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
        String imageUrl = matcher.group(2);
        // ★ 网络图直接用
        if (imageUrl.startsWith("http")) {
            matcher.appendReplacement(sb, "![" + matcher.group(1) + "](" + imageUrl + ")");
        } else {
            // ★ 本地图 → 网络图
            String networkImageUrl;
            if(imageUrl.startsWith("/")) {
                networkImageUrl = abstractBaseUrl + imageUrl;     // 绝对路径
            } else {
                networkBaseUrl = relativeBaseUrl + imageUrl;     // 相对路径
            }
            // ★ 防 `//` → `/`（保留 http://）
            networkImageUrl = networkImageUrl.replaceAll("(?<!http:)(?<!https:)//", "/");
            matcher.appendReplacement(sb, "![" + matcher.group(1) + "](" + networkImageUrl + ")");
        }
    }
    matcher.appendTail(sb);
    return sb;
}
```

**关键正则**：`(?<!http:)(?<!https:)//` —— 负向后顾，避免误把 `http://` 中的 `//` 替换成 `/`。

---

## 十七、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/handler/EmbeddingHandler.java` | 988 | 本文档主体 |
| `llm/document/TikaDocumentParser.java` | 294 | [15-handler-parsers.md](15-handler-parsers.md) |
| `llm/document/WebPageParser.java` | — | Jsoup 抓网页 |
| `llm/splitter/CustomDocumentSplitter.java` | — | 自定义分段 |
| `llm/config/EmbedStoreConfigBean.java` | 48 | pgvector 连接配置 |
| `llm/config/KnowConfigBean.java` | — | MinerU 开关 |
| `llm/consts/LLMConsts.java` | 222 | 常量（DEFAULT_SEGMENT_SIZE / DIMENSION 等） |
| `llm/mapper/AiragKnowledgeMapper.java` | — | `getByIdIgnoreTenant` |
| `common/handler/IEmbeddingHandler.java` | — | 接口（base-core） |
| `common/vo/knowledge/KnowledgeSearchResult.java` | — | 检索结果封装 |

---

## 十八、下一章

[14-handler-plugin.md](14-handler-plugin.md) — `PluginToolBuilder` + `JeecgToolsProvider` 完整解读。
