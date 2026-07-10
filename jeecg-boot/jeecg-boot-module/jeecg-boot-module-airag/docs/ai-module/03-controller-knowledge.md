# 03 · `/airag/knowledge/*` 控制器 · 完整解读

> 本文档以 `POST /airag/knowledge/doc/edit`（添加知识库文档 → 触发向量化）为主线，从 HTTP 请求落到 `AirragKnowledgeDocServiceImpl.editDocument` → `rebuildDocument` → 异步线程内 `EmbeddingHandler.embeddingDocument` → `parseFile` / `parseWebPage` → 分段 → `embeddingModel.embedAll` → `PgVector` 存储，**整条调用链不断裂**。
> 其他端点（CRUD、`embedding/search`、`plugin/add`、`plugin/query` 等）在末尾第 6 节列出。

---

## 一、路由总览

| 方法 | 路径 | 来源 |
|------|------|------|
| `GET` | `/airag/knowledge/list` | `queryPageList` |
| `POST` | `/airag/knowledge/add` | `add` |
| `PUT,POST` | `/airag/knowledge/edit` | `edit` |
| `PUT` | `/airag/knowledge/rebuild` | `rebuild` |
| `DELETE` | `/airag/knowledge/delete` | `delete` |
| `GET` | `/airag/knowledge/queryById` | `queryById` |
| `GET` | `/airag/knowledge/doc/list` | `queryDocumentPageList` |
| `POST` | `/airag/knowledge/doc/edit` | `addDocument` |
| `POST` | `/airag/knowledge/doc/import/zip` | `importDocumentFromZip` |
| `GET` | `/airag/knowledge/doc/import/task/list` | `importDocumentTaskList` |
| `PUT` | `/airag/knowledge/doc/rebuild` | `rebuildDocument` |
| `DELETE` | `/airag/knowledge/doc/deleteBatch` | `deleteDocumentBatch` |
| `DELETE` | `/airag/knowledge/doc/deleteAll` | `deleteDocumentAll` |
| `GET` | `/airag/knowledge/embedding/hitTest/{knowId}` | `hitTest` |
| `GET` | `/airag/knowledge/embedding/search` | `embeddingSearch` |
| `GET` | `/airag/knowledge/query/batch/byId` | `queryBatchByIds` |
| `POST` | `/airag/knowledge/plugin/add` | 追加记忆（`add` 重载） |
| `POST` | `/airag/knowledge/plugin/query` | 查询记忆 |

文件：`AiragKnowledgeController.java`（427 行）。

---

## 二、入口：`POST /airag/knowledge/doc/edit`

### 2.1 控制器（AirragKnowledgeController.java:215-219）

```java
@PostMapping(value = "/doc/edit")
@RequiresPermissions("airag:knowledge:doc:edit")
public Result<?> addDocument(@RequestBody AiragKnowledgeDoc airagKnowledgeDoc) {
    return airagKnowledgeDocService.editDocument(airagKnowledgeDoc);
}
```

`@RequiresPermissions("airag:knowledge:doc:edit")` —— Shiro 鉴权，需用户拥有 `airag:knowledge:doc:edit` 权限。

入参 `AiragKnowledgeDoc` 字段定义见 §五。

### 2.2 Service：`AirragKnowledgeDocServiceImpl.editDocument`（L93-112）

```java
@Transactional(rollbackFor = {Exception.class})
@Override
public Result<?> editDocument(AiragKnowledgeDoc airagKnowledgeDoc) {
    AssertUtils.assertNotEmpty("文档不能未空", airagKnowledgeDoc);
    AssertUtils.assertNotEmpty("知识库不能未空", airagKnowledgeDoc.getKnowledgeId());
    AssertUtils.assertNotEmpty("文档标题不能未空", airagKnowledgeDoc.getTitle());
    AssertUtils.assertNotEmpty("文档类型不能未空", airagKnowledgeDoc.getType());
    
    if (KNOWLEDGE_DOC_TYPE_TEXT.equals(airagKnowledgeDoc.getType())) {
        AssertUtils.assertNotEmpty("文档内容不能为空", airagKnowledgeDoc.getContent());
    }
    
    airagKnowledgeDoc.setStatus(KNOWLEDGE_DOC_STATUS_DRAFT);  // "draft"
    
    // 保存到数据库
    if (this.saveOrUpdate(airagKnowledgeDoc)) {
        // 重建向量
        return this.rebuildDocument(airagKnowledgeDoc.getId());
    } else {
        return Result.error("保存失败");
    }
}
```

**逐行解读**：

| 行 | 行为 |
|---|------|
| 96-101 | 5 条校验：文档非空、知识库 ID 非空、标题非空、类型非空、`type=text` 时还要内容非空 |
| 104 | 初始状态 `draft`（草稿） |
| 106 | MyBatis-Plus `saveOrUpdate` —— 根据 `id` 自动选 INSERT 或 UPDATE |
| 108 | 保存成功后立即触发**异步向量化** |

### 2.3 异步向量化：`rebuildDocument(docIds)`（L125-196）

```java
@Transactional(rollbackFor = {java.lang.Exception.class})
@Override
public Result<?> rebuildDocument(String docIds) {
    AssertUtils.assertNotEmpty("请选择要重建的文档", docIds);
    List<String> docIdList = Arrays.asList(docIds.split(","));
    
    // 1. 查所有文档
    List<AiragKnowledgeDoc> docList = airagKnowledgeDocMapper.selectBatchIds(docIdList);
    AssertUtils.assertNotEmpty("文档不存在", docList);
    
    // 2. 状态过滤（修复 stuck 在"building" 的文档：QQYUN-11943）
    List<AiragKnowledgeDoc> knowledgeDocs = docList.stream()
        .filter(doc -> {
            if (KNOWLEDGE_DOC_STATUS_BUILDING.equalsIgnoreCase(doc.getStatus())) {
                Date updateTime = doc.getUpdateTime();
                if (updateTime != null) {
                    // 向量化超过 5 分钟，重新触发
                    long timeDifference = System.currentTimeMillis() - updateTime.getTime();
                    return timeDifference > 5 * 60 * 1000;   // 5 min
                } else {
                    return true;
                }
            }
            return true;
        })
        .peek(doc -> doc.setStatus(KNOWLEDGE_DOC_STATUS_BUILDING))   // 标记为 BUILDING
        .collect(Collectors.toList());
    
    if (oConvertUtils.isObjectEmpty(knowledgeDocs)) return Result.ok("操作成功");
    
    // 3. 持久化状态
    this.updateBatchById(knowledgeDocs);
    
    // 4. ★ 异步触发向量化
    String tenantId = TenantContext.getTenant();
    String token = TokenUtils.getTokenByRequest();
    
    knowledgeDocs.forEach((doc) -> {
        CompletableFuture.runAsync(() -> {
            // ★ 透传租户和 Token 到异步线程
            UserTokenContext.setToken(token);
            TenantContext.setTenant(tenantId);
            
            String knowId = doc.getKnowledgeId();
            log.info("开始重建文档, 知识库id: {}, 文档id: {}", knowId, doc.getId());
            doc.setStatus(KNOWLEDGE_DOC_STATUS_BUILDING);
            this.updateById(doc);
            
            try {
                Map<String, Object> metadata = embeddingHandler.embeddingDocument(knowId, doc);
                if (null != metadata) {
                    doc.setStatus(KNOWLEDGE_DOC_STATUS_COMPLETE);  // 成功
                    this.updateById(doc);
                } else {
                    this.handleDocBuildFailed(doc, "向量化失败");
                }
            } catch (Throwable t) {
                this.handleDocBuildFailed(doc, t.getMessage());
                log.error("重建文档失败: " + t.getMessage(), t);
            }
        }, buildDocExecutorService);   // ★ 10 线程固定线程池
    });
    
    log.info("返回操作成功");
    return Result.ok("操作成功");
}
```

**逐行解读**：

| 行 | 行为 | 关键点 |
|---|------|--------|
| 131 | docIds 是逗号分隔字符串，拆分成 List | String.split 正则拆分 |
| 136-150 | **状态过滤（stuck detection）** | 如果文档卡在 `building` 状态超过 5 分钟 → 重新触发。修复 [QQYUN-11943]：之前上传后一直显示"构建中"的 bug |
| 152 | `peek` 标记为 `BUILDING` | 不触发副作用，仅 set 字段 |
| 162 | 持久化新状态 | updateBatchById |
| 165-166 | 透传 `tenantId` 和 `token` 到异步线程 | `@Async` 切线程后 ThreadLocal 上下文丢失，必须手动重新设 |
| 167-193 | `CompletableFuture.runAsync(...)` | **不阻塞**：HTTP 立即返回 `操作成功`，实际向量化在后台线程跑 |
| 187 | `try { embeddingHandler.embeddingDocument(...) }` | ★ 真正调 EmbeddingHandler |
| 195 | 10 线程固定线程池 | `Executors.newFixedThreadPool(10)` |
| 197-215 | 异常处理 `handleDocBuildFailed` | 写入 `status=FAILED` + `metadata.failedReason=错误信息` |

### 2.4 `editDocument` → `rebuildDocument("单id")` 整图

```
editDocument(doc) 
   └─ saveOrUpdate(doc) → status=draft
       └─ rebuildDocument(doc.id)
           ├─ selectBatchIds([id])
           ├─ filter → status=building (updatable)
           ├─ updateBatchById
           └─ CompletableFuture.runAsync:
               └─ embeddingHandler.embeddingDocument(knowId, doc)
                   ├─ 解析 → 标题拼接
                   ├─ createDocumentSplitter(doc)
                   ├─ embeddingModel.embedAll(segments)
                   ├─ embeddingStore.removeAll(docId)  // 删旧
                   ├─ embeddingStore.addAll(embeddings, segments)
                   └─ 返回 metadata map
                   ↓
                   onSuccess: status=complete
                   onFailure: status=failed + metadata.failedReason
```

---

## 三、核心：`EmbeddingHandler.embeddingDocument`

文件 `llm/handler/EmbeddingHandler.java`（988 行）。这是整个 RAG 链路的核心，逐段解读。

### 3.1 方法入口（L168-260）

```java
public Map<String, Object> embeddingDocument(String knowId, AiragKnowledgeDoc doc) {
    AiragKnowledge airagKnowledge = airagKnowledgeService.getById(knowId);
    AssertUtils.assertNotEmpty("知识库不存在", airagKnowledge);
    AssertUtils.assertNotEmpty("请先为知识库配置向量模型库", airagKnowledge.getEmbedId());
    AssertUtils.assertNotEmpty("文档不能为空", doc);
    
    String content = doc.getContent();
    
    // 1. ★ 解析 content（按 doc.type 分发）
    if (oConvertUtils.isEmpty(content)) {
        switch (doc.getType()) {
            case KNOWLEDGE_DOC_TYPE_FILE:    // "file"
                if (knowConfigBean.isEnableMinerU()) {
                    parseFileByMinerU(doc);   // 启用 magic-pdf
                }
                content = parseFile(doc);
                break;
            case KNOWLEDGE_DOC_TYPE_WEB:     // "web"
                content = parseWebPage(doc);  // Jsoup → Markdown
                doc.setContent(content);      // 回写，便于后续查看
                break;
        }
    }
    
    // 2. title + content 拼接
    if (oConvertUtils.isNotEmpty(doc.getTitle())) {
        content = doc.getTitle() + "\n\n" + content;
    }
    
    // 3. 模型准备
    AiragModel model = getEmbedModelData(airagKnowledge.getEmbedId());
    AiModelOptions modelOp = buildModelOptions(model);
    EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
    EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
    
    // 4. ★ 删旧向量
    embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isEqualTo(doc.getId()));
    
    // 5. 分段器（按 metadata 中的策略）
    DocumentSplitter splitter = createDocumentSplitter(doc);
    
    // 6. 元数据拼装（langchain4j 的 Metadata）
    Metadata metadata = Metadata.metadata(EMBED_STORE_METADATA_DOCID, doc.getId())
        .put(EMBED_STORE_METADATA_KNOWLEDGEID, doc.getKnowledgeId())
        .put(EMBED_STORE_METADATA_DOCNAME, FilenameUtils.getName(doc.getTitle()))
        .put(EMBED_STORE_CREATE_TIME, String.valueOf(doc.getCreateTime() != null ? doc.getCreateTime().getTime() : System.currentTimeMillis()));
    
    // 7. 用户隔离（记忆库 QQYUN-14265）
    String username = doc.getCreateBy();
    if (oConvertUtils.isEmpty(username)) {
        try {
            HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
            String token = TokenUtils.getTokenByRequest(request);
            username = JwtUtil.getUsername(token);
        } catch (Exception e) {
            username = "admin";  // 兜底
        }
    }
    if (oConvertUtils.isNotEmpty(username)) {
        metadata.put(EMBED_STORE_METADATA_USER_NAME, username);
    }
    
    // 8. ★★ 分段并嵌入
    Document from = Document.from(content, metadata);
    boolean hasHtmlTable = content != null && PATTERN_HTML_TABLE.matcher(content).find();
    
    if (hasHtmlTable) {
        // HTML 表格特殊处理（#9551）：表格完整保留
        try {
            List<TextSegment> segments = splitDocumentPreservingHtmlTables(from, splitter);
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
        } catch (Exception e) {
            log.error("向量存储失败，请检查向量模型配置是否正确", e);
            throw new JeecgBootException("向量存储失败：" + e.getMessage());
        }
    } else {
        // 普通分段
        try {
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
            ingestor.ingest(from);
        } catch (Exception e) {
            log.error("向量存储失败，请检查向量模型配置是否正确", e);
            throw new JeecgBootException("向量存储失败：" + e.getMessage());
        }
    }
    
    return metadata.toMap();
}
```

**逐行解读（重点 8 步）**：

#### 步骤 1 — 文档解析（L176-191）

```java
if (oConvertUtils.isEmpty(content)) {
    switch (doc.getType()) {
        case KNOWLEDGE_DOC_TYPE_FILE:    // "file"
            if (knowConfigBean.isEnableMinerU()) {
                parseFileByMinerU(doc);   // 启用 MinerU 时
            }
            content = parseFile(doc);
            break;
        case KNOWLEDGE_DOC_TYPE_WEB:     // "web"
            content = parseWebPage(doc);
            doc.setContent(content);
            break;
    }
}
```

只有 `content` 为空时才走解析路径（即 `type=text` 时前端已经传了 content，直接跳到 §2）。

#### 步骤 2 — 标题拼接（L193-196）

```java
if (oConvertUtils.isNotEmpty(doc.getTitle())) {
    content = doc.getTitle() + "\n\n" + content;
}
```

把标题塞到正文前面，向量化时检索命中包含标题（**#QQYUN-11443 修复**：标题一般有语义，应当也参与检索匹配）。

#### 步骤 3 — 模型与向量库准备（L199-202）

```java
AiragModel model = getEmbedModelData(airagKnowledge.getEmbedId());
AiModelOptions modelOp = buildModelOptions(model);
EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);   // ★ langchain4j
EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
```

- `getEmbedModelData(modelId)` —— 见 [13-handler-embedding.md#三](13-handler-embedding.md)。模型未激活回退到 `yml` 默认
- `AiModelFactory.createEmbeddingModel(modelOp)` —— base-core 工厂方法，按 `provider` 决定 OpenAI / 智普 / 通义 等
- `getEmbedStore(model)` —— 见 §3.5

#### 步骤 4 — 删旧向量（L204）

```java
embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isEqualTo(doc.getId()));
```

**幂等更新**：用 metadata 过滤条件精确删除这个 doc 之前的所有向量段，避免每次重建时积累重复数据。

#### 步骤 5 — 分段器选择（L206）

```java
DocumentSplitter splitter = createDocumentSplitter(doc);
```

详见 [13-handler-embedding.md#四 自定义分段器选择](13-handler-embedding.md)。

#### 步骤 6 — 元数据拼装（L213-217）

```java
Metadata metadata = Metadata.metadata(EMBED_STORE_METADATA_DOCID, doc.getId())
    .put(EMBED_STORE_METADATA_KNOWLEDGEID, doc.getKnowledgeId())
    .put(EMBED_STORE_METADATA_DOCNAME, FilenameUtils.getName(doc.getTitle()))
    .put(EMBED_STORE_CREATE_TIME, String.valueOf(
        doc.getCreateTime() != null ? doc.getCreateTime().getTime() : System.currentTimeMillis()));
```

5 个 metadata 字段：
- `docId` —— 用于重建时定位
- `knowledgeId` —— 检索时按知识库过滤
- `docName` —— 用于前端展示
- `createTime` —— QQYUN-14479 优化：记忆库按时间排序
- `username` —— QQYUN-14265 优化：记忆库按用户隔离

#### 步骤 7 — 分段并嵌入（L235-256）

两路分支：

**HTML 表格路径**（`#9551` 修复）：
```java
if (hasHtmlTable) {
    List<TextSegment> segments = splitDocumentPreservingHtmlTables(from, splitter);
    List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
    embeddingStore.addAll(embeddings, segments);
}
```

`splitDocumentPreservingHtmlTables` 是关键：先把 HTML 表格完整切出来作为一个独立的段，剩下的文本走正常 `splitter` 分段。这样表格不会被截断。

**普通路径**：
```java
EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
    .documentSplitter(splitter).embeddingModel(embeddingModel).embeddingStore(embeddingStore).build();
ingestor.ingest(from);   // 一站式：分段 → 嵌入 → 入库
```

`EmbeddingStoreIngestor` 是 langchain4j 的高层 API，把"分段、嵌入、写库"打包成一次调用。

### 3.2 `parseFile(doc)` —— Tika/POI 解析文件（L799-841）

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
    
    // ★ 安全：路径遍历校验
    filePath = ensureFile(filePath);
    
    File docFile = new File(filePath);
    if (docFile.exists()) {
        Document document = new TikaDocumentParser(AutoDetectParser::new, null, null, null).parse(docFile);
        if (null != document) {
            String content = document.text();
            String fileType = FilenameUtils.getExtension(docFile.getName());
            // ★ Markdown 图片本地路径 → 网络路径
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

**逐行解读**：

| 行 | 行为 |
|---|------|
| 802 | `metadata.filePath` 来自前端：上传文件接口返回的存储路径（`/airag/.../xxx.pdf`） |
| 808 | **`ensureFile(filePath)` —— 路径遍历校验**（#9425） |
| 813 | `new TikaDocumentParser(...).parse(docFile)` —— 调用 Apache Tika。`AutoDetectParser` 自动选 PDF/Word/PowerPoint 等格式解析器 |
| 817 | `content = document.text()` —— 提取纯文本 |
| 818-835 | **MD 文件特殊处理**：把 Markdown 中的本地图片路径（`![alt](file:///C:/upload/xxx.png)`）替换为网络访问路径，**前端能直接展示** |

### 3.3 `parseFileByMinerU(doc)` —— MinerU 高质量 PDF 解析（L880-940）

```java
private void parseFileByMinerU(AiragKnowledgeDoc doc) {
    String metadata = doc.getMetadata();
    AssertUtils.assertNotEmpty("请先上传文件", metadata);
    JSONObject metadataJson = JSONObject.parseObject(metadata);
    if (!metadataJson.containsKey(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH)) {
        throw new JeecgBootException("请先上传文件");
    }
    String filePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
    AssertUtils.assertNotEmpty("请先上传文件", filePath);
    filePath = ensureFile(filePath);
    
    File docFile = new File(filePath);
    String fileType = FilenameUtils.getExtension(filePath);
    if (!docFile.exists() || "txt".equalsIgnoreCase(fileType) || "md".equalsIgnoreCase(fileType)) {
        return ;   // 跳过：txt/md 不需要 magic-pdf
    }
    
    // ★ 安全：拒绝文件名注入命令
    try {
        CommandExecUtil.validateFilePath(docFile.getAbsolutePath());
        CommandExecUtil.validateFilePath(docFile.getName());
    } catch (IllegalArgumentException e) {
        log.error("文件路径包含非法字符，拒绝执行 MinerU 解析: {}", e.getMessage());
        throw new JeecgBootException("文件名包含非法字符，无法处理该文件");
    }
    
    // ★ Conda 命令构造
    String[] command;
    if (oConvertUtils.isNotEmpty(knowConfigBean.getCondaEnv())) {
        command = new String[]{"conda", "run", "-n", knowConfigBean.getCondaEnv(), "magic-pdf"};
    } else {
        command = new String[]{"magic-pdf"};
    }
    
    String outputPath = docFile.getParentFile().getAbsolutePath();
    String[] args = {
        "-p", docFile.getAbsolutePath(),
        "-o", outputPath,
    };
    
    try {
        String execLog = CommandExecUtil.execCommand(command, args);
        log.info("执行命令行:" + Arrays.toString(command) + " args:" + Arrays.toString(args) + "\n log::" + execLog);
        // magic-pdf 输出目录：{outputPath}/{fileBaseName}/auto/{fileBaseName}.md
        String fileBaseName = FilenameUtils.getBaseName(docFile.getName());
        String newFileDir = outputPath + File.separator + fileBaseName + File.separator + "auto" + File.separator ;
        File convertedFile = new File(newFileDir + fileBaseName + ".md");
        if (convertedFile.exists()) {
            log.info("文件转换成md成功,替换文件路径和静态资源路径");
            newFileDir = newFileDir.replaceFirst("^" + uploadpath, "");
            metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, newFileDir + fileBaseName + ".md");
            metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, newFileDir);
            doc.setMetadata(metadataJson.toJSONString());
        }
    } catch (IOException e) {
        log.error("文件转换md失败,使用传统提取方案{}", e.getMessage(), e);
    }
}
```

**逐行解读**：
- L895-897 白名单：`txt`/`md` 跳过 magic-pdf（直接用 Tika）
- L900-906 **文件名 Shell 注入校验**（调用 `CommandExecUtil.validateFilePath`）
- L908-915 命令构造：conda 环境存在时 `conda run -n <env> magic-pdf`，否则裸 `magic-pdf`
- L917-919 args：`-p <file>` `-o <outputDir>` 对应 magic-pdf CLI
- L922-936 替换 filePath → md 转换后的位置（后续会走 Tika + MD 路径处理）
- 失败时**降级回传统 Tika**（不抛异常）

### 3.4 `parseWebPage(doc)` —— Jsoup + Markdown（L764-790）

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

`WebPageParser`（`llm/document/WebPageParser.java`）—— Jsoup 抓 HTML 内容，转 Markdown。

### 3.5 `getEmbedStore(model)` —— PgVectorEmbeddingStore 构造（L680-729）

```java
private EmbeddingStore<TextSegment> getEmbedStore(AiragModel model) {
    AssertUtils.assertNotEmpty("未配置模型", model);
    String modelId = model.getId();
    String connectionInfo = embedStoreConfigBean.getHost() + embedStoreConfigBean.getPort() + embedStoreConfigBean.getDatabase();
    String key = modelId + connectionInfo;
    
    // ★ 缓存：避免重复构造
    if (EMBED_STORE_CACHE.containsKey(key)) {
        return EMBED_STORE_CACHE.get(key);
    }
    
    AiModelOptions modelOp = buildModelOptions(model);
    EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
    
    String tableName = embedStoreConfigBean.getTable();
    
    // ★ 维度不一致表名后缀（QQYUN-12345）
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
        .indexListSize(100)
        .createTable(true)
        .dropTableFirst(false)
        .build();
    
    EMBED_STORE_CACHE.put(key, embeddingStore);
    return embeddingStore;
}
```

**关键设计**：
- **缓存**：同一 `modelId + host:port:database` 共享一个 `EmbeddingStore`
- **表名后缀**：维度 ≠ 1536（默认）自动加 `_dimension` 后缀（[QQYUN-12345](issues/12345)）—— 同一 Postgres 实例可支持多个不同维度的嵌入模型
- 默认表名 `embeddings`，维度 1536；维度 1024 模型则用 `embeddings_1024`

### 3.6 `getEmbedModelData` —— 模型降级（L631-652）

```java
private AiragModel getEmbedModelData(String modelId) {
    if (oConvertUtils.isNotEmpty(modelId)) {
        AiragModel model = airagModelMapper.getByIdIgnoreTenant(modelId);
        if (model != null) {
            AssertUtils.assertEquals("仅支持向量模型", LLMConsts.MODEL_TYPE_EMBED, model.getModelType());
            // ★ 未激活回退（QQYUN-14645）
            if (model.getActivateFlag() != null && model.getActivateFlag() == 1) {
                return model;
            }
            log.warn("向量模型[{}]未激活，尝试使用 yml 中配置的默认向量模型", modelId);
        }
    }
    // 回退到 yml 默认
    if (aiChatConfig != null && oConvertUtils.isNotEmpty(aiChatConfig.getAiModelEmbed().getApiKey())) {
        log.info("使用 yml 中配置的默认向量模型: {}", aiChatConfig.getAiModelEmbed().getModel());
        return buildDefaultEmbedModel(aiChatConfig.getAiModelEmbed());
    }
    AssertUtils.assertNotEmpty("向量模型不能为空，请先配置向量模型或在 yml 中设置默认向量模型(jeecg.ai-chat.ai-model-embed)", modelId);
    return null;
}
```

降级链路：
1. 用 `airagKnowledge.embedId` 查 AiragModel（必须 `modelType='EMBED'`）
2. **未激活** → 不报错，回退到 `yml` 默认向量模型（[QQYUN-14645](issues/14645)）
3. `yml` 也未配置 → 抛 `assertNotEmpty` 错误

`buildDefaultEmbedModel()` 用 yml 的 `jeecg.ai-chat.ai-model-embed.*` 字段构造一个 `AiragModel` 副本（MODEL_TYPE_EMBED）返回。

---

## 四、向量化过程中的安全校验

### 4.1 `ensureFile` —— 路径遍历防护（L950-984）

```java
@NotNull
private String ensureFile(String filePath) {
    Matcher matcher = LLMConsts.WEB_PATTERN.matcher(filePath);
    if (matcher.matches()) {
        // 网络 URL：下载到 uploadpath/tmp/{uuid}/{file}
        log.info("网络资源,下载到临时目录:" + filePath);
        String tempFilePath = uploadpath + File.separator + "tmp" + File.separator + UUIDGenerator.generate() + File.separator;
        String fileName = filePath;
        if (fileName.contains("?")) fileName = fileName.substring(0, fileName.indexOf("?"));
        fileName = FilenameUtils.getName(fileName);
        tempFilePath = tempFilePath + fileName;
        FileDownloadUtils.download2DiskFromNet(filePath, tempFilePath);
        filePath = tempFilePath;
    } else {
        // ★ 本地路径：路径遍历校验
        SsrfFileTypeFilter.checkPathTraversal(filePath);
        Path root = Paths.get(uploadpath).toAbsolutePath().normalize();
        // 去除前导分隔符
        String relativePath = filePath.replaceAll("^[\\\\/]+", "");
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        // ★ 必须仍然在 uploadpath 之下
        if (!target.startsWith(root)) {
            log.error("检测到路径遍历攻击! filePath: {}, 解析后: {}", filePath, target);
            throw new JeecgBootException("文件路径包含非法字符");
        }
        filePath = target.toString();
    }
    return filePath;
}
```

**逐行解读**：
- **网络 URL** → 下载到 `uploadpath/tmp/{uuid}/{filename}`，避免 SSRF / 本地路径混淆
- **本地路径** → `Paths.get(uploadpath).toAbsolutePath().normalize()` 拿到 root，然后把传进来的 `filePath` 作为相对路径 `resolve` 上去，再 `normalize`，检查最终结果是否还以 `root` 开头——**这是 JDK 推荐的路径遍历防护标准做法**（[issues/9425](issues/9425)）

### 4.2 `CommandExecUtil.validateFilePath` —— 文件名 Shell 注入

参见 [15-handler-parsers.md#CommandExecUtil](15-handler-parsers.md)。正则 `[&|;<>\`$!\"'\r\n]` 阻断命名注入。

---

## 五、实体：`AiragKnowledge` 与 `AiragKnowledgeDoc`

### 5.1 `AiragKnowledge` —— 知识库（L22-119）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (ASSIGN_ID) | 主键 |
| `createBy/createTime/updateBy/updateTime/sysOrgCode/tenantId` | 标准审计 | |
| `name` | String | 知识库名称 |
| `embedId` | String | 关联的向量模型 ID（AiragModel.id, modelType='EMBED'） |
| `descr` | String | 描述（用于前端展示） |
| `status` | String | `enable` / `disable` |
| `type` | String | `knowledge`（普通知识） / `memory`（记忆库） |
| `metadata` | String | 元数据 JSON。可存分段策略等 |

### 5.2 `AiragKnowledgeDoc` —— 文档（L29-124）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 主键 |
| `knowledgeId` | String | 所属知识库 |
| `title` | String | 文档标题 |
| `type` | String | `text` / `file` / `web` |
| `content` | String | 直接内容（type=text）/ 解析后内容（runtime） |
| `metadata` | String | 元数据 JSON：`{filePath, sourcesPath, website}` |
| `status` | String | `draft` / `building` / `complete` / `failed` |

### 5.3 状态转换图

```
        ┌──────────┐
        │  draft   │ ← saveOrUpdate
        └─────┬────┘
              │ rebuildDocument / async task
              ▼
        ┌──────────┐
        │ building │ ← rebuildDocument 启动时
        └─────┬────┘
              │
   ┌──────────┴──────────┐
   ▼                     ▼
┌─────────┐       ┌─────────┐
│complete │       │ failed  │
└─────────┘       └────┬────┘
                       │
                       │ 失败原因写入 metadata.failedReason
                       ▼
                （保留在 DB 可查）
```

---

## 六、其他 `/airag/knowledge/*` 接口

### 6.1 CRUD 接口

| 端点 | 方法 | 关键逻辑 |
|------|------|---------|
| `GET /airag/knowledge/list` | `queryPageList` | `QueryGenerator.initQueryWrapper` 自动构建 QueryWrapper；支持字段模糊匹配 |
| `POST /airag/knowledge/add` | `add` | 设置 `status=enable`、`type=knowledge`（默认） |
| `PUT,POST /airag/knowledge/edit` | `edit` | **如果切换了 `embedId` → 触发整库文档重建**（`oldEmbedId.equalsIgnoreCase` 比较） |
| `PUT /airag/knowledge/rebuild` | `rebuild` | 按 knowIds 循环 `rebuildDocumentByKnowId` |
| `DELETE /airag/knowledge/delete` | `delete` | [issues/8337] 租户校验 + 同时调 `airagKnowledgeDocService.removeByKnowIds` |
| `GET /airag/knowledge/queryById` | `queryById` | 直接 `getById` |
| `GET /airag/knowledge/doc/list` | `queryDocumentPageList` | 必须传 `knowledgeId` |
| `GET /airag/knowledge/query/batch/byId` | `queryBatchByIds` | `listByIds` 批量查询 |
| `GET /airag/knowledge/doc/import/task/list` | `importDocumentTaskList` | ★ **目前固定返回空列表**（`Collections.emptyList()`），可能是 TODO |

### 6.2 `embedding/hitTest` 与 `embedding/search`

```java
@GetMapping(value = "/embedding/hitTest/{knowId}")
public Result<?> hitTest(@PathVariable("knowId") String knowId,
                         @RequestParam(name = "queryText") String queryText,
                         @RequestParam(name = "topNumber") Integer topNumber,
                         @RequestParam(name = "similarity") Double similarity) {
    List<Map<String, Object>> searchResp = embeddingHandler.searchEmbedding(knowId, queryText, topNumber, similarity);
    return Result.ok(searchResp);
}

@GetMapping(value = "/embedding/search")
public Result<?> embeddingSearch(@RequestParam("knowIds") List<String> knowIds,
                                 @RequestParam(name = "queryText") String queryText,
                                 @RequestParam(name = "topNumber", required = false) Integer topNumber,
                                 @RequestParam(name = "similarity", required = false) Double similarity) {
    KnowledgeSearchResult searchResp = embeddingHandler.embeddingSearch(knowIds, queryText, topNumber, similarity);
    return Result.ok(searchResp);
}
```

`EmbeddingHandler.searchEmbedding` 与 `embeddingSearch` 详见 [13-handler-embedding.md#五 向量检索](13-handler-embedding.md)。

### 6.3 `plugin/add` 与 `plugin/query` —— 记忆库专用

```java
@PostMapping(value = "/plugin/add")
public Result<?> add(@RequestBody AiragKnowledgeDoc airagKnowledgeDoc, HttpServletRequest request) {
    if (oConvertUtils.isEmpty(airagKnowledgeDoc.getKnowledgeId())) {
        return Result.error("知识库ID不能为空");
    }
    if (oConvertUtils.isEmpty(airagKnowledgeDoc.getContent())) {
        return Result.error("内容不能为空");
    }
    if (oConvertUtils.isEmpty(airagKnowledgeDoc.getTitle())) {
        // ★ 自动取内容前 20 字作为标题
        String content = airagKnowledgeDoc.getContent();
        String title = content.length() > 20 ? content.substring(0, 20) : content;
        airagKnowledgeDoc.setTitle(title);
    }
    airagKnowledgeDoc.setType(LLMConsts.KNOWLEDGE_DOC_TYPE_TEXT);  // 强制 type=text
    return airagKnowledgeDocService.editDocument(airagKnowledgeDoc);
}

@PostMapping(value = "/plugin/query")
public Result<?> pluginQuery(@RequestBody Map<String, Object> params, HttpServletRequest request) {
    String knowId = (String) params.get("knowledgeId");
    String queryText = (String) params.get("queryText");
    if (oConvertUtils.isEmpty(knowId)) return Result.error("知识库ID不能为空");
    if (oConvertUtils.isEmpty(queryText)) return Result.error("查询内容不能为空");
    
    LambdaQueryWrapper<AiragKnowledgeDoc> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(AiragKnowledgeDoc::getKnowledgeId, knowId);
    long count = airagKnowledgeDocService.count(queryWrapper);
    if(count == 0) return Result.ok("");
    
    // ★ 默认查所有条数
    KnowledgeSearchResult searchResp = embeddingHandler.embeddingSearch(
        Collections.singletonList(knowId), queryText, (int) count, null);
    return Result.ok(searchResp);
}
```

**`plugin/add` 与 `plugin/query`** 是记忆库插件的两个端点：
- `plugin/add` —— 强制 `type=text`，自动截取内容前 20 字为标题
- `plugin/query` —— 查全部条目（不带 topNumber 限制），不带 similarity（默认 0.75）

---

## 七、`POST /airag/knowledge/doc/import/zip` —— ZIP 批量导入

详见 `AirragKnowledgeDocServiceImpl.importDocumentFromZip`（L292-376）。核心流程：

```
1. SsrfFileTypeFilter.checkUploadFileType(zipFile)         ← 防护 1
2. 检查扩展名 = .zip
3. CommonUtils.uploadLocal 上传到 uploadpath/knowId/uuid.zip
4. 读知识库 metadata → 是否有 defaultSegment
5. unzipFile:
   - entryCount ≤ 10000                              ← 防护 2 (zip bomb)
   - shouldSkipZipEntry (跳过 __MACOSX/._/.DS_Store)  ← 防护 3 (macOS 隐藏)
   - safeResolve (拒绝路径穿越 ..)                  ← 防护 4
   - 单文件 ≤ 150MB                                 ← 防护 5
   - 总解压 ≤ 1GB                                   ← 防护 6
6. 仅保留 SUPPORT_DOC_TYPE（{txt,pdf,docx,doc,pptx,ppt,xlsx,xls,md}）
7. 每个文件 → AiragKnowledgeDoc（type='file', metadata.filePath=相对路径）
8. saveBatch → rebuildDocument(docIds) → 走 §二 异步向量化
```

**注意**：辅助方法 `unzipFile` / `safeResolve` / `copyLimited` / `shouldSkipZipEntry` 定义在 `AirragKnowledgeDocServiceImpl` 末尾（L388-512）。

---

## 八、完整调用链图：`POST /airag/knowledge/doc/edit` 调通到底

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 前端触发 "上传文档到知识库"                                            │
│    POST /airag/knowledge/doc/edit                                       │
│    Body: { knowledgeId, title, type='text'|'file'|'web', content? metadata? } │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. AiragKnowledgeController.addDocument                                 │
│    @RequiresPermissions("airag:knowledge:doc:edit")                     │
│    return airagKnowledgeDocService.editDocument(...)                    │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. AirragKnowledgeDocServiceImpl.editDocument                            │
│    5 条 assertNotEmpty 校验                                              │
│    setStatus(draft) → saveOrUpdate(doc)                                   │
│    → rebuildDocument(doc.id)                                             │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. AirragKnowledgeDocServiceImpl.rebuildDocument(docId)                  │
│    ├─ selectBatchIds([docId])                                            │
│    ├─ filter stuck-building（>5min 自动重试 QQYUN-11943）                │
│    ├─ peek status=building → updateBatchById                            │
│    ├─ TenantContext + UserTokenContext 透传到异步线程                    │
│    └─ CompletableFuture.runAsync(() -> {                                 │
│           ├─ EmbeddingHandler.embeddingDocument(knowId, doc)             │
│           │      ├─ (省略，详细见下文)                                    │
│           ├─ success → setStatus(complete) + updateById                  │
│           └─ failure → handleDocBuildFailed → setStatus(failed)          │
│                                                                             │
│    ← return Result.ok("操作成功")         【不阻塞 HTTP】                │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼ (异步线程)
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. EmbeddingHandler.embeddingDocument(knowId, doc)                        │
│    ├─ 读 AiragKnowledge                                                  │
│    ├─ if content==空：                                                    │
│    │     type=file → ensureFile + parseFile (Tika/POI/MinerU)            │
│    │     type=web  → parseWebPage (Jsoup)                                │
│    ├─ title + "\n\n" + content                                            │
│    ├─ getEmbedModelData(embedId) → AiModelOptions → EmbeddingModel      │
│    ├─ getEmbedStore(model) → PgVectorEmbeddingStore                       │
│    ├─ removeAll(docId=doc.id)    // 删旧                                  │
│    ├─ createDocumentSplitter(doc) // 自定义分段                            │
│    ├─ build Metadata (docId, knowledgeId, docName, createTime, username)│
│    ├─ if hasHtmlTable: splitDocumentPreservingHtmlTables (HTML 表格保留)│
│    ├─     else:    EmbeddingStoreIngestor.ingest(document)               │
│    └─ return metadata.toMap()                                            │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 6. langchain4j EmbeddingStoreIngestor                                    │
│    ┌─ splitter.split(document) → List<TextSegment>                       │
│    ├─ embeddingModel.embedAll(segments) → List<Embedding>                │
│    └─ embeddingStore.addAll(embeddings, segments) → PostgreSQL pgvector │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 7. PgVector 表 "embeddings" 实际写入                                    │
│    - rows: (id, embedding vector[1536], text, metadata jsonb)           │
│    - IVFFlat index (useIndex=true, indexListSize=100)                    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 九、对应源码文件列表

| 文件 | 行数 | 作用 |
|------|------|------|
| `llm/controller/AiragKnowledgeController.java` | 427 | 全部 18 个 `/airag/knowledge/*` 端点 |
| `llm/service/IAirragKnowledgeService.java` | — | 接口 |
| `llm/service/IAirragKnowledgeDocService.java` | 88 | 接口 |
| `llm/service/impl/AirragKnowledgeServiceImpl.java` | 222 | 知识库 CRUD + `getPluginMemory` |
| `llm/service/impl/AirragKnowledgeDocServiceImpl.java` | 515 | 文档生命周期 + ZIP 导入 + 异步向量化 |
| `llm/mapper/AirragKnowledgeMapper.java` | — | `getByIdIgnoreTenant` |
| `llm/mapper/AirragKnowledgeDocMapper.java` | — | 含 `deleteByMainId`（按 knowId 删全部 doc） |
| `llm/handler/EmbeddingHandler.java` | 988 | ★ 核心：embedingDocument / searchEmbedding / getQueryRouter / getEmbedStore |
| `llm/document/TikaDocumentParser.java` | 294 | Tika + Apache POI 文档解析 |
| `llm/document/WebPageParser.java` | — | Jsoup → Markdown |
| `llm/splitter/CustomDocumentSplitter.java` | — | 按指定分隔符分段 |
| `llm/config/EmbedStoreConfigBean.java` | 48 | pgvector 连接配置（@ConfigurationProperties("jeecg.airag.embed-store")） |
| `llm/config/KnowConfigBean.java` | — | MinerU 是否启用 + conda 环境 |
| `llm/entity/AiragKnowledge.java` | 120 | 知识库实体 |
| `llm/entity/AiragKnowledgeDoc.java` | 125 | 文档实体 |
| `llm/consts/LLMConsts.java` | 222 | 全部常量 |
| `common/vo/knowledge/KnowledgeSearchResult.java` | — | 检索结果封装（base-core） |

---

## 十、下一章

[04-controller-model.md](04-controller-model.md) — `/airag/airagModel/*` 控制器详解，包括模型配置 CRUD、`/test` 实测激活逻辑。
