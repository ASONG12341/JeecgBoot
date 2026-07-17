//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbAuditLogMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbAuditLogRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GbAuditLogRepositoryTest {

    @InjectMocks
    private GbAuditLogRepositoryImpl repository;

    @Mock
    private GbAuditLogMapper mapper;

    @Test
    void shouldSaveAuditLogAndSetCreatedAt() {
        GbAuditLog log = new GbAuditLog();
        log.setUserQuery("3S 电池包过充测试阈值");
        log.setRoutingIntent("PARAM_QUERY");
        log.setSuccess(true);

        repository.save(log);

        ArgumentCaptor<GbAuditLog> captor = ArgumentCaptor.forClass(GbAuditLog.class);
        verify(mapper).insert(captor.capture());
        GbAuditLog saved = captor.getValue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUserQuery()).isEqualTo("3S 电池包过充测试阈值");
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（会话/时间区间/成功计数/路由意图分布）-----------
    @Test
    void findBySessionIdShouldDelegateToSelectListAndReturnPassthrough() {
        GbAuditLog a = newLog("sess-1", "CLAUSE_LOOKUP", true, 120, new Date(1000));
        GbAuditLog b = newLog("sess-1", "SEMANTIC_SEARCH", false, 300, new Date(2000));
        when(mapper.selectList(any())).thenReturn(Arrays.asList(b, a));

        List<GbAuditLog> result = repository.findBySessionId("sess-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSessionId()).isEqualTo("sess-1");
        verify(mapper).selectList(any());
    }

    @Test
    void findBySessionIdShouldReturnEmptyListWhenSessionIdBlank() {
        assertThat(repository.findBySessionId("")).isEmpty();
        assertThat(repository.findBySessionId(null)).isEmpty();
    }

    @Test
    void findByTimeRangeShouldReturnPassthroughListWithLimit() {
        GbAuditLog a = newLog("sess-1", "CLAUSE_LOOKUP", true, 120, new Date(2000));
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(a));

        List<GbAuditLog> result = repository.findByTimeRange(new Date(1000), new Date(3000), 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLatencyMs()).isEqualTo(120);
        verify(mapper).selectList(any());
    }

    @Test
    void findByTimeRangeShouldDefaultLimitWhenNonPositive() {
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());

        repository.findByTimeRange(new Date(0), new Date(1), 0);

        verify(mapper).selectList(any());
    }

    @Test
    void countBySuccessShouldReturnSelectCountValue() {
        when(mapper.selectCount(any())).thenReturn(7L);

        long count = repository.countBySuccess(true, new Date(0), new Date(100000));

        assertThat(count).isEqualTo(7L);
        verify(mapper).selectCount(any());
    }

    @Test
    void countByRoutingIntentShouldGroupByRoutingIntentAndCount() {
        GbAuditLog a = newLog("s", "CLAUSE_LOOKUP", true, 1, new Date(1));
        GbAuditLog b = newLog("s", "CLAUSE_LOOKUP", true, 2, new Date(2));
        GbAuditLog c = newLog("s", "PARAM_QUERY", false, 3, new Date(3));
        // 空 routingIntent → 计入 fallback（key = "FALLBACK"）
        GbAuditLog d = newLog("s", null, false, 4, new Date(4));
        GbAuditLog e = newLog("s", "", true, 5, new Date(5));
        when(mapper.selectList(any())).thenReturn(Arrays.asList(a, b, c, d, e));

        Map<String, Long> dist = repository.countByRoutingIntent(new Date(0), new Date(100));

        assertThat(dist).containsEntry("CLAUSE_LOOKUP", 2L);
        assertThat(dist).containsEntry("PARAM_QUERY", 1L);
        assertThat(dist).containsEntry("FALLBACK", 2L);
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（会话/时间区间/成功计数/路由意图分布）-----------

    private GbAuditLog newLog(String sessionId, String routingIntent, boolean success, int latencyMs, Date createdAt) {
        GbAuditLog log = new GbAuditLog();
        log.setSessionId(sessionId);
        log.setRoutingIntent(routingIntent);
        log.setSuccess(success);
        log.setLatencyMs(latencyMs);
        log.setCreatedAt(createdAt);
        return log;
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository 测试-----------
