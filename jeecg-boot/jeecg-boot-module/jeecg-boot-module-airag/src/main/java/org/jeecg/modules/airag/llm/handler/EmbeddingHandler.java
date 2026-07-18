package org.jeecg.modules.airag.llm.handler;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenModelName;
import dev.langchain4j.data.document.Document;
//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】GbQueryIntent + ExpandingQueryTransformer + RRF imports-------
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】GbQueryIntent + ExpandingQueryTransformer + RRF imports-------
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 8】PgVectorEmbeddingStore HYBRID 搜索支持--------
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore.SearchMode;
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 8】PgVectorEmbeddingStore HYBRID 搜索支持--------
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.parser.AutoDetectParser;
import org.jeecg.ai.factory.AiModelFactory;
import org.jeecg.ai.factory.AiModelOptions;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.filter.SsrfFileTypeFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jeecg.config.AiChatConfig;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.*;
import org.jeecg.modules.airag.common.handler.IEmbeddingHandler;
import org.jeecg.modules.airag.common.vo.knowledge.KnowledgeSearchResult;
import org.jeecg.modules.airag.llm.config.EmbedStoreConfigBean;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.document.TikaDocumentParser;
import org.jeecg.modules.airag.llm.document.WebPageParser;
import org.jeecg.modules.airag.llm.entity.AiragKnowledge;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.extractor.GbMetadataExtractor;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeMapper;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeService;
import org.jeecg.modules.airag.llm.splitter.CustomDocumentSplitter;
import org.jeecg.modules.airag.llm.vo.GbMetadata;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.jeecg.common.util.oConvertUtils.getInteger;
import static org.jeecg.modules.airag.llm.consts.LLMConsts.KNOWLEDGE_DOC_TYPE_FILE;
import static org.jeecg.modules.airag.llm.consts.LLMConsts.KNOWLEDGE_DOC_TYPE_WEB;

/**
 * 向量工具类
 *
 * @Author: chenrui
 * @Date: 2025/2/18 14:31
 */
@Slf4j
@Component
public class EmbeddingHandler implements IEmbeddingHandler {

    @Autowired
    EmbedStoreConfigBean embedStoreConfigBean;

    @Autowired
    private AiragModelMapper airagModelMapper;

    @Autowired
    @Lazy
    private IAiragKnowledgeService airagKnowledgeService;

    @Autowired
    private AiragKnowledgeMapper airagKnowledgeMapper;

    @Value(value = "${jeecg.path.upload:}")
    private String uploadpath;

    @Autowired
    KnowConfigBean knowConfigBean;

    @Autowired(required = false)
    private MineruApiClient mineruApiClient;

    @Autowired(required = false)
    private AiChatConfig aiChatConfig;

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 7】embeddingDocument 注入 GbMetadataExtractor--------
    @Autowired
    private GbMetadataExtractor gbMetadataExtractor;
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 7】embeddingDocument 注入 GbMetadataExtractor--------

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】ExpandingQueryTransformer 懒初始化字段--------
    /**
     * 查询扩展器懒初始化缓存：首次启用 queryExpansion 时构建 ChatModel + ExpandingQueryTransformer。
     * volatile + 双重检查锁，避免多线程重复构建。
     */
    private volatile ExpandingQueryTransformer expandingQueryTransformer;
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】ExpandingQueryTransformer 懒初始化字段--------

    /**
     * 默认分段长度
     */
    private static final int DEFAULT_SEGMENT_SIZE = 1000;

    /**
     * 默认分段重叠长度
     */
    private static final int DEFAULT_OVERLAP_SIZE = 50;

    /**
     * 最大输出长度
     */
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 4000;

    //update-begin---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径向量化按批处理，避免 DashScope embedding 批量上限--------
    /**
     * 单次 embedding 请求最大分段数
     * <p>
     * DashScope text-embedding 同步接口对单次请求 input.contents 数量硬上限是 10。
     * LangChain4j OpenAiEmbeddingModel 默认 maxSegmentsPerBatch=2048，会直接超过 DashScope 上限。
     * 在 HTML 表格路径下不再依赖 EmbeddingStoreIngestor 的内部批量，故在此处手动切片。
     */
    private static final int EMBED_BATCH_SIZE = 10;
    //update-end---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径向量化按批处理，避免 DashScope embedding 批量上限--------

    /**
     * 向量存储元数据:knowledgeId
     */
    public static final String EMBED_STORE_METADATA_KNOWLEDGEID = "knowledgeId";

    /**
     * 向量存储元数据: 用户账号
     */
    public static final String EMBED_STORE_METADATA_USER_NAME = "username";

    /**
     * 向量存储元数据:docId
     */
    public static final String EMBED_STORE_METADATA_DOCID = "docId";

    /**
     * 向量存储元数据:docName
     */
    public static final String EMBED_STORE_METADATA_DOCNAME = "docName";

    /**
     * 向量存储元数据：创建时间
     */
    public static final String EMBED_STORE_CREATE_TIME = "createTime";

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 按条款粒度向量化，metadata 带 4 槽位 + locator 键-----------
    /**
     * 向量存储元数据:standard_id（按条款入库时写入，用于按标准删除旧条款向量）
     */
    public static final String EMBED_STORE_METADATA_STANDARD_ID = "standard_id";
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 按条款粒度向量化，metadata 带 4 槽位 + locator 键-----------

    /**
     * 向量存储缓存
     */
    private static final ConcurrentHashMap<String, EmbeddingStore<TextSegment>> EMBED_STORE_CACHE = new ConcurrentHashMap<>();


    /**
     * 正则匹配: md图片
     * "!\\[(.*?)]\\((.*?)(\\s*=\\d+)?\\)"
     */
    private static final Pattern PATTERN_MD_IMAGE = Pattern.compile("!\\[(.*?)]\\((.*?)\\)");

    //update-begin---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格向量化分段时被截断修复-----------
    /**
     * 正则匹配: HTML表格完整块（跨行，大小写不敏感）
     */
    private static final Pattern PATTERN_HTML_TABLE = Pattern.compile("(?is)<table\\b.*?</table>");
    //update-end---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格向量化分段时被截断修复-----------

    /**
     * 向量化文档
     *
     * @param knowId
     * @param doc
     * @return
     * @author chenrui
     * @date 2025/2/18 11:52
     */
    //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】仅解析正文（MinerU/Tika），不写向量，供向导②使用-----------
    /**
     * 仅解析文档正文（不向量化）。
     * <p>
     * 国标线性向导步骤②：MinerU 会把 full.md 落到磁盘并回写 metadata.filePath；
     * <b>禁止</b>把整篇国标 Markdown 写入 {@code airag_knowledge_doc.content}
     * （MySQL 列为 TEXT，约 64KB，国标全文必触发 Data too long）。
     * 正文通过 metadata.filePath 指向的 .md 文件读取。
     * </p>
     *
     * @param doc 知识库文档（file 类型会走 MinerU/Tika；会更新 metadata，不写 content）
     * @return 解析后的正文；失败返回 null
     */
    public String extractDocumentTextOnly(AiragKnowledgeDoc doc) {
        AssertUtils.assertNotEmpty("文档不能为空", doc);
        String content = doc.getContent();
        if (oConvertUtils.isEmpty(content)) {
            switch (doc.getType()) {
                case KNOWLEDGE_DOC_TYPE_FILE:
                    if (knowConfigBean.isEnableMinerU()) {
                        parseFileByMinerU(doc);
                    }
                    content = parseFile(doc);
                    break;
                case KNOWLEDGE_DOC_TYPE_WEB:
                    content = parseWebPage(doc);
                    // web 内容通常较短；若仍过大也不落 content，由调用方决定
                    break;
                default:
                    break;
            }
        }
        // 若正文已解析但 metadata.filePath 仍是 PDF 等二进制路径，落盘为 .md 供后续读取
        // （MinerU 成功时 filePath 已指向 .md；Tika/失败兜底路径需要这里补写）
        if (oConvertUtils.isNotEmpty(content) && KNOWLEDGE_DOC_TYPE_FILE.equals(doc.getType())) {
            ensureMarkdownFilePersisted(doc, content);
        }
        // 明确不 setContent：避免 updateById 把超长正文写入 TEXT 列
        return content;
    }

    /**
     * 将解析正文落到 uploadpath 下 .md 文件，并回写 metadata.filePath（相对路径）。
     * 已是文本路径则不覆盖。
     */
    private void ensureMarkdownFilePersisted(AiragKnowledgeDoc doc, String content) {
        try {
            String metadataStr = doc.getMetadata();
            JSONObject meta = oConvertUtils.isEmpty(metadataStr) ? new JSONObject() : JSONObject.parseObject(metadataStr);
            if (meta == null) {
                meta = new JSONObject();
            }
            String filePath = meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
            if (oConvertUtils.isNotEmpty(filePath) && isTextLikeFilePath(filePath)) {
                // 已有 md 路径（MinerU 成功），不覆盖
                return;
            }
            String baseName = oConvertUtils.isNotEmpty(doc.getTitle())
                    ? FilenameUtils.getBaseName(doc.getTitle())
                    : "doc";
            // 清理非法文件名字符
            baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String relativeDir = "mineru" + File.separator + UUIDGenerator.generate() + File.separator + baseName + File.separator + "auto" + File.separator;
            String relativeMd = relativeDir + baseName + ".md";
            File outDir = new File(uploadpath + File.separator + relativeDir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                log.warn("[GB线性入库] 创建 md 目录失败: {}", outDir.getAbsolutePath());
                return;
            }
            File mdFile = new File(uploadpath + File.separator + relativeMd);
            FileUtils.writeStringToFile(mdFile, content, StandardCharsets.UTF_8);
            meta.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeMd);
            meta.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
            // 保留原始上传路径便于 PDF 预览
            if (oConvertUtils.isNotEmpty(filePath) && !meta.containsKey("originalFilePath")) {
                meta.put("originalFilePath", filePath);
            }
            doc.setMetadata(meta.toJSONString());
            log.info("[GB线性入库] 正文已落盘 md: {}", relativeMd);
        } catch (Exception e) {
            log.warn("[GB线性入库] 正文落盘 md 失败: {}", e.getMessage());
        }
    }

    private static boolean isTextLikeFilePath(String filePath) {
        if (oConvertUtils.isEmpty(filePath)) {
            return false;
        }
        String lower = filePath.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".txt")
                || lower.endsWith(".html")
                || lower.endsWith(".htm");
    }

    /**
     * 将 Markdown 中相对图片路径固化为 /sys/common/static/{sourcesPath}/...
     * 例：![](images/a.jpg) + sourcesPath=mineru/xx/auto/ → ![](/sys/common/static/mineru/xx/auto/images/a.jpg)
     */
    public static String rewriteLocalImagesToStaticPath(String markdown, String sourcesPath) {
        if (oConvertUtils.isEmpty(markdown) || oConvertUtils.isEmpty(sourcesPath)) {
            return markdown;
        }
        String base = sourcesPath.replace("\\", "/");
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        while (base.startsWith("/")) {
            base = base.substring(1);
        }
        final String prefix = "/sys/common/static/" + base;
        Matcher matcher = PATTERN_MD_IMAGE.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String imageUrl = matcher.group(2);
            if (imageUrl == null) {
                continue;
            }
            String src = imageUrl.trim();
            int sp = src.indexOf(' ');
            if (sp > 0) {
                src = src.substring(0, sp);
            }
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("/sys/common/static/")) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String rel = src.replace("\\", "/");
            if (rel.startsWith("./")) {
                rel = rel.substring(2);
            }
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            String abs = (prefix + rel).replaceAll("(?<!https:)(?<!http:)//+", "/");
            matcher.appendReplacement(sb, Matcher.quoteReplacement("![" + alt + "](" + abs + ")"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    //update-end---author:song ---date:2026-07-18  for：【GB线性入库】仅解析正文（MinerU/Tika），不写向量，供向导②使用-----------

    public Map<String, Object> embeddingDocument(String knowId, AiragKnowledgeDoc doc) {
        AiragKnowledge airagKnowledge = airagKnowledgeService.getById(knowId);
        AssertUtils.assertNotEmpty("知识库不存在", airagKnowledge);
        AssertUtils.assertNotEmpty("请先为知识库配置向量模型库", airagKnowledge.getEmbedId());
        AssertUtils.assertNotEmpty("文档不能为空", doc);
        // 读取文档
        String content = doc.getContent();
        // 向量化并存储
        if (oConvertUtils.isEmpty(content)) {
            switch (doc.getType()) {
                case KNOWLEDGE_DOC_TYPE_FILE:
                    //解析文件
                    if (knowConfigBean.isEnableMinerU()) {
                        parseFileByMinerU(doc);
                    }
                    content = parseFile(doc);
                    break;
                case KNOWLEDGE_DOC_TYPE_WEB:
                    content = parseWebPage(doc);
                    // 将解析的网页内容回写到文档，便于后续查看
                    doc.setContent(content);
                    break;
            }
        }
        //update-begin---author:chenrui ---date:20250307  for：[QQYUN-11443]【AI】是不是应该把标题也生成到向量库里，标题一般是有意义的------------
        if (oConvertUtils.isNotEmpty(doc.getTitle())) {
            content = doc.getTitle() + "\n\n" + content;
        }
        //update-end---author:chenrui ---date:20250307  for：[QQYUN-11443]【AI】是不是应该把标题也生成到向量库里，标题一般是有意义的------------

        // 向量化 date:2025/2/18
        AiragModel model = getEmbedModelData(airagKnowledge.getEmbedId());
        AiModelOptions modelOp = buildModelOptions(model);
        EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
        // 删除旧数据
        embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isEqualTo(doc.getId()));
        // 分段器
        DocumentSplitter splitter = createDocumentSplitter(doc);
        // 分段并存储
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        Metadata metadata = Metadata.metadata(EMBED_STORE_METADATA_DOCID, doc.getId())
                .put(EMBED_STORE_METADATA_KNOWLEDGEID, doc.getKnowledgeId())
                .put(EMBED_STORE_METADATA_DOCNAME, FilenameUtils.getName(doc.getTitle()))
                 //初始化记忆库的时候添加创建时间选项
                .put(EMBED_STORE_CREATE_TIME, String.valueOf(doc.getCreateTime() != null ? doc.getCreateTime().getTime() : System.currentTimeMillis()));
        //update-begin---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---
        //添加用户名字到元数据里面，用于记忆库中数据隔离
        String username = doc.getCreateBy();
        if (oConvertUtils.isEmpty(username)) {
            try {
                HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
                String token = TokenUtils.getTokenByRequest(request);
                username = JwtUtil.getUsername(token);
            } catch (Exception e) {
                // ignore：token获取不到默认为admin
                username = "admin";
            }
        }
        if (oConvertUtils.isNotEmpty(username)) {
            metadata.put(EMBED_STORE_METADATA_USER_NAME, username);
        }
        //update-end---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---
        Document from = Document.from(content, metadata);
        //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 7】提取 GB 结构化 metadata 并写入 Document（reference-share 模式：ingestor 与 HTML 两条路径都自动继承）-------
        // 通过 from.metadata() 写入，因为 LangChain4j 的 Metadata 是引用共享的，
        // splitter.split(doc) 产生的 TextSegment 都会带这些字段（无论走 ingestor 还是 splitDocumentPreservingHtmlTables）。
        if (gbMetadataExtractor != null) {
            try {
                GbMetadata gbMetadata = gbMetadataExtractor.extractFromText(content);
                gbMetadata.applyTo(from.metadata());
            } catch (Exception e) {
                log.warn("[GB-RAG P1.1] 提取 GB metadata 失败, 跳过 metadata 注入: {}", e.getMessage());
            }
        }
        //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 7】提取 GB 结构化 metadata 并写入 Document（reference-share 模式：ingestor 与 HTML 两条路径都自动继承）-------
        //update-begin---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格分段时被截断，保留完整表格块-----------
        boolean hasHtmlTable = content != null && PATTERN_HTML_TABLE.matcher(content).find();
        if (hasHtmlTable) {
            try {
                List<TextSegment> segments = splitDocumentPreservingHtmlTables(from, splitter);
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径按 20 条分批向量化，避免 DashScope 批量上限报错-----------
                List<Embedding> embeddings = batchEmbedAll(embeddingModel, segments, EMBED_BATCH_SIZE);
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径按 20 条分批向量化，避免 DashScope 批量上限报错-----------
//                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(embeddings, segments);
            } catch (Exception e) {
                log.error("向量存储失败，请检查向量模型配置是否正确", e);
                throw new JeecgBootException("向量存储失败：" + e.getMessage());
            }
        } else {
            //update-begin---author:jeecg---date:2026-02-26---for:[#9374]【AI知识库】千帆向量报错，添加异常处理防止空指针
            try {
                ingestor.ingest(from);
            } catch (Exception e) {
                log.error("向量存储失败，请检查向量模型配置是否正确", e);
                throw new JeecgBootException("向量存储失败：" + e.getMessage());
            }
            //update-end---author:jeecg---date:2026-02-26---for:[#9374]【AI知识库】千帆向量报错，添加异常处理防止空指针
        }
        //update-end---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格分段时被截断，保留完整表格块-----------

        return metadata.toMap();
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 按条款粒度向量化，metadata 带 4 槽位 + locator 键-----------
    /**
     * 按条款粒度向量化并写入向量库（GB-RAG v4 Phase 5 核心任务：连通 LLM 抽取的 4 槽位与向量库）。
     * <p>
     * 每个 GbClause 生成 1 个 TextSegment，其 Metadata 携带：
     * <ul>
     *   <li>{@code standard_id}     ← clause.getStandardId()（用于按标准删除旧向量）</li>
     *   <li>{@code knowledgeId}     ← knowId（与既有 embeddingDocument 写入的隔离键一致，被 searchEmbedding 的 base filter 命中）</li>
     *   <li>{@code standard_no}     ← standardNo 参数（locator，buildMetadataFilter 读）</li>
     *   <li>{@code clause_id}       ← clause.getClausePath()（locator，buildMetadataFilter 读，对齐 intent.clauseId）</li>
     *   <li>{@code clause_path}     ← clause.getClausePath()（冗余键，便于检索结果回填）</li>
     *   <li>{@code primary_type}    ← clause.getPrimaryType()（槽位1，buildMetadataFilter 读，GB-RAG v4 关键修复）</li>
     *   <li>{@code secondary_type}  ← clause.getSecondaryType()（槽位2，buildMetadataFilter 读，GB-RAG v4 关键修复）</li>
     *   <li>{@code polarity}        ← clause.getPolarity()</li>
     *   <li>{@code condition_text}  ← clause.getConditionText()（非空才写，槽位4）</li>
     * </ul>
     * 流程：解析知识库 → embedId → AiragModel → embeddingModel + embeddingStore →
     * 按 distinct standard_id 删除旧条款向量 → 按 clause 建 TextSegment → batchEmbedAll(10) → addAll。
     * <p>
     * 防御性：knowId 为空 / clauses 为空 → 记日志并返回（不抛异常，不阻塞入库管线）。
     * 整个嵌入路径包裹在 try/catch 中：失败只记日志，不抛异常（不能打断 ingestion pipeline）。
     *
     * @param knowId     知识库 ID（必填，非空时才进行向量化）
     * @param standardNo 标准号（如 "GB 31241-2022"），写入 metadata.standard_no
     * @param clauses    条款列表（每个条款产出 1 个 chunk；空则直接返回）
     */
    public void embedClauses(String knowId, String standardNo, List<GbClause> clauses) {
        // 防御：空入参直接返回，不抛异常（null 与纯空白都视为空，符合 "null/blank knowId" 契约）
        if (knowId == null || knowId.trim().isEmpty()) {
            log.warn("[GB-RAG v4 P5] embedClauses 跳过: knowId 为空");
            return;
        }
        if (clauses == null || clauses.isEmpty()) {
            log.info("[GB-RAG v4 P5] embedClauses 跳过: clauses 为空, knowId={}, standardNo={}", knowId, standardNo);
            return;
        }

        try {
            // 解析知识库 → embedId → AiragModel → embeddingModel + embeddingStore
            AiragKnowledge airagKnowledge = airagKnowledgeService.getById(knowId);
            if (airagKnowledge == null || oConvertUtils.isEmpty(airagKnowledge.getEmbedId())) {
                log.warn("[GB-RAG v4 P5] embedClauses 跳过: 知识库不存在或未配置向量模型, knowId={}", knowId);
                return;
            }
            AiragModel model = getEmbedModelData(airagKnowledge.getEmbedId());
            AiModelOptions modelOp = buildModelOptions(model);
            EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
            EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);

            // 删除该批次涉及的所有 distinct standardId 对应的旧条款向量（重入库幂等）
            clauses.stream()
                    .map(GbClause::getStandardId)
                    .filter(oConvertUtils::isNotEmpty)
                    .distinct()
                    .forEach(sid -> {
                        try {
                            embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_STANDARD_ID).isEqualTo(sid));
                        } catch (Exception e) {
                            log.warn("[GB-RAG v4 P5] 删除旧条款向量失败, standardId={}, 错误: {}", sid, e.getMessage());
                        }
                    });

            // 为每个条款构建一个 TextSegment（text 为空则跳过），Metadata 携带 4 槽位 + locator
            List<TextSegment> segments = new ArrayList<>(clauses.size());
            for (GbClause clause : clauses) {
                if (clause == null || oConvertUtils.isEmpty(clause.getText())) {
                    continue;
                }
                Metadata md = Metadata.metadata(EMBED_STORE_METADATA_STANDARD_ID, clause.getStandardId())
                        .put(EMBED_STORE_METADATA_KNOWLEDGEID, knowId)
                        .put("standard_no", standardNo == null ? "" : standardNo)
                        .put("clause_id", clause.getClausePath() == null ? "" : clause.getClausePath())
                        .put("clause_path", clause.getClausePath() == null ? "" : clause.getClausePath())
                        .put("primary_type", clause.getPrimaryType() == null ? "" : clause.getPrimaryType())
                        .put("secondary_type", clause.getSecondaryType() == null ? "" : clause.getSecondaryType())
                        .put("polarity", clause.getPolarity() == null ? "" : clause.getPolarity());
                if (clause.getConditionText() != null) {
                    md.put("condition_text", clause.getConditionText());
                }
                segments.add(TextSegment.from(clause.getText(), md));
            }
            if (segments.isEmpty()) {
                log.info("[GB-RAG v4 P5] embedClauses 跳过: 过滤空文本后无可用条款, knowId={}, standardNo={}", knowId, standardNo);
                return;
            }

            // 分批向量化（DashScope 单批 10 条上限），add 到 store
            List<Embedding> embeddings = batchEmbedAll(embeddingModel, segments, EMBED_BATCH_SIZE);
            embeddingStore.addAll(embeddings, segments);
            log.info("[GB-RAG v4 P5] embedClauses 完成: knowId={}, standardNo={}, 条款数={}, 写入 chunk 数={}",
                    knowId, standardNo, clauses.size(), segments.size());
        } catch (Exception e) {
            // 失败只记日志，不抛异常 —— 不能打断 ingestion pipeline
            log.error("[GB-RAG v4 P5] embedClauses 失败: knowId={}, standardNo={}, 错误: {}", knowId, standardNo, e.getMessage(), e);
        }
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 按条款粒度向量化，metadata 带 4 槽位 + locator 键-----------

    /**
     * 创建分段器
     *
     * @param doc
     * @return
     */
    private DocumentSplitter createDocumentSplitter(AiragKnowledgeDoc doc) {
        DocumentSplitter splitter = null;
        int maxSegment = DEFAULT_SEGMENT_SIZE;
        int overlapSize = DEFAULT_OVERLAP_SIZE;

        if (oConvertUtils.isNotEmpty(doc.getMetadata())) {
            try {
                JSONObject json = JSONObject.parseObject(doc.getMetadata());

                //update-begin---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
                // 文档使用知识库默认分段策略：读取知识库自身的 metadata 来决定分段方式
                Boolean useKnowledgeDefault = json.getBoolean(LLMConsts.USE_KNOWLEDGE_DEFAULT);
                if (Boolean.TRUE.equals(useKnowledgeDefault)) {
                    if (oConvertUtils.isNotEmpty(doc.getKnowledgeId())) {
                        AiragKnowledge knowledge = airagKnowledgeMapper.selectById(doc.getKnowledgeId());
                        if (knowledge != null && oConvertUtils.isNotEmpty(knowledge.getMetadata())) {
                            // 用知识库的 metadata 覆盖，后续逻辑统一处理
                            json = JSONObject.parseObject(knowledge.getMetadata());
                        } else {
                            // 知识库没有配置分段策略，使用默认分段器
                            return DocumentSplitters.recursive(maxSegment, overlapSize);
                        }
                    } else {
                        return DocumentSplitters.recursive(maxSegment, overlapSize);
                    }
                }
                //update-end---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------

                Object segmentStrategy = json.get(LLMConsts.SEGMENT_STRATEGY);
                //update-begin---author:wangshuai ---date:2026-04-09  for：【issue/9418】AI知识库上传文件太大向量化失败-----------
                // 1. 不论策略是auto还是custom，优先使用前端传入的分段大小和重叠度
                Integer sizeObj = json.getInteger(LLMConsts.MAX_SEGMENT);
                if (sizeObj != null && sizeObj > 0) {
                    maxSegment = sizeObj;
                }
                Double overlapObj = json.getDouble(LLMConsts.OVERLAP);
                if (overlapObj != null && overlapObj >= 0) {
                    double rate = overlapObj / 100;
                    overlapSize = (int) (maxSegment * rate);
                }
                if(segmentStrategy != null && LLMConsts.SEGMENT_STRATEGY_CUSTOM.equals(segmentStrategy.toString())){
                //update-end---author:wangshuai ---date:2026-04-09  for：【issue/9418】AI知识库上传文件太大向量化失败-----------
                    String splitChar = json.getString(LLMConsts.SEPARATOR);
                    if (oConvertUtils.isNotEmpty(splitChar)) {
                        //自定义
                        if(LLMConsts.SEGMENT_STRATEGY_CUSTOM.equals(splitChar)){
                            splitChar = oConvertUtils.getString(json.getString(LLMConsts.CUSTOM_SEPARATOR),"\n");
                        }
                        // 处理转义字符
                        splitChar = splitChar.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
                        String textRules = json.getString(LLMConsts.TEXT_RULES);
                        
                        splitter = new CustomDocumentSplitter(textRules, splitChar, maxSegment, overlapSize);
                    }
                }
            } catch (Exception e) {
                log.warn("解析自定义分词配置失败: {}", e.getMessage());
            }
        }

        if (splitter == null) {
            splitter = DocumentSplitters.recursive(maxSegment, overlapSize);
        }
        return splitter;
    }

    //update-begin---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格分段时被截断，新增保留表格完整性的分段辅助方法-----------
    /**
     * 按 HTML 表格边界分段：表格块完整保留，表格外文本交给 splitter 正常分段
     */
    public static List<TextSegment> splitDocumentPreservingHtmlTables(Document document, DocumentSplitter splitter) {
        String text = document.text();
        Metadata metadata = document.metadata();
        List<TextSegment> result = new ArrayList<>();
        Matcher matcher = PATTERN_HTML_TABLE.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start());
            if (!before.isBlank()) {
                appendSplitText(before, metadata, splitter, result);
            }
            appendSegment(matcher.group(), metadata, result);
            lastEnd = matcher.end();
        }
        String remaining = text.substring(lastEnd);
        if (!remaining.isBlank()) {
            appendSplitText(remaining, metadata, splitter, result);
        }
        reindexSegments(result);
        return result;
    }

    /**
     * 将非表格文本交给 splitter 分段后追加到 result
     */
    public static void appendSplitText(String text, Metadata metadata, DocumentSplitter splitter, List<TextSegment> result) {
        List<TextSegment> segments = splitter.split(Document.from(text, metadata));
        result.addAll(segments);
    }

    //update-begin---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径按批向量化，避免 DashScope embedding 批量上限--------
    /**
     * 将分段按指定大小分批调用 embeddingModel.embedAll，规避部分模型（如 DashScope text-embedding）
     * 对单次请求 input.contents 数量的上限。返回的 Embedding 顺序与输入 segments 一一对应。
     *
     * @param embeddingModel embedding 模型
     * @param segments 待向量化分段
     * @param batchSize 每批最大分段数
     * @return 与 segments 顺序一致的 Embedding 列表
     */
    public static List<Embedding> batchEmbedAll(EmbeddingModel embeddingModel, List<TextSegment> segments, int batchSize) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        if (batchSize <= 0) {
            batchSize = segments.size();
        }
        List<Embedding> result = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            result.addAll(embeddingModel.embedAll(batch).content());
        }
        return result;
    }
    //update-end---author:song ---date:2026-07-10  for：【issues/9551】HTML表格路径按批向量化，避免 DashScope embedding 批量上限--------
    /**
     * 将文本作为单个完整段追加到 result（不经过分段器，用于保留完整表格块）
     */
    public static void appendSegment(String text, Metadata metadata, List<TextSegment> result) {
        result.add(TextSegment.from(text, metadata));
    }

    /**
     * 为分段列表的 metadata 写入从 0 开始的连续 index，供检索时标识顺序
     */
    public static void reindexSegments(List<TextSegment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).metadata().put("index", String.valueOf(i));
        }
    }
    //update-end---author:wangshuai ---date:2026-04-20  for：【issues/9551】HTML表格分段时被截断，新增保留表格完整性的分段辅助方法-----------

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】查询扩展器懒加载（双重检查锁）--------
    /**
     * 懒加载 ExpandingQueryTransformer：首次调用时基于 embeddingModel 的 provider 构建 ChatModel，
     * 后续所有扩展查询复用同一实例。注意：这里复用 embeddingModel 的 AiModelOptions 来构建 ChatModel；
     * provider 必须支持 chat（OpenAI/Qwen/Zhipu/DeepSeek 等均可）。
     */
    private ExpandingQueryTransformer getExpandingQueryTransformer(AiragModel model) {
        ExpandingQueryTransformer local = expandingQueryTransformer;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (expandingQueryTransformer != null) {
                return expandingQueryTransformer;
            }
            AiModelOptions modelOp = buildModelOptions(model);
            ChatModel chatModel = AiModelFactory.createChatModel(modelOp);
            ExpandingQueryTransformer built = new ExpandingQueryTransformer(chatModel);
            expandingQueryTransformer = built;
            return built;
        }
    }
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】查询扩展器懒加载（双重检查锁）--------

    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位 + 通用骨架（废弃电池键 test_type/n_cells_alias/chapter/status）-----------
    /**
     * 在已有 filter 上叠加 QueryIntent（领域无关，4 槽位 + 通用骨架）的标量过滤条件。
     * <p>
     * 过滤键对齐 P1 gb_clause 表 + airag_embedding metadata：
     * <ul>
     *   <li>{@code clause_id}     ← intent.clauseId（条款号，如 "9.2"）</li>
     *   <li>{@code standard_no}   ← intent.standardNo（GB 标准号，如 "GB 31241"）</li>
     *   <li>{@code amendment}     ← intent.version（版次/年份，如 "2022"）</li>
     *   <li>{@code primary_type}  ← intent.primaryType（槽位1-做什么）</li>
     *   <li>{@code secondary_type}← intent.secondaryType（槽位2-对谁）</li>
     * </ul>
     * 任一字段为空则跳过该子句。intent 为 null 直接返回 base。
     */
    private Filter buildMetadataFilter(Filter base, QueryIntent intent) {
        Filter result = base;
        if (intent == null) {
            return result;
        }
        if (oConvertUtils.isNotEmpty(intent.getClauseId())) {
            result = new And(result, metadataKey("clause_id").isEqualTo(intent.getClauseId()));
        }
        if (oConvertUtils.isNotEmpty(intent.getStandardNo())) {
            result = new And(result, metadataKey("standard_no").isEqualTo(intent.getStandardNo()));
        }
        if (oConvertUtils.isNotEmpty(intent.getVersion())) {
            result = new And(result, metadataKey("amendment").isEqualTo(intent.getVersion()));
        }
        if (oConvertUtils.isNotEmpty(intent.getPrimaryType())) {
            result = new And(result, metadataKey("primary_type").isEqualTo(intent.getPrimaryType()));
        }
        if (oConvertUtils.isNotEmpty(intent.getSecondaryType())) {
            result = new And(result, metadataKey("secondary_type").isEqualTo(intent.getSecondaryType()));
        }
        return result;
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位 + 通用骨架-----------

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】Multi-Query RRF 融合--------
    /**
     * 对多个扩展查询的召回结果做 RRF (Reciprocal Rank Fusion) 融合。
     * key = docId + "_" + index（同一 chunk 在不同查询里应当被识别为同一个候选）。
     */
    private List<EmbeddingMatch<TextSegment>> rrfMerge(
            List<EmbeddingMatch<TextSegment>> allMatches,
            int topK) {
        Map<String, EmbeddingMatch<TextSegment>> matchById = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        int k = knowConfigBean != null ? knowConfigBean.getRrfK() : 60;

        Map<String, List<EmbeddingMatch<TextSegment>>> byQuery = allMatches.stream()
                .collect(Collectors.groupingBy(m -> m.embedded().text()));

        byQuery.forEach((queryText, list) -> {
            list.sort(Comparator.<EmbeddingMatch<TextSegment>>comparingDouble(EmbeddingMatch::score).reversed());
            for (int rank = 0; rank < list.size(); rank++) {
                dev.langchain4j.data.document.Metadata m = list.get(rank).embedded().metadata();
                String docId = m.getString(EMBED_STORE_METADATA_DOCID);
                String idx = m.getString("index");
                String id = (docId == null ? "" : docId) + "_" + (idx == null ? String.valueOf(rank) : idx);
                scores.merge(id, 1.0 / (k + rank + 1), Double::sum);
                matchById.putIfAbsent(id, list.get(rank));
            }
        });

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> matchById.get(e.getKey()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】Multi-Query RRF 融合--------

    /**
     * 向量查询(多知识库)
     *
     * @param knowIds
     * @param queryText
     * @param topNumber
     * @param similarity
     * @return
     * @author chenrui
     * @date 2025/2/18 16:52
     */
    @Override
    public KnowledgeSearchResult embeddingSearch(List<String> knowIds, String queryText, Integer topNumber, Double similarity) {
        AssertUtils.assertNotEmpty("请选择知识库", knowIds);
        AssertUtils.assertNotEmpty("请填写查询内容", queryText);

        topNumber = getInteger(topNumber, 5);

        //命中的文档列表
        List<Map<String, Object>> documents = new ArrayList<>(16);
        for (String knowId : knowIds) {
            List<Map<String, Object>> searchResp = searchEmbedding(knowId, queryText, topNumber, similarity);
            if (oConvertUtils.isObjectNotEmpty(searchResp)) {
                documents.addAll(searchResp);
            }
        }

        StringBuilder data = new StringBuilder();
        //update-begin---author:wangshuai---date:2026-01-04---for:【QQYUN-14479】给ai的时候需要限制几个字---
        //是否为记忆库
        boolean memoryMode = false;
        //记忆库只有一个
        if (knowIds.size() == 1) {
            String firstId = knowIds.get(0);
            if (oConvertUtils.isNotEmpty(firstId)) {
                AiragKnowledge k = airagKnowledgeMapper.getByIdIgnoreTenant(firstId);
                memoryMode = (k != null && LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(k.getType()));
            }
        }
        //如果是记忆库按照创建时间排序，如果不是按照score分值进行排序
        List<Map<String, Object>> prepared = documents.stream()
                .sorted(memoryMode
                        ? Comparator.comparingLong((Map<String, Object> doc) -> oConvertUtils.getLong(doc.get(EMBED_STORE_CREATE_TIME), 0L)).reversed()
                        : Comparator.comparingDouble((Map<String, Object> doc) -> (Double) doc.get("score")).reversed())
                .collect(Collectors.toList());
        List<Map<String, Object>> limited = new ArrayList<>();
        //将返回的结果按照最大的token进行长度限制
        for (Map<String, Object> doc : prepared) {
            if (limited.size() >= topNumber) {
                break;
            }
            String content = oConvertUtils.getString(doc.get("content"), "");
            int remain = DEFAULT_MAX_OUTPUT_CHARS - data.length();
            if (remain <= 0) {
                break;
            }
            //数据库中文本的长度和已经拼接的长度
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
        //update-end---author:wangshuai---date:2026-01-04---for:【QQYUN-14479】给ai的时候需要限制几个字---
    }

    /**
     * 向量查询（4参，保留向后兼容：内部委托 5 参重载，intent=null）
     */
    public List<Map<String, Object>> searchEmbedding(String knowId, String queryText, Integer topNumber, Double similarity) {
        return searchEmbedding(knowId, queryText, topNumber, similarity, null);
    }

    /**
     * 向量查询（支持 QueryIntent 标量过滤 + HYBRID 模式下忽略 minScore + 可选 Multi-Query 扩展 + RRF 融合）
     *
     * @param knowId    知识库 ID
     * @param queryText 查询文本
     * @param topNumber 召回条数
     * @param similarity 相似度阈值；HYBRID 模式下传 null（pgvector HYBRID 不支持 minScore 语义）
     * @param intent    领域无关查询意图（可为 null）；非 null 时叠加 clause_id/standard_no/amendment/primary_type/secondary_type 标量过滤
     * @return 命中 chunks（结果 map 在原 score/content/chunk/docName/createTime 基础上额外携带 standardNo/clauseId/clausePath/primaryType）
     * @author chenrui（4 参原版）
     * @date 2025/2/18 16:52
     * <p>P1.1 Task 9 by song-claude 2026-07-11；GB-RAG v4 P3 by song 2026-07-16（intent 改 QueryIntent + 输出 map 补 GB 键）</p>
     */
    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】searchEmbedding 新增 GbQueryIntent 重载-------
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】searchEmbedding 5 参 intent 改 QueryIntent + 输出 map 补 GB 键（修 VectorChannel 空字段）-----------
    public List<Map<String, Object>> searchEmbedding(String knowId, String queryText, Integer topNumber, Double similarity, QueryIntent intent) {
        AssertUtils.assertNotEmpty("请选择知识库", knowId);
        AiragKnowledge knowledge = airagKnowledgeMapper.getByIdIgnoreTenant(knowId);
        AssertUtils.assertNotEmpty("知识库不存在", knowledge);
        AssertUtils.assertNotEmpty("请填写查询内容", queryText);
        AiragModel model = getEmbedModelData(knowledge.getEmbedId());

        AiModelOptions modelOp = buildModelOptions(model);
        EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);
        Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        topNumber = getInteger(topNumber, modelOp.getTopNumber());
        similarity = oConvertUtils.getDou(similarity, modelOp.getSimilarity());

        //update-begin---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---
        Filter filter = metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId);

        // P1.1 Task 9 / GB-RAG v4 P3: 叠加 QueryIntent 标量过滤
        filter = buildMetadataFilter(filter, intent);

        // 记忆库的时候需要根据用户隔离
        if (LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(knowledge.getType())) {
            try {
                HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
                String token = TokenUtils.getTokenByRequest(request);
                String username = JwtUtil.getUsername(token);
                if (oConvertUtils.isNotEmpty(username)) {
                    filter = new And(filter, metadataKey(EMBED_STORE_METADATA_USER_NAME).isEqualTo(username));
                }
            } catch (Exception e) {
                // ignore
                log.info("构建过滤器异常,{}", e.getMessage());
            }
        }
        //update-end---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---

        // P1.1 Task 9: HYBRID 模式下不传 minScore，因为 pgvector HYBRID 检索用 RRF 融合分数，不再使用单一相似度阈值
        boolean hybridOn = knowConfigBean != null && knowConfigBean.isHybridSearch();
        Double effectiveMinScore = hybridOn ? null : similarity;

        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);

        List<EmbeddingMatch<TextSegment>> relevant;
        // P1.1 Task 9: 可选查询扩展（Multi-Query）→ 分别检索 → RRF 融合
        if (knowConfigBean != null && knowConfigBean.isQueryExpansionEnabled()) {
            try {
                ExpandingQueryTransformer transformer = getExpandingQueryTransformer(model);
                java.util.Collection<Query> expanded = transformer.transform(Query.from(queryText));
                List<EmbeddingMatch<TextSegment>> merged = new ArrayList<>();
                for (Query q : expanded) {
                    Embedding qEmb = embeddingModel.embed(q.text()).content();
                    EmbeddingSearchRequest qReq = EmbeddingSearchRequest.builder()
                            .queryEmbedding(qEmb)
                            .query(q.text())
                            .maxResults(topNumber)
                            .minScore(effectiveMinScore)
                            .filter(filter)
                            .build();
                    merged.addAll(embeddingStore.search(qReq).matches());
                }
                relevant = rrfMerge(merged, topNumber);
            } catch (Exception e) {
                log.warn("[GB-RAG P1.1] 查询扩展失败，回退到单查询: {}", e.getMessage());
                EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .query(queryText)
                        .maxResults(topNumber)
                        .minScore(effectiveMinScore)
                        .filter(filter)
                        .build();
                relevant = embeddingStore.search(embeddingSearchRequest).matches();
            }
        } else {
            EmbeddingSearchRequest embeddingSearchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .query(queryText)
                    .maxResults(topNumber)
                    .minScore(effectiveMinScore)
                    .filter(filter)
                    .build();
            relevant = embeddingStore.search(embeddingSearchRequest).matches();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (oConvertUtils.isObjectNotEmpty(relevant)) {
            result = relevant.stream().map(matchRes -> {
                Map<String, Object> data = new HashMap<>();
                data.put("score", matchRes.score());
                data.put("content", matchRes.embedded().text());
                Metadata metadata = matchRes.embedded().metadata();
                data.put("chunk", metadata.getInteger("index"));
                data.put(EMBED_STORE_METADATA_DOCNAME, metadata.getString(EMBED_STORE_METADATA_DOCNAME));
                //查询返回的时候增加创建时间，用于排序
                String ct = metadata.getString(EMBED_STORE_CREATE_TIME);
                data.put(EMBED_STORE_CREATE_TIME, ct);
                //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】输出 map 补 GB 元数据键（修 VectorChannel convertToRetrievalResult 读取 null 字段的数据流断裂）-----------
                // VectorChannel.convertToRetrievalResult 直接从 searchResult map 顶层读 standardNo/clauseId/clausePath/primaryType，
                // 而原输出 map 只有 score/content/chunk/docName/createTime 5 个键 → 全部读到 null。
                // 此处把 embedding match metadata 中的 snake_case 键映射为 camelCase 写到 map 顶层。
                String standardNo = metadata.getString("standard_no");
                if (standardNo != null) {
                    data.put("standardNo", standardNo);
                }
                String clauseId = metadata.getString("clause_id");
                if (clauseId != null) {
                    data.put("clauseId", clauseId);
                }
                String clausePath = metadata.getString("clause_path");
                if (clausePath != null) {
                    data.put("clausePath", clausePath);
                }
                String primaryType = metadata.getString("primary_type");
                if (primaryType != null) {
                    data.put("primaryType", primaryType);
                }
                //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】输出 map 补 GB 元数据键（修 VectorChannel 数据流断裂）-----------
                return data;
            }).collect(Collectors.toList());
        }
        return result;
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】searchEmbedding 5 参 intent 改 QueryIntent + 输出 map 补 GB 键-----------
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】searchEmbedding 新增 GbQueryIntent 重载-------

    /**
     * 获取向量查询路由
     *
     * @param knowIds
     * @param topNumber
     * @param similarity
     * @return
     * @author chenrui
     * @date 2025/2/20 21:03
     */
    @Override
    public QueryRouter getQueryRouter(List<String> knowIds, Integer topNumber, Double similarity) {
        return getQueryRouter(knowIds, topNumber, similarity, null);
    }

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】getQueryRouter 新增 GbQueryIntent 重载-------
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】getQueryRouter 4 参 intent 改 QueryIntent + 保留 GbQueryIntent 桥接重载-----------
    /**
     * 4参重载（QueryIntent 版）：构造 EmbeddingStoreContentRetriever 时叠加 QueryIntent（领域无关，4 槽位 + 通用骨架）的标量过滤，
     * 以及 HYBRID 模式下不传 minScore。
     */
    public QueryRouter getQueryRouter(List<String> knowIds, Integer topNumber, Double similarity, QueryIntent intent) {
        AssertUtils.assertNotEmpty("请选择知识库", knowIds);
        //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        log.info("[RAG][getQueryRouter] 进入构建 queryRouter, 知识库IDs={}, 召回条数={}, 相似度阈值={}", knowIds, topNumber, similarity);
        //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        List<ContentRetriever> retrievers = Lists.newArrayList();
        for (String knowId : knowIds) {
            if (oConvertUtils.isEmpty(knowId)) {
                continue;
            }
            AiragKnowledge knowledge = airagKnowledgeMapper.getByIdIgnoreTenant(knowId);
            AssertUtils.assertNotEmpty("知识库不存在", knowledge);
            //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            log.info("[RAG][getQueryRouter] 当前知识库 id={}, 名称={}, 向量模型id={}", knowId, knowledge.getName(), knowledge.getEmbedId());
            //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            AiragModel model = getEmbedModelData(knowledge.getEmbedId());
            AiModelOptions modelOptions = buildModelOptions(model);
            EmbeddingModel embeddingModel;
            EmbeddingStore<TextSegment> embeddingStore;
            try {
                embeddingModel = AiModelFactory.createEmbeddingModel(modelOptions);
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                log.info("[RAG][getQueryRouter] embeddingModel 创建成功, 实现类={}", embeddingModel.getClass().getName());
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                embeddingStore = getEmbedStore(model);
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                log.info("[RAG][getQueryRouter] embeddingStore 创建成功, 实现类={}", embeddingStore.getClass().getName());
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            } catch (Exception e) {
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                log.error("[RAG][getQueryRouter] 构建 embeddingModel/embeddingStore 失败, knowId={}, 错误信息={}", knowId, e.getMessage(), e);
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                throw new JeecgBootException("构建知识库检索器失败: " + e.getMessage(), e);
            }
            topNumber = getInteger(topNumber, 5);
            similarity = oConvertUtils.getDou(similarity, 0.75);

            //update-begin---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---
            Filter filter = metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId);
            // P1.1 Task 9 / GB-RAG v4 P3: 叠加 QueryIntent 标量过滤
            filter = buildMetadataFilter(filter, intent);
            // 记忆库的时候需要根据用户隔离
            if (LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(knowledge.getType())) {
                try {
                    HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
                    String token = TokenUtils.getTokenByRequest(request);
                    String username = JwtUtil.getUsername(token);
                    if (oConvertUtils.isNotEmpty(username)) {
                        filter = new And(filter, metadataKey(EMBED_STORE_METADATA_USER_NAME).isEqualTo(username));
                    }
                } catch (Exception e) {
                    // ignore
                    log.info("构建过滤器异常,{}",e.getMessage());
                }
            }
            //update-end---author:wangshuai---date:2025-12-26---for:【QQYUN-14265】【AI】支持记忆---

            // P1.1 Task 9: HYBRID 模式下不传 minScore（pgvector HYBRID 走 RRF 融合分数）
            boolean hybridOn = knowConfigBean != null && knowConfigBean.isHybridSearch();
            Double effectiveMinScore = hybridOn ? null : similarity;

            // 构建一个嵌入存储内容检索器，用于从嵌入存储中检索内容
            EmbeddingStoreContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .maxResults(topNumber)
                    .minScore(effectiveMinScore)
                    .filter(filter)
                    .build();
            retrievers.add(contentRetriever);
            //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            log.info("[RAG][getQueryRouter] 检索器已加入, knowId={}, 召回条数={}, 相似度阈值={}", knowId, topNumber, similarity);
            //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        }
        if (retrievers.isEmpty()) {
            //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            log.warn("[RAG][getQueryRouter] 检索器列表为空, 将返回 null, 知识库IDs={}", knowIds);
            //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            return null;
        } else {
            //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            log.info("[RAG][getQueryRouter] 构建 DefaultQueryRouter 成功, 检索器数量={}", retrievers.size());
            //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            return new DefaultQueryRouter(retrievers);
        }
    }
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 9】getQueryRouter 新增 GbQueryIntent 重载-------

    /**
     * 删除向量化文档
     *
     * @param knowId
     * @param modelId
     * @author chenrui
     * @date 2025/2/18 19:07
     */
    public void deleteEmbedDocsByKnowId(String knowId, String modelId) {
        AssertUtils.assertNotEmpty("选择知识库", knowId);
        AiragModel model = getEmbedModelData(modelId);

        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
        // 删除数据
        embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_KNOWLEDGEID).isEqualTo(knowId));
    }

    /**
     * 删除向量化文档
     *
     * @param docIds
     * @param modelId
     * @author chenrui
     * @date 2025/2/18 19:07
     */
    public void deleteEmbedDocsByDocIds(List<String> docIds, String modelId) {
        AssertUtils.assertNotEmpty("选择文档", docIds);
        AiragModel model = getEmbedModelData(modelId);

        EmbeddingStore<TextSegment> embeddingStore = getEmbedStore(model);
        // 删除数据
        embeddingStore.removeAll(metadataKey(EMBED_STORE_METADATA_DOCID).isIn(docIds));
    }

    /**
     * 查询向量模型数据，若未指定或不存在则回退到 yml 中配置的默认向量模型
     *
     * @param modelId
     * @return
     * @author chenrui
     * @date 2025/2/20 20:08
     */
    private AiragModel getEmbedModelData(String modelId) {
        //update-begin---author:wangshuai---date:2026-03-09---for:【QQYUN-14645】添加默认向量模型---
        if (oConvertUtils.isNotEmpty(modelId)) {
            AiragModel model = airagModelMapper.getByIdIgnoreTenant(modelId);
            if (model != null) {
                AssertUtils.assertEquals("仅支持向量模型", LLMConsts.MODEL_TYPE_EMBED, model.getModelType());
                // 判断模型是否已激活，未激活则回退到默认模型
                if (model.getActivateFlag() != null && model.getActivateFlag() == 1) {
                    return model;
                }
                log.warn("向量模型[{}]未激活，尝试使用 yml 中配置的默认向量模型", modelId);
            }
        }
        // 回退到 yml 默认向量模型
        if (aiChatConfig != null && oConvertUtils.isNotEmpty(aiChatConfig.getAiModelEmbed().getApiKey())) {
            log.info("使用 yml 中配置的默认向量模型: {}", aiChatConfig.getAiModelEmbed().getModel());
            return buildDefaultEmbedModel(aiChatConfig.getAiModelEmbed());
        }
        AssertUtils.assertNotEmpty("向量模型不能为空，请先配置向量模型或在 yml 中设置默认向量模型(jeecg.ai-chat.ai-model-embed)", modelId);
        return null;
        //update-end---author:wangshuai---date:2026-03-09---for:【QQYUN-14645】添加默认向量模型---
    }

    /**
     * 根据 yml 配置构建默认向量模型对象
     *
     * @param embedConfig yml 中的向量模型配置
     * @return AiragModel
     */
    private AiragModel buildDefaultEmbedModel(AiChatConfig.ModelConfig embedConfig) {
        AiragModel model = new AiragModel();
        model.setModelName(embedConfig.getModel());
        model.setBaseUrl(embedConfig.getApiHost());
        model.setProvider(embedConfig.getProvider());
        model.setModelType(LLMConsts.MODEL_TYPE_EMBED);
        JSONObject credential = new JSONObject();
        credential.put("apiKey", embedConfig.getApiKey());
        model.setCredential(credential.toJSONString());
        return model;
    }

    /**
     * 获取向量存储
     *
     * @param model
     * @return
     * @author chenrui
     * @date 2025/2/18 14:56
     */
    private EmbeddingStore<TextSegment> getEmbedStore(AiragModel model) {
        AssertUtils.assertNotEmpty("未配置模型", model);
        String modelId = model.getId();
        String connectionInfo = embedStoreConfigBean.getHost() + embedStoreConfigBean.getPort() + embedStoreConfigBean.getDatabase();
        String key = modelId + connectionInfo;
        if (EMBED_STORE_CACHE.containsKey(key)) {
            return EMBED_STORE_CACHE.get(key);
        }


        AiModelOptions modelOp = buildModelOptions(model);
        EmbeddingModel embeddingModel = AiModelFactory.createEmbeddingModel(modelOp);

        String tableName = embedStoreConfigBean.getTable();

        // update-begin---author:sunjianlei ---date:20250509  for：【QQYUN-12345】向量模型维度不一致问题
        // 如果该模型不是默认的向量维度
        int dimension = embeddingModel.dimension();
        if (!LLMConsts.EMBED_MODEL_DEFAULT_DIMENSION.equals(dimension)) {
            // 就加上维度后缀，防止因维度不一致导致保存失败
            tableName += ("_" + dimension);
        }
        // update-end-----author:sunjianlei ---date:20250509  for：【QQYUN-12345】向量模型维度不一致问题

        //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 8】PgVectorEmbeddingStore 根据开关启用 HYBRID-------
        EmbeddingStore<TextSegment> embeddingStore = PgVectorEmbeddingStore.builder()
                // Connection and table parameters
                .host(embedStoreConfigBean.getHost())
                .port(embedStoreConfigBean.getPort())
                .database(embedStoreConfigBean.getDatabase())
                .user(embedStoreConfigBean.getUser())
                .password(embedStoreConfigBean.getPassword())
                .table(tableName)
                // Embedding dimension
                // Required: Must match the embedding model’s output dimension
                .dimension(embeddingModel.dimension())
                // Indexing and performance options
                // Enable IVFFlat index
                .useIndex(true)
                // Number of lists
                // for IVFFlat index
                .indexListSize(100)
                // Table creation options
                // Automatically create the table if it doesn’t exist
                .createTable(true)
                //Don’t drop the table first (set to true if you want a fresh start)
                .dropTableFirst(false)
                // 检索模式由 KnowConfigBean 的 Kill Switch 控制，默认 VECTOR (行为与之前完全一致)
                .searchMode(knowConfigBean != null && knowConfigBean.isHybridSearch() ? SearchMode.HYBRID : SearchMode.VECTOR)
                .textSearchConfig(knowConfigBean != null ? knowConfigBean.getTextSearchConfig() : "simple")
                .rrfK(knowConfigBean != null ? knowConfigBean.getRrfK() : 60)
                .build();
        //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 8】PgVectorEmbeddingStore 根据开关启用 HYBRID-------
        EMBED_STORE_CACHE.put(key, embeddingStore);
        return embeddingStore;
    }

    /**
     * 构造ModelOptions
     *
     * @param model
     * @return
     * @author chenrui
     * @date 2025/3/11 17:45
     */
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
        modelOpBuilder.topNumber(5);
        modelOpBuilder.similarity(0.75);
        return modelOpBuilder.build();
    }

    /**
     * 解析网页内容，使用Jsoup爬取并转换为Markdown
     *
     * @param doc 知识库文档（metadata中需包含website字段）
     * @return Markdown格式的网页内容
     * @date 2026/3/19
     */
    private String parseWebPage(AiragKnowledgeDoc doc) {
        String metadata = doc.getMetadata();
        AssertUtils.assertNotEmpty("请先配置网页URL", metadata);
        JSONObject metadataJson = JSONObject.parseObject(metadata);
        String website = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_WEBSITE);
        AssertUtils.assertNotEmpty("请先配置网页URL", website);

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

    /**
     * 解析文件
     *
     * @param doc
     * @author chenrui
     * @date 2025/3/5 11:31
     */
    private String parseFile(AiragKnowledgeDoc doc) {
        String metadata = doc.getMetadata();
        AssertUtils.assertNotEmpty("请先上传文件", metadata);
        JSONObject metadataJson = JSONObject.parseObject(metadata);
        if (!metadataJson.containsKey(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH)) {
            throw new JeecgBootException("请先上传文件");
        }
        String filePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
        AssertUtils.assertNotEmpty("请先上传文件", filePath);
        // 网络资源,先下载到临时目录
        filePath = ensureFile(filePath);
        // 提取文档内容
        File docFile = new File(filePath);
        if (docFile.exists()) {
            Document document = new TikaDocumentParser(AutoDetectParser::new, null, null, null).parse(docFile);
            if (null != document) {
                String content = document.text();
                // 判断是否md文档
                String fileType = FilenameUtils.getExtension(docFile.getName());
                if ("md".contains(fileType)) {
                    // 如果是md文件，查找所有图片语法，如果是本地图片，替换成网络图片
                    String baseUrl = "#{domainURL}/sys/common/static/";
                    String sourcePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH);
                    if(oConvertUtils.isNotEmpty(sourcePath)) {
                        String escapedPath = uploadpath;
                        //update-begin---author:wangshuai---date:2025-06-03---for:【QQYUN-12636】【AI知识库】文档库上传 本地local 文档中的图片不展示---
                        /*if (File.separator.equals("\\")){
                            escapedPath = uploadpath.replace("//", "\\\\");
                        }*/
                        //update-end---author:wangshuai---date:2025-06-03---for:【QQYUN-12636】【AI知识库】文档库上传 本地local 文档中的图片不展示---
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

    @NotNull
    private static StringBuffer replaceImageUrl(String content, String abstractBaseUrl, String relativeBaseUrl) {
        // 正则表达式匹配md文件中的图片语法 ![alt text](image url)
        Matcher matcher = PATTERN_MD_IMAGE.matcher(content);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String imageUrl = matcher.group(2);
            // 检查是否是本地图片路径
            if (!imageUrl.startsWith("http")) {
                // 替换成网络图片路径
                String networkImageUrl = abstractBaseUrl + imageUrl;
                if(imageUrl.startsWith("/")) {
                    // 绝对路径
                    networkImageUrl = abstractBaseUrl + imageUrl;
                }else{
                    // 相对路径
                    networkImageUrl = relativeBaseUrl + imageUrl;
                }
                // 修改图片路径中//->/，但保留http://和https://
                networkImageUrl = networkImageUrl.replaceAll("(?<!http:)(?<!https:)//", "/");
                matcher.appendReplacement(sb, "![" + matcher.group(1) + "](" + networkImageUrl + ")");
            } else {
                matcher.appendReplacement(sb, "![" + matcher.group(1) + "](" + imageUrl + ")");
            }
        }
        matcher.appendTail(sb);
        return sb;
    }

    /**
     * 通过MinerU解析文件
     *
     * @param doc
     * @author chenrui
     * @date 2025/4/1 17:37
     */
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
        if (!docFile.exists()
                || "txt".equalsIgnoreCase(fileType)
                || "md".equalsIgnoreCase(fileType)) {
            return ;
        }

        //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU本地部署切换为官方API-----------
        // 根据配置选择本地部署或官方 API
        if ("cloud".equalsIgnoreCase(knowConfigBean.getMinerU().getMode())) {
            parseFileByMinerUCloud(doc, docFile, metadataJson);
            return;
        }
        //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU本地部署切换为官方API-----------

        // 安全校验：拒绝文件名/路径中含有 Shell 注入字符的文件，防止命令注入
        try {
            CommandExecUtil.validateFilePath(docFile.getAbsolutePath());
            CommandExecUtil.validateFilePath(docFile.getName());
        } catch (IllegalArgumentException e) {
            log.error("文件路径包含非法字符，拒绝执行 MinerU 解析: {}", e.getMessage());
            throw new JeecgBootException("文件名包含非法字符，无法处理该文件");
        }

        // 使用 String[] 数组构建命令，避免 split(" ") 带来的参数边界问题
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
            // 如果成功,替换文件路径和静态资源路径
            String fileBaseName = FilenameUtils.getBaseName(docFile.getName());
            String newFileDir = outputPath + File.separator + fileBaseName + File.separator + "auto" + File.separator ;
            // 先检查文件是否存在,存在才替换
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

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir，调用方仅传路径-------
    /**
     * 通过 MinerU 官方 API 解析文件
     *
     * @param doc          知识库文档
     * @param docFile      本地文件
     * @param metadataJson 文档元数据
     * @author song
     * @date 2026/7/9
     */
    private void parseFileByMinerUCloud(AiragKnowledgeDoc doc, File docFile, JSONObject metadataJson) {
        AssertUtils.assertNotEmpty("MinerU 官方 API 客户端未初始化", mineruApiClient);

        //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir，调用方仅传路径-------
        long startTime = System.currentTimeMillis();
        String fileType = FilenameUtils.getExtension(docFile.getName());

        // 先准备输出目录（images 拷贝与 full.md 写入由 MineruApiClient 统一处理）
        String fileBaseName = FilenameUtils.getBaseName(docFile.getName());
        String relativeDir = "mineru" + File.separator + UUIDGenerator.generate() + File.separator + fileBaseName + File.separator + "auto" + File.separator;
        String outputPath = uploadpath + File.separator + relativeDir;
        File outputDir = new File(outputPath);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new JeecgBootException("创建 MinerU 官方 API 解析结果目录失败: " + outputPath);
        }

        //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，调用方传入fileBaseName + ".md"与metadataJson FILEPATH路径保持一致-------
        String markdown = mineruApiClient.parse(docFile, fileType, outputDir, fileBaseName + ".md");
        //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，调用方传入fileBaseName + ".md"与metadataJson FILEPATH路径保持一致-------

        if (oConvertUtils.isEmpty(markdown)) {
            log.warn("MinerU 官方 API 解析结果为空, file: {}", docFile.getName());
            return;
        }

        // 回写 metadata：md 路径 + sourcesPath；保留原始 PDF 供左侧预览/续跑
        //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】保留 originalFilePath，刷新后续跑仍能开 PDF-----------
        String prevPath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
        if (oConvertUtils.isNotEmpty(prevPath) && !metadataJson.containsKey("originalFilePath")) {
            // 仅当原路径像 PDF/Office 时保留
            String lower = prevPath.toLowerCase();
            if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                    || lower.contains(".pdf") || !isTextLikeFilePath(prevPath)) {
                metadataJson.put("originalFilePath", prevPath);
            }
        }
        //update-end---author:song ---date:2026-07-18  for：【GB线性入库】保留 originalFilePath-----------
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeDir + fileBaseName + ".md");
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
        //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】解析阶段就把 images/ 写成可访问静态路径并回写 md 文件-----------
        // 业界常见做法：入库解析时固化资源 URL，预览不再依赖前端猜相对路径
        try {
            String rewritten = rewriteLocalImagesToStaticPath(markdown, relativeDir);
            if (oConvertUtils.isNotEmpty(rewritten) && !rewritten.equals(markdown)) {
                File mdOut = new File(outputPath, fileBaseName + ".md");
                FileUtils.writeStringToFile(mdOut, rewritten, StandardCharsets.UTF_8);
                log.info("MinerU md 图片路径已固化为 static 路径, file: {}", mdOut.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("MinerU md 图片路径固化失败（预览仍可尝试动态 rewrite）: {}", e.getMessage());
        }
        //update-end---author:song ---date:2026-07-18  for：【GB线性入库】解析阶段就把 images/ 写成可访问静态路径并回写 md 文件-----------
        doc.setMetadata(metadataJson.toJSONString());

        log.info("MinerU 官方 API 解析结果已写入本地, file: {}, dir: {}, cost: {}ms",
                docFile.getName(), outputPath, System.currentTimeMillis() - startTime);
        //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir--------
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir，调用方仅传路径-------

    /**
     * 确保文件存在
     * @param filePath
     * @return
     * @author chenrui
     * @date 2025/4/1 17:36
     */
    @NotNull
    private String ensureFile(String filePath) {
        // 网络资源,先下载到临时目录
        Matcher matcher = LLMConsts.WEB_PATTERN.matcher(filePath);
        if (matcher.matches()) {
            log.info("网络资源,下载到临时目录:" + filePath);
            // 准备文件
            String tempFilePath = uploadpath + File.separator + "tmp" + File.separator + UUIDGenerator.generate() + File.separator;
            String fileName = filePath;
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf("?"));
            }
            fileName = FilenameUtils.getName(fileName);
            tempFilePath = tempFilePath + fileName;
            FileDownloadUtils.download2DiskFromNet(filePath, tempFilePath);
            filePath = tempFilePath;
        } else {
            //update-begin---author:wangshuai---date:2026-03-30---for:【issues/9424】CommandExecUtil 命令执行过程中存在疑似路径遍历漏洞/【issues/9425】EmbeddingHandler 知识库解析过程中疑似存在路径遍历漏洞---
            // 1. 路径遍历检查：拒绝 .. 和 %2e 等绕过手段
            SsrfFileTypeFilter.checkPathTraversal(filePath);
            // 2. 标准化路径并校验是否在 uploadpath 范围内
            Path root = Paths.get(uploadpath).toAbsolutePath().normalize();
            //update-begin---author:wangshuai ---date:2026-04-13  for：zip文件 filePath 以 \ 或 / 开头，在Windows下被Path.resolve当成驱动器根路径导致误判路径遍历，先剥掉前导分隔符-----------
            // 去除前导分隔符，保证作为相对路径 resolve 到 uploadpath 之下
            String relativePath = filePath.replaceAll("^[\\\\/]+", "");
            Path target = root.resolve(relativePath).toAbsolutePath().normalize();
            //update-end---author:wangshuai ---date:2026-04-13  for：zip文件 filePath 以 \ 或 / 开头，在Windows下被Path.resolve当成驱动器根路径导致误判路径遍历，先剥掉前导分隔符-----------
            if (!target.startsWith(root)) {
                log.error("检测到路径遍历攻击! filePath: {}, 解析后: {}", filePath, target);
                throw new JeecgBootException("文件路径包含非法字符");
            }
            filePath = target.toString();
            //update-end---author:wangshuai---date:2026-03-30---for:【issues/9424】CommandExecUtil 命令执行过程中存在疑似路径遍历漏洞/【issues/9425】EmbeddingHandler 知识库解析过程中疑似存在路径遍历漏洞---
        }
        return filePath;
    }


}
