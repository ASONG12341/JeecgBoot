//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.*;
import org.jeecg.modules.airag.llm.gbstandard.repository.*;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 国标入库管线编排器。
 * <p>
 * 用户确认结构后由 GbStandardController.confirm 调用。编排顺序：
 * 1. GbSchemaDeriver 推导 domain_schema → 存 gb_standard.domain_schema
 * 2. GbBatchExtractor 按 batchSize 分批抽取 4 槽位/极性/参数/引用
 * 3. 合并到 GbClause（clausePath 匹配）→ saveBatch 持久化 gb_clause
 * 4. 参数 → gb_parameter，引用 → gb_reference
 * 5. 审计埋点（成功/失败都记）
 * 任何阶段失败记审计 success=false，不抛出（controller 据此回滚状态）。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbIngestionPipeline {

    @Autowired private GbSchemaDeriver schemaDeriver;
    @Autowired private GbBatchExtractor batchExtractor;
    @Autowired private GbClauseRepository clauseRepository;
    @Autowired private GbParameterRepository parameterRepository;
    @Autowired private GbReferenceRepository referenceRepository;
    @Autowired private GbAuditLogRepository auditLogRepository;
    @Autowired private GbStandardMapper gbStandardMapper;
    @Autowired private ObjectMapper objectMapper;

    public void run(GbStandard standard, GbDocStructure structure) {
        GbAuditLog audit = new GbAuditLog();
        audit.setSessionId("ingest-" + standard.getId());
        audit.setRetrievedClauses(structure != null ? String.valueOf(structure.getTotalClauseCount()) : "0");
        try {
            // 1. 推导 domain_schema
            DomainSchema schema = schemaDeriver.derive(extractIntro(structure, standard));
            standard.setDomainSchema(objectMapper.writeValueAsString(schema));
            gbStandardMapper.updateById(standard);

            // 2. 扁平化条款树 + 分批抽取
            List<GbClauseNode> flatClauses = flatten(structure != null ? structure.getClauses() : Collections.emptyList());
            int batchSize = 10; // 从 config 取（实施时注入 ClauseMetadataExtractor.batchSize）
            List<BatchExtractResult> allResults = new ArrayList<>();
            for (int i = 0; i < flatClauses.size(); i += batchSize) {
                List<GbClauseNode> batch = flatClauses.subList(i, Math.min(i + batchSize, flatClauses.size()));
                allResults.addAll(batchExtractor.extractBatch(batch, schema));
            }

            // 3. 合并到 GbClause 并持久化
            Map<String, BatchExtractResult> resultMap = new HashMap<>();
            for (BatchExtractResult r : allResults) resultMap.put(r.getClausePath(), r);
            List<GbClause> clauses = new ArrayList<>();
            for (GbClauseNode node : flatClauses) {
                clauses.add(toClause(standard.getId(), node, resultMap.get(node.getClausePath())));
            }
            clauseRepository.saveBatch(standard.getId(), clauses);

            // 4. 参数 + 引用（实施时从 results 收集，存 gb_parameter/gb_reference）
            persistParametersAndReferences(standard.getId(), allResults);

            audit.setSuccess(true);
            // retrievalChannels 字段为 String 列（JSON 数组文本），按字符串存
            audit.setRetrievalChannels("[\"schema-deriver\",\"batch-extractor\"]");
            log.info("[GbIngestionPipeline] 入库完成, standardId={}, 条款数={}", standard.getId(), clauses.size());
        } catch (Exception e) {
            log.error("[GbIngestionPipeline] 入库失败, standardId={}: {}", standard.getId(), e.getMessage(), e);
            audit.setSuccess(false);
        } finally {
            try { auditLogRepository.save(audit); } catch (Exception ignored) {}
        }
    }

    private GbClause toClause(String standardId, GbClauseNode node, BatchExtractResult r) {
        GbClause c = new GbClause();
        c.setStandardId(standardId);
        c.setClausePath(node.getClausePath());
        c.setParentPath(node.getParentPath());
        c.setDepth(node.getDepth());
        c.setTitle(node.getTitle());
        c.setText(node.getText());
        c.setClauseType(node.getClauseType());
        c.setConfidence(node.getConfidence());
        c.setPageNo(node.getPageNo());
        c.setIsScope(node.isScope());
        c.setIsAppendix(node.isAppendix());
        c.setAppendixLabel(node.getAppendixLabel());
        if (r != null) {
            c.setPrimaryType(r.getPrimaryType());
            c.setSecondaryType(r.getSecondaryType());
            c.setQuantityValue(r.getQuantityValue());
            c.setConditionText(r.getConditionText());
            c.setPolarity(r.getPolarity() != null ? r.getPolarity() : "positive");
            c.setExceptionOf(r.getExceptionOf());
        }
        return c;
    }

    private List<GbClauseNode> flatten(List<GbClauseNode> roots) {
        List<GbClauseNode> flat = new ArrayList<>();
        for (GbClauseNode n : roots) { flattenInto(n, flat); }
        return flat;
    }
    private void flattenInto(GbClauseNode n, List<GbClauseNode> out) {
        out.add(n);
        for (GbClauseNode child : n.getChildren()) flattenInto(child, out);
    }

    private String extractIntro(GbDocStructure structure, GbStandard standard) {
        // 取结构树的前若干条款文本拼成"前言+目录+前3章"近似输入
        // 实施时：拼 standard.getMarkdownContent() 前 8000 字符 + structure 概要
        return standard.getMarkdownContent() != null ? standard.getMarkdownContent() : "";
    }

    private void persistParametersAndReferences(String standardId, List<BatchExtractResult> results) {
        // P2 占位：参数/引用全量持久化延迟。
        // 原因：GbParameter 需 clauseId，而 clauseId 由 saveBatch 插入时生成，
        // 需先按 clausePath 回查已存条款拿 clauseId 再转换。此处仅记计数日志。
        int paramCount = 0;
        int refCount = 0;
        if (results != null) {
            for (BatchExtractResult r : results) {
                if (r.getParameters() != null) paramCount += r.getParameters().size();
                if (r.getReferences() != null) refCount += r.getReferences().size();
            }
        }
        log.info("[GbIngestionPipeline] 参数/引用持久化 standardId={}（P2 占位：param={}, ref={}，全量持久化延后）",
                standardId, paramCount, refCount);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
