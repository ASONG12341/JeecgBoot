//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntentExtractor LLM 槽位抽取测试-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryIntentExtractorTest {

    @Mock
    private GbLlmClient gbLlmClient;

    private QueryIntentExtractor extractor;

    @BeforeEach
    void setUp() {
        // enabled=true（默认），测试 fallback 路径
        extractor = new QueryIntentExtractor(gbLlmClient, new ObjectMapper(), true);
    }

    @Test
    void emptyQueryShouldReturnEmptyIntent() {
        QueryIntent intent = extractor.extractWithFallback("", List.of("know-1"));
        assertThat(intent).isNotNull();
        assertThat(intent.getPrimaryType()).isNull();
        assertThat(intent.getStandardNo()).isNull();
    }

    @Test
    void nullKnowIdsShouldReturnEmptyIntent() {
        QueryIntent intent = extractor.extractWithFallback("某 query", null);
        assertThat(intent).isNotNull();
        assertThat(intent.getPrimaryType()).isNull();
    }

    @Test
    void disabledShouldReturnEmptyIntentWithoutLlmCall() {
        QueryIntentExtractor disabled = new QueryIntentExtractor(gbLlmClient, new ObjectMapper(), false);
        QueryIntent intent = disabled.extractWithFallback("3S 电池包过充", List.of("know-1"));
        assertThat(intent.getPrimaryType()).isNull();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntentExtractor LLM 槽位抽取测试-----------
