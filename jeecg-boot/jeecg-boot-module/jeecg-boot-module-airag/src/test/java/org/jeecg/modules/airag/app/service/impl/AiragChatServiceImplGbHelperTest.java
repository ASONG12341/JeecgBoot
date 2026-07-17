//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】AiragChatServiceImpl GB 辅助方法测试（isGbStandardKnowledge + buildGbCompliancePrompt + 审计防御）-----------
package org.jeecg.modules.airag.app.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.jeecg.modules.airag.llm.gbstandard.tool.GbCalculationToolBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 5 测试：AiragChatServiceImpl 的 GB 集成辅助方法单元测试。
 * <p>
 * AiragChatServiceImpl 字段很多（airagAppMapper / aiChatHandler / redisTemplate …），整体 @InjectMocks 会
 * 因为 Spring 上下文 / LangChain4j 依赖等无法干净构造。按 brief，聚焦测三个抽取出来的"纯辅助"行为：
 * <ol>
 *   <li>{@code isGbStandardKnowledge(knowIds)}：用 mock 的 GbStandardMapper 验证 IN 查询返回 count&gt;0 → true；
 *       空 knowIds / count=0 / mapper 抛异常 → false。</li>
 *   <li>{@code buildGbCompliancePrompt()}：返回固定领域无关字符串（不含电池/钢铁等行业词）。</li>
 *   <li>{@code saveGbAuditLog(...)}：repository.save 抛异常时被吞掉（不冒泡），保证审计失败不破坏聊天。</li>
 * </ol>
 * 完整集成（setTools / appendMessage / TokenStream 审计）按 brief 仅做编译 + 不回归，不做 brittle 全 mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiragChatServiceImplGbHelperTest {

    @InjectMocks
    private AiragChatServiceImpl service;

    @Mock
    private GbCalculationToolBuilder gbCalculationToolBuilder;

    @Mock
    private GbStandardProperties gbStandardProperties;

    @Mock
    private GbStandardMapper gbStandardMapper;

    @Mock
    private GbAuditLogRepository gbAuditLogRepository;

    // ===== isGbStandardKnowledge =====

    /** 命中：mapper 返回 count>0 → true。 */
    @Test
    void isGbStandardKnowledgeShouldReturnTrueWhenMapperCountPositive() throws Exception {
        when(gbStandardMapper.selectCount(any())).thenReturn(3L);
        Boolean result = invokeIsGbStandardKnowledge(Arrays.asList("k1", "k2"));
        assertThat(result).isTrue();
    }

    /** 未命中：mapper 返回 0 → false。 */
    @Test
    void isGbStandardKnowledgeShouldReturnFalseWhenCountZero() throws Exception {
        when(gbStandardMapper.selectCount(any())).thenReturn(0L);
        Boolean result = invokeIsGbStandardKnowledge(Collections.singletonList("k-no-gb"));
        assertThat(result).isFalse();
    }

    /** 空 knowIds → 直接 false，且不查 mapper。 */
    @Test
    void isGbStandardKnowledgeShouldReturnFalseAndSkipMapperWhenKnowIdsEmpty() throws Exception {
        Boolean resultNull = invokeIsGbStandardKnowledge(null);
        Boolean resultEmpty = invokeIsGbStandardKnowledge(Collections.emptyList());
        assertThat(resultNull).isFalse();
        assertThat(resultEmpty).isFalse();
        verify(gbStandardMapper, never()).selectCount(any());
    }

    /** 防御：mapper 抛异常时被吞掉，按 false 返回（不破坏聊天主流程）。 */
    @Test
    void isGbStandardKnowledgeShouldReturnFalseWhenMapperThrows() throws Exception {
        when(gbStandardMapper.selectCount(any())).thenThrow(new RuntimeException("db down"));
        Boolean result = invokeIsGbStandardKnowledge(Collections.singletonList("k1"));
        assertThat(result).isFalse();
    }

    // ===== buildGbCompliancePrompt =====

    /** 合规提示必须含核心规则关键词；同时严格遵守领域无关红线（不含任何具体行业词）。 */
    @Test
    void buildGbCompliancePromptShouldContainCoreRulesAndStayDomainAgnostic() throws Exception {
        String prompt = invokeBuildGbCompliancePrompt();
        assertThat(prompt)
                .contains("query_gb_parameter")          // 规则 2：数值计算强制走工具
                .contains("标准号")                       // 规则 1：引用标准号/条款号
                .contains("条款号")
                .contains("否定")                         // 规则 3：否定/例外语义反转
                .contains("例外")
                .contains("差异")                         // 规则 4：多标准差异
                .contains("版次");                        // 规则 5：现行版/旧版版次
        // RED LINE: 领域无关 —— 不得出现具体行业词汇
        assertThat(prompt).doesNotContain("电池", "钢铁", "锂", "电芯", "过充", "充电");
    }

    /** 合规提示是稳定字符串（每次返回相同内容，便于幂等追加）。 */
    @Test
    void buildGbCompliancePromptShouldBeStableAcrossInvocations() throws Exception {
        String p1 = invokeBuildGbCompliancePrompt();
        String p2 = invokeBuildGbCompliancePrompt();
        assertThat(p1).isEqualTo(p2);
        assertThat(p1).startsWith("你是国标合规助手");
    }

    // ===== saveGbAuditLog 防御 =====

    /** 审计 repository 抛异常时被吞掉，不向上传播（保证聊天主流程不被破坏）。 */
    @Test
    void saveGbAuditLogShouldSwallowRepositoryException() throws Exception {
        when(gbAuditLogRepository.save(any())).thenThrow(new RuntimeException("audit db down"));
        // 不应抛异常
        invokeSaveGbAuditLog("sess-1", "用户问题", "LLM 回答", 100L, true, null);
        verify(gbAuditLogRepository, times(1)).save(any());
    }

    /** 正常路径：调用一次 repository.save。 */
    @Test
    void saveGbAuditLogShouldDelegateToRepositoryOnHappyPath() throws Exception {
        when(gbAuditLogRepository.save(any())).thenReturn(1);
        invokeSaveGbAuditLog("sess-2", "q", "r", 50L, true, "CLAUSE_LOOKUP");
        verify(gbAuditLogRepository, times(1)).save(any());
    }

    // ===== 反射辅助 =====

    private Boolean invokeIsGbStandardKnowledge(List<String> knowIds) throws Exception {
        Method m = AiragChatServiceImpl.class.getDeclaredMethod("isGbStandardKnowledge", List.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(service, knowIds);
    }

    private String invokeBuildGbCompliancePrompt() throws Exception {
        Method m = AiragChatServiceImpl.class.getDeclaredMethod("buildGbCompliancePrompt");
        m.setAccessible(true);
        return (String) m.invoke(service);
    }

    private void invokeSaveGbAuditLog(String sessionId, String userQuery, String llmResponse,
                                      long latencyMs, boolean success, String routingIntent) throws Exception {
        Method m = AiragChatServiceImpl.class.getDeclaredMethod(
                "saveGbAuditLog",
                String.class, String.class, String.class, long.class, boolean.class, String.class);
        m.setAccessible(true);
        m.invoke(service, sessionId, userQuery, llmResponse, latencyMs, success, routingIntent);
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】AiragChatServiceImpl GB 辅助方法测试-----------
