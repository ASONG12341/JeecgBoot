//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository saveBatch 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbClauseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbClauseRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GbClauseRepositorySaveTest {

    @InjectMocks
    private GbClauseRepositoryImpl repository;

    @Mock
    private GbClauseMapper mapper;

    @Test
    void saveBatchShouldDeleteExistingThenInsertAll() {
        GbClause c1 = new GbClause();
        c1.setClausePath("9.1");
        GbClause c2 = new GbClause();
        c2.setClausePath("9.2");
        when(mapper.insert(any(GbClause.class))).thenReturn(1);

        int count = repository.saveBatch("std-1", List.of(c1, c2));

        assertThat(count).isEqualTo(2);
        // 先删旧数据
        verify(mapper).deleteByStandardId("std-1");
        // 再插两条
        verify(mapper, times(2)).insert(any(GbClause.class));
    }

    @Test
    void saveBatchShouldSetStandardIdIfMissing() {
        GbClause c = new GbClause();
        c.setClausePath("9.1");
        // standardId 未设
        when(mapper.insert(any(GbClause.class))).thenReturn(1);

        repository.saveBatch("std-99", List.of(c));

        verify(mapper).insert(argThat((GbClause clause) -> "std-99".equals(clause.getStandardId())));
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository saveBatch 测试-----------
