package org.jeecg.modules.airag.llm.intent;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
     * 用于构造 credential JSON 的 ObjectMapper，不依赖测试里被 spy 的 objectMapper。
     */
    private static final ObjectMapper CREDENTIAL_MAPPER = new ObjectMapper();

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

    @Mock
    private IAiragModelService airagModelService;

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
        stubModelService(buildModel(tag));
        runQueries(tag);

        System.out.println("[A/B] ===== " + tag + " end =====");
    }

    /**
     * 构造一条模拟的 airag_model 记录。
     *
     * 字段含义：
     * - name:        模型配置在表里的标识名，和 properties 里的 primary 对应即可
     * - modelName:   实际调用的模型名，固定 qwen-flash
     * - baseUrl:     DashScope OpenAI 兼容地址，固定不变
     * - credential:  API key（以生产环境 JSON 格式 {"apiKey":"..."} 存储）
     * - activateFlag: 是否激活，必须填 1
     */
    private AiragModel buildModel(String modelName) {
        String credentialJson;
        try {
            credentialJson = CREDENTIAL_MAPPER.writeValueAsString(
                    Collections.singletonMap("apiKey", DASHSCOPE_API_KEY));
        } catch (Exception e) {
            throw new IllegalStateException("构造 credential JSON 失败", e);
        }
        return new AiragModel()
                .setName(modelName)
                .setModelName(modelName)
                .setBaseUrl(DASHSCOPE_BASE_URL)
                .setCredential(credentialJson)
                .setActivateFlag(1);
    }

    /**
     * mock IAiragModelService，让 extractor 调用 lambdaQuery() 时直接返回上面构造的 AiragModel。
     * 这样就不需要真实数据库了。
     */
    private void stubModelService(AiragModel model) {
        LambdaQueryChainWrapper<AiragModel> wrapper = mock(LambdaQueryChainWrapper.class);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.one()).thenReturn(model);
        when(airagModelService.lambdaQuery()).thenReturn(wrapper);
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
