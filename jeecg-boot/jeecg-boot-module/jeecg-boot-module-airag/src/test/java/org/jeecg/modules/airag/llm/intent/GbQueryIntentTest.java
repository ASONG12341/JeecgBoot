package org.jeecg.modules.airag.llm.intent;

import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GbQueryIntentTest {

    @Test
    void shouldRoundTripNewMetadataFields() {
        GbQueryIntent intent = GbQueryIntent.builder()
                .clauseId("9.2")
                .amendment("2022")
                .status("current")
                .build();
        assertThat(intent.getClauseId()).isEqualTo("9.2");
        assertThat(intent.getAmendment()).isEqualTo("2022");
        assertThat(intent.getStatus()).isEqualTo("current");
    }
}
