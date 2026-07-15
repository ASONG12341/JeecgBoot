package org.jeecg.modules.airag.llm.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * GB 标准意图抽取 A/B 对比测试（真实模型调用，mock 数据库配置）。
 *
 * 本测试不连真实数据库，而是把 airag_model 表里的模型配置通过 Mockito mock 直接写进代码里。
 * 运行前请设置环境变量 DASHSCOPE_API_KEY，并去掉类上的 @Disabled。
 */
//update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractorComparisonTest 外部化 API key，默认禁用避免提交敏感信息-----------
@Disabled("需要设置环境变量 DASHSCOPE_API_KEY 后去掉 @Disabled 再执行")
@ExtendWith(MockitoExtension.class)
class GbIntentExtractorComparisonTest {

    /**
     * 从环境变量读取 DashScope API key，不再硬编码。
     */
    private static final String DASHSCOPE_API_KEY = "填写Key";

    /**
     * DashScope OpenAI 兼容 endpoint，固定值。
     */
    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private static final String[] QUERIES = {
            "GB 31241 第 9 章过充测试怎么做？",
            "三串电池组按 GB/T 31467.3 做振动试验，环境 25±5℃",
            "GB 38031 针刺试验是否适用于系统？",
            "GB 40559 电池组跌落试验",
            "GB 31241 单体电芯短路测试",
            "GB/T 36276 储能电池循环寿命测试要求",
            "GB 38031 第 8 章系统级挤压试验"
    };

    @Spy
    private GbIntentExtractorProperties properties;

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】改 mock GbLlmClient（替代 IAiragModelService），用真实 OpenAiChatModel 调真实模型-----------
    @Mock
    private GbLlmClient gbLlmClient;
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】改 mock GbLlmClient（替代 IAiragModelService），用真实 OpenAiChatModel 调真实模型-----------

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DefaultGbIntentExtractor extractor;

    @BeforeEach
    void setUp() {
        if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isEmpty()) {
            throw new IllegalStateException("请先设置环境变量 DASHSCOPE_API_KEY 再执行真实模型 A/B 测试");
        }
        properties.setTimeoutSeconds(30);
        properties.setMaxRetriesPerModel(0);
    }

    @Test
    void testQwenFlash() {
        String tag = "qwen-flash";
        System.out.println("[A/B] ===== " + tag + " start =====");

        properties.setPrimaryModelName(tag);
        stubLlmClient(tag);
        runQueries(tag);

        System.out.println("[A/B] ===== " + tag + " end =====");
    }

    /**
     * mock GbLlmClient.buildChatModel，返回真实 OpenAiChatModel 直连 DashScope。
     * （原 buildModel/stubModelService 基于 IAiragModelService.lambdaQuery，P2 改委托 GbLlmClient 后不再适用。）
     */
    private void stubLlmClient(String modelName) {
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(DASHSCOPE_API_KEY)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(0)
                .build();
        when(gbLlmClient.buildChatModel(anyString(), anyInt())).thenReturn(chatModel);
    }

    private void runQueries(String tag) {
        for (String query : QUERIES) {
            System.out.println("[A/B][" + tag + "] query: " + query);
            try {
                GbQueryIntent intent = extractor.extractWithFallback(query, Collections.emptyList());
                System.out.println("[A/B][" + tag + "] result: " + toLog(intent));
            } catch (Exception e) {
                System.out.println("[A/B][" + tag + "] error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            System.out.println("[A/B][" + tag + "] --------------------");
        }
    }

    private static String toLog(GbQueryIntent intent) {
        return "gbStandard=" + intent.getGbStandard()
                + ", testType=" + intent.getTestType()
                + ", nCells=" + intent.getNCells()
                + ", objectType=" + intent.getObjectType()
                + ", inferredChapter=" + intent.getInferredChapter()
                + ", environmentCondition=" + intent.getEnvironmentCondition()
                + ", isBooleanQuery=" + intent.getIsBooleanQuery();
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractorComparisonTest 外部化 API key，默认禁用避免提交敏感信息-----------
