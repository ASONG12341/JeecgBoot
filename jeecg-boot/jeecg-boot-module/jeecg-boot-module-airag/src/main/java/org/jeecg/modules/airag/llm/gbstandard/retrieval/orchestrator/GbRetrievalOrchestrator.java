package org.jeecg.modules.airag.llm.gbstandard.retrieval.orchestrator;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.ContextAssembler;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.channel.RetrievalChannel;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResponse;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.fusion.RRFFusionStrategy;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.planner.QueryPlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * GB 检索编排器
 * <p>
 * 核心职责：
 * 1. 接收检索请求
 * 2. 调用 QueryPlanner 生成检索计划
 * 3. 并发调用多个检索通道
 * 4. 使用 RRF 融合结果
 * 5. 返回最终结果
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component
public class GbRetrievalOrchestrator {

    @Autowired
    private QueryPlanner queryPlanner;

    @Autowired
    private RRFFusionStrategy fusionStrategy;

    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】注入 ContextAssembler，融合后做引用上下文增强-----------
    @Autowired
    private ContextAssembler contextAssembler;
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】注入 ContextAssembler-----------

    /**
     * 用于并发检索的线程池
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);

    /**
     * 执行检索
     *
     * @param request 检索请求
     * @return 检索响应
     */
    public RetrievalResponse retrieve(RetrievalRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[GbRetrievalOrchestrator] 开始检索, query={}, intent={}", 
                request.getQuery(), request.getIntent());

        try {
            // 1. 查询规划
            QueryPlanner.RetrievalPlan plan = queryPlanner.plan(request);
            log.info("[GbRetrievalOrchestrator] 检索计划: channels={}, weights={}", 
                    plan.getSelectedChannels(), plan.getChannelWeights());

            // 2. 并发调用检索通道
            Map<String, List<RetrievalResult>> channelResults = executeChannels(plan);

            //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】改调 weightedFuse（通道权重生效）+ 融合后引用上下文增强-----------
            // 3. 结果融合（加权：使用 QueryPlanner 按意图配置的 channelWeights，让通道权重真正影响最终排序）
            List<RetrievalResult> fusedResults = fusionStrategy.weightedFuse(
                    channelResults,
                    plan.getChannelWeights(),
                    plan.getTopK()
            );

            // 3.5 引用上下文增强（后处理：注入引用标注 + 追加引用条款上下文，不改变 score）
            fusedResults = contextAssembler.enhance(fusedResults);
            //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】改调 weightedFuse + 引用上下文增强-----------

            // 4. 构建响应
            long duration = System.currentTimeMillis() - startTime;
            RetrievalResponse response = buildResponse(fusedResults, channelResults, plan, duration);

            log.info("[GbRetrievalOrchestrator] 检索完成, 结果数={}, 耗时={}ms", 
                    fusedResults.size(), duration);

            return response;

        } catch (Exception e) {
            log.error("[GbRetrievalOrchestrator] 检索失败: {}", e.getMessage(), e);
            
            // 返回空结果
            RetrievalResponse errorResponse = new RetrievalResponse();
            errorResponse.setSuccess(false);
            errorResponse.setErrorMessage("检索失败: " + e.getMessage());
            errorResponse.setDuration(System.currentTimeMillis() - startTime);
            return errorResponse;
        }
    }

    /**
     * 并发执行检索通道
     *
     * @param plan 检索计划
     * @return 通道结果映射
     */
    private Map<String, List<RetrievalResult>> executeChannels(QueryPlanner.RetrievalPlan plan) {
        Map<String, List<RetrievalResult>> channelResults = new HashMap<>();
        List<String> selectedChannels = plan.getSelectedChannels();

        // 创建并发任务列表
        List<CompletableFuture<Void>> futures = selectedChannels.stream()
                .map(channelName -> CompletableFuture.runAsync(() -> {
                    try {
                        RetrievalChannel channel = queryPlanner.getChannel(channelName);
                        if (channel == null) {
                            log.warn("[GbRetrievalOrchestrator] 通道不存在: {}", channelName);
                            channelResults.put(channelName, new ArrayList<>());
                            return;
                        }

                        // 检查通道是否适用
                        if (!channel.isApplicable(plan.getRequest())) {
                            log.info("[GbRetrievalOrchestrator] 通道不适用: {}", channelName);
                            channelResults.put(channelName, new ArrayList<>());
                            return;
                        }

                        // 执行检索
                        List<RetrievalResult> results = channel.retrieve(plan.getRequest());
                        channelResults.put(channelName, results);

                        log.info("[GbRetrievalOrchestrator] 通道 {} 检索完成, 结果数={}", 
                                channelName, results.size());

                    } catch (Exception e) {
                        log.error("[GbRetrievalOrchestrator] 通道 {} 检索失败: {}", 
                                channelName, e.getMessage(), e);
                        channelResults.put(channelName, new ArrayList<>());
                    }
                }, executorService))
                .collect(Collectors.toList());

        // 等待所有通道完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return channelResults;
    }

    /**
     * 构建检索响应
     *
     * @param fusedResults 融合后的结果
     * @param channelResults 各通道结果
     * @param plan 检索计划
     * @param duration 耗时
     * @return 检索响应
     */
    private RetrievalResponse buildResponse(
            List<RetrievalResult> fusedResults,
            Map<String, List<RetrievalResult>> channelResults,
            QueryPlanner.RetrievalPlan plan,
            long duration) {

        RetrievalResponse response = new RetrievalResponse();
        response.setSuccess(true);
        response.setResults(fusedResults);
        response.setTotal(fusedResults.size());
        response.setDuration(duration);

        // 设置检索统计信息
        Map<String, Object> stats = new HashMap<>();
        stats.put("intent", plan.getRequest().getIntent());
        stats.put("channels", plan.getSelectedChannels());
        stats.put("channelWeights", plan.getChannelWeights());
        
        // 各通道召回数量
        Map<String, Integer> channelCounts = new HashMap<>();
        channelResults.forEach((channel, results) -> 
                channelCounts.put(channel, results.size()));
        stats.put("channelCounts", channelCounts);
        
        response.setStats(stats);

        return response;
    }

    /**
     * 关闭线程池。
     * <p>
     * update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】加 @PreDestroy 修固定线程池内存泄漏（Bean 销毁时自动关闭）-----------
     * 原 shutdown() 需手动调用，应用关闭时不会被触发，导致 5 线程的固定线程池泄漏。
     * 加 @PreDestroy 后 Spring 容器销毁 Bean 时自动调用，确保线程释放。
     * update-end---author:song ---date:2026-07-16-----------
     * </p>
     */
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        log.info("[GbRetrievalOrchestrator] 线程池已关闭");
    }
}
