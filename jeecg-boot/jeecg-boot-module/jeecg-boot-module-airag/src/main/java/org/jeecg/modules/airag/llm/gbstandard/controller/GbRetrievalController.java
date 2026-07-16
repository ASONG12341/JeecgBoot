package org.jeecg.modules.airag.llm.gbstandard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntentExtractor;
import org.jeecg.modules.airag.llm.gbstandard.query.RoutingIntent;
import org.jeecg.modules.airag.llm.gbstandard.query.RoutingIntentClassifier;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResponse;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.intent.AdaptiveIntentExtractor;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.orchestrator.GbRetrievalOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * GB 检索控制器
 * <p>
 * 提供国标知识库的智能检索接口
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Tag(name = "GB检索", description = "国标知识库检索接口")
@RestController
@RequestMapping("/airag/gb-standard/retrieval")
public class GbRetrievalController {

    @Autowired
    private GbRetrievalOrchestrator retrievalOrchestrator;

    @Autowired
    private AdaptiveIntentExtractor intentExtractor;

    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】/query 注入两层意图：规则路由（激活通道）+ LLM 槽位（精排过滤）-----------
    @Autowired
    private RoutingIntentClassifier routingIntentClassifier;

    @Autowired
    private QueryIntentExtractor queryIntentExtractor;
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】/query 注入两层意图-----------

    /**
     * 智能检索接口
     * <p>
     * 支持多种检索模式：
     * 1. 自然语言查询
     * 2. 条款编号查询
     * 3. 参数查询
     * 4. 引用追溯
     * </p>
     *
     * @param request 检索请求
     * @return 检索结果
     */
    @Operation(summary = "智能检索", description = "支持自然语言、条款编号、参数等多种检索模式")
    @PostMapping("/query")
    public Result<RetrievalResponse> query(
            @Parameter(description = "检索请求") @RequestBody RetrievalRequest request) {
        
        log.info("[GbRetrievalController] 收到检索请求: query={}, intent={}", 
                request.getQuery(), request.getIntent());

        try {
            // 参数校验
            if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                return Result.error("查询内容不能为空");
            }

            String query = request.getQuery();

            //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】两层意图接入：路由（RoutingIntent）决定激活通道 + LLM 槽位（QueryIntent）做精排过滤-----------
            // 第一层：规则路由意图（<1ms），决定激活哪些检索通道。若客户端已显式指定 intent，则尊重客户端；否则由规则分类器判定。
            if (request.getIntent() == null || request.getIntent().isEmpty()) {
                RoutingIntent routing = routingIntentClassifier.classify(query);
                request.setIntent(routing.name());
                log.info("[GbRetrievalController] 规则路由意图: {}", routing.name());
            }

            // 第二层：LLM 4 槽位抽取（带 fallback，失败/禁用返回空 QueryIntent，不阻塞检索）。
            // 若客户端未预置 queryIntent，则现场抽取；若已预置（如程序化调用），直接复用避免重复调用 LLM。
            if (request.getQueryIntent() == null) {
                QueryIntent qi = queryIntentExtractor.extractWithFallback(query, request.getKnowledgeIds());
                request.setQueryIntent(qi);
                log.info("[GbRetrievalController] LLM 槽位意图: standardNo={}, primaryType={}",
                        qi != null ? qi.getStandardNo() : null,
                        qi != null ? qi.getPrimaryType() : null);
            }
            //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】两层意图接入-----------

            // 执行检索
            RetrievalResponse response = retrievalOrchestrator.retrieve(request);

            log.info("[GbRetrievalController] 检索完成, 结果数={}, 耗时={}ms", 
                    response.getTotal(), response.getDuration());

            return Result.OK(response);

        } catch (Exception e) {
            log.error("[GbRetrievalController] 检索失败: {}", e.getMessage(), e);
            return Result.error("检索失败: " + e.getMessage());
        }
    }

    /**
     * 意图提取接口
     * <p>
     * 用于测试和调试意图提取功能
     * </p>
     *
     * @param query 用户查询
     * @return 意图类型
     */
    @Operation(summary = "意图提取", description = "提取用户查询的意图类型")
    @GetMapping("/extract-intent")
    public Result<String> extractIntent(
            @Parameter(description = "用户查询") @RequestParam String query) {
        
        log.info("[GbRetrievalController] 提取意图: query={}", query);

        try {
            String intent = intentExtractor.extractIntent(query);
            log.info("[GbRetrievalController] 提取结果: intent={}", intent);
            return Result.OK(intent);

        } catch (Exception e) {
            log.error("[GbRetrievalController] 意图提取失败: {}", e.getMessage(), e);
            return Result.error("意图提取失败: " + e.getMessage());
        }
    }

    /**
     * 快速条款查询
     * <p>
     * 根据条款编号快速定位条款内容
     * </p>
     *
     * @param standardId 标准ID
     * @param clauseNumber 条款编号
     * @return 条款内容
     */
    @Operation(summary = "快速条款查询", description = "根据条款编号快速定位条款内容")
    @GetMapping("/clause/{standardId}/{clauseNumber}")
    public Result<RetrievalResponse> queryClause(
            @Parameter(description = "标准ID") @PathVariable String standardId,
            @Parameter(description = "条款编号") @PathVariable String clauseNumber) {
        
        log.info("[GbRetrievalController] 快速条款查询: standardId={}, clauseNumber={}", 
                standardId, clauseNumber);

        try {
            // 构建检索请求
            RetrievalRequest request = new RetrievalRequest();
            request.setStandardId(standardId);
            request.setClauseNumber(clauseNumber);
            request.setIntent("CLAUSE_LOOKUP");
            request.setTopK(1);

            // 执行检索
            RetrievalResponse response = retrievalOrchestrator.retrieve(request);

            return Result.OK(response);

        } catch (Exception e) {
            log.error("[GbRetrievalController] 条款查询失败: {}", e.getMessage(), e);
            return Result.error("条款查询失败: " + e.getMessage());
        }
    }

    /**
     * 参数查询
     * <p>
     * 查询标准中的参数要求
     * </p>
     *
     * @param standardId 标准ID
     * @param parameterName 参数名称
     * @return 参数信息
     */
    @Operation(summary = "参数查询", description = "查询标准中的参数要求")
    @GetMapping("/parameter/{standardId}")
    public Result<RetrievalResponse> queryParameter(
            @Parameter(description = "标准ID") @PathVariable String standardId,
            @Parameter(description = "参数名称") @RequestParam String parameterName) {
        
        log.info("[GbRetrievalController] 参数查询: standardId={}, parameterName={}", 
                standardId, parameterName);

        try {
            // 构建检索请求
            RetrievalRequest request = new RetrievalRequest();
            request.setStandardId(standardId);
            request.setParameterName(parameterName);
            request.setIntent("PARAM_QUERY");
            request.setTopK(5);

            // 执行检索
            RetrievalResponse response = retrievalOrchestrator.retrieve(request);

            return Result.OK(response);

        } catch (Exception e) {
            log.error("[GbRetrievalController] 参数查询失败: {}", e.getMessage(), e);
            return Result.error("参数查询失败: " + e.getMessage());
        }
    }
}
