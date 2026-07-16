//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】LLM 槽位抽取器（领域无关，复用 GbLlmClient，带 fallback）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 国标检索意图 LLM 槽位抽取器（领域无关）。
 * <p>
 * 第二层意图：LLM 抽取 4 槽位 + 通用骨架字段。prompt 领域无关，
 * 槽位含义由 domain_schema 在运行时注入（当前 P3 先用通用描述，
 * domain_schema 注入留待标准多挂载时增强）。
 * 失败/禁用返回空 QueryIntent（全 null），不阻塞检索（退化为纯向量召回）。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component
public class QueryIntentExtractor {

    private final GbLlmClient gbLlmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    @Autowired
    public QueryIntentExtractor(GbLlmClient gbLlmClient,
                                ObjectMapper objectMapper,
                                @Value("${jeecg.airag.gb-standard.query-intent-enabled:true}") boolean enabled) {
        this.gbLlmClient = gbLlmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // 领域无关系统提示：只描述 4 通用槽位 + 骨架字段，不列任何领域 enum
    private static final String SYSTEM_PROMPT = """
            你是技术规范文档的检索意图分析专家。把用户的口语化提问转为结构化 JSON。

            输出字段（全部领域无关）：
            - standardNo：提到的标准号（如 GB 31241），未提及则 null
            - clauseId：条款号（如 9.2），未提及则 null
            - version：版次/年份，未提及则 null
            - objectType：对象类型（通用，未提及则 null）
            - isBooleanQuery：true=用户问能否/是否；false=问如何/怎么
            - primaryType：这条问题在问"做什么"（测试类型/功能类别等），无法判断则 null
            - secondaryType：针对"什么对象"，无法判断则 null
            - quantityValue：涉及的量值（数字），无法判断则 null
            - conditionText：条件（如温度/环境），无法判断则 null

            严格输出合法 JSON，不要解释性文字。
            """;

    public QueryIntent extractWithFallback(String userQuery, List<String> knowIds) {
        if (!enabled) {
            log.debug("[QueryIntentExtractor] 已禁用，返回空 intent");
            return QueryIntent.builder().build();
        }
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return QueryIntent.builder().build();
        }
        if (knowIds == null || knowIds.isEmpty()) {
            return QueryIntent.builder().build();
        }
        try {
            ChatModel chatModel = gbLlmClient.buildChatModel("qwen-flash", 5);
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userQuery))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            QueryIntent intent = objectMapper.readValue(json, QueryIntent.class);
            log.info("[QueryIntentExtractor] 抽取成功: standardNo={}, primaryType={}",
                    intent.getStandardNo(), intent.getPrimaryType());
            return intent;
        } catch (Exception e) {
            log.warn("[QueryIntentExtractor] 抽取失败，返回空 intent: {}", e.getMessage());
            return QueryIntent.builder().build();
        }
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】LLM 槽位抽取器（领域无关，复用 GbLlmClient，带 fallback）-----------
