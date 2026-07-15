//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】共享 LLM 客户端（从 DefaultGbIntentExtractor 提取，DRY）-----------
package org.jeecg.modules.airag.llm.gbstandard.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * GB-RAG 共享 LLM 客户端。
 * <p>从 DefaultGbIntentExtractor 提取的 OpenAI 兼容端点接入逻辑（buildChatModel + resolveApiKey），
 * 供 GbSchemaDeriver / GbBatchExtractor / DefaultGbIntentExtractor 复用，避免重复。</p>
 *
 * @author song
 * @date 2026-07-15
 */
@Slf4j
@Component
public class GbLlmClient {

    private final IAiragModelService airagModelService;

    public GbLlmClient(IAiragModelService airagModelService) {
        this.airagModelService = airagModelService;
    }

    /**
     * 按模型名 + 超时构建 ChatModel（查 airag_model 表，解析 credential）。
     */
    public ChatModel buildChatModel(String modelName, int timeoutSeconds) {
        AiragModel model = airagModelService.lambdaQuery()
                .eq(AiragModel::getName, modelName)
                .eq(AiragModel::getActivateFlag, 1).one();
        if (model == null) {
            throw new IllegalStateException("未找到已激活的模型: " + modelName);
        }
        String baseUrl = model.getBaseUrl();
        String apiKey = resolveApiKey(model.getCredential());
        String realModelName = model.getModelName();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(realModelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .build();
    }

    /**
     * 解析 airag_model.credential：JSON {"apiKey":"..."} 或明文。
     */
    public String resolveApiKey(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("credential 为空");
        }
        String trimmed = credential.trim();
        if (trimmed.startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                if (node.has("apiKey")) {
                    return node.get("apiKey").asText();
                }
            } catch (Exception e) {
                log.warn("[GbLlmClient] credential JSON 解析失败，按明文处理: {}", e.getMessage());
            }
        }
        return trimmed;
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】共享 LLM 客户端（从 DefaultGbIntentExtractor 提取，DRY）-----------
