package org.jeecg.modules.airag.llm.handler;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.router.QueryRouter;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntentExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 切换 QueryIntent 测试（extractIntent 返回 QueryIntent + buildQueryRouter 委托 getQueryRouter）-----------
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P3 Task 9】移除 IntentContext / GbQueryIntent 断言（旧意图系统已删除）-----------
/**
 * Task 5 测试：验证 AIChatHandler 切换到 QueryIntent 后：
 * <ol>
 *   <li>{@code extractIntent} 返回 {@link QueryIntent}（GB-RAG v4 P3 Task 9：旧 IntentContext ThreadLocal 已整体删除，不再需要"不写 ThreadLocal"断言）。</li>
 *   <li>package-private {@code buildQueryRouter(knowIds, top, sim, QueryIntent)} 把 intent 委托给
 *       {@code embeddingHandler.getQueryRouter(..., QueryIntent)}。</li>
 * </ol>
 *
 * 测试策略：AIChatHandler 字段很多（airagModelMapper / llmHandler / aiChatConfig …）难以全部 @InjectMocks，
 * 因此按 brief 抽取 package-private {@code buildQueryRouter} 辅助方法只测 intent→router 委托；
 * {@code extractIntent} 用反射调用（private），验证返回类型。
 */
@ExtendWith(MockitoExtension.class)
class AIChatHandlerIntentTest {

    @Mock
    private EmbeddingHandler embeddingHandler;

    @Mock
    private QueryIntentExtractor queryIntentExtractor;

    @InjectMocks
    private AIChatHandler aiChatHandler;

    // ===== extractIntent 行为 =====

    /** extractIntent 命中 GB 标准号时调用 queryIntentExtractor.extractWithFallback，返回其结果（QueryIntent）。 */
    @Test
    void extractIntentShouldReturnQueryIntent() throws Exception {
        QueryIntent expected = QueryIntent.builder().standardNo("GB 31241").primaryType("overcharge").build();
        when(queryIntentExtractor.extractWithFallback(eq("GB 31241 电池过充测试要求"), any()))
                .thenReturn(expected);

        AIChatParams params = new AIChatParams();
        params.setKnowIds(List.of("know-1"));
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(UserMessage.from("GB 31241 电池过充测试要求"));

        QueryIntent result = invokeExtractIntent("completions", messages, params);

        assertThat(result).isNotNull();
        assertThat(result.getStandardNo()).isEqualTo("GB 31241");
        assertThat(result.getPrimaryType()).isEqualTo("overcharge");
        verify(queryIntentExtractor, times(1)).extractWithFallback(any(), any());
    }

    /** knowIds 为空时 extractIntent 静默返回 null，且不调 LLM 抽取。 */
    @Test
    void extractIntentShouldReturnNullWhenKnowIdsEmpty() throws Exception {
        AIChatParams params = new AIChatParams(); // knowIds 未设置
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(UserMessage.from("GB 31241 过充"));

        QueryIntent result = invokeExtractIntent("completions", messages, params);

        assertThat(result).isNull();
        verifyNoInteractions(queryIntentExtractor);
    }

    /** 用户问题不含 GB 标准号时跳过 LLM 调用（GB_PATTERN 预检），返回 null。 */
    @Test
    void extractIntentShouldSkipLlmWhenQueryHasNoGbStandardNo() throws Exception {
        AIChatParams params = new AIChatParams();
        params.setKnowIds(List.of("know-1"));
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(UserMessage.from("今天天气怎么样"));

        QueryIntent result = invokeExtractIntent("chat", messages, params);

        assertThat(result).isNull();
        verifyNoInteractions(queryIntentExtractor);
    }

    /** extractor 抛异常时 extractIntent 吞掉异常返回 null（不破坏 chat 主流程）。 */
    @Test
    void extractIntentShouldReturnNullOnExtractorFailure() throws Exception {
        when(queryIntentExtractor.extractWithFallback(any(), any()))
                .thenThrow(new RuntimeException("llm down"));

        AIChatParams params = new AIChatParams();
        params.setKnowIds(List.of("know-1"));
        List<dev.langchain4j.data.message.ChatMessage> messages =
                List.of(UserMessage.from("GB 31241 过充"));

        QueryIntent result = invokeExtractIntent("chat", messages, params);

        assertThat(result).isNull();
    }

    // ===== buildQueryRouter 委托 =====

    /** package-private buildQueryRouter 把 intent 透传给 embeddingHandler.getQueryRouter(QueryIntent 重载)。 */
    @Test
    void buildQueryRouterShouldDelegateToEmbeddingHandlerQueryIntentOverload() {
        QueryIntent intent = QueryIntent.builder().standardNo("GB 31241").build();
        QueryRouter stubRouter = org.mockito.Mockito.mock(QueryRouter.class);
        when(embeddingHandler.getQueryRouter(eq(List.of("know-1")), eq(5), eq(0.8),
                org.mockito.ArgumentMatchers.<QueryIntent>eq(intent))).thenReturn(stubRouter);

        QueryRouter result = aiChatHandler.buildQueryRouter(List.of("know-1"), 5, 0.8, intent);

        assertThat(result).isSameAs(stubRouter);
        verify(embeddingHandler, times(1)).getQueryRouter(eq(List.of("know-1")), eq(5), eq(0.8),
                org.mockito.ArgumentMatchers.<QueryIntent>eq(intent));
    }

    /** intent 为 null 时仍正常委托（getQueryRouter 接受 null intent）。 */
    @Test
    void buildQueryRouterShouldDelegateWhenIntentIsNull() {
        QueryRouter stubRouter = org.mockito.Mockito.mock(QueryRouter.class);
        when(embeddingHandler.getQueryRouter(eq(List.of("know-1")), eq(5), eq(0.8),
                org.mockito.ArgumentMatchers.<QueryIntent>isNull())).thenReturn(stubRouter);

        QueryRouter result = aiChatHandler.buildQueryRouter(List.of("know-1"), 5, 0.8, null);

        assertThat(result).isSameAs(stubRouter);
        verify(embeddingHandler, times(1)).getQueryRouter(eq(List.of("know-1")), eq(5), eq(0.8),
                org.mockito.ArgumentMatchers.<QueryIntent>isNull());
    }

    // ===== 反射工具 =====

    private QueryIntent invokeExtractIntent(String caller,
                                            List<dev.langchain4j.data.message.ChatMessage> messages,
                                            AIChatParams params) throws Exception {
        Method m = AIChatHandler.class.getDeclaredMethod("extractIntent",
                String.class, List.class, AIChatParams.class);
        m.setAccessible(true);
        return (QueryIntent) m.invoke(aiChatHandler, caller, messages, params);
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P3 Task 9】移除 IntentContext / GbQueryIntent 断言-----------
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】AIChatHandler 切换 QueryIntent 测试-----------
