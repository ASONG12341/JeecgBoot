package org.jeecg.modules.airag.llm.gbstandard.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbClause 4 槽位字段测试-----------
class GbClauseSlotTest {

    @Test
    void shouldHoldFourSemanticSlots() {
        GbClause clause = new GbClause();
        clause.setPrimaryType("overcharge");
        clause.setSecondaryType("pack");
        clause.setQuantityValue(new BigDecimal("3"));
        clause.setConditionText("25±5℃");

        assertThat(clause.getPrimaryType()).isEqualTo("overcharge");
        assertThat(clause.getSecondaryType()).isEqualTo("pack");
        assertThat(clause.getQuantityValue()).isEqualByComparingTo("3");
        assertThat(clause.getConditionText()).isEqualTo("25±5℃");
    }

    @Test
    void slotsShouldDefaultToNullForDomainAgnosticEmptyClause() {
        GbClause clause = new GbClause();
        assertThat(clause.getPrimaryType()).isNull();
        assertThat(clause.getSecondaryType()).isNull();
        assertThat(clause.getQuantityValue()).isNull();
        assertThat(clause.getConditionText()).isNull();
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbClause 4 槽位字段测试-----------
