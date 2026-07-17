//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationToolBuilder 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.service.GbStandardResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbCalculationToolBuilderTest {

    @InjectMocks
    private GbCalculationToolBuilder builder;

    @Mock private GbStandardResolver standardResolver;
    @Mock private GbParameterRepository parameterRepository;
    @Mock private SafeArithmeticEvaluator arithmeticEvaluator;

    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        // Inject a real ObjectMapper (InjectMocks won't create it)
        try {
            java.lang.reflect.Field f = GbCalculationToolBuilder.class.getDeclaredField("objectMapper");
            f.setAccessible(true);
            f.set(builder, new ObjectMapper());
        } catch (Exception e) { throw new RuntimeException(e); }
        Map<ToolSpecification, ToolExecutor> tools = builder.buildTools();
        executor = tools.values().iterator().next();
    }

    private String run(String argsJson) {
        return executor.execute(ToolExecutionRequest.builder().arguments(argsJson).build(), "mem-1");
    }

    @Test
    void shouldReturnStaticValueWhenParamValuePresent() {
        when(standardResolver.resolveStandardId("GB 31241")).thenReturn(Optional.of("std-1"));
        GbParameter p = new GbParameter();
        p.setParamValue(new BigDecimal("18.0"));
        p.setUnit("V");
        when(parameterRepository.findByName(eq("std-1"), eq("overcharge_threshold"))).thenReturn(p);

        String result = run("{\"standardNo\":\"GB 31241\",\"paramName\":\"overcharge_threshold\"}");
        assertThat(result).contains("18.0").contains("V").contains("静态值");
    }

    @Test
    void shouldComputeViaFormulaWhenNoStaticValue() {
        when(standardResolver.resolveStandardId("GB 31241")).thenReturn(Optional.of("std-1"));
        GbParameter p = new GbParameter();
        p.setFormula("n * 6.0");
        p.setUnit("V");
        when(parameterRepository.findByName(eq("std-1"), eq("overcharge_threshold"))).thenReturn(p);
        when(arithmeticEvaluator.evaluate(eq("n * 6.0"), any())).thenReturn(Optional.of(new BigDecimal("18.0")));

        String result = run("{\"standardNo\":\"GB 31241\",\"paramName\":\"overcharge_threshold\",\"variables\":\"{\\\"n\\\":3}\"}");
        assertThat(result).contains("18.0").contains("公式");
    }

    @Test
    void shouldReturnNotFoundWhenStandardMissing() {
        when(standardResolver.resolveStandardId("GB 99999")).thenReturn(Optional.empty());
        String result = run("{\"standardNo\":\"GB 99999\",\"paramName\":\"x\"}");
        assertThat(result).contains("未找到标准");
    }

    @Test
    void shouldReturnNotFoundWhenParamMissing() {
        when(standardResolver.resolveStandardId("GB 31241")).thenReturn(Optional.of("std-1"));
        when(parameterRepository.findByName(eq("std-1"), eq("missing"))).thenReturn(null);
        String result = run("{\"standardNo\":\"GB 31241\",\"paramName\":\"missing\"}");
        assertThat(result).contains("未找到参数");
    }

    @Test
    void toolSpecShouldHaveCorrectNameAndRequiredFields() {
        Map<ToolSpecification, ToolExecutor> tools = builder.buildTools();
        ToolSpecification spec = tools.keySet().iterator().next();
        assertThat(spec.name()).isEqualTo("query_gb_parameter");
        assertThat(spec.parameters().required()).containsExactlyInAnyOrder("standardNo", "paramName");
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationToolBuilder 测试-----------
