//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbReferenceRepository saveBatch 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbReferenceMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbReferenceRepositoryImpl;
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
class GbReferenceRepositorySaveTest {

    @InjectMocks
    private GbReferenceRepositoryImpl repository;

    @Mock
    private GbReferenceMapper referenceMapper;

    @Test
    void saveBatchShouldDeleteExistingThenInsertAll() {
        GbReference r1 = new GbReference();
        r1.setTargetType("intra");
        GbReference r2 = new GbReference();
        r2.setTargetType("inter");
        when(referenceMapper.insert(any(GbReference.class))).thenReturn(1);

        int count = repository.saveBatch("std-1", List.of(r1, r2));

        assertThat(count).isEqualTo(2);
        verify(referenceMapper).deleteByStandardId("std-1");
        verify(referenceMapper, times(2)).insert(any(GbReference.class));
    }

    @Test
    void saveBatchShouldSetSourceStandardIdIfMissing() {
        GbReference r = new GbReference();
        when(referenceMapper.insert(any(GbReference.class))).thenReturn(1);

        repository.saveBatch("std-99", List.of(r));

        verify(referenceMapper).insert(argThat((GbReference ref) -> "std-99".equals(ref.getSourceStandardId())));
    }

    @Test
    void saveBatchShouldReturnZeroForEmptyList() {
        assertThat(repository.saveBatch("std-1", List.of())).isEqualTo(0);
        verify(referenceMapper, never()).deleteByStandardId(any());
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
