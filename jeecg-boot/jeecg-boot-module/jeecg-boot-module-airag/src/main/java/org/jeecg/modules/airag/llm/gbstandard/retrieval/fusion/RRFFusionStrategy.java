package org.jeecg.modules.airag.llm.gbstandard.retrieval.fusion;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合策略
 * <p>
 * 用于合并多个检索通道的结果，公式：
 * RRF(d) = Σ 1/(k + rank(d))
 * 其中 k 通常取 60
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component
public class RRFFusionStrategy {

    /**
     * RRF 常数，通常取 60
     */
    private static final int K = 60;

    /**
     * 融合多个通道的检索结果
     *
     * @param channelResults 多个通道的检索结果 Map<通道名称, 结果列表>
     * @param topK 最终返回的结果数量
     * @return 融合后的结果列表
     */
    public List<RetrievalResult> fuse(Map<String, List<RetrievalResult>> channelResults, int topK) {
        log.info("[RRFFusion] 开始融合结果, 通道数={}, topK={}", channelResults.size(), topK);

        // 文档ID -> RRF 分数
        Map<String, Double> docScores = new HashMap<>();
        // 文档ID -> RetrievalResult
        Map<String, RetrievalResult> docMap = new HashMap<>();

        // 遍历每个通道的结果
        for (Map.Entry<String, List<RetrievalResult>> entry : channelResults.entrySet()) {
            String channelName = entry.getKey();
            List<RetrievalResult> results = entry.getValue();

            log.info("[RRFFusion] 处理通道: {}, 结果数={}", channelName, results.size());

            // 计算每个文档的 RRF 分数
            for (int i = 0; i < results.size(); i++) {
                RetrievalResult result = results.get(i);
                String docId = generateDocId(result);
                int rank = i + 1; // 排名从 1 开始

                // RRF 公式: 1/(k + rank)
                double rrfScore = 1.0 / (K + rank);

                // 累加分数
                docScores.merge(docId, rrfScore, Double::sum);
                
                // 保存文档（如果已存在，保留分数最高的版本）
                if (!docMap.containsKey(docId) || result.getScore() > docMap.get(docId).getScore()) {
                    docMap.put(docId, result);
                }
            }
        }

        // 按融合分数排序
        List<RetrievalResult> fusedResults = docScores.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue())) // 降序
                .limit(topK)
                .map(entry -> {
                    String docId = entry.getKey();
                    RetrievalResult result = docMap.get(docId);
                    // 更新分数为融合分数
                    result.setScore(entry.getValue());
                    //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 sourceChannel 对齐-----------
                    // 标记来源为融合结果
                    result.setSourceChannel("FUSION_RRF");
                    //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 sourceChannel 对齐-----------
                    return result;
                })
                .collect(Collectors.toList());

        log.info("[RRFFusion] 融合完成, 最终结果数={}", fusedResults.size());
        return fusedResults;
    }

    /**
     * 生成文档唯一标识
     * <p>
     * 使用内容哈希作为文档ID，确保相同内容在不同通道中被识别为同一文档
     * </p>
     *
     * @param result 检索结果
     * @return 文档ID
     */
    private String generateDocId(RetrievalResult result) {
        //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 clausePath/text 对齐-----------
        // 如果有条款路径，使用条款路径作为ID
        if (result.getClausePath() != null && !result.getClausePath().isEmpty()) {
            return "CLAUSE_" + result.getClausePath();
        }

        // 否则使用内容的哈希值
        if (result.getText() != null) {
            return "CONTENT_" + result.getText().hashCode();
        }
        //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 clausePath/text 对齐-----------

        // 兜底：使用 UUID
        return UUID.randomUUID().toString();
    }

    /**
     * 加权融合（可选方案）
     * <p>
     * 为不同通道设置不同的权重，公式：
     * Score(d) = Σ weight_i * score_i(d)
     * </p>
     *
     * @param channelResults 多个通道的检索结果
     * @param channelWeights 通道权重
     * @param topK 最终返回的结果数量
     * @return 融合后的结果列表
     */
    public List<RetrievalResult> weightedFuse(
            Map<String, List<RetrievalResult>> channelResults,
            Map<String, Double> channelWeights,
            int topK) {
        
        log.info("[WeightedFusion] 开始加权融合, 通道数={}, topK={}", channelResults.size(), topK);

        Map<String, Double> docScores = new HashMap<>();
        Map<String, RetrievalResult> docMap = new HashMap<>();

        // 遍历每个通道
        for (Map.Entry<String, List<RetrievalResult>> entry : channelResults.entrySet()) {
            String channelName = entry.getKey();
            List<RetrievalResult> results = entry.getValue();
            double weight = channelWeights.getOrDefault(channelName, 1.0);

            log.info("[WeightedFusion] 处理通道: {}, 权重={}, 结果数={}", channelName, weight, results.size());

            for (RetrievalResult result : results) {
                String docId = generateDocId(result);
                double weightedScore = result.getScore() * weight;

                docScores.merge(docId, weightedScore, Double::sum);
                
                if (!docMap.containsKey(docId) || result.getScore() > docMap.get(docId).getScore()) {
                    docMap.put(docId, result);
                }
            }
        }

        // 排序并返回 topK
        List<RetrievalResult> fusedResults = docScores.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(topK)
                .map(entry -> {
                    RetrievalResult result = docMap.get(entry.getKey());
                    result.setScore(entry.getValue());
                    //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 sourceChannel 对齐-----------
                    result.setSourceChannel("FUSION_WEIGHTED");
                    //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，字段名 sourceChannel 对齐-----------
                    return result;
                })
                .collect(Collectors.toList());

        log.info("[WeightedFusion] 加权融合完成, 最终结果数={}", fusedResults.size());
        return fusedResults;
    }
}
