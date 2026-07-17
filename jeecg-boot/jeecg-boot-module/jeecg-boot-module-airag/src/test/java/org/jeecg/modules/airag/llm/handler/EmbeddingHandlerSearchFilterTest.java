package org.jeecg.modules.airag.llm.handler;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.jeecg.ai.factory.AiModelFactory;
import org.jeecg.ai.factory.AiModelOptions;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.config.EmbedStoreConfigBean;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragKnowledge;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.extractor.GbMetadataExtractor;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeMapper;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeService;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//update-begin---author:song-claude ---date:2026-07-12  for：【GB-RAG P1.1 Task 11】searchEmbedding 标量过滤 + HYBRID minScore 单元测试--------
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】searchEmbedding intent 改 QueryIntent（领域无关 4 槽位 + 通用骨架）-----------
/**
 * EmbeddingHandler.searchEmbedding 关键路径单元测试。
 *
 * 覆盖目标：
 *  1) 传入 QueryIntent 后，构造出的 EmbeddingSearchRequest.filter 非空（基于 clause_id/standard_no/amendment/primary_type/secondary_type 标量条件叠加 knowledgeId）。
 *  2) HYBRID 模式下，minScore 不等于传入的相似度阈值（HYBRID 必须忽略外部 similarity）。
 *
 * 实现说明 / 妥协说明：
 *  - EmbeddingHandler.getEmbedStore() 是 private，且内部直连 PgVectorEmbeddingStore 真实构建，
 *    不易 mock。采用反射把 mock EmbeddingStore 塞进静态缓存 EMBED_STORE_CACHE，
 *    让 getEmbedStore() 命中缓存直接返回 mock，从而绕过真正的 PgVector 构建。
 *  - LangChain4j 1.17.2 EmbeddingSearchRequest builder 在 minScore == null 时回退为 0.0
 *    （Utils.getOrDefault(null, 0.0)），因此 HYBRID 模式下 request.minScore() 返回 0.0 原始 double，
 *    无法断言 "isNull"。改用 "isNotEqualTo(0.75)" 验证 HYBRID 确实改写了 minScore。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmbeddingHandlerSearchFilterTest {

    private static final String CACHE_KEY_HOST = "127.0.0.1";
    private static final int CACHE_KEY_PORT = 5432;
    private static final String CACHE_KEY_DB = "postgres";
    private static final String MODEL_ID = "m1";
    private static final String KNOW_ID = "k1";

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
        // 默认 Kill Switch 状态：HYBRID / QueryExpansion 全关
        when(knowConfigBean.isHybridSearch()).thenReturn(false);
        when(knowConfigBean.isQueryExpansionEnabled()).thenReturn(false);
        when(knowConfigBean.getRrfK()).thenReturn(60);

        // 让 getEmbedStore() 算出的 cache key 与我们塞进缓存的 key 保持一致
        when(embedStoreConfigBean.getHost()).thenReturn(CACHE_KEY_HOST);
        when(embedStoreConfigBean.getPort()).thenReturn(CACHE_KEY_PORT);
        when(embedStoreConfigBean.getDatabase()).thenReturn(CACHE_KEY_DB);

        // 预填静态缓存，命中后 getEmbedStore() 直接返回 mock，绕过 PgVectorEmbeddingStore 构建
        ConcurrentHashMap<String, EmbeddingStore<TextSegment>> cache = getEmbedStoreCache();
        cache.put(MODEL_ID + CACHE_KEY_HOST + CACHE_KEY_PORT + CACHE_KEY_DB, embeddingStore);
    }

    @Test
    void shouldBuildMetadataFilterFromIntent() {
        AiragKnowledge knowledge = new AiragKnowledge();
        knowledge.setId(KNOW_ID);
        knowledge.setEmbedId(MODEL_ID);
        knowledge.setType("file");
        when(airagKnowledgeMapper.getByIdIgnoreTenant(KNOW_ID)).thenReturn(knowledge);

        AiragModel model = buildEmbedModel();
        when(airagModelMapper.getByIdIgnoreTenant(MODEL_ID)).thenReturn(model);

        try (MockedStatic<AiModelFactory> factory = mockStatic(AiModelFactory.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> AiModelFactory.createEmbeddingModel(any(AiModelOptions.class)))
                    .thenReturn(embeddingModel);
            when(embeddingModel.embed(anyString()))
                    .thenReturn(Response.from(Embedding.from(new float[1536])));
            when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                    .thenReturn(new EmbeddingSearchResult<>(Collections.emptyList()));

            QueryIntent intent = QueryIntent.builder()
                    .secondaryType("9")
                    .primaryType("overcharge")
                    .clauseId("9.2")
                    .version("2022")
                    .build();

            embeddingHandler.searchEmbedding(KNOW_ID, "过压充电要求", 5, 0.75, intent);

            ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
            verify(embeddingStore).search(captor.capture());
            EmbeddingSearchRequest request = captor.getValue();
            assertThat(request.filter()).as("intent 应参与构造 filter").isNotNull();
            // 非 HYBRID 模式下 minScore 等于传入的 similarity（0.75）
            assertThat(request.minScore()).isEqualTo(0.75);
        }
    }

    @Test
    void shouldIgnorePassedInSimilarityInHybridMode() {
        // 单独开启 HYBRID 开关，验证 minScore 不等于传入的 0.75
        when(knowConfigBean.isHybridSearch()).thenReturn(true);

        AiragKnowledge knowledge = new AiragKnowledge();
        knowledge.setId(KNOW_ID);
        knowledge.setEmbedId(MODEL_ID);
        knowledge.setType("file");
        when(airagKnowledgeMapper.getByIdIgnoreTenant(KNOW_ID)).thenReturn(knowledge);

        AiragModel model = buildEmbedModel();
        when(airagModelMapper.getByIdIgnoreTenant(MODEL_ID)).thenReturn(model);

        try (MockedStatic<AiModelFactory> factory = mockStatic(AiModelFactory.class,
                org.mockito.Mockito.CALLS_REAL_METHODS)) {
            factory.when(() -> AiModelFactory.createEmbeddingModel(any(AiModelOptions.class)))
                    .thenReturn(embeddingModel);
            when(embeddingModel.embed(anyString()))
                    .thenReturn(Response.from(Embedding.from(new float[1536])));
            when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                    .thenReturn(new EmbeddingSearchResult<>(Collections.emptyList()));

            embeddingHandler.searchEmbedding(KNOW_ID, "过压充电要求", 5, 0.75, (QueryIntent) null);

            ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
            verify(embeddingStore).search(captor.capture());
            EmbeddingSearchRequest request = captor.getValue();
            // HYBRID 模式下 production 代码传 null 给 builder.minScore，
            // LangChain4j 1.17.2 builder 在 minScore == null 时回退为 0.0（getOrDefault(null, 0.0)），
            // 因此 request.minScore() 返回 0.0（primitive double），不可能 isNull。
            // 关键观察：HYBRID 开启时 request.minScore() 必须不等于传入的 0.75，
            // 否则就违背了"HYBRID 忽略外部 similarity"的语义。
            assertThat(request.minScore())
                    .as("HYBRID 模式下 minScore 必须不等于传入的 0.75（生产代码故意覆盖为 0/null）")
                    .isNotEqualTo(0.75);
            assertThat(request.filter()).as("HYBRID 模式下 filter 仍应包含 knowledgeId").isNotNull();
        }
    }

    private AiragModel buildEmbedModel() {
        AiragModel model = new AiragModel();
        model.setId(MODEL_ID);
        model.setModelType(LLMConsts.MODEL_TYPE_EMBED);
        model.setProvider("openai");
        model.setModelName("text-embedding");
        model.setBaseUrl("http://localhost");
        model.setCredential("{\"apiKey\":\"sk-test\"}");
        model.setActivateFlag(1);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, EmbeddingStore<TextSegment>> getEmbedStoreCache() throws Exception {
        Field cacheField = EmbeddingHandler.class.getDeclaredField("EMBED_STORE_CACHE");
        cacheField.setAccessible(true);
        return (ConcurrentHashMap<String, EmbeddingStore<TextSegment>>) cacheField.get(null);
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】searchEmbedding intent 改 QueryIntent-----------
//update-end---author:song-claude ---date:2026-07-12  for：【GB-RAG P1.1 Task 11】searchEmbedding 标量过滤 + HYBRID minScore 单元测试--------
