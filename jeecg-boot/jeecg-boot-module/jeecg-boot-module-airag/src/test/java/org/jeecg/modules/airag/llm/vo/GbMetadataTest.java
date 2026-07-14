package org.jeecg.modules.airag.llm.vo;

import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】GbMetadata 单元测试-----------
class GbMetadataTest {

    @Test
    void shouldWriteNonEmptyFieldsOnly() {
        GbMetadata gbMetadata = GbMetadata.builder()
                .chapter("9")
                .testType("overcharge")
                .status("current")
                .build();
        Metadata metadata = Metadata.metadata("existing", "value");
        gbMetadata.applyTo(metadata);

        assertThat(metadata.getString(GbMetadata.KEY_CHAPTER)).isEqualTo("9");
        assertThat(metadata.getString(GbMetadata.KEY_TEST_TYPE)).isEqualTo("overcharge");
        assertThat(metadata.getString(GbMetadata.KEY_STATUS)).isEqualTo("current");
        assertThat(metadata.getString(GbMetadata.KEY_CLAUSE_ID)).isNull();
        assertThat(metadata.getString("existing")).isEqualTo("value");
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】GbMetadata 单元测试-----------