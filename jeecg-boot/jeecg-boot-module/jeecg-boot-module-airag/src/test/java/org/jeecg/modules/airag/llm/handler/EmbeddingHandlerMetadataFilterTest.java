package org.jeecg.modules.airag.llm.handler;

import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位测试-----------
@ExtendWith(MockitoExtension.class)
class EmbeddingHandlerMetadataFilterTest {

    @InjectMocks
    private EmbeddingHandler embeddingHandler;

    /** 反射调 private buildMetadataFilter(Filter, QueryIntent) 验证不抛异常 + 接受 QueryIntent */
    @Test
    void buildMetadataFilterShouldAcceptQueryIntentWithSlots() throws Exception {
        Method m = EmbeddingHandler.class.getDeclaredMethod("buildMetadataFilter",
                dev.langchain4j.store.embedding.filter.Filter.class, QueryIntent.class);
        m.setAccessible(true);

        QueryIntent intent = QueryIntent.builder()
                .primaryType("overcharge")
                .secondaryType("pack")
                .clauseId("9.2")
                .standardNo("GB 31241")
                .version("2022")
                .quantityValue(new BigDecimal("3"))
                .build();

        Object result = m.invoke(embeddingHandler,
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("knowledgeId").isEqualTo("k1"),
                intent);
        assertThat(result).isNotNull();
    }

    @Test
    void buildMetadataFilterShouldHandleNullIntent() throws Exception {
        Method m = EmbeddingHandler.class.getDeclaredMethod("buildMetadataFilter",
                dev.langchain4j.store.embedding.filter.Filter.class, QueryIntent.class);
        m.setAccessible(true);
        Object result = m.invoke(embeddingHandler,
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("knowledgeId").isEqualTo("k1"),
                null);
        assertThat(result).isNotNull();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】buildMetadataFilter 改读 4 槽位测试-----------
