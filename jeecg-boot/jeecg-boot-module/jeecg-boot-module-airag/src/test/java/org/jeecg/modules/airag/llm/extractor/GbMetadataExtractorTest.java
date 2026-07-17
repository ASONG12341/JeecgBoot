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

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】standardNo + clausePath 抽取测试-----------
    @Test
    void shouldExtractStandardNo() {
        String text = "依据 GB 31241-2022 第9章要求";
        GbMetadata meta = extractor.extractFromText(text);
        assertThat(meta.getStandardNo()).contains("GB31241");
    }

    @Test
    void shouldExtractClausePath() {
        String text = "过压充电保护见 9.2.3 条";
        GbMetadata meta = extractor.extractFromText(text);
        assertThat(meta.getClausePath()).isEqualTo("9.2.3");
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GbMetadataExtractor 单元测试（覆盖 chapter/testType/amendment/status/nCellsAlias 五条启发式路径，LLM fallback 关闭）-----------
