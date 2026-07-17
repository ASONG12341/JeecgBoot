package org.jeecg.modules.airag.llm.gbstandard.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardProperties 配置子节测试-----------
class GbStandardPropertiesTest {

    @Test
    void schemaDeriverShouldHaveSensibleDefaults() {
        GbStandardProperties.SchemaDeriver deriver = new GbStandardProperties.SchemaDeriver();
        assertThat(deriver.isEnabled()).isTrue();          // 默认启用（领域无关，每标准 1 次）
        assertThat(deriver.getModelName()).isEqualTo("qwen-flash");
        assertThat(deriver.getTimeoutSeconds()).isEqualTo(10);
    }

    @Test
    void clauseMetadataExtractorShouldHaveBatchSizeDefault() {
        GbStandardProperties.ClauseMetadataExtractor extractor = new GbStandardProperties.ClauseMetadataExtractor();
        assertThat(extractor.isEnabled()).isTrue();
        assertThat(extractor.getBatchSize()).isEqualTo(10);
        assertThat(extractor.getModelName()).isEqualTo("qwen-flash");
        assertThat(extractor.getTimeoutSeconds()).isEqualTo(30);
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】tool 子配置测试-----------
    @Test
    void toolShouldHaveCalcEnabledDefaultFalse() {
        assertThat(new GbStandardProperties.Tool().isCalcEnabled()).isFalse();
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】tool 子配置测试-----------
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardProperties 配置子节测试-----------
