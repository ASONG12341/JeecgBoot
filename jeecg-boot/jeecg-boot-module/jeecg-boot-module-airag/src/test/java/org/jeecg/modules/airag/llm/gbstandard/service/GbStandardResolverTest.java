//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbStandardResolver 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.service;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GbStandardResolverTest {

    @InjectMocks
    private GbStandardResolver resolver;

    @Mock
    private GbStandardMapper gbStandardMapper;

    @Test
    void shouldResolveStandardIdWhenFound() {
        GbStandard s = new GbStandard();
        s.setId("std-1");
        s.setStatus("current");
        when(gbStandardMapper.selectList(any())).thenReturn(List.of(s));

        Optional<String> id = resolver.resolveStandardId("GB 31241");
        assertThat(id).contains("std-1");
    }

    @Test
    void shouldPreferCurrentStatusWhenMultipleVersions() {
        GbStandard old = new GbStandard();
        old.setId("std-old");
        old.setStatus("superseded");
        GbStandard cur = new GbStandard();
        cur.setId("std-cur");
        cur.setStatus("current");
        when(gbStandardMapper.selectList(any())).thenReturn(List.of(old, cur));

        Optional<String> id = resolver.resolveStandardId("GB 31241");
        assertThat(id).contains("std-cur");
    }

    @Test
    void shouldReturnEmptyWhenNotFound() {
        when(gbStandardMapper.selectList(any())).thenReturn(List.of());
        assertThat(resolver.resolveStandardId("GB 99999")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        assertThat(resolver.resolveStandardId("")).isEmpty();
        assertThat(resolver.resolveStandardId(null)).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbStandardResolver 测试-----------
