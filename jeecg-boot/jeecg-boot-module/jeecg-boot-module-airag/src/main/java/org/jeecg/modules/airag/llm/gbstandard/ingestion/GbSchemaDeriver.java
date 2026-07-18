//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】domain_schema 推导器（每标准 1 次 LLM，领域无关）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.springframework.stereotype.Component;

/**
 * 国标领域 Schema 推导器。
 * <p>
 * 输入标准前言+目录+前3章，输出 4 槽位的 label/enum/unit（domain_schema）。
 * 领域无关：prompt 只描述 4 个通用槽位语义（做什么/对谁/多少/什么条件），
 * 不预设任何领域的取值。失败退化为空 schema，不阻塞入库。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbSchemaDeriver {

    private final IAiragModelService airagModelService;
    private final GbStandardProperties.SchemaDeriver config;
    private final ObjectMapper objectMapper;
    private final GbLlmClient llmClient;  // Task 0 提取的共享客户端

    public GbSchemaDeriver(IAiragModelService airagModelService,
                           GbStandardProperties properties,
                           ObjectMapper objectMapper,
                           GbLlmClient llmClient) {
        this.airagModelService = airagModelService;
        // SchemaDeriver 是 @ConfigurationProperties 嵌套对象，不是独立 Bean；从外层配置取
        this.config = properties.getSchemaDeriver();
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    // 领域无关系统提示：只描述 4 个通用槽位，不列任何领域取值
    private static final String SYSTEM_PROMPT = """
            你是技术规范文档的领域分析专家。分析给定的国标文本（前言/目录/前几章），
            推导出 4 个通用语义槽位在本标准中的具体含义。

            4 个槽位是所有技术规范的共性维度（领域无关）：
            - primaryType：这条条款在说"做什么"（如测试类型/功能类别/操作类别）
            - secondaryType：针对"什么对象"（如被测物/适用组件）
            - quantityValue：涉及的"量值"是什么（如数量/规格/容量）
            - conditionText：在"什么条件"下（如环境/状态/前提）

            请输出 JSON，每个槽位给出 label（中文标签）、enumValues（常见取值数组，可空）、unit（单位，可空）。
            若某槽位在本标准中不适用，对应字段给 null。
            严格输出合法 JSON，不要解释性文字。格式：
            {"primaryType":{"label":"...","enumValues":[...],"unit":"..."},
             "secondaryType":{"label":"...","enumValues":[...],"unit":"..."},
             "quantityValue":{"label":"...","enumValues":null,"unit":"..."},
             "conditionText":{"label":"...","enumValues":null,"unit":"..."}}
            """;

    public DomainSchema derive(String standardIntro) {
        if (!config.isEnabled()) {
            log.info("[GbSchemaDeriver] 已禁用，返回空 domain_schema");
            return new DomainSchema();
        }
        if (standardIntro == null || standardIntro.isBlank()) {
            log.info("[GbSchemaDeriver] 输入为空，返回空 domain_schema");
            return new DomainSchema();
        }
        try {
            ChatModel chatModel = buildChatModel();
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(truncate(standardIntro, 8000)))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            DomainSchema schema = objectMapper.readValue(json, DomainSchema.class);
            log.info("[GbSchemaDeriver] domain_schema 推导成功: primaryType.label={}",
                    schema.getPrimaryType() != null ? schema.getPrimaryType().getLabel() : "null");
            return schema;
        } catch (Exception e) {
            log.warn("[GbSchemaDeriver] domain_schema 推导失败，退化为空 schema: {}", e.getMessage());
            return new DomainSchema();
        }
    }

    private ChatModel buildChatModel() {
        // 用 Task 0 提取的 GbLlmClient（不再重复 buildChatModel/resolveApiKey 逻辑）
        return llmClient.buildChatModel(config.getModelName(), config.getTimeoutSeconds());
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】domain_schema 推导器（每标准 1 次 LLM，领域无关）-----------
