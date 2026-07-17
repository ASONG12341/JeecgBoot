package org.jeecg.modules.airag.llm.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KnowConfigBeanTest {

    @Test
    void shouldHaveSafeDefaults() {
        KnowConfigBean bean = new KnowConfigBean();
        assertThat(bean.isHybridSearch()).isFalse();
        assertThat(bean.getTextSearchConfig()).isEqualTo("simple");
        assertThat(bean.getRrfK()).isEqualTo(60);
        assertThat(bean.isQueryExpansionEnabled()).isFalse();
    }
}