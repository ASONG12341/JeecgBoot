//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】GbAuditLogController 单元测试-----------
package org.jeecg.modules.airag.llm.gbstandard.controller;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.jeecg.modules.airag.llm.gbstandard.vo.AuditStatsVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GbAuditLogController 单元测试。
 * <p>
 * 不使用 @WebMvcTest（避免拉起 Spring 上下文），直接以 {@code @InjectMocks} 实例化 controller，
 * 通过 Mock repository 验证三件事：
 * <ul>
 *   <li>路由方法被正确调用（参数透传）</li>
 *   <li>VO 统计聚合正确（成功率、avg latency、路由分布、fallback 计数）</li>
 *   <li>Result 封装语义正确（成功路径 OK + 数据；空列表场景也不应失败）</li>
 * </ul>
 *
 * @author song
 * @date 2026-07-17
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbAuditLogControllerTest {

    @InjectMocks
    private GbAuditLogController controller;

    @Mock
    private GbAuditLogRepository repository;

    // ==================== /stats ====================

    @Test
    void statsShouldAggregateSuccessRateAndAvgLatency() {
        // 时间窗内 4 条：3 成功 + 1 失败，latency = [100, 200, 300, 0(null)]
        GbAuditLog ok1 = newAudit("CLAUSE_LOOKUP", true, 100);
        GbAuditLog ok2 = newAudit("CLAUSE_LOOKUP", true, 200);
        GbAuditLog ok3 = newAudit("PARAM_QUERY", true, 300);
        GbAuditLog fail = newAudit(null, false, null);
        List<GbAuditLog> recent = Arrays.asList(ok1, ok2, ok3, fail);

        when(repository.findByTimeRange(any(), any(), anyInt())).thenReturn(recent);
        when(repository.countBySuccess(eq(true), any(), any())).thenReturn(3L);
        when(repository.countBySuccess(eq(false), any(), any())).thenReturn(1L);

        Map<String, Long> dist = new HashMap<>();
        dist.put("CLAUSE_LOOKUP", 2L);
        dist.put("PARAM_QUERY", 1L);
        dist.put("FALLBACK", 1L);
        when(repository.countByRoutingIntent(any(), any())).thenReturn(dist);

        Result<AuditStatsVO> result = controller.stats(null, null);

        assertThat(result.isSuccess()).isTrue();
        AuditStatsVO vo = result.getResult();
        assertThat(vo).isNotNull();
        assertThat(vo.getTotalQueries()).isEqualTo(4L);
        assertThat(vo.getSuccessCount()).isEqualTo(3L);
        // 3 / 4 = 0.75
        assertThat(vo.getSuccessRate()).isEqualTo(0.75);
        // avg of [100, 200, 300] = 200.0（null latency 不计入）
        assertThat(vo.getAvgLatencyMs()).isEqualTo(200.0);
        assertThat(vo.getByRoutingIntent()).containsEntry("CLAUSE_LOOKUP", 2L);
        assertThat(vo.getFallbackCount()).isEqualTo(1L);
    }

    @Test
    void statsShouldReturnZeroAvgLatencyWhenAllNull() {
        List<GbAuditLog> recent = Arrays.asList(
                newAudit("CLAUSE_LOOKUP", true, null),
                newAudit("CLAUSE_LOOKUP", false, null));

        when(repository.findByTimeRange(any(), any(), anyInt())).thenReturn(recent);
        when(repository.countBySuccess(eq(true), any(), any())).thenReturn(1L);
        when(repository.countBySuccess(eq(false), any(), any())).thenReturn(1L);
        when(repository.countByRoutingIntent(any(), any()))
                .thenReturn(Collections.singletonMap("CLAUSE_LOOKUP", 2L));

        Result<AuditStatsVO> result = controller.stats(null, null);

        assertThat(result.isSuccess()).isTrue();
        AuditStatsVO vo = result.getResult();
        assertThat(vo.getAvgLatencyMs()).isEqualTo(0.0);
        assertThat(vo.getSuccessRate()).isEqualTo(0.5);
        assertThat(vo.getFallbackCount()).isEqualTo(0L);
    }

    @Test
    void statsShouldHandleEmptyWindowGracefully() {
        when(repository.findByTimeRange(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(repository.countBySuccess(anyBoolean(), any(), any())).thenReturn(0L);
        when(repository.countByRoutingIntent(any(), any())).thenReturn(Collections.emptyMap());

        Result<AuditStatsVO> result = controller.stats(1L, 2L);

        assertThat(result.isSuccess()).isTrue();
        AuditStatsVO vo = result.getResult();
        assertThat(vo.getTotalQueries()).isZero();
        assertThat(vo.getSuccessRate()).isZero();
        assertThat(vo.getAvgLatencyMs()).isZero();
        assertThat(vo.getByRoutingIntent()).isEmpty();
        assertThat(vo.getFallbackCount()).isZero();
    }

    // ==================== /session/{sessionId} ====================

    @Test
    void sessionShouldReturnLogsForSession() {
        GbAuditLog a = newAudit("CLAUSE_LOOKUP", true, 100);
        when(repository.findBySessionId("sess-1")).thenReturn(Collections.singletonList(a));

        Result<List<GbAuditLog>> result = controller.session("sess-1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).hasSize(1);
        verify(repository).findBySessionId("sess-1");
    }

    @Test
    void sessionShouldReturnErrorWhenSessionIdBlank() {
        Result<List<GbAuditLog>> result = controller.session("");

        assertThat(result.isSuccess()).isFalse();
    }

    // ==================== /recent ====================

    @Test
    void recentShouldDefaultLimitAndReturnList() {
        GbAuditLog a = newAudit("CLAUSE_LOOKUP", true, 100);
        when(repository.findByTimeRange(any(), any(), anyInt())).thenReturn(Collections.singletonList(a));

        Result<List<GbAuditLog>> result = controller.recent(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).hasSize(1);
        verify(repository).findByTimeRange(any(), any(), anyInt());
    }

    @Test
    void recentShouldClampNegativeLimitToDefault() {
        when(repository.findByTimeRange(any(), any(), anyInt())).thenReturn(Collections.emptyList());

        Result<List<GbAuditLog>> result = controller.recent(-5);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEmpty();
        verify(repository).findByTimeRange(any(), any(), anyInt());
    }

    private GbAuditLog newAudit(String routingIntent, Boolean success, Integer latencyMs) {
        GbAuditLog log = new GbAuditLog();
        log.setRoutingIntent(routingIntent);
        log.setSuccess(success);
        log.setLatencyMs(latencyMs);
        log.setCreatedAt(new Date());
        return log;
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】GbAuditLogController 单元测试-----------
