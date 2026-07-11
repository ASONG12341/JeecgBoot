package org.jeecg.modules.airag.llm.intent;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultGbIntentExtractor 单元测试。
 *
 * 覆盖场景：
 * 1. GbIntentValidator 对非法字段的就地清洗
 * 2. 空/空字符串用户查询直接返回 null intent
 * 3. 缺失模型配置时返回 null intent
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

    @Mock
    private ObjectMapper objectMapper;

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
