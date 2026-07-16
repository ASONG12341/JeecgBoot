package org.jeecg.modules.airag.llm.handler;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.ai.handler.LLMHandler;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.AssertUtils;
import org.jeecg.common.util.filter.SsrfFileTypeFilter;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.AiChatConfig;
import org.jeecg.config.AiRagConfigBean;
import org.jeecg.modules.airag.common.consts.AiragConsts;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.common.handler.IAIChatHandler;
import org.jeecg.modules.airag.common.handler.IGbIntentExtractor;
import org.jeecg.modules.airag.common.handler.McpToolProviderWrapper;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 切换 QueryIntent（废弃 IntentContext ThreadLocal）-----------
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntentExtractor;
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 切换 QueryIntent（废弃 IntentContext ThreadLocal）-----------
import org.jeecg.modules.airag.llm.entity.AiragMcp;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.mapper.AiragMcpMapper;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * 大模型聊天工具类
 *
 * @Author: chenrui
 * @Date: 2025/2/18 14:31
 */
@Slf4j
@Component
public class AIChatHandler implements IAIChatHandler {

    @Autowired
    AiragModelMapper airagModelMapper;

    @Autowired
    AiragMcpMapper airagMcpMapper;

    @Autowired
    EmbeddingHandler embeddingHandler;

    @Autowired
    LLMHandler llmHandler;

    @Autowired
    AiRagConfigBean aiRagConfigBean;

    @Value(value = "${jeecg.path.upload:}")
    private String uploadpath;

    @Autowired
    private AiChatConfig aiChatConfig;

    // update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】AIChatHandler 注入 IGbIntentExtractor（v3.1 §4.3.4 要求在 mergeParams 同步抽取 intent）-----------
    @Autowired
    private IGbIntentExtractor gbIntentExtractor;
    // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】AIChatHandler 注入 IGbIntentExtractor（v3.1 §4.3.4 要求在 mergeParams 同步抽取 intent）-----------
    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 注入领域无关 QueryIntentExtractor（extractIntent 改用它；gbIntentExtractor 暂留待 Task 9 删除旧类）-----------
    @Autowired
    private QueryIntentExtractor queryIntentExtractor;
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 注入领域无关 QueryIntentExtractor（extractIntent 改用它；gbIntentExtractor 暂留待 Task 9 删除旧类）-----------

    /**
     * 问答
     *
     * @param modelId
     * @param messages
     * @return
     * @author chenrui
     * @date 2025/2/18 21:03
     */
    @Override
    public String completions(String modelId, List<ChatMessage> messages) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AssertUtils.assertNotEmpty("请选择模型", modelId);
        // 整理消息
        return completions(modelId, messages, null);
    }

    /**
     * 问答
     *
     * @param modelId
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/2/18 21:03
     */
    @Override
    public String completions(String modelId, List<ChatMessage> messages, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AssertUtils.assertNotEmpty("请选择模型", modelId);

        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        //AssertUtils.assertSame("模型未激活,请先在[AI模型配置]中[测试激活]模型", airagModel.getActivateFlag(), 1);
        return completions(airagModel, messages, params);
    }

    /**
     * 问答
     *
     * @param airagModel
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/2/24 17:30
     */
    public String completions(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params) {
        //update-begin---author:song-claude ---date:2026-07-12  for：【修复 P1.2 阶段 mergeParams 顺序错位】completions 抽取 intent 上移到 mergeParams 之前(IntentContext.get() 在 mergeParams 内非 null,P1.1-3 标量过滤生效)-----------
        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent 返回 QueryIntent 显式传参（废弃 IntentContext ThreadLocal；load-bearing 顺序不变：extractIntent 仍在 mergeParams 之前）-----------
        // 预抽取 intent(必须在 mergeParams 之前,否则 P1.1-3 标量过滤拿不到 intent)
        QueryIntent intent = null;
        try {
            intent = extractIntent("completions", messages, params);
        } catch (Exception e) {
            log.warn("[AI-CHAT][completions] 预抽取 intent 失败, 继续主流程", e);
        }
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent 返回 QueryIntent 显式传参（废弃 IntentContext ThreadLocal；load-bearing 顺序不变：extractIntent 仍在 mergeParams 之前）-----------
        //update-end---author:song-claude ---date:2026-07-12  for：【修复 P1.2 阶段 mergeParams 顺序错位】completions 抽取 intent 上移到 mergeParams 之前(IntentContext.get() 在 mergeParams 内非 null,P1.1-3 标量过滤生效)-----------

        params = mergeParams(airagModel, params, intent);
        //update-begin---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------
        messages = injectThinkingPlaceholderIfNeeded(messages, airagModel.getModelName());
        //update-end---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------

        // update-begin---author:song-claude ---date:2026-07-12  for：【v3.1 P1.2】completions 同步抽取 intent 已上移到 mergeParams 之前;此处保留 closeMcpConnections finally 块(语义不变)-----------
        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】finally 不再 clear IntentContext（已废弃，无 ThreadLocal 写入）；仅保留 closeMcpConnections-----------
        String resp = null;
        try {
            try {
                resp = llmHandler.completions(messages, params);
            } catch (ToolExecutionException e) {
                // 工具调用执行失败：先用 matchErrorMsg 翻译 cause，再拼装友好提示
                String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                causeMsg = matchErrorMsg(causeMsg, causeMsg);
                log.error("AI工具执行异常 - {}", causeMsg, e);
                return "";
            } catch (Exception e) {
                throw translateLlmException(e, "调用大模型接口失败，详情请查看后台日志。");
            }
            if (resp != null && resp.contains("</think>")
                    && (null == params.getNoThinking() || params.getNoThinking())) {
                String[] thinkSplit = resp.split("</think>");
                resp = thinkSplit[thinkSplit.length - 1];
            }
            return resp;
        } finally {
            // update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】completions finally 关闭 MCP 连接（防御式：即使 llmHandler 内部已关闭也幂等；防止 MCP 客户端连接泄漏）-----------
            closeMcpConnections(params);
            // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】completions finally 关闭 MCP 连接（防御式：即使 llmHandler 内部已关闭也幂等；防止 MCP 客户端连接泄漏）-----------
        }
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】finally 不再 clear IntentContext（已废弃，无 ThreadLocal 写入）；仅保留 closeMcpConnections-----------
        // update-end---author:song-claude ---date:2026-07-12  for：【v3.1 P1.2】completions 同步抽取 intent 已上移到 mergeParams 之前;此处保留 closeMcpConnections finally 块(语义不变)-----------
    }

    /**
     * 使用默认模型问答
     *
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/3/12 15:13
     */
    @Override
    public String completionsByDefaultModel(List<ChatMessage> messages, AIChatParams params) {
        return completions(new AiragModel(), messages, params);
    }

    /**
     * 聊天(流式)
     *
     * @param modelId
     * @param messages
     * @return
     * @author chenrui
     * @date 2025/2/20 21:06
     */
    @Override
    public TokenStream chat(String modelId, List<ChatMessage> messages) {
        return chat(modelId, messages, null);
    }

    /**
     * 聊天(流式)
     *
     * @param modelId
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/2/18 21:03
     */
    @Override
    public TokenStream chat(String modelId, List<ChatMessage> messages, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AssertUtils.assertNotEmpty("请选择模型", modelId);

        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        //update-begin---author:wangshuai---date:2026-03-02---for:【QQYUN-14781】实现一个AI模型未激活或者不可用的情况，直接使用平台底层配置的默认模型---
        //未激活的模型走默认模型
        if(null == airagModel || airagModel.getActivateFlag() == 0){
            log.warn("模型未激活,采用默认模型");
            return chatByDefaultModel(messages,params);
        }
        //update-end---author:wangshuai---date:2026-03-02---for:【QQYUN-14781】实现一个AI模型未激活或者不可用的情况，直接使用平台底层配置的默认模型---
        return chat(airagModel, messages, params);
    }

    /**
     * 聊天(流式)
     *
     * @param airagModel
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/2/24 17:29
     */
    private TokenStream chat(AiragModel airagModel, List<ChatMessage> messages, AIChatParams params) {
        //update-begin---author:song-claude ---date:2026-07-12  for：【修复 P1.2 阶段 mergeParams 顺序错位】流式 chat 抽取 intent 上移到 mergeParams 之前(IntentContext.get() 在 mergeParams 内非 null,P1.1-3 标量过滤生效)-----------
        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent 返回 QueryIntent 显式传参（废弃 IntentContext ThreadLocal；load-bearing 顺序不变：extractIntent 仍在 mergeParams 之前）-----------
        // 预抽取 intent(必须在 mergeParams 之前,否则 P1.1-3 标量过滤拿不到 intent)
        QueryIntent intent = null;
        try {
            intent = extractIntent("chat", messages, params);
        } catch (Exception e) {
            log.warn("[AI-CHAT][chat] 预抽取 intent 失败, 继续主流程", e);
        }
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent 返回 QueryIntent 显式传参（废弃 IntentContext ThreadLocal；load-bearing 顺序不变：extractIntent 仍在 mergeParams 之前）-----------
        //update-end---author:song-claude ---date:2026-07-12  for：【修复 P1.2 阶段 mergeParams 顺序错位】流式 chat 抽取 intent 上移到 mergeParams 之前(IntentContext.get() 在 mergeParams 内非 null,P1.1-3 标量过滤生效)-----------

        params = mergeParams(airagModel, params, intent);
        //update-begin---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------
        messages = injectThinkingPlaceholderIfNeeded(messages, airagModel.getModelName());
        //update-end---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------

        // update-begin---author:song-claude ---date:2026-07-12  for：【v3.1 P1.2】流式 chat 同步抽取 intent 已上移到 mergeParams 之前;此处保留 closeMcpConnections finally 块(语义不变)-----------
        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】finally 不再 clear IntentContext（已废弃，无 ThreadLocal 写入）；仅保留 closeMcpConnections-----------
        try {
            return llmHandler.chat(messages, params);
        } finally {
            // update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】流式 chat finally 关闭 MCP 连接（流式 TokenStream 启动前的最后一次同步清理机会；流式消费完成后的连接关闭依赖 llmHandler 内部 / 后续 P1.3 阶段处理）-----------
            closeMcpConnections(params);
            // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】流式 chat finally 关闭 MCP 连接（流式 TokenStream 启动前的最后一次同步清理机会；流式消费完成后的连接关闭依赖 llmHandler 内部 / 后续 P1.3 阶段处理）-----------
        }
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】finally 不再 clear IntentContext（已废弃，无 ThreadLocal 写入）；仅保留 closeMcpConnections-----------
        // update-end---author:song-claude ---date:2026-07-12  for：【v3.1 P1.2】流式 chat 同步抽取 intent 已上移到 mergeParams 之前;此处保留 closeMcpConnections finally 块(语义不变)-----------
    }

    //update-begin---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------
    /**
     * 当目标模型是 DeepSeek 推理模型(deepseek-v4-flash 等)时，
     * 为历史 AI 消息(从 MessageHistory 重建出来的、thinking 字段为空的 AiMessage)注入占位 thinking。
     *
     * 原因：DeepSeek 推理模型校验请求时要求每条 assistant 历史消息携带 reasoning_content 字段；
     * langchain4j 的 sendThinking 仅在 AiMessage.thinking() 非空时才会注入 reasoning_content；
     * 而历史持久化层(MessageHistory)目前没有保存 reasoning_content，重建出的 AiMessage thinking 始终为 null，
     * 导致 DeepSeek 返回 "The reasoning_content in the thinking mode must be passed back to the API."
     *
     * 临时方案：注入占位字符串让 langchain4j 通过 isNullOrEmpty 校验、把 reasoning_content 字段带上，
     * 后续若 MessageHistory 升级支持持久化 reasoning_content 可去掉该兜底。
     *
     * @param messages  原始消息列表(可能为 null/空)
     * @param modelName 目标模型名(用于判定是否需要注入)
     * @return 处理后的新消息列表(若无需处理则原样返回)
     * @author scott
     * @date 2026-04-29
     */
    private static List<ChatMessage> injectThinkingPlaceholderIfNeeded(List<ChatMessage> messages, String modelName) {
        if (messages == null || messages.isEmpty()
                || !LLMConsts.isDeepSeekThinkingModel(modelName)) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages.size());
        int injected = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) msg;
                if (oConvertUtils.isEmpty(aiMsg.thinking())) {
                    AiMessage rebuilt = AiMessage.builder()
                            .text(aiMsg.text())
                            // 占位：让 langchain4j 把 reasoning_content 字段带上以满足 DeepSeek 校验
                            .thinking("...")
                            .toolExecutionRequests(aiMsg.toolExecutionRequests())
                            .attributes(aiMsg.attributes())
                            .build();
                    result.add(rebuilt);
                    injected++;
                    continue;
                }
            }
            result.add(msg);
        }
        if (injected > 0) {
            log.info("[AI-CHAT][issues/9585] 为 DeepSeek 推理模型[{}]的 {} 条历史 AI 消息注入了占位 thinking",
                    modelName, injected);
        }
        return result;
    }
    //update-end---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------

    /**
     * 使用默认模型聊天
     *
     * @param messages
     * @param params
     * @return
     * @author chenrui
     * @date 2025/3/12 15:13
     */
    @Override
    public TokenStream chatByDefaultModel(List<ChatMessage> messages, AIChatParams params) {
        return chat(new AiragModel(), messages, params);
    }

    /**
     * 合并 airagmodel和params,params为准
     *
     * @param airagModel
     * @param params
     * @return
     * @author chenrui
     * @date 2025/3/11 17:45
     */
    private AIChatParams mergeParams(AiragModel airagModel, AIChatParams params) {
        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】保留 2 参重载（imageGenerate/imageEdit 无 RAG intent，传 null），3 参重载承载 chat/completions 的 QueryIntent 透传-----------
        return mergeParams(airagModel, params, null);
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】保留 2 参重载（imageGenerate/imageEdit 无 RAG intent，传 null），3 参重载承载 chat/completions 的 QueryIntent 透传-----------
    }

    /**
     * 合并 airagmodel和params,params为准；并把 QueryIntent 透传给 queryRouter 构建。
     *
     * <p>GB-RAG v4 P3：intent 由调用方（completions/chat）在 mergeParams 之前用 extractIntent 抽取并通过参数传入，
     * 替代旧的 IntentContext.get() ThreadLocal 读取。load-bearing 顺序：extractIntent 必须在 mergeParams 之前。
     *
     * @param airagModel 模型
     * @param params     参数（含 knowIds）
     * @param intent     预抽取的 QueryIntent（可为 null：无 RAG 上下文 / 抽取失败 / 图像生成场景）
     * @return 合并后的 params
     * @author song
     * @date 2026-07-16
     */
    private AIChatParams mergeParams(AiragModel airagModel, AIChatParams params, QueryIntent intent) {
        if (null == airagModel) {
            return params;
        }
        if (params == null) {
            params = new AIChatParams();
        }

        params.setProvider(airagModel.getProvider());
        params.setModelName(airagModel.getModelName());
        params.setBaseUrl(airagModel.getBaseUrl());
        if (oConvertUtils.isObjectNotEmpty(airagModel.getCredential())) {
            JSONObject modelCredential = JSONObject.parseObject(airagModel.getCredential());
            params.setApiKey(oConvertUtils.getString(modelCredential.getString("apiKey"), null));
            params.setSecretKey(oConvertUtils.getString(modelCredential.getString("secretKey"), null));
            boolean httpVersionOne = false;
            if(modelCredential.containsKey("httpVersionOne")){
                httpVersionOne = modelCredential.getInteger("httpVersionOne") == 1;
            }
            params.setIzHttpVersionOne(httpVersionOne);
        }
        if (oConvertUtils.isObjectNotEmpty(airagModel.getModelParams())) {
            JSONObject modelParams = JSONObject.parseObject(airagModel.getModelParams());
            if (oConvertUtils.isObjectEmpty(params.getTemperature())) {
                params.setTemperature(modelParams.getDouble("temperature"));
            }
            if (oConvertUtils.isObjectEmpty(params.getTopP())) {
                params.setTopP(modelParams.getDouble("topP"));
            }
            if (oConvertUtils.isObjectEmpty(params.getPresencePenalty())) {
                params.setPresencePenalty(modelParams.getDouble("presencePenalty"));
            }
            if (oConvertUtils.isObjectEmpty(params.getFrequencyPenalty())) {
                params.setFrequencyPenalty(modelParams.getDouble("frequencyPenalty"));
            }
            if (oConvertUtils.isObjectEmpty(params.getMaxTokens())) {
                params.setMaxTokens(modelParams.getInteger("maxTokens"));
            }
            if (oConvertUtils.isObjectEmpty(params.getTimeout())) {
                params.setTimeout(modelParams.getInteger("timeout"));
            }
            if (oConvertUtils.isObjectEmpty(params.getEnableSearch())) {
                params.setEnableSearch(modelParams.getBoolean("enableSearch"));
            }
            //update-begin---author:wangshuai---date:2026-03-20---for:【issues/8】保存激活qwen-vl-ocr模型报错---
            if (oConvertUtils.isObjectEmpty(params.getExtraParams()) && modelParams.containsKey("extraParams")) {
                params.setExtraParams(modelParams.getObject("extraParams", Map.class));
            }
            //update-end---author:wangshuai---date:2026-03-20---for:【issues/8】保存激活qwen-vl-ocr模型报错---
        }

        // RAG
        List<String> knowIds = params.getKnowIds();
        //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        log.info("[RAG][mergeParams] AIChatParams 传入的 knowIds={}", knowIds);
        //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        if (oConvertUtils.isObjectNotEmpty(knowIds)) {
            try {
                //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 10】AIChatHandler.mergeParams 从 IntentContext 读取 intent 并传入 getQueryRouter 重载（避免在 mergeParams 再调一次 extractWithFallback 触发重复 LLM 调用）-----------
                //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】mergeParams 不再读 IntentContext.get()，改用参数传入的 QueryIntent；通过 package-private buildQueryRouter 委托 embeddingHandler.getQueryRouter(QueryIntent 重载)，便于单测-----------
                QueryRouter queryRouter = buildQueryRouter(knowIds, params.getTopNumber(), params.getSimilarity(), intent);
                //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】mergeParams 不再读 IntentContext.get()，改用参数传入的 QueryIntent；通过 package-private buildQueryRouter 委托 embeddingHandler.getQueryRouter(QueryIntent 重载)，便于单测-----------
                //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 10】AIChatHandler.mergeParams 从 IntentContext 读取 intent 并传入 getQueryRouter 重载（避免在 mergeParams 再调一次 extractWithFallback 触发重复 LLM 调用）-----------
                params.setQueryRouter(queryRouter);
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                log.info("[RAG][mergeParams] queryRouter 已注入到 params, 类型={}",
                        queryRouter == null ? "null" : queryRouter.getClass().getName());
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            } catch (Exception e) {
                //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                log.error("[RAG][mergeParams] 构建 queryRouter 失败, knowIds={}, 错误信息={}", knowIds, e.getMessage(), e);
                //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
                throw e;
            }
        } else {
            //update-begin---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
            log.warn("[RAG][mergeParams] knowIds 为空, RAG 不会生效. modelName={}", airagModel.getModelName());
            //update-end---author:song ---date:2026-07-10  for：【issues/9551】RAG 检索可观测日志，定位 queryRouter 是否真的被注入-----------
        }

        // 设置确保maxTokens值正确
        if (oConvertUtils.isObjectNotEmpty(params.getMaxTokens()) && params.getMaxTokens() <= 0) {
            params.setMaxTokens(null);
        }

        // 默认超时时间
        if(oConvertUtils.isObjectEmpty(params.getTimeout())){
            params.setTimeout(AiragConsts.DEFAULT_TIMEOUT);
        }

        //deepseek-reasoner 推理模型不支持插件tool
        String modelName = airagModel.getModelName();
        if(!LLMConsts.DEEPSEEK_REASONER.equals(modelName)){
            // 插件/MCP处理
            buildPlugins(params);
        }

        //update-begin---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------
        // 仅对 DeepSeek 推理模型(deepseek-reasoner/deepseek-v4-flash 等)开启思考过程的捕获与回传，
        // 否则 deepseek-v4-flash 在工具调用多轮对话中会返回:
        // "The reasoning_content in the thinking mode must be passed back to the API."
        // 注意：不要对 deepseek-chat 等非推理模型开启，避免无意义的请求字段污染。
        boolean isDsThinking = LLMConsts.isDeepSeekThinkingModel(modelName);
        log.info("[AI-CHAT][issues/9585] mergeParams provider={}, modelName={}, isDeepSeekThinkingModel={}, params.returnThinking={}, params.sendThinking={}",
                airagModel.getProvider(), modelName, isDsThinking, params.getReturnThinking(), params.getSendThinking());
        if (isDsThinking) {
            // returnThinking: 把响应中的 reasoning_content 解析到 AiMessage.thinking
            //update-begin---author:wangshuai ---date:2026-05-11  for：[issues/9607]deepseek-v4-flash 联网搜索后模型调用异常-----------
            //新模型中returnThinking必须为true，即深度思考
            params.setReturnThinking(true);
            // sendThinking: 把 AiMessage.thinking 以 reasoning_content 字段回传到下次请求
            params.setSendThinking(true);
            //update-end---author:wangshuai ---date:2026-05-11  for：[issues/9607]deepseek-v4-flash 联网搜索后模型调用异常-----------
            log.info("[AI-CHAT][issues/9585][issues/9607] mergeParams after-fix returnThinking={}, sendThinking={}",
                    params.getReturnThinking(), params.getSendThinking());
        }
        //update-end---author:scott ---date:20260429  for：[issues/9585]DeepSeek大模型切换为新发布deepseek-v4-flash，流程中调用出现异常------------

        return params;
    }

    // update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】extractLastUserQuery 辅助方法 + extractAndCacheIntent 统一方法（DRY 重构：completions + chat 共享）-----------
    /**
     * 从 messages 列表中提取最后一条 user message 的文本（review 优化 1：用 ListIterator 从尾部遍历，比索引 for-loop 更 Java 风格）
     */
    private String extractLastUserQuery(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        var it = messages.listIterator(messages.size());
        while (it.hasPrevious()) {
            ChatMessage msg = it.previous();
            if (msg instanceof UserMessage um) {
                return um.singleText();
            }
        }
        return null;
    }

    /**
     * 统一 intent 抽取方法（DRY 重构，v3.1 P1.2；GB-RAG v4 P3 改写）：从 messages 提取 userQuery，
     * 调 QueryIntentExtractor.extractWithFallback，返回领域无关 {@link QueryIntent}（不写 ThreadLocal）。
     *
     * <p>GB-RAG v4 P3 变更：
     * <ul>
     *   <li>抽取器由 IGbIntentExtractor → QueryIntentExtractor（领域无关）。</li>
     *   <li>结果通过返回值显式传参给 mergeParams，废弃 IntentContext ThreadLocal。</li>
     * </ul>
     *
     * 调用方（completions / chat）通过 caller 参数区分日志 tag。
     * 失败不影响主流程（catch 后返回 null）。
     *
     * 触发条件（任一命中即静默返回 null）：
     * 1. params.getKnowIds() 为空（无 RAG 上下文）
     * 2. messages 为空（无用户输入）
     * 3. 最后一条非 UserMessage
     * 4. userQuery 全空白
     * 5. userQuery 不包含 GB 标准号（review 风险 4：正则预检，避免无效 LLM 调用）
     *
     * 性能优化（review 风险 4）：
     * - GB_PATTERN 正则预检：无 GB 标准号的问题直接跳过，节省一次 LLM Structured Output 调用（~200-500ms 延迟）
     *
     * @param caller   调用方标识（"completions" / "chat"）
     * @param messages 当前 chat 的消息列表
     * @param params   AIChatParams（含 knowIds）
     * @return 抽取到的 QueryIntent；未触发/失败/无 GB 标准号时返回 null
     */
    /**
     * GB 标准号正则预检（review 风险 4）。
     * 匹配模式：GB 31241 / GB/T 31467.3 / GB 38031 / GB/T 36276 等。
     */
    private static final java.util.regex.Pattern GB_PATTERN = java.util.regex.Pattern.compile("GB[\\s/]*T?[\\s/]*\\d+(?:\\.\\d+)?(?:[-/]\\d+)?");

    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent：返回 QueryIntent，不写 IntentContext ThreadLocal；改用领域无关 QueryIntentExtractor-----------
    private QueryIntent extractIntent(String caller, List<ChatMessage> messages, AIChatParams params) {
        List<String> knowIds = params.getKnowIds();
        if (knowIds == null || knowIds.isEmpty()) {
            return null;
        }
        String userQuery = extractLastUserQuery(messages);
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return null;
        }
        // GB 标准号正则预检：无关问题跳过 LLM 调用（review 风险 4 性能优化）
        if (!GB_PATTERN.matcher(userQuery).find()) {
            log.debug("[RAG][{}] 用户问题未匹配到 GB 标准号（pattern={}），跳过 intent 抽取", caller, GB_PATTERN.pattern());
            return null;
        }
        try {
            QueryIntent intent = queryIntentExtractor.extractWithFallback(userQuery, knowIds);
            log.info("[RAG][{}] QueryIntent 抽取成功: intent={}, knowIds={}", caller, intent, knowIds);
            return intent;
        } catch (Exception e) {
            // intent 抽取失败不影响 chat 主流程；只 log warn，返回 null
            log.warn("[RAG][{}] QueryIntent 抽取失败, knowIds={}, 错误={}", caller, knowIds, e.getMessage());
            return null;
        }
    }
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】extractAndCacheIntent → extractIntent：返回 QueryIntent，不写 IntentContext ThreadLocal；改用领域无关 QueryIntentExtractor-----------

    /**
     * package-private 辅助方法（GB-RAG v4 P3 测试性）：把 QueryIntent 委托给
     * {@link EmbeddingHandler#getQueryRouter(List, Integer, Double, QueryIntent)}（使用 QueryIntent 重载，非旧 GbQueryIntent 桥接重载）。
     *
     * <p>抽取目的：AIChatHandler 字段很多（airagModelMapper / llmHandler / aiChatConfig …），
     * 难以在单测里全部 @InjectMocks。把 intent→router 的委托点隔离到这个 package-private 方法，
     * 单测只需 mock EmbeddingHandler 即可验证"用对了 getQueryRouter 重载 + intent 透传"。
     *
     * @param knowIds   知识库 id 列表
     * @param topNumber 召回条数
     * @param similarity 相似度阈值
     * @param intent    预抽取的 QueryIntent（可为 null）
     * @return 构建好的 QueryRouter（可能为 null）
     */
    QueryRouter buildQueryRouter(List<String> knowIds, Integer topNumber, Double similarity, QueryIntent intent) {
        return embeddingHandler.getQueryRouter(knowIds, topNumber, similarity, intent);
    }
    // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】extractLastUserQuery 辅助方法 + extractAndCacheIntent 统一方法（DRY 重构：completions + chat 共享）-----------

    // update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】closeMcpConnections 防御式关闭（review 风险 2）：completions/chat finally 调用；幂等包装（多次 close 不抛异常）；失败隔离（单 wrapper 关闭失败不影响其他）-----------
    /**
     * 防御式关闭 MCP 连接（review 风险 2）。
     *
     * 调用方：completions / chat 的 finally 块。
     * 设计：
     * 1. 幂等：单 wrapper 多次 close 不抛异常（依赖 McpToolProviderWrapper.close() 实现）
     * 2. 失败隔离：单个 wrapper 关闭失败不影响其他 wrapper；仅 log warn
     * 3. null 安全：getMcpToolProviderWrappers() 返回 null 或空列表时静默跳过
     *
     * 流式场景的注意：chat() finally 在 llmHandler.chat() return 之前调用，
     * 可能早于 TokenStream 消费完成。如 llmHandler 内部已 close则幂等；
     * 否则依赖 llmHandler 在 TokenStream 生命周期内 close（后续 P1.3 阶段处理）。
     *
     * @param params AIChatParams（含 mcpToolProviderWrappers 列表）
     */
    private void closeMcpConnections(AIChatParams params) {
        if (params == null) {
            return;
        }
        List<McpToolProviderWrapper> wrappers = params.getMcpToolProviderWrappers();
        if (wrappers == null || wrappers.isEmpty()) {
            return;
        }
        for (McpToolProviderWrapper wrapper : wrappers) {
            if (wrapper == null) {
                continue;
            }
            try {
                wrapper.close();
            } catch (Exception e) {
                log.warn("[AI-CHAT][MCP] 关闭 MCP 连接失败, type={}, 错误={}",
                        wrapper.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
    // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】closeMcpConnections 防御式关闭（review 风险 2）：completions/chat finally 调用；幂等包装（多次 close 不抛异常）；失败隔离（单 wrapper 关闭失败不影响其他）-----------

    /**
     * 构造插件和MCP工具
     * for [QQYUN-12453]【AI】支持插件
     * for [QQYUN-9234] MCP服务连接关闭 - 使用包装器保存连接引用
     * @param params
     * @author chenrui
     * @date 2025/10/31 14:04
     */
    private void buildPlugins(AIChatParams params) {
        List<String> pluginIds = params.getPluginIds();

        if(oConvertUtils.isObjectNotEmpty(pluginIds)){
            List<McpToolProvider> mcpToolProviders = new ArrayList<>();
            List<McpToolProviderWrapper> mcpToolProviderWrappers = new ArrayList<>();
            Map<ToolSpecification, ToolExecutor> pluginTools = new HashMap<>();

            for (String pluginId : pluginIds.stream().distinct().collect(Collectors.toList())) {
                AiragMcp airagMcp = airagMcpMapper.selectById(pluginId);
                if (airagMcp == null) {
                    continue;
                }

                String category = airagMcp.getCategory();
                if (oConvertUtils.isEmpty(category)) {
                    // 兼容旧数据：如果没有category字段，默认为mcp
                    category = "mcp";
                }

                if ("mcp".equalsIgnoreCase(category)) {
                    // MCP类型：构建McpToolProviderWrapper（包含连接引用用于后续关闭）
                    // for [QQYUN-9234] MCP服务连接关闭
                    McpToolProviderWrapper wrapper = buildMcpToolProviderWrapper(
                            airagMcp.getName(),
                            airagMcp.getType(),
                            airagMcp.getEndpoint(),
                            airagMcp.getHeaders(),
                            aiRagConfigBean.getAllowSensitiveNodes()
                    );
                    if (wrapper != null) {
                        mcpToolProviders.add(wrapper.getMcpToolProvider());
                        mcpToolProviderWrappers.add(wrapper);
                    }
                } else if ("plugin".equalsIgnoreCase(category)) {
                    // 插件类型：构建ToolSpecification和ToolExecutor
                    Map<ToolSpecification, ToolExecutor> tools = PluginToolBuilder.buildTools(airagMcp, params.getCurrentHttpRequest());
                    if (tools != null && !tools.isEmpty()) {
                        pluginTools.putAll(tools);
                    }
                }
            }

            // 设置MCP工具提供者
            if (!mcpToolProviders.isEmpty()) {
                params.setMcpToolProviders(mcpToolProviders);
            }
            
            // 保存MCP连接包装器，用于后续关闭
            // for [QQYUN-9234] MCP服务连接关闭
            if (!mcpToolProviderWrappers.isEmpty()) {
                params.setMcpToolProviderWrappers(mcpToolProviderWrappers);
            }

            // 设置插件工具
            if (!pluginTools.isEmpty()) {
                if (params.getTools() == null) {
                    params.setTools(new HashMap<>());
                }
                params.getTools().putAll(pluginTools);
            }
        }
    }

    @Override
    public UserMessage buildUserMessage(String content, List<String> images) {
        AssertUtils.assertNotEmpty("请输入消息内容", content);
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(content));
        if (oConvertUtils.isObjectNotEmpty(images)) {
            // 获取所有图片,将他们转换为ImageContent
            List<ImageContent> imageContents = buildImageContents(images);
            contents.addAll(imageContents);
        }
        return UserMessage.from(contents);
    }

    @Override
    public List<ImageContent> buildImageContents(List<String> images) {
        List<ImageContent> imageContents = new ArrayList<>();
        for (String imageUrl : images) {
            Matcher matcher = LLMConsts.WEB_PATTERN.matcher(imageUrl);
            if (matcher.matches()) {
                // 来源于网络
                imageContents.add(ImageContent.from(imageUrl));
            } else {
                // 本地文件
                String filePath = uploadpath + File.separator + imageUrl;
                // 读取文件并转换为 base64 编码字符串
                try {
                    SsrfFileTypeFilter.checkPathTraversal(filePath);
                    Path path = Paths.get(filePath);
                    byte[] fileContent = Files.readAllBytes(path);
                    String base64Data = Base64.getEncoder().encodeToString(fileContent);
                    // 获取文件的 MIME 类型
                    String mimeType = Files.probeContentType(path);
                    // 构建 ImageContent 对象
                    imageContents.add(ImageContent.from(base64Data, mimeType));
                } catch (IOException e) {
                    log.error("读取文件失败: {}", imageUrl, e);
                    throw new RuntimeException("发送消息失败,读取文件异常:" + e.getMessage(), e);
                }
            }
        }
        return imageContents;
    }

    //================================================= begin【QQYUN-12145】【AI】AI 绘画创作 ========================================
    /**
     * 文本生成图片
     * @param modelId
     * @param messages
     * @param params
     * @return
     */
    @Override
    public List<Map<String, Object>> imageGenerate(String modelId, String messages, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        //AssertUtils.assertNotEmpty("请选择图片大模型", modelId);
        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        return this.imageGenerate(airagModel, messages, params);
    }

    /**
     * 文本生成图片
     *
     * @param airagModel
     * @param messages
     * @param params
     * @return
     */
    public List<Map<String, Object>> imageGenerate(AiragModel airagModel, String messages, AIChatParams params) {
        if(airagModel == null || (airagModel.getActivateFlag()!=null && airagModel.getActivateFlag() == 0)){
            if (airagModel != null && oConvertUtils.isNotEmpty(airagModel.getId())) {
                log.warn("模型未激活,采用默认文生图模型");
            }
            //判断是否配置了默认模型
            if(aiChatConfig == null || oConvertUtils.isEmpty(aiChatConfig.getAiModelDraw().getApiKey())){
                throw new JeecgBootBizTipException("当前系统未配置默认图像模型，请前往yml中配置默认模型");
            }
            airagModel = this.getDefaultDrawModel(aiChatConfig.getAiModelDraw());
        }
        params = mergeParams(airagModel, params);
        try {
            return llmHandler.imageGenerate(messages, params);
        } catch (Exception e) {
            throw translateLlmException(e, "调用绘画AI接口失败，详情请查看后台日志。");
        }
    }


    /**
     * 图生图
     * @param modelId
     * @param messages
     * @param params
     * @return
     */
    @Override
    public List<Map<String, Object>> imageEdit(String modelId, String messages, List<String> images, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        return this.imageEdit(airagModel, messages, images, params);
    }

    /**
     * 图生图
     *
     * @param images
     * @param params
     * @return
     */
    public List<Map<String, Object>> imageEdit(AiragModel airagModel,String messages, List<String> images, AIChatParams params) {
        if(null == airagModel || airagModel.getActivateFlag() == 0){
            if (airagModel != null && oConvertUtils.isNotEmpty(airagModel.getId())) {
                log.warn("模型未激活,采用默认图生图模型");
            }
            //判断是否配置了默认模型
            if(aiChatConfig == null || oConvertUtils.isEmpty(aiChatConfig.getAiModelPicDraw().getApiKey())){
                throw new JeecgBootBizTipException("当前系统未配置默认图像模型，请前往yml中配置默认模型");
            }
            airagModel = this.getDefaultDrawModel(aiChatConfig.getAiModelPicDraw());
        }
        params = mergeParams(airagModel, params);
        List<String> originalImageBase64List = getFirstImageBase64(images);
        try {
            return llmHandler.imageEdit(messages, originalImageBase64List, params);
        } catch (Exception e) {
            throw translateLlmException(e, "调用绘画AI接口失败，详情请查看后台日志。");
        }
    }

    /**
     * 需要将图片转换成Base64编码
     * @param images 图片路径列表
     * @return Base64编码字符串
     */
    private List<String> getFirstImageBase64(List<String> images) {
        List<String> originalImageBase64List = new ArrayList<>();
        if (images != null && !images.isEmpty()) {
            for (String imageUrl : images) {
                Matcher matcher = LLMConsts.WEB_PATTERN.matcher(imageUrl);
                try {
                    byte[] fileContent;
                    if (matcher.matches()) {
                        // 来源于网络
                        java.net.URL url = new java.net.URL(imageUrl);
                        java.net.URLConnection conn = url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(10000);
                        try (java.io.InputStream in = conn.getInputStream()) {
                            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                            int nRead;
                            byte[] data = new byte[1024];
                            while ((nRead = in.read(data, 0, data.length)) != -1) {
                                buffer.write(data, 0, nRead);
                            }
                            buffer.flush();
                            fileContent = buffer.toByteArray();
                        }
                    } else {
                        //update-begin---author:liusq ---date:2026-03-30  for：【issues/9431】修复getFirstImageBase64路径遍历漏洞(CWE-22)-----------
                        // 本地文件
                        String filePath = uploadpath + File.separator + imageUrl;
                        SsrfFileTypeFilter.checkPathTraversal(filePath);
                        // 路径遍历校验：规范化后确保文件在uploadpath目录内
                        File uploadDir = new File(uploadpath).getCanonicalFile();
                        File targetFile = new File(filePath).getCanonicalFile();
                        if (!targetFile.toPath().startsWith(uploadDir.toPath())) {
                            throw new JeecgBootException("非法文件路径，禁止访问上传目录之外的文件: " + imageUrl);
                        }
                        fileContent = Files.readAllBytes(targetFile.toPath());
                        //update-end---author:liusq ---date:2026-03-30  for：【issues/9431】修复getFirstImageBase64路径遍历漏洞(CWE-22)-----------
                    }
                    originalImageBase64List.add(Base64.getEncoder().encodeToString(fileContent));
                } catch (Exception e) {
                    log.error("图片读取失败: {}", imageUrl, e);
                    throw new JeecgBootException("图片读取失败: " + imageUrl);
                }
            }
        }
        return originalImageBase64List;
    }
    //================================================= end 【QQYUN-12145】【AI】AI 绘画创作 ========================================

    /**
     * 将 LLM 调用异常统一翻译为友好的 JeecgBootException。
     * <p>
     * 处理优先级：
     * <ol>
     *   <li>请求超时（timeout）→ 排队提示</li>
     *   <li>工具调用上下文丢失（messages with role 'tool'…）→ 友好提示</li>
     *   <li>{@link IAIChatHandler#MODEL_ERROR_MAP} 中的关键字匹配 → 对应中文提示</li>
     *   <li>兜底 → defaultMsg 参数</li>
     * </ol>
     *
     * @param e          原始异常
     * @param defaultMsg 兜底提示语
     * @return 封装后的 JeecgBootException，供调用方直接 throw
     * @author chenrui
     * @date 2025/3/5
     */
    private JeecgBootException translateLlmException(Exception e, String defaultMsg) {
        String exceptionMsg = e.getMessage();
        String errMsg = defaultMsg;

        if (oConvertUtils.isNotEmpty(exceptionMsg)) {
            // 1.工具调用消息序列不完整
            if (exceptionMsg.contains("messages with role 'tool' must be a response to a preceeding message with 'tool_calls'")) {
                errMsg = "消息序列不完整，可能是因为历史消息数量设置过小导致工具调用上下文丢失。建议增加历史消息数量后重试。";
                log.error("AI模型调用异常: 工具调用消息序列不完整，建议增加历史消息数量。异常详情: {}", exceptionMsg, e);
                return new JeecgBootException(errMsg);
            }

            // 2.根据常见异常关键字做细致翻译（大小写不敏感）
            errMsg = matchErrorMsg(exceptionMsg, errMsg);
        }

        log.error("AI模型调用异常: {}", errMsg, e);
        return new JeecgBootException(errMsg);
    }

    /**
     * 获取默认图像模型
     *
     * @return
     */
    private AiragModel getDefaultDrawModel(AiChatConfig.ModelConfig aiModelDraw) {
        AiragModel airagModel = new AiragModel();
        airagModel.setModelName(aiModelDraw.getModel());
        airagModel.setBaseUrl(aiModelDraw.getApiHost());
        airagModel.setProvider(aiModelDraw.getProvider());
        JSONObject credentialObject = new JSONObject();
        credentialObject.put("apiKey",aiModelDraw.getApiKey());
        airagModel.setCredential(credentialObject.toJSONString());
        return airagModel;
    }
}
