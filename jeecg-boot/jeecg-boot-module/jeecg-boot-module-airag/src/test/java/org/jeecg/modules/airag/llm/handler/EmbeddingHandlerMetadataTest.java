package org.jeecg.modules.airag.llm.handler;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.jeecg.ai.factory.AiModelFactory;
import org.jeecg.ai.factory.AiModelOptions;
import org.jeecg.modules.airag.llm.config.EmbedStoreConfigBean;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragKnowledge;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.extractor.GbMetadataExtractor;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeMapper;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeService;
import org.jeecg.modules.airag.llm.vo.GbMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//update-begin---author:song-claude ---date:2026-07-12  for：【GB-RAG P1.1 Task 11】embeddingDocument 写入 GB metadata 单元测试--------
/**
 * EmbeddingHandler.embeddingDocument 关键路径单元测试：
 *  - 验证 GbMetadataExtractor.extractFromText() 被调用，
 *    且抽取结果（chapter/testType/status）被写入 TextSegment.metadata()，
 *    最终进入 embeddingStore.addAll(embeddings, segments) 的 segments 列表。
 *
 * 实现说明 / 妥协说明：
 *  - EmbeddingHandler.getEmbedStore() 是 private 且内部直连 PgVectorEmbeddingStore 真实构建，
 *    采用反射把 mock EmbeddingStore 塞进静态缓存 EMBED_STORE_CACHE 绕过真实构建。
 *  - 使用纯文字内容（无 HTML 表格）触发 ingestor.ingest(from) 路径，避免触发 HTML 表格特殊分段逻辑。
 *  - doc.getMetadata() 留空，createDocumentSplitter 走默认 DocumentSplitters.recursive 分段器，
 *    使整段文本落入同一个 TextSegment，方便断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingHandlerMetadataTest {

    private static final String CACHE_KEY_HOST = "127.0.0.1";
    private static final int CACHE_KEY_PORT = 5432;
    private static final String CACHE_KEY_DB = "postgres";
    private static final String MODEL_ID = "m1";
    private static final String KNOW_ID = "k1";
    private static final String DOC_ID = "d1";

    @InjectMocks
    private EmbeddingHandler embeddingHandler;

    @Mock
    private IAiragKnowledgeService airagKnowledgeService;

    @Mock
    private AiragKnowledgeMapper airagKnowledgeMapper;

    @Mock
    private AiragModelMapper airagModelMapper;

    @Mock
    private KnowConfigBean knowConfigBean;

    @Mock
    private EmbedStoreConfigBean embedStoreConfigBean;

    @Mock
    private GbMetadataExtractor gbMetadataExtractor;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @BeforeEach
    void setUp() throws Exception {
        // 关闭所有 Kill Switch：HYBRID / QueryExpansion
        when(knowConfigBean.isHybridSearch()).thenReturn(false);
        when(knowConfigBean.isQueryExpansionEnabled()).thenReturn(false);
        when(knowConfigBean.getRrfK()).thenReturn(60);

        when(embedStoreConfigBean.getHost()).thenReturn(CACHE_KEY_HOST);
        when(embedStoreConfigBean.getPort()).thenReturn(CACHE_KEY_PORT);
        when(embedStoreConfigBean.getDatabase()).thenReturn(CACHE_KEY_DB);

        // 预填静态缓存，命中后 getEmbedStore() 直接返回 mock
        ConcurrentHashMap<String, EmbeddingStore<TextSegment>> cache = getEmbedStoreCache();
        cache.put(MODEL_ID + CACHE_KEY_HOST + CACHE_KEY_PORT + CACHE_KEY_DB, embeddingStore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldWriteGbMetadataToSegments() {
        AiragKnowledge knowledge = new AiragKnowledge();
        knowledge.setId(KNOW_ID);
        knowledge.setEmbedId(MODEL_ID);
        when(airagKnowledgeService.getById(KNOW_ID)).thenReturn(knowledge);

        AiragModel model = new AiragModel();
        model.setId(MODEL_ID);
        model.setModelType(LLMConsts.MODEL_TYPE_EMBED);
        model.setProvider("openai");
        model.setModelName("text-embedding");
        model.setBaseUrl("http://localhost");
        model.setCredential("{\"apiKey\":\"sk-test\"}");
        model.setActivateFlag(1);
        when(airagModelMapper.getByIdIgnoreTenant(MODEL_ID)).thenReturn(model);

        AiragKnowledgeDoc doc = new AiragKnowledgeDoc();
        doc.setId(DOC_ID);
        doc.setKnowledgeId(KNOW_ID);
        doc.setTitle("test");
        // 纯文字（不含 <table>），触发 ingestor.ingest(from) 路径
        doc.setContent("第9章 过压充电测试要求，依据GB 31241-2022");

        when(gbMetadataExtractor.extractFromText(anyString())).thenReturn(
                GbMetadata.builder()
                        .chapter("9")
                        .testType("过压充电")
                        .status("current")
                        .build()
        );

        try (MockedStatic<AiModelFactory> factory = mockStatic(AiModelFactory.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> AiModelFactory.createEmbeddingModel(any(AiModelOptions.class)))
                    .thenReturn(embeddingModel);
            when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(
                    Collections.singletonList(Embedding.from(new float[1536]))
            ));

            embeddingHandler.embeddingDocument(KNOW_ID, doc);

            // 验证 GbMetadataExtractor 被传入 doc content 调用
            verify(gbMetadataExtractor).extractFromText(
                    org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains("过压充电")));

            // 验证 embeddingStore.addAll(embeddings, segments) 的 segments 参数带上了 GB metadata
            ArgumentCaptor<List<TextSegment>> segmentCaptor = ArgumentCaptor.forClass(List.class);
            verify(embeddingStore).addAll(anyList(), segmentCaptor.capture());
            List<TextSegment> segments = segmentCaptor.getValue();
            assertThat(segments).as("应至少写出一个分段").isNotEmpty();
            Metadata metadata = segments.get(0).metadata();
            assertThat(metadata.getString(GbMetadata.KEY_CHAPTER)).as("chapter 应来自 GbMetadataExtractor").isEqualTo("9");
            assertThat(metadata.getString(GbMetadata.KEY_TEST_TYPE)).as("test_type 应来自 GbMetadataExtractor").isEqualTo("过压充电");
            assertThat(metadata.getString(GbMetadata.KEY_STATUS)).as("status 应来自 GbMetadataExtractor").isEqualTo("current");
            // 同时验证既有 metadata（knowledgeId 等）也被保留
            assertThat(metadata.getString(EmbeddingHandler.EMBED_STORE_METADATA_KNOWLEDGEID))
                    .as("knowledgeId 应被写入 metadata")
                    .isEqualTo(KNOW_ID);
            assertThat(metadata.getString(EmbeddingHandler.EMBED_STORE_METADATA_DOCID))
                    .as("docId 应被写入 metadata")
                    .isEqualTo(DOC_ID);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, EmbeddingStore<TextSegment>> getEmbedStoreCache() throws Exception {
        Field cacheField = EmbeddingHandler.class.getDeclaredField("EMBED_STORE_CACHE");
        cacheField.setAccessible(true);
        return (ConcurrentHashMap<String, EmbeddingStore<TextSegment>>) cacheField.get(null);
    }
}
//update-end---author:song-claude ---date:2026-07-12  for：【GB-RAG P1.1 Task 11】embeddingDocument 写入 GB metadata 单元测试--------
