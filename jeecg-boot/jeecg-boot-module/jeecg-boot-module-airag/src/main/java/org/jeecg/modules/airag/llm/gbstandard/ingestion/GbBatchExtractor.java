//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量 LLM 抽取器（1 次调用产 4 槽位+极性+参数+引用，领域无关）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.dto.BatchExtractResult;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.vo.DomainSchema;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 国标条款批量 LLM 抽取器。
 * <p>
 * 1 次调用打包 N 个条款（batchSize 个），同时产出：4 槽位值、极性、参数、引用关系。
 * 领域无关：槽位含义由 domain_schema 在 prompt 中动态注入，不硬编码领域词。
 * 失败/禁用退化为空结果（clausePath 保留，槽位全空），不阻塞入库。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbBatchExtractor {

    private final IAiragModelService airagModelService;
    private final GbStandardProperties.ClauseMetadataExtractor config;
    private final ObjectMapper objectMapper;
    private final GbLlmClient llmClient;  // Task 0 提取的共享客户端

    public GbBatchExtractor(IAiragModelService airagModelService,
                            GbStandardProperties properties,
                            ObjectMapper objectMapper,
                            GbLlmClient llmClient) {
        this.airagModelService = airagModelService;
        // ClauseMetadataExtractor 是 @ConfigurationProperties 嵌套对象，不是独立 Bean；从外层配置取
        this.config = properties.getClauseMetadataExtractor();
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是技术规范文档的结构化抽取专家。对下面给出的每个条款，抽取其结构化信息。

            本标准的 4 个语义槽位含义：
            {{DOMAIN_SCHEMA}}

            对每个条款输出：
            - clausePath：条款路径（原样回填）
            - primaryType / secondaryType / quantityValue / conditionText：4 槽位值（不符合则 null）
            - polarity：positive(肯定要求) / negative(否定/禁止) / exception(例外)
            - exceptionOf：例外针对的条款路径（仅 exception 时填）
            - parameters：条款涉及的参数/公式（数组，每项含 paramName/formula/paramValue/unit）
            - references：条款引用关系（数组，每项含 targetType=intra|inter/targetStandardNo/targetClausePath/refType）

            输出一个 JSON 数组，每个元素对应一个条款。严格输出合法 JSON，不要解释性文字。
            """;

    public List<BatchExtractResult> extractBatch(List<GbClauseNode> clauses, DomainSchema schema) {
        if (!config.isEnabled()) {
            log.info("[GbBatchExtractor] 已禁用，返回空结果（保留 clausePath）");
            return fallbackResults(clauses);
        }
        if (clauses == null || clauses.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            ChatModel chatModel = buildChatModel();
            String prompt = SYSTEM_PROMPT_TEMPLATE.replace("{{DOMAIN_SCHEMA}}", describeSchema(schema));
            String userMsg = buildUserMessage(clauses);
            ChatRequest request = ChatRequest.builder()
                    .messages(SystemMessage.from(prompt), UserMessage.from(userMsg))
                    .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                    .build();
            String json = chatModel.chat(request).aiMessage().text();
            List<BatchExtractResult> results = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("[GbBatchExtractor] 批量抽取成功: {} 条款 -> {} 结果", clauses.size(), results.size());
            return results;
        } catch (Exception e) {
            log.warn("[GbBatchExtractor] 批量抽取失败，退化为空结果: {}", e.getMessage());
            return fallbackResults(clauses);
        }
    }

    /** 禁用/失败时的退化：保留 clausePath，槽位全空 */
    private List<BatchExtractResult> fallbackResults(List<GbClauseNode> clauses) {
        List<BatchExtractResult> results = new ArrayList<>();
        for (GbClauseNode c : clauses) {
            BatchExtractResult r = new BatchExtractResult();
            r.setClausePath(c.getClausePath());
            results.add(r);
        }
        return results;
    }

    /** 把 DomainSchema 转成 prompt 里的文字描述（领域无关） */
    private String describeSchema(DomainSchema schema) {
        StringBuilder sb = new StringBuilder();
        if (schema.getPrimaryType() != null) sb.append("- primaryType: ").append(schema.getPrimaryType().getLabel()).append("\n");
        if (schema.getSecondaryType() != null) sb.append("- secondaryType: ").append(schema.getSecondaryType().getLabel()).append("\n");
        if (schema.getQuantityValue() != null) sb.append("- quantityValue: ").append(schema.getQuantityValue().getLabel()).append("\n");
        if (schema.getConditionText() != null) sb.append("- conditionText: ").append(schema.getConditionText().getLabel()).append("\n");
        return sb.length() == 0 ? "（本标准未推导出领域 schema，按通用语义自由抽取）" : sb.toString();
    }

    private String buildUserMessage(List<GbClauseNode> clauses) {
        StringBuilder sb = new StringBuilder("条款列表（JSON）：\n[\n");
        for (int i = 0; i < clauses.size(); i++) {
            GbClauseNode c = clauses.get(i);
            sb.append("{\"clausePath\":\"").append(c.getClausePath()).append("\",")
              .append("\"text\":\"").append(escape(c.getText())).append("\"}");
            if (i < clauses.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private ChatModel buildChatModel() {
        // 用 Task 0 提取的 GbLlmClient
        return llmClient.buildChatModel(config.getModelName(), config.getTimeoutSeconds());
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量 LLM 抽取器（1 次调用产 4 槽位+极性+参数+引用，领域无关）-----------
