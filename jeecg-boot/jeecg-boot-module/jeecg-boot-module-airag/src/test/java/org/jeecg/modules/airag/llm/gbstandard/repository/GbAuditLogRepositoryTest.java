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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository 测试-----------
