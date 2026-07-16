package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbBatchExtractor 测试-----------
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbBatchExtractorTest {

    @Mock
    private IAiragModelService airagModelService;
    @Mock
    private GbLlmClient llmClient;

    private GbStandardProperties.ClauseMetadataExtractor config;
    private GbBatchExtractor extractor;

    @BeforeEach
    void setUp() {
        config = new GbStandardProperties.ClauseMetadataExtractor();
        extractor = new GbBatchExtractor(airagModelService, config, new ObjectMapper(), llmClient);
    }

    @Test
    void disabledShouldReturnEmptyResultsPreservingClausePath() {
        config.setEnabled(false);
        GbClauseNode node = new GbClauseNode();
        node.setClausePath("9.2");
        node.setText("过压充电保护");
        List<BatchExtractResult> results = extractor.extractBatch(List.of(node), new DomainSchema());
        // 关闭时：返回与条款数相同的结果，但槽位全空（不阻塞，回填时只写 clausePath）
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClausePath()).isEqualTo("9.2");
        assertThat(results.get(0).getPrimaryType()).isNull();
        assertThat(results.get(0).getPolarity()).isNull();
    }

    @Test
    void emptyClausesShouldReturnEmptyList() {
        List<BatchExtractResult> results = extractor.extractBatch(List.of(), new DomainSchema());
        assertThat(results).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbBatchExtractor 测试-----------
