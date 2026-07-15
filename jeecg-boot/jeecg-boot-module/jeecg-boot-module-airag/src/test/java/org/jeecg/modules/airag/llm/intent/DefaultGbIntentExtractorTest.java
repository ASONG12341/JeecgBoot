package org.jeecg.modules.airag.llm.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.llm.GbLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DefaultGbIntentExtractor 单元测试。
 *
 * 覆盖场景：
 * 1. GbIntentValidator 对非法字段的就地清洗
 * 2. 空/空字符串用户查询直接返回 null intent
 * 3. 缺失模型配置时返回 null intent
 * 4. 模型返回正常 JSON 时成功抽取 intent
 *
 * //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】适配 GbLlmClient 注入：删除 resolveApiKey 直测（已迁至 GbLlmClientTest），mock GbLlmClient 替代原 IAiragModelService + buildChatModel spy-----------
 * credential 解析（JSON/纯文本/空值/异常 JSON）的覆盖已迁移到 GbLlmClientTest，
 * 此处仅验证 DefaultGbIntentExtractor 的抽取主流程（委托 GbLlmClient 后行为不变）。
 * //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】适配 GbLlmClient 注入：删除 resolveApiKey 直测（已迁至 GbLlmClientTest），mock GbLlmClient 替代原 IAiragModelService + buildChatModel spy-----------
 */
//update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】新增 DefaultGbIntentExtractor 单元测试-----------
@ExtendWith(MockitoExtension.class)
class DefaultGbIntentExtractorTest {

    @InjectMocks
    private DefaultGbIntentExtractor extractor;

    @Spy
    private GbIntentExtractorProperties properties;

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】改注入 GbLlmClient（替代 IAiragModelService），抽取流程委托它构建 ChatModel-----------
    @Mock
    private GbLlmClient gbLlmClient;
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】改注入 GbLlmClient（替代 IAiragModelService），抽取流程委托它构建 ChatModel-----------

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testValidateFiltersInvalidFields() {
        System.out.println("[Test] testValidateFiltersInvalidFields start");
        GbQueryIntent intent = GbQueryIntent.builder()
                .testType("  Invalid Type  ")
                .objectType("invalid")
                .gbStandard("GB 31241")
                .build();

        boolean valid = GbIntentValidator.validate(intent);
        System.out.println("[Test] result: valid=" + valid
                + ", gbStandard=" + intent.getGbStandard()
                + ", testType=" + intent.getTestType()
                + ", objectType=" + intent.getObjectType());

        assertTrue(valid, "gbStandard 合法时校验应返回 true");
        assertEquals("invalid_type", intent.getTestType(), "testType 应被归一化为小写 snake_case");
        assertNull(intent.getObjectType(), "非法 objectType 应被置为 null");
    }

    @Test
    void testEmptyUserQueryReturnsNullIntent() {
        System.out.println("[Test] testEmptyUserQueryReturnsNullIntent start");
        List<String> knowIds = Collections.emptyList();

        GbQueryIntent intent = extractor.extractWithFallback("   ", knowIds);
        System.out.println("[Test] result: " + toLog(intent));

        assertNotNull(intent, "空查询应返回非空的 null intent 对象");
        assertAll(
                () -> assertNull(intent.getGbStandard()),
                () -> assertNull(intent.getTestType()),
                () -> assertNull(intent.getNCells()),
                () -> assertNull(intent.getObjectType()),
                () -> assertNull(intent.getInferredChapter()),
                () -> assertNull(intent.getEnvironmentCondition()),
                () -> assertNull(intent.getIsBooleanQuery())
        );
    }

    @Test
    void testNullUserQueryReturnsNullIntent() {
        System.out.println("[Test] testNullUserQueryReturnsNullIntent start");
        List<String> knowIds = Collections.emptyList();

        GbQueryIntent intent = extractor.extractWithFallback(null, knowIds);
        System.out.println("[Test] result: " + toLog(intent));

        assertNotNull(intent, "null 查询应返回非空的 null intent 对象");
        assertAll(
                () -> assertNull(intent.getGbStandard()),
                () -> assertNull(intent.getTestType()),
                () -> assertNull(intent.getNCells()),
                () -> assertNull(intent.getObjectType()),
                () -> assertNull(intent.getInferredChapter()),
                () -> assertNull(intent.getEnvironmentCondition()),
                () -> assertNull(intent.getIsBooleanQuery())
        );
    }

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】原 testMissingModelConfigReturnsNullIntent 改为 mock GbLlmClient.buildChatModel 抛 IllegalStateException（替代原 lambdaQuery mock）-----------
    @Test
    void testMissingModelConfigReturnsNullIntent() {
        System.out.println("[Test] testMissingModelConfigReturnsNullIntent start");
        // GbLlmClient 内部未找到激活模型时抛 IllegalStateException，DefaultGbIntentExtractor 应捕获并返回 null intent
        when(gbLlmClient.buildChatModel(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("未找到已激活的模型: qwen-flash"));

        GbQueryIntent intent = extractor.extractWithFallback("GB 31241 过充测试", Collections.emptyList());
        System.out.println("[Test] result: " + toLog(intent));

        assertNotNull(intent, "模型配置缺失时应返回非空的 null intent 对象");
        assertAll(
                () -> assertNull(intent.getGbStandard()),
                () -> assertNull(intent.getTestType()),
                () -> assertNull(intent.getNCells()),
                () -> assertNull(intent.getObjectType()),
                () -> assertNull(intent.getInferredChapter()),
                () -> assertNull(intent.getEnvironmentCondition()),
                () -> assertNull(intent.getIsBooleanQuery())
        );
    }
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】原 testMissingModelConfigReturnsNullIntent 改为 mock GbLlmClient.buildChatModel 抛 IllegalStateException（替代原 lambdaQuery mock）-----------

    //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充 credential 解析单元测试：覆盖 JSON、纯文本、空值、异常 JSON-----------
    // update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充 credential 解析单元测试：覆盖 JSON、纯文本、空值、异常 JSON-----------
    // update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】resolveApiKey 已迁至 GbLlmClient，相关直测随之移除（见 GbLlmClientTest），原 testResolveApiKey* 4 个用例删除-----------
    // update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】resolveApiKey 已迁至 GbLlmClient，相关直测随之移除（见 GbLlmClientTest），原 testResolveApiKey* 4 个用例删除-----------

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】testSuccessfulExtractionFromModelResponse 改为 mock GbLlmClient.buildChatModel 返回 ChatModel（替代原 spy(extractor).buildChatModel）-----------
    @Test
    void testSuccessfulExtractionFromModelResponse() {
        System.out.println("[Test] testSuccessfulExtractionFromModelResponse start");

        String json = """
                {
                  "gbStandard": "GB 31241",
                  "testType": "overcharge",
                  "nCells": 3,
                  "objectType": "cell",
                  "inferredChapter": "7",
                  "environmentCondition": "25±5℃",
                  "isBooleanQuery": false
                }
                """;

        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(json))
                .build();
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(response);

        // 模型查找 + ChatModel 构建已委托给 GbLlmClient，此处直接 mock 它返回构造好的 ChatModel
        when(gbLlmClient.buildChatModel(anyString(), anyInt())).thenReturn(chatModel);

        GbQueryIntent intent = extractor.extractWithFallback(
                "GB 31241 第 7 章过充测试，3S 单体电芯，25±5℃", Collections.emptyList());
        System.out.println("[Test] result: " + toLog(intent));

        assertNotNull(intent, "应返回非空 intent");
        assertEquals("GB 31241", intent.getGbStandard());
        assertEquals("overcharge", intent.getTestType());
        assertEquals(3, intent.getNCells());
        assertEquals("cell", intent.getObjectType());
        assertEquals("7", intent.getInferredChapter());
        assertEquals("25±5℃", intent.getEnvironmentCondition());
        assertEquals(Boolean.FALSE, intent.getIsBooleanQuery());
    }
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】testSuccessfulExtractionFromModelResponse 改为 mock GbLlmClient.buildChatModel 返回 ChatModel（替代原 spy(extractor).buildChatModel）-----------

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
//update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】新增 DefaultGbIntentExtractor 单元测试-----------
