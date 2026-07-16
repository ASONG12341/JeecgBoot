//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】ParamChannel 单元测试（mock GbParameterRepository）-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ParamChannel 单元测试。
 * 覆盖：PARAM_QUERY 路由命中、quantityValue 槽位命中、参数名来自 QueryIntent.secondaryType、空条件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParamChannelTest {

    @Mock
    private GbParameterRepository parameterRepository;

    @InjectMocks
    private ParamChannel paramChannel;

    private GbParameter sampleParam;

    @BeforeEach
    void setUp() {
        sampleParam = new GbParameter();
        sampleParam.setId("p1");
        sampleParam.setStandardId("std-1");
        sampleParam.setClauseId("c1");
        sampleParam.setParamName("抗拉强度");
        sampleParam.setParamValue(new BigDecimal("500"));
        sampleParam.setUnit("MPa");
        sampleParam.setFormula("F/A");
    }

    @Test
    void getChannelNameShouldReturnParam() {
        assertThat(paramChannel.getChannelName()).isEqualTo("PARAM");
    }

    @Test
    void searchByStandardIdAndParamNameShouldReturnResults() {
        when(parameterRepository.searchByName(eq("std-1"), eq("抗拉强度")))
                .thenReturn(Arrays.asList(sampleParam));

        RetrievalRequest request = new RetrievalRequest();
        request.setStandardId("std-1");
        request.setParameterName("抗拉强度");
        request.setIntent("PARAM_QUERY");

        List<RetrievalResult> results = paramChannel.search(request);

        assertThat(results).hasSize(1);
        RetrievalResult r = results.get(0);
        assertThat(r.getSourceChannel()).isEqualTo("PARAM");
        assertThat(r.getStandardId()).isEqualTo("std-1");
        assertThat(r.getClauseId()).isEqualTo("c1");
        assertThat(r.getTitle()).isEqualTo("抗拉强度");
        assertThat(r.getText()).contains("抗拉强度").contains("500").contains("MPa").contains("F/A");
        assertThat(r.getParameters()).hasSize(1);
        assertThat(r.getParameters().get(0)).containsEntry("paramName", "抗拉强度");
        // 分数非零（结构化命中）
        assertThat(r.getScore()).isGreaterThan(0.0);
    }

    @Test
    void paramNameShouldFallbackToQueryIntentSecondaryType() {
        when(parameterRepository.searchByName(eq("std-1"), eq("拉伸")))
                .thenReturn(Collections.singletonList(sampleParam));

        RetrievalRequest request = new RetrievalRequest();
        request.setStandardId("std-1");
        request.setQuery("拉伸的数值");
        request.setIntent("PARAM_QUERY");
        request.setQueryIntent(QueryIntent.builder()
                .secondaryType("拉伸")
                .quantityValue(new BigDecimal("400"))
                .build());

        List<RetrievalResult> results = paramChannel.search(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSourceChannel()).isEqualTo("PARAM");
        // 验证确实用了 secondaryType 作为 paramName
        org.mockito.Mockito.verify(parameterRepository)
                .searchByName("std-1", "拉伸");
    }

    @Test
    void isApplicableWhenRoutingIntentIsParamQuery() {
        RetrievalRequest request = new RetrievalRequest();
        request.setIntent("PARAM_QUERY");
        assertThat(paramChannel.isApplicable(request)).isTrue();
    }

    @Test
    void isApplicableWhenQuantityValueSlotNonZero() {
        RetrievalRequest request = new RetrievalRequest();
        request.setIntent("SEMANTIC_SEARCH");
        request.setQueryIntent(QueryIntent.builder()
                .quantityValue(new BigDecimal("10"))
                .build());
        assertThat(paramChannel.isApplicable(request)).isTrue();
    }

    @Test
    void isApplicableFalseForSemanticSearchWithoutQuantity() {
        RetrievalRequest request = new RetrievalRequest();
        request.setIntent("SEMANTIC_SEARCH");
        request.setQueryIntent(QueryIntent.builder().build());
        assertThat(paramChannel.isApplicable(request)).isFalse();
    }

    @Test
    void isApplicableFalseWhenNoIntent() {
        RetrievalRequest request = new RetrievalRequest();
        assertThat(paramChannel.isApplicable(request)).isFalse();
    }

    @Test
    void searchWithQuantityButNoParamNameFallsBackToAllStandardParams() {
        when(parameterRepository.findByStandardId("std-1"))
                .thenReturn(Arrays.asList(sampleParam));

        RetrievalRequest request = new RetrievalRequest();
        request.setStandardId("std-1");
        request.setQueryIntent(QueryIntent.builder()
                .quantityValue(new BigDecimal("400"))
                .build());

        List<RetrievalResult> results = paramChannel.search(request);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSourceChannel()).isEqualTo("PARAM");
    }

    @Test
    void searchWithNoCriteriaReturnsEmpty() {
        // 无 standardId 且无 paramName 且无 quantityValue
        RetrievalRequest request = new RetrievalRequest();
        request.setQuery("随便问问");

        List<RetrievalResult> results = paramChannel.search(request);
        assertThat(results).isEmpty();
    }

    @Test
    void searchRepoReturnsNullDoesNotBlowUp() {
        when(parameterRepository.searchByName(any(), any())).thenReturn(null);

        RetrievalRequest request = new RetrievalRequest();
        request.setStandardId("std-1");
        request.setParameterName("x");

        List<RetrievalResult> results = paramChannel.search(request);
        assertThat(results).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】ParamChannel 单元测试-----------
