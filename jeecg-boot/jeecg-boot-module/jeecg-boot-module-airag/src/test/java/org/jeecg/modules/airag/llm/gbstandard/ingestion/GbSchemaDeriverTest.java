package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbSchemaDeriver 测试-----------
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbSchemaDeriverTest {

    @Mock
    private IAiragModelService airagModelService;
    @Mock
    private GbLlmClient llmClient;

    private GbStandardProperties.SchemaDeriver config;
    private GbSchemaDeriver deriver;

    @BeforeEach
    void setUp() {
        config = new GbStandardProperties.SchemaDeriver();
        deriver = new GbSchemaDeriver(airagModelService, config, new ObjectMapper(), llmClient);
    }

    @Test
    void disabledShouldReturnEmptySchemaWithoutLlmCall() {
        config.setEnabled(false);
        DomainSchema schema = deriver.derive("任何前言文本");
        // 关闭时退化为空 schema（4 槽位全 null），不阻塞入库
        assertThat(schema.getPrimaryType()).isNull();
        assertThat(schema.getSecondaryType()).isNull();
        assertThat(schema.getQuantityValue()).isNull();
        assertThat(schema.getConditionText()).isNull();
    }

    @Test
    void emptyInputShouldReturnEmptySchemaFallback() {
        // 空输入不调 LLM，直接返回空 schema
        DomainSchema schema = deriver.derive("");
        assertThat(schema.getPrimaryType()).isNull();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbSchemaDeriver 测试-----------
