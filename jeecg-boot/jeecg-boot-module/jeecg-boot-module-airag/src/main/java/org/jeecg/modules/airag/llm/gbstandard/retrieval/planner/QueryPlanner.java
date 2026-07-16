package org.jeecg.modules.airag.llm.gbstandard.retrieval.planner;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.channel.RetrievalChannel;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充 HashMap 导入-----------
// 已补充 java.util.HashMap 导入
//update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充 HashMap 导入-----------

/**
 * 查询规划器
 * <p>
 * 根据意图和查询特征，选择合适的检索通道组合
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component
public class QueryPlanner {

    private final Map<String, RetrievalChannel> channelMap;

    @Autowired
    public QueryPlanner(Map<String, RetrievalChannel> channelMap) {
        this.channelMap = channelMap;
        log.info("[QueryPlanner] 初始化完成, 可用通道: {}", channelMap.keySet());
    }

    /**
     * 规划检索策略
     * <p>
     * 根据意图选择检索通道：
     * - CLAUSE_LOOKUP: StructureChannel (精确) + VectorChannel (语义)
     * - PARAM_QUERY: StructureChannel (参数表) + VectorChannel
     * - REF_TRACE: StructureChannel (引用链) + TermChannel
     * - SEMANTIC_SEARCH: VectorChannel (主) + StructureChannel (辅)
     * - TERM_LOOKUP: TermChannel (主) + VectorChannel
     * </p>
     *
     * @param request 检索请求
     * @return 检索计划（包含选中的通道列表）
     */
    public RetrievalPlan plan(RetrievalRequest request) {
        log.info("[QueryPlanner] 开始规划检索策略, intent={}, query={}", 
                request.getIntent(), request.getQuery());

        RetrievalPlan plan = new RetrievalPlan();
        plan.setRequest(request);

        // 根据意图选择通道
        List<String> selectedChannels = selectChannels(request);
        plan.setSelectedChannels(selectedChannels);

        // 设置通道权重
        Map<String, Double> channelWeights = calculateChannelWeights(request, selectedChannels);
        plan.setChannelWeights(channelWeights);

        // 设置检索参数
        plan.setTopK(request.getTopK());
        plan.setSimilarityThreshold(request.getSimilarityThreshold());

        log.info("[QueryPlanner] 检索计划生成完成, 选中通道={}, 权重={}", 
                selectedChannels, channelWeights);

        return plan;
    }

    /**
     * 根据意图选择检索通道
     *
     * @param request 检索请求
     * @return 通道名称列表
     */
    private List<String> selectChannels(RetrievalRequest request) {
        List<String> channels = new ArrayList<>();
        String intent = request.getIntent();

        if (intent == null || intent.isEmpty()) {
            // 默认策略：使用所有通道
            channels.add("vectorChannel");
            channels.add("structureChannel");
            channels.add("termChannel");
            return channels;
        }

        switch (intent.toUpperCase()) {
            case "CLAUSE_LOOKUP":
                // 条款查询：结构化为主，向量为辅
                channels.add("structureChannel");
                channels.add("vectorChannel");
                break;

            case "PARAM_QUERY":
                //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数查询接入 paramChannel（参数表精确命中）-----------
                // 参数查询：参数通道为主（直接命中 gb_parameter），结构化为辅，向量兜底
                channels.add("paramChannel");
                channels.add("structureChannel");
                channels.add("vectorChannel");
                //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数查询接入 paramChannel-----------
                break;

            case "REF_TRACE":
                // 引用追溯：结构化为主，术语为辅
                channels.add("structureChannel");
                channels.add("termChannel");
                break;

            case "SEMANTIC_SEARCH":
                // 语义搜索：向量为主，结构化为辅
                channels.add("vectorChannel");
                channels.add("structureChannel");
                break;

            case "TERM_LOOKUP":
                // 术语查询：术语为主，向量为辅
                channels.add("termChannel");
                channels.add("vectorChannel");
                break;

            default:
                // 未知意图：使用所有通道
                log.warn("[QueryPlanner] 未知意图: {}, 使用所有通道", intent);
                channels.add("vectorChannel");
                channels.add("structureChannel");
                channels.add("termChannel");
        }

        return channels;
    }

    /**
     * 计算通道权重
     *
     * @param request 检索请求
     * @param selectedChannels 选中的通道
     * @return 通道权重映射
     */
    private Map<String, Double> calculateChannelWeights(RetrievalRequest request, 
                                                          List<String> selectedChannels) {
        String intent = request.getIntent();
        Map<String, Double> weights = new HashMap<>();

        if (intent == null || intent.isEmpty()) {
            // 默认权重：均等
            double defaultWeight = 1.0 / selectedChannels.size();
            for (String channel : selectedChannels) {
                weights.put(channel, defaultWeight);
            }
            return weights;
        }

        switch (intent.toUpperCase()) {
            case "CLAUSE_LOOKUP":
                weights.put("structureChannel", 0.7);
                weights.put("vectorChannel", 0.3);
                break;

            case "PARAM_QUERY":
                //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数查询权重：paramChannel 为主-----------
                weights.put("paramChannel", 0.6);
                weights.put("structureChannel", 0.25);
                weights.put("vectorChannel", 0.15);
                //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数查询权重-----------
                break;

            case "REF_TRACE":
                weights.put("structureChannel", 0.8);
                weights.put("termChannel", 0.2);
                break;

            case "SEMANTIC_SEARCH":
                weights.put("vectorChannel", 0.8);
                weights.put("structureChannel", 0.2);
                break;

            case "TERM_LOOKUP":
                weights.put("termChannel", 0.7);
                weights.put("vectorChannel", 0.3);
                break;

            default:
                // 均等权重
                double defaultWeight = 1.0 / selectedChannels.size();
                for (String channel : selectedChannels) {
                    weights.put(channel, defaultWeight);
                }
        }

        return weights;
    }

    /**
     * 获取检索通道实例
     *
     * @param channelName 通道名称
     * @return 检索通道
     */
    public RetrievalChannel getChannel(String channelName) {
        return channelMap.get(channelName);
    }

    /**
     * 检索计划
     */
    public static class RetrievalPlan {
        /**
         * 原始请求
         */
        private RetrievalRequest request;

        /**
         * 选中的通道列表
         */
        private List<String> selectedChannels;

        /**
         * 通道权重
         */
        private Map<String, Double> channelWeights;

        /**
         * TopK 参数
         */
        private int topK = 10;

        /**
         * 相似度阈值
         */
        private double similarityThreshold = 0.7;

        // Getters and Setters
        public RetrievalRequest getRequest() {
            return request;
        }

        public void setRequest(RetrievalRequest request) {
            this.request = request;
        }

        public List<String> getSelectedChannels() {
            return selectedChannels;
        }

        public void setSelectedChannels(List<String> selectedChannels) {
            this.selectedChannels = selectedChannels;
        }

        public Map<String, Double> getChannelWeights() {
            return channelWeights;
        }

        public void setChannelWeights(Map<String, Double> channelWeights) {
            this.channelWeights = channelWeights;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }
}