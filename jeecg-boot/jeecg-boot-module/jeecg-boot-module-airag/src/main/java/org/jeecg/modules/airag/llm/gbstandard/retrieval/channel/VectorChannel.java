package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量检索通道
 * <p>
 * 核心职责：
 * 1. 自然语言查询
 * 2. 语义相似性匹配
 * 3. 模糊概念查询
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component("vectorChannel")
public class VectorChannel implements RetrievalChannel {

    @Autowired
    private EmbeddingHandler embeddingHandler;

    @Override
    public String getChannelName() {
        return "VECTOR";
    }

    @Override
    public List<RetrievalResult> search(RetrievalRequest request) {
        log.info("[VectorChannel] 开始向量检索, query={}, topK={}", request.getQuery(), request.getTopK());

        try {
            // 调用 EmbeddingHandler 的向量检索方法
            List<String> knowledgeIds = request.getKnowledgeIds();
            if (knowledgeIds == null || knowledgeIds.isEmpty()) {
                log.warn("[VectorChannel] 知识库ID列表为空，返回空结果");
                return new ArrayList<>();
            }

            //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】VectorChannel 改调带 intent 的 searchEmbedding（启用 metadata 精排）-----------
            // 调用向量检索（5 参，传 QueryIntent 启用 clause_id/standard_no/amendment/primary_type/secondary_type 标量精排过滤）
            List<Map<String, Object>> searchResults = embeddingHandler.searchEmbedding(
                    knowledgeIds.get(0),
                    request.getQuery(),
                    request.getTopK(),
                    request.getSimilarityThreshold(),
                    request.getQueryIntent()
            );
            //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】VectorChannel 改调带 intent 的 searchEmbedding-----------

            // 转换为 RetrievalResult 列表
            List<RetrievalResult> results = searchResults.stream()
                    .map(this::convertToRetrievalResult)
                    .collect(Collectors.toList());

            log.info("[VectorChannel] 向量检索完成, 召回结果数={}", results.size());
            return results;

        } catch (Exception e) {
            log.error("[VectorChannel] 向量检索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isAvailable() {
        // 向量通道始终可用
        return embeddingHandler != null;
    }

    @Override
    public double getWeight(RetrievalRequest request) {
        // 返回请求中配置的向量检索权重
        return request.isEnableVector() ? request.getVectorWeight() : 0.0;
    }

    /**
     * 将 EmbeddingHandler 的检索结果转换为 RetrievalResult
     * <p>
     * searchEmbedding 5 参重载返回的 map 在 score/content/chunk/docName/createTime 基础上
     * 额外携带 standardNo/clauseId/clausePath/primaryType（均为 map 顶层键）。
     * 故此处直接从顶层读取，不再依赖不存在的嵌套 "metadata" 键。
     * </p>
     */
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】修 convertToRetrievalResult 数据流断裂：直接从顶层读 standardNo/clauseId/clausePath/primaryType-----------
    private RetrievalResult convertToRetrievalResult(Map<String, Object> searchResult) {
        RetrievalResult result = new RetrievalResult();

        // 设置文本内容
        result.setText((String) searchResult.get("content"));

        // 设置分数（EmbeddingHandler 写入的是 float/double，兼容 Number）
        Object scoreObj = searchResult.get("score");
        if (scoreObj instanceof Double) {
            result.setScore((Double) scoreObj);
        } else if (scoreObj instanceof Float) {
            result.setScore(((Float) scoreObj).doubleValue());
        } else if (scoreObj instanceof Number) {
            result.setScore(((Number) scoreObj).doubleValue());
        }

        // 设置来源通道
        result.setSourceChannel("VECTOR");

        // 标准 / 条款信息：直接从 map 顶层读取（Task 4 已补 standardNo/clauseId/clausePath/primaryType）
        result.setStandardNo((String) searchResult.get("standardNo"));
        result.setClauseId((String) searchResult.get("clauseId"));
        result.setClausePath((String) searchResult.get("clausePath"));
        // primaryType 写入 metadata，供下游使用（RetrievalResult 无 primaryType 字段）
        // standardId / standardName / title / clauseType / requirementStrength 在 embedding map 中不存在，保持 null

        // 构建元数据：保留 docName / chunk / createTime / primaryType 等辅助信息
        Map<String, Object> metadata = new HashMap<>();

        String docName = (String) searchResult.get(EmbeddingHandler.EMBED_STORE_METADATA_DOCNAME);
        if (docName != null) {
            metadata.put("docName", docName);
        }

        Object chunkObj = searchResult.get("chunk");
        if (chunkObj instanceof Integer) {
            metadata.put("chunkIndex", chunkObj);
        }

        String createTime = (String) searchResult.get(EmbeddingHandler.EMBED_STORE_CREATE_TIME);
        if (createTime != null) {
            metadata.put("createTime", createTime);
        }

        String primaryType = (String) searchResult.get("primaryType");
        if (primaryType != null) {
            metadata.put("primaryType", primaryType);
        }

        result.setMetadata(metadata);

        return result;
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】修 convertToRetrievalResult 数据流断裂-----------
}