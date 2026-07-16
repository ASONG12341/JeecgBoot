//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntent 领域无关意图 POJO 测试-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class QueryIntentTest {

    @Test
    void shouldHoldGenericSkeletonAndFourSlots() {
        QueryIntent intent = QueryIntent.builder()
                .standardNo("GB 31241")
                .clauseId("9.2")
                .version("2022")
                .objectType("pack")
                .isBooleanQuery(false)
                .primaryType("overcharge")      // 测试数据用电池词，但字段本身领域无关
                .secondaryType("pack")
                .quantityValue(new BigDecimal("3"))
                .conditionText("25±5℃")
                .build();

        assertThat(intent.getStandardNo()).isEqualTo("GB 31241");
        assertThat(intent.getClauseId()).isEqualTo("9.2");
        assertThat(intent.getPrimaryType()).isEqualTo("overcharge");
        assertThat(intent.getQuantityValue()).isEqualByComparingTo("3");
    }

    @Test
    void emptyBuilderShouldHaveAllNullSlots() {
        QueryIntent intent = QueryIntent.builder().build();
        assertThat(intent.getPrimaryType()).isNull();
        assertThat(intent.getSecondaryType()).isNull();
        assertThat(intent.getQuantityValue()).isNull();
        assertThat(intent.getConditionText()).isNull();
        assertThat(intent.getStandardNo()).isNull();
    }

    @Test
    void routingIntentShouldHaveThreeValues() {
        assertThat(RoutingIntent.values())
                .containsExactly(RoutingIntent.CLAUSE_LOOKUP, RoutingIntent.PARAM_QUERY, RoutingIntent.SEMANTIC_SEARCH);
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】QueryIntent 领域无关意图 POJO 测试-----------
