package org.jeecg.modules.airag.llm.extractor;

import org.jeecg.modules.airag.llm.vo.GbMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GbMetadataExtractor 单元测试（覆盖 chapter/testType/amendment/status/nCellsAlias 五条启发式路径，LLM fallback 关闭）-----------
@ExtendWith(MockitoExtension.class)
class GbMetadataExtractorTest {

    @InjectMocks
    private GbMetadataExtractor extractor;

    @Mock
    private org.jeecg.modules.airag.llm.config.KnowConfigBean knowConfigBean;

    @Mock
    private org.jeecg.modules.airag.common.handler.IGbIntentExtractor gbIntentExtractor;

    @Test
    void shouldExtractChapterAndTestTypeFromGbText() {
        String text = "第9章 过压充电测试要求，依据GB 31241-2022";
        GbMetadata meta = extractor.extractFromText(text);
        assertThat(meta.getChapter()).isEqualTo("9");
        assertThat(meta.getTestType()).isEqualTo("过压充电");
        assertThat(meta.getAmendment()).isEqualTo("2022");
        assertThat(meta.getStatus()).isEqualTo("current");
    }

    @Test
    void shouldMarkSuperseded() {
        String text = "本条款已废止";
        GbMetadata meta = extractor.extractFromText(text);
        assertThat(meta.getStatus()).isEqualTo("superseded");
    }

    @Test
    void shouldExtractNCellsAlias() {
        String text = "3S 电池组短路测试";
        GbMetadata meta = extractor.extractFromText(text);
        assertThat(meta.getNCellsAlias()).isEqualTo("3");
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GbMetadataExtractor 单元测试（覆盖 chapter/testType/amendment/status/nCellsAlias 五条启发式路径，LLM fallback 关闭）-----------
