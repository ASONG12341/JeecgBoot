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
import org.jeecg.modules.airag.llm.gbstandard.service.GbStandardResolver;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING：注入 EmbeddingHandler + GbStandardResolver，加 3 个断点-catching verify-----------
    @Mock private EmbeddingHandler embeddingHandler;
    @Mock private GbStandardResolver gbStandardResolver;
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING-----------

    @Test
    void runShouldDeriveExtractPersistAndAuditInOrder() {
        GbStandard standard = new GbStandard();
        standard.setId("std-1");
        standard.setKnowledgeId("know-1");
        standard.setStandardNo("GB 31241-2022");
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
        // 构造 1 个参数 + 1 个跨标准引用，让 persistParametersAndReferences 有数据可处理
        BatchExtractResult.ParamExtract param = new BatchExtractResult.ParamExtract();
        param.setParamName("最大充电电压");
        param.setFormula("U = 4.25 * n");
        param.setParamValue(new BigDecimal("4.25"));
        param.setUnit("V");
        result.setParameters(List.of(param));
        BatchExtractResult.RefExtract ref = new BatchExtractResult.RefExtract();
        ref.setTargetType("inter");
        ref.setTargetStandardNo("GB/T 18287");
        ref.setTargetClausePath("5.1");
        ref.setRefType("normative_reference");
        result.setReferences(List.of(ref));
        when(batchExtractor.extractBatch(any(), any())).thenReturn(List.of(result));
        when(clauseRepository.saveBatch(eq("std-1"), any())).thenReturn(1);
        when(gbStandardResolver.resolveStandardId(eq("GB/T 18287"))).thenReturn(Optional.of("std-target-1"));
        // objectMapper.writeValueAsString 在 run() 内被调用（存 domain_schema）—— 返回任意字符串避免 NPE
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {
        }

        pipeline.run(standard, structure);

        // 验证编排顺序（严格 InOrder，确保 derive→updateById→extractBatch→saveBatch→audit）
        org.mockito.InOrder o = Mockito.inOrder(schemaDeriver, gbStandardMapper, batchExtractor, clauseRepository, auditLogRepository);
        o.verify(schemaDeriver).derive(any());                       // 1. 推导 schema
        o.verify(gbStandardMapper).updateById(any(GbStandard.class)); // 2. 存 domain_schema
        o.verify(batchExtractor).extractBatch(any(), any());          // 3. 批量抽取
        o.verify(clauseRepository).saveBatch(eq("std-1"), any());     // 4. 持久化条款
        o.verify(auditLogRepository).save(any());                     // 5. 审计埋点

        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING 断点-catching：验证 embedClauses + param/ref saveBatch 真被调用-----------
        // 既往测试只验证 derive→saveBatch→audit 顺序，从未验证 embedClauses / param/ref saveBatch 被调用 —— 这是 P5 数据流断点漏检的根因。
        // 这 3 个 verify 是把 LLM→airag_embedding（向量化） + LLM→gb_parameter/gb_reference（结构化持久化）两条断路重新接通的回归闸门。
        verify(embeddingHandler).embedClauses(eq("know-1"), eq("GB 31241-2022"), any());
        verify(parameterRepository).saveBatch(eq("std-1"), any());
        verify(referenceRepository).saveBatch(eq("std-1"), any());
        // 跨标准引用解析也应被触发（inter + targetStandardNo）
        verify(gbStandardResolver).resolveStandardId(eq("GB/T 18287"));
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING 断点-catching-----------
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
