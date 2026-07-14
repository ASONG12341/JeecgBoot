package org.jeecg.modules.airag.llm.intent;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
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
import static org.mockito.Mockito.*;

/**
 * DefaultGbIntentExtractor 单元测试。
 *
 * 覆盖场景：
 * 1. GbIntentValidator 对非法字段的就地清洗
 * 2. 空/空字符串用户查询直接返回 null intent
 * 3. 缺失模型配置时返回 null intent
 * 4. credential JSON / 纯文本 / 异常值解析
 * 5. 模型返回正常 JSON 时成功抽取 intent
 */
//update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】新增 DefaultGbIntentExtractor 单元测试-----------
@ExtendWith(MockitoExtension.class)
class DefaultGbIntentExtractorTest {

    @InjectMocks
    private DefaultGbIntentExtractor extractor;

    @Spy
    private GbIntentExtractorProperties properties;

    @Mock
    private IAiragModelService airagModelService;

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

    @Test
    @SuppressWarnings("unchecked")
    void testMissingModelConfigReturnsNullIntent() {
        System.out.println("[Test] testMissingModelConfigReturnsNullIntent start");
        LambdaQueryChainWrapper<AiragModel> wrapper = mock(LambdaQueryChainWrapper.class);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.one()).thenReturn(null);
        when(airagModelService.lambdaQuery()).thenReturn(wrapper);

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

    //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充 credential 解析单元测试：覆盖 JSON、纯文本、空值、异常 JSON-----------
    @Test
    void testResolveApiKeyFromJson() {
        System.out.println("[Test] testResolveApiKeyFromJson start");
        String apiKey = extractor.resolveApiKey("{\"apiKey\":\"sk-json-key\"}");
        System.out.println("[Test] result: " + apiKey);
        assertEquals("sk-json-key", apiKey, "应从 JSON credential 中提取 apiKey");
    }

    @Test
    void testResolveApiKeyFromPlainText() {
        System.out.println("[Test] testResolveApiKeyFromPlainText start");
        String apiKey = extractor.resolveApiKey("sk-plain-key");
        System.out.println("[Test] result: " + apiKey);
        assertEquals("sk-plain-key", apiKey, "纯文本 credential 应原样返回");
    }

    @Test
    void testResolveApiKeyWithNullOrEmpty() {
        System.out.println("[Test] testResolveApiKeyWithNullOrEmpty start");
        assertNull(extractor.resolveApiKey(null), "null credential 应返回 null");
        assertEquals("", extractor.resolveApiKey(""), "空字符串 credential 应返回空字符串");
        assertEquals("", extractor.resolveApiKey("   "), "纯空白 credential 应返回空字符串");
    }

    @Test
    void testResolveApiKeyInvalidJsonFallsBackToPlainText() {
        System.out.println("[Test] testResolveApiKeyInvalidJsonFallsBackToPlainText start");
        String credential = "{not-a-valid-json}";
        String apiKey = extractor.resolveApiKey(credential);
        System.out.println("[Test] result: " + apiKey);
        assertEquals(credential, apiKey, "非法 JSON credential 应回退为原字符串");
    }
    //update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充 credential 解析单元测试：覆盖 JSON、纯文本、空值、异常 JSON-----------

    //update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充模型正常返回 JSON 时成功抽取 intent 的 mock 测试-----------
    @Test
    @SuppressWarnings("unchecked")
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

        AiragModel model = new AiragModel()
                .setName("qwen-flash")
                .setModelName("qwen-flash")
                .setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .setCredential("{\"apiKey\":\"sk-test\"}")
                .setActivateFlag(1);

        LambdaQueryChainWrapper<AiragModel> wrapper = mock(LambdaQueryChainWrapper.class);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.one()).thenReturn(model);
        when(airagModelService.lambdaQuery()).thenReturn(wrapper);

        DefaultGbIntentExtractor extractorSpy = spy(extractor);
        doReturn(chatModel).when(extractorSpy).buildChatModel(any(AiragModel.class));

        GbQueryIntent intent = extractorSpy.extractWithFallback(
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
    //update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】补充模型正常返回 JSON 时成功抽取 intent 的 mock 测试-----------

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
