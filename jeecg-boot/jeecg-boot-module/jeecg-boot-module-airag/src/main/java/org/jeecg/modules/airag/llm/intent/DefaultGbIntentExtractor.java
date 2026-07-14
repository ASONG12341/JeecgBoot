// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】DefaultGbIntentExtractor Qwen 化重构：固定 qwen-flash 单模型 + credential JSON 解析 + ChatModel JSON Mode-----------
package org.jeecg.modules.airag.llm.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.common.handler.IGbIntentExtractor;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * DefaultGbIntentExtractor · Qwen 化实现
 *
 * @author song-claude
 * @date 2026-07-11
 */
@Slf4j
@Component
public class DefaultGbIntentExtractor implements IGbIntentExtractor {

    private static final String EXTRACTOR_SYSTEM_PROMPT = """
            你是一个 GB 国标意图解析专家。请从用户问题中提取结构化 JSON。

            字段规则：
            - gbStandard: 用户问题中提到的 GB 标准号，如 "GB 31241" / "GB/T 31467.3" / "GB/T 36276"；
              只接受 "GB" 或 "GB/T" 开头的标准号，未提及则 null，严禁臆造。
            - testType: 用户提到的测试类型，请归一化为英文 snake_case 后输出；无法识别则 null。
              常见映射示例（不限于这些）：
              过充→overcharge、过放→over_discharge、外部短路→external_short_circuit、短路→short_circuit、
              挤压→crush、针刺→nail_penetration、跌落→drop、热冲击→thermal_shock、加热→heating、
              振动→vibration、循环寿命→cycle_life、热→thermal。
            - nCells: 电池串数。口语如 "3S" / "三串" / "三个电芯" → 3；未提及则 null。
            - objectType: cell(单体) / pack(电池组/电池包) / system(系统)；未提及则 null。
            - inferredChapter: 根据 gbStandard + objectType 推断的章号，如 "7" / "8.2"；无法推断则 null。
            - environmentCondition: 环境条件如 '25±5℃'；未提及则 null。
            - isBooleanQuery: true(能否/是否/会怎样/是否适用) / false(如何/怎么/一般陈述)。
            //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】EXTRACTOR_SYSTEM_PROMPT 增加 clauseId/amendment/status 字段规则-----------
            - clauseId: 条款号，如 "9.2" / "8.3.1"；未提及则 null。
            - amendment: 标准版次/修订年份，如 "2022"；未提及则 null。
            - status: 条款状态，"current"(现行) 或 "superseded"(废止)；出现"废止"/"已撤销"→"superseded"，否则未提及则 null。
            //update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】EXTRACTOR_SYSTEM_PROMPT 增加 clauseId/amendment/status 字段规则-----------

            必须严格输出 JSON，不要包含任何解释性文字。JSON 输出格式如下：
            {
              "gbStandard": "...",
              "testType": "...",
              "nCells": ...,
              "objectType": "...",
              "inferredChapter": "...",
              "environmentCondition": "...",
              "isBooleanQuery": true/false,
              //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】JSON 示例增加 clauseId/amendment/status-----------
              "clauseId": "...",
              "amendment": "...",
              "status": "..."
              //update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】JSON 示例增加 clauseId/amendment/status-----------
            }
            //update-begin---author:song-claude ---date:2026-07-11  for：【修复模块编译错误】DefaultGbIntentExtractor.java EXTRACTOR_SYSTEM_PROMPT text block 末尾补回分号-----------
            """;
            //update-end---author:song-claude ---date:2026-07-11  for：【修复模块编译错误】DefaultGbIntentExtractor.java EXTRACTOR_SYSTEM_PROMPT text block 末尾补回分号-----------

    @Autowired
    private GbIntentExtractorProperties properties;

    @Autowired
    private IAiragModelService airagModelService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public GbQueryIntent extractWithFallback(String userQuery, List<String> knowIds) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            log.debug("[GB检索][GbIntentExtractor] userQuery 为空，跳过抽取");
            return nullIntent();
        }

        GbQueryIntent intent = tryExtract(userQuery, properties.getPrimaryModelName(), knowIds);
        if (intent != null) {
            log.info("[GB检索][GbIntentExtractor] 抽取成功: testType={}, objectType={}, knowIds={}",
                    intent.getTestType(), intent.getObjectType(), knowIds);
            return intent;
        }

        log.warn("[GB检索][GbIntentExtractor] 抽取失败，返回 null intent, knowIds={}", knowIds);
        return nullIntent();
    }

    private GbQueryIntent tryExtract(String userQuery, String modelName, List<String> knowIds) {
        AiragModel model = airagModelService.lambdaQuery()
                .eq(AiragModel::getName, modelName)
                .eq(AiragModel::getActivateFlag, 1)
                .one();
        if (model == null) {
            log.warn("[GB检索][GbIntentExtractor] 未找到 name={} 的激活模型配置", modelName);
            return null;
        }

        ChatModel chatModel = buildChatModel(model);
        int maxAttempts = Math.max(1, properties.getMaxRetriesPerModel() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatRequest request = ChatRequest.builder()
                        .messages(
                                SystemMessage.from(EXTRACTOR_SYSTEM_PROMPT),
                                UserMessage.from(userQuery)
                        )
                        .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                        .build();

                ChatResponse response = chatModel.chat(request);
                String json = response.aiMessage().text();
                if (json == null || json.trim().isEmpty()) {
                    log.warn("[GB检索][GbIntentExtractor] 返回空文本, attempt={}", attempt);
                    continue;
                }

                GbQueryIntent intent = objectMapper.readValue(json, GbQueryIntent.class);
                if (GbIntentValidator.validate(intent)) {
                    return intent;
                }
                log.warn("[GB检索][GbIntentExtractor] 校验后无有效字段, attempt={}", attempt);
            } catch (Exception e) {
                log.warn("[GB检索][GbIntentExtractor] 抽取异常, attempt={}, error={}", attempt, e.getMessage());
            }
        }
        return null;
    }

    ChatModel buildChatModel(AiragModel model) {
        String baseUrl = model.getBaseUrl();
        String apiKey = resolveApiKey(model.getCredential());
        String modelName = model.getModelName();

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .maxRetries(0)
                .build();
    }

    /**
     * 解析 airag_model.credential 字段。
     *
     * <p>生产环境 credential 存储为 JSON 格式：{"apiKey":"..."}；
     * 兼容旧版/测试环境直接存储为纯文本 API key 的情况。</p>
     *
     * @param credential 数据库中的 credential 原始值
     * @return 实际用于请求的 API key
     */
    String resolveApiKey(String credential) {
        if (credential == null) {
            return null;
        }

        String trimmed = credential.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                JsonNode node = objectMapper.readTree(trimmed);
                JsonNode apiKeyNode = node.get("apiKey");
                if (apiKeyNode != null && !apiKeyNode.isNull()) {
                    return apiKeyNode.asText().trim();
                }
            } catch (Exception e) {
                log.warn("[GB检索][GbIntentExtractor] credential JSON 解析失败，按纯文本使用: {}", e.getMessage());
            }
        }
        return trimmed;
    }

    private GbQueryIntent nullIntent() {
        return GbQueryIntent.builder()
                .gbStandard(null)
                .testType(null)
                .nCells(null)
                .objectType(null)
                .inferredChapter(null)
                .environmentCondition(null)
                .isBooleanQuery(null)
                //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】nullIntent 增加 clauseId/amendment/status-----------
                .clauseId(null)
                .amendment(null)
                .status(null)
                //update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.1 Task 2】nullIntent 增加 clauseId/amendment/status-----------
                .build();
    }
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】DefaultGbIntentExtractor Qwen 化重构：固定 qwen-flash 单模型 + credential JSON 解析 + ChatModel JSON Mode-----------
