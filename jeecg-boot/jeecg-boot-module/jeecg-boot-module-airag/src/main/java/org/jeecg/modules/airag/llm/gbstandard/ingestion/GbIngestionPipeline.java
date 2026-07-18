//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.*;
import org.jeecg.modules.airag.llm.gbstandard.repository.*;
import org.jeecg.modules.airag.llm.gbstandard.service.GbStandardResolver;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
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
    // 嵌套配置不是独立 Bean；注入外层 GbStandardProperties 再取 clauseMetadataExtractor
    @Autowired private GbStandardProperties gbStandardProperties;
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING：注入 EmbeddingHandler（向量化断路）+ GbStandardResolver（跨标准引用目标解析）-----------
    @Autowired private EmbeddingHandler embeddingHandler;
    @Autowired private GbStandardResolver gbStandardResolver;
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING-----------

    public boolean run(GbStandard standard, GbDocStructure structure) {
        GbAuditLog audit = new GbAuditLog();
        audit.setSessionId("ingest-" + standard.getId());
        audit.setRetrievedClauses(structure != null ? String.valueOf(structure.getTotalClauseCount()) : "0");
        boolean success = false;
        try {
            // 1. 推导 domain_schema
            DomainSchema schema = schemaDeriver.derive(extractIntro(structure, standard));
            standard.setDomainSchema(objectMapper.writeValueAsString(schema));
            gbStandardMapper.updateById(standard);

            // 2. 扁平化条款树 + 分批抽取
            List<GbClauseNode> flatClauses = flatten(structure != null ? structure.getClauses() : Collections.emptyList());
            int batchSize = Math.max(1, gbStandardProperties.getClauseMetadataExtractor().getBatchSize()); // 从配置读取，防御性下限 1
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

            //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING：saveBatch 后把 4 槽位写入向量库（连通 LLM→airag_embedding，让 buildMetadataFilter 有数据）-----------
            // saveBatch 内 ASSIGN_ID 在 insert 前已写入 entity，故此处每个 GbClause 已携带 id；embedClauses 据此向量化并携带 metadata。
            embeddingHandler.embedClauses(standard.getKnowledgeId(), standard.getStandardNo(), clauses);
            //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】-----------

            // 4. 参数 + 引用（实施时从 results 收集，存 gb_parameter/gb_reference）
            persistParametersAndReferences(standard.getId(), allResults, clauses);

            success = true;
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
        return success;
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

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING：persistParametersAndReferences 真实实现（替换 P2 占位），连通 LLM→gb_parameter/gb_reference-----------
    /**
     * 把 BatchExtractResult 里的参数/引用映射到 GbParameter/GbReference 并全量持久化（delete-then-insert）。
     * <p>
     * 设计要点：
     * <ul>
     *   <li>clauseId 通过 clausePath→id 映射回填（saveBatch 内 ASSIGN_ID 已在 insert 前写入 entity）。</li>
     *   <li>跨标准引用（targetType=inter）的 targetStandardId 由 GbStandardResolver 解析；intra 时为 null。</li>
     *   <li>两条 saveBatch 各自 try/catch：param/ref 持久化失败只记日志，绝不能打断主入库流程。</li>
     * </ul>
     */
    private void persistParametersAndReferences(String standardId, List<BatchExtractResult> results, List<GbClause> clauses) {
        // 1. 构建 clausePath → clauseId 映射（用于回填 GbParameter.clauseId）
        Map<String, String> clausePathToId = new HashMap<>();
        if (clauses != null) {
            for (GbClause c : clauses) {
                if (c.getClausePath() != null && c.getId() != null) {
                    clausePathToId.put(c.getClausePath(), c.getId());
                }
            }
        }

        // 2. 收集 GbParameter + GbReference
        List<GbParameter> paramList = new ArrayList<>();
        List<GbReference> refList = new ArrayList<>();
        if (results != null) {
            for (BatchExtractResult r : results) {
                if (r == null) continue;
                String clauseId = r.getClausePath() != null ? clausePathToId.get(r.getClausePath()) : null;
                // 参数映射
                if (r.getParameters() != null) {
                    for (BatchExtractResult.ParamExtract p : r.getParameters()) {
                        if (p == null) continue;
                        GbParameter gp = new GbParameter();
                        gp.setStandardId(standardId);
                        gp.setClauseId(clauseId); // path 未命中时为 null（按需求保留，不跳过）
                        gp.setParamName(p.getParamName());
                        gp.setFormula(p.getFormula());
                        gp.setParamValue(p.getParamValue());
                        gp.setUnit(p.getUnit());
                        paramList.add(gp);
                    }
                }
                // 引用映射
                if (r.getReferences() != null) {
                    for (BatchExtractResult.RefExtract ref : r.getReferences()) {
                        if (ref == null) continue;
                        GbReference gr = new GbReference();
                        gr.setSourceStandardId(standardId);
                        gr.setSourceClausePath(r.getClausePath());
                        gr.setTargetType(ref.getTargetType());
                        gr.setTargetStandardNo(ref.getTargetStandardNo());
                        gr.setTargetClausePath(ref.getTargetClausePath());
                        gr.setRefType(ref.getRefType());
                        // 跨标准引用：解析 standardNo→standardId；intra 或无 standardNo 时为 null
                        gr.setTargetStandardId(
                                "inter".equalsIgnoreCase(ref.getTargetType()) && ref.getTargetStandardNo() != null && !ref.getTargetStandardNo().isBlank()
                                        ? gbStandardResolver.resolveStandardId(ref.getTargetStandardNo()).orElse(null)
                                        : null
                        );
                        refList.add(gr);
                    }
                }
            }
        }

        // 3. 全量持久化（各自 try/catch —— 失败不能打断主管线）
        try {
            parameterRepository.saveBatch(standardId, paramList);
            log.info("[GbIngestionPipeline] 参数持久化完成 standardId={}, 参数数={}", standardId, paramList.size());
        } catch (Exception e) {
            log.error("[GbIngestionPipeline] 参数持久化失败 standardId={}, 参数数={}, 错误: {}", standardId, paramList.size(), e.getMessage(), e);
        }
        try {
            referenceRepository.saveBatch(standardId, refList);
            log.info("[GbIngestionPipeline] 引用持久化完成 standardId={}, 引用数={}", standardId, refList.size());
        } catch (Exception e) {
            log.error("[GbIngestionPipeline] 引用持久化失败 standardId={}, 引用数={}, 错误: {}", standardId, refList.size(), e.getMessage(), e);
        }
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 4】MASTER WIRING-----------
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】入库管线编排器（derive→extract→persist→audit）-----------
