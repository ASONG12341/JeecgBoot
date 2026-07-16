//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbIngestionPipeline 编排测试-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbClauseRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbReferenceRepository;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GbIngestionPipelineTest {

    @InjectMocks
    private GbIngestionPipeline pipeline;

    @Mock private GbSchemaDeriver schemaDeriver;
    @Mock private GbBatchExtractor batchExtractor;
    @Mock private GbClauseRepository clauseRepository;
    @Mock private GbParameterRepository parameterRepository;
    @Mock private GbReferenceRepository referenceRepository;
    @Mock private GbAuditLogRepository auditLogRepository;
    @Mock private GbStandardMapper gbStandardMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties.ClauseMetadataExtractor clauseMetadataConfig;

    @Test
    void runShouldDeriveExtractPersistAndAuditInOrder() {
        GbStandard standard = new GbStandard();
        standard.setId("std-1");
        standard.setMarkdownContent("前言...");
        GbDocStructure structure = new GbDocStructure();
        GbClauseNode node = new GbClauseNode();
        node.setClausePath("9.2");
        node.setText("过压充电保护");
        structure.setClauses(List.of(node));

        when(schemaDeriver.derive(any())).thenReturn(new DomainSchema());
        when(clauseMetadataConfig.getBatchSize()).thenReturn(10);
        BatchExtractResult result = new BatchExtractResult();
        result.setClausePath("9.2");
        result.setPrimaryType("overcharge");
        when(batchExtractor.extractBatch(any(), any())).thenReturn(List.of(result));
        when(clauseRepository.saveBatch(eq("std-1"), any())).thenReturn(1);

        pipeline.run(standard, structure);

        // 验证编排顺序（严格 InOrder，确保 derive→updateById→extractBatch→saveBatch→audit）
        org.mockito.InOrder o = Mockito.inOrder(schemaDeriver, gbStandardMapper, batchExtractor, clauseRepository, auditLogRepository);
        o.verify(schemaDeriver).derive(any());                       // 1. 推导 schema
        o.verify(gbStandardMapper).updateById(any(GbStandard.class)); // 2. 存 domain_schema
        o.verify(batchExtractor).extractBatch(any(), any());          // 3. 批量抽取
        o.verify(clauseRepository).saveBatch(eq("std-1"), any());     // 4. 持久化条款
        o.verify(auditLogRepository).save(any());                     // 5. 审计埋点
    }

    @Test
    void runShouldAuditFailureWhenExceptionThrown() {
        GbStandard standard = new GbStandard();
        standard.setId("std-1");
        when(schemaDeriver.derive(any())).thenThrow(new RuntimeException("LLM 挂了"));

        pipeline.run(standard, new GbDocStructure());

        // 异常时仍记审计（success=false）
        verify(auditLogRepository).save(any());
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbIngestionPipeline 编排测试-----------
