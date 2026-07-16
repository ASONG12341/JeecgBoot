//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】ContextAssembler 单元测试（mock GbReferenceRepository）-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval;

import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbReferenceRepository;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 单元测试。
 * 覆盖：引用条款被追加为 [引用上下文]、引用标注格式化、不修改 score、无定位信息原样返回、空引用原样返回。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAssemblerTest {

    @Mock
    private GbReferenceRepository referenceRepository;

    @InjectMocks
    private ContextAssembler contextAssembler;

    private GbReference sampleRef;

    @BeforeEach
    void setUp() {
        sampleRef = new GbReference();
        sampleRef.setId("r1");
        sampleRef.setSourceStandardId("std-1");
        sampleRef.setSourceClausePath("9.2");
        sampleRef.setTargetStandardNo("GB/T 31467.3");
        sampleRef.setTargetClausePath("5.1.2");
        sampleRef.setRefType("normative_reference");
        sampleRef.setRefText("电池组过压保护阈值应符合 GB/T 31467.3 第5.1.2条要求。");
    }

    @Test
    void enhanceAppendsReferenceContextAndCitationWithoutChangingScore() {
        when(referenceRepository.findBySourceClause(eq("std-1"), eq("9.2")))
                .thenReturn(Collections.singletonList(sampleRef));

        RetrievalResult input = new RetrievalResult();
        input.setStandardId("std-1");
        input.setClausePath("9.2");
        input.setText("原文条款内容。");
        double originalScore = 0.92;
        input.setScore(originalScore);

        List<RetrievalResult> out = contextAssembler.enhance(Collections.singletonList(input));

        assertThat(out).hasSize(1);
        RetrievalResult r = out.get(0);

        // 关键：score 不变（引用上下文不参与打分）
        assertThat(r.getScore()).isEqualTo(originalScore);

        // references 结构化字段被填充
        assertThat(r.getReferences()).hasSize(1);
        assertThat(r.getReferences().get(0))
                .containsEntry("targetStandardNo", "GB/T 31467.3")
                .containsEntry("targetClausePath", "5.1.2");

        // 文本被追加：引用标注 + [引用上下文]
        assertThat(r.getText()).contains("原文条款内容。")
                .contains("[引用]")
                .contains("GB/T 31467.3")
                .contains("§5.1.2")
                .contains("[引用上下文]")
                .contains("电池组过压保护阈值");
    }

    @Test
    void citationFormatShouldIncludeStandardNoAndClause() {
        when(referenceRepository.findBySourceClause(eq("std-1"), eq("9.2")))
                .thenReturn(Collections.singletonList(sampleRef));

        RetrievalResult input = baseLocatedResult();
        contextAssembler.enhance(Collections.singletonList(input));

        // 引用标注格式：[GB/T 31467.3] §5.1.2（无 version/page 字段则省略）
        assertThat(input.getText()).contains("[GB/T 31467.3] §5.1.2");
    }

    @Test
    void enhanceShouldHandleMultipleReferences() {
        GbReference ref2 = new GbReference();
        ref2.setId("r2");
        ref2.setSourceStandardId("std-1");
        ref2.setSourceClausePath("9.2");
        ref2.setTargetStandardNo("GB 31241");
        ref2.setTargetClausePath("7.3");
        ref2.setRefText("参见 GB 31241 7.3。");

        when(referenceRepository.findBySourceClause(eq("std-1"), eq("9.2")))
                .thenReturn(Arrays.asList(sampleRef, ref2));

        RetrievalResult input = baseLocatedResult();
        contextAssembler.enhance(Collections.singletonList(input));

        assertThat(input.getReferences()).hasSize(2);
        assertThat(input.getText()).contains("[GB/T 31467.3] §5.1.2").contains("[GB 31241] §7.3");
    }

    @Test
    void resultWithoutStandardIdShouldPassThroughUnchanged() {
        RetrievalResult input = new RetrievalResult();
        input.setClausePath("9.2");
        input.setText("无标准ID。");
        input.setScore(0.5);

        List<RetrievalResult> out = contextAssembler.enhance(Collections.singletonList(input));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getText()).isEqualTo("无标准ID。");
        assertThat(out.get(0).getReferences()).isNull();
        verify(referenceRepository, never()).findBySourceClause(any(), any());
    }

    @Test
    void resultWithoutClausePathShouldPassThroughUnchanged() {
        RetrievalResult input = new RetrievalResult();
        input.setStandardId("std-1");
        input.setText("无条款路径。");
        input.setScore(0.5);

        List<RetrievalResult> out = contextAssembler.enhance(Collections.singletonList(input));
        assertThat(out.get(0).getText()).isEqualTo("无条款路径。");
    }

    @Test
    void noReferencesFoundShouldLeaveResultUnchanged() {
        when(referenceRepository.findBySourceClause(eq("std-1"), eq("9.2")))
                .thenReturn(Collections.emptyList());

        RetrievalResult input = baseLocatedResult();
        contextAssembler.enhance(Collections.singletonList(input));

        assertThat(input.getReferences()).isNull();
        assertThat(input.getText()).isEqualTo("原文条款内容。");
    }

    @Test
    void emptyOrNullInputShouldReturnSafely() {
        assertThat(contextAssembler.enhance(null)).isNull();
        assertThat(contextAssembler.enhance(Collections.emptyList())).isEmpty();
    }

    @Test
    void multipleResultsEnhancedIndependently() {
        when(referenceRepository.findBySourceClause(eq("std-1"), eq("9.2")))
                .thenReturn(Collections.singletonList(sampleRef));
        when(referenceRepository.findBySourceClause(eq("std-2"), eq("4.1")))
                .thenReturn(Collections.emptyList());

        RetrievalResult r1 = new RetrievalResult();
        r1.setStandardId("std-1");
        r1.setClausePath("9.2");
        r1.setText("a");
        r1.setScore(0.9);

        RetrievalResult r2 = new RetrievalResult();
        r2.setStandardId("std-2");
        r2.setClausePath("4.1");
        r2.setText("b");
        r2.setScore(0.8);

        List<RetrievalResult> out = contextAssembler.enhance(Arrays.asList(r1, r2));
        assertThat(out).hasSize(2);
        // r1 命中引用，文本被扩充；r2 无引用，原样
        assertThat(out.get(0).getText()).contains("[引用上下文]");
        assertThat(out.get(1).getText()).isEqualTo("b");
        // 两者 score 都不变
        assertThat(out.get(0).getScore()).isEqualTo(0.9);
        assertThat(out.get(1).getScore()).isEqualTo(0.8);
    }

    private RetrievalResult baseLocatedResult() {
        RetrievalResult r = new RetrievalResult();
        r.setStandardId("std-1");
        r.setClausePath("9.2");
        r.setText("原文条款内容。");
        r.setScore(0.92);
        return r;
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】ContextAssembler 单元测试-----------
