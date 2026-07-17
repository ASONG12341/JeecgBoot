//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】SafeArithmeticEvaluator 安全四则运算测试-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SafeArithmeticEvaluatorTest {

    private final SafeArithmeticEvaluator evaluator = new SafeArithmeticEvaluator();

    @Test
    void shouldEvaluateBasicArithmetic() {
        assertThat(evaluator.evaluate("3 * 6.0", Map.of())).contains(new BigDecimal("18.0"));
        assertThat(evaluator.evaluate("2 + 3 * 4", Map.of())).contains(new BigDecimal("14"));
        assertThat(evaluator.evaluate("(2 + 3) * 4", Map.of())).contains(new BigDecimal("20"));
    }

    @Test
    void shouldSubstituteVariables() {
        Optional<BigDecimal> r = evaluator.evaluate("n * 6.0", Map.of("n", new BigDecimal("3")));
        assertThat(r).contains(new BigDecimal("18.0"));
    }

    @Test
    void shouldRejectUnknownVariable() {
        // 未知变量 → empty（不幻觉）
        assertThat(evaluator.evaluate("x * 2", Map.of())).isEmpty();
    }

    @Test
    void shouldRejectFunctionsAndInjection() {
        // 禁止函数调用 / 非法字符 → empty
        assertThat(evaluator.evaluate("Runtime.getRuntime()", Map.of())).isEmpty();
        assertThat(evaluator.evaluate("1; DROP TABLE", Map.of())).isEmpty();
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】SafeArithmeticEvaluator 安全四则运算测试-----------
