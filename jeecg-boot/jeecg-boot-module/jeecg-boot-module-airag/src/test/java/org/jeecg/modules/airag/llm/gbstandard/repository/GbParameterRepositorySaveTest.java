//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbParameterRepository saveBatch 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbParameterMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.impl.GbParameterRepositoryImpl;
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
class GbParameterRepositorySaveTest {

    @InjectMocks
    private GbParameterRepositoryImpl repository;

    @Mock
    private GbParameterMapper parameterMapper;

    @Test
    void saveBatchShouldDeleteExistingThenInsertAll() {
        GbParameter p1 = new GbParameter();
        p1.setParamName("overcharge_threshold");
        GbParameter p2 = new GbParameter();
        p2.setParamName("undervoltage_threshold");
        when(parameterMapper.insert(any(GbParameter.class))).thenReturn(1);

        int count = repository.saveBatch("std-1", List.of(p1, p2));

        assertThat(count).isEqualTo(2);
        verify(parameterMapper).deleteByStandardId("std-1");
        verify(parameterMapper, times(2)).insert(any(GbParameter.class));
    }

    @Test
    void saveBatchShouldSetStandardIdIfMissing() {
        GbParameter p = new GbParameter();
        p.setParamName("threshold");
        when(parameterMapper.insert(any(GbParameter.class))).thenReturn(1);

        repository.saveBatch("std-99", List.of(p));

        verify(parameterMapper).insert(argThat((GbParameter param) -> "std-99".equals(param.getStandardId())));
    }

    @Test
    void saveBatchShouldReturnZeroForEmptyList() {
        assertThat(repository.saveBatch("std-1", List.of())).isEqualTo(0);
        verify(parameterMapper, never()).deleteByStandardId(any());
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
