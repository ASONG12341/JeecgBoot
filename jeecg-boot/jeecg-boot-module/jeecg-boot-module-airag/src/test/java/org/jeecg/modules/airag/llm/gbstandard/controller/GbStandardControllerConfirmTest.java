//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardController.confirm 状态机接入入库管线测试-----------
package org.jeecg.modules.airag.llm.gbstandard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.GbDocumentStructureParser;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.GbIngestionPipeline;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeDocMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GbStandardController.confirm 状态机接入入库管线的单元测试。
 * <p>
 * 验证两条关键路径：
 * <ul>
 *   <li>Happy: PARSED → CONFIRMED → INDEXING → pipeline.run → COMPLETED</li>
 *   <li>Failure: pipeline.run 抛异常 → 回滚到 CONFIRMED</li>
 * </ul>
 *
 * <p>状态机顺序通过在 {@code updateById} 上装一个 Answer 记录每次调用时刻的
 * parseStatus 来验证（避免同一 doc 实例被原地修改导致的捕获时序问题，
 * 也避开 MyBatis-Plus {@code updateById} 重载带来的 matcher 歧义）。</p>
 *
 * @author song
 * @date 2026-07-15
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GbStandardControllerConfirmTest {

    @InjectMocks
    private GbStandardController controller;

    @Mock private GbStandardProperties gbStandardProperties;
    @Mock private GbStandardMapper gbStandardMapper;
    @Mock private AiragKnowledgeDocMapper airagKnowledgeDocMapper;
    @Mock private GbDocumentStructureParser structureParser;
    @Mock private GbIngestionPipeline ingestionPipeline;

    private static final String DOC_ID = "doc-001";

    /** 记录每次 updateById 调用时刻 doc 的 parseStatus（时序快照）。 */
    private final List<String> statusSequence = new ArrayList<>();

    private AiragKnowledgeDoc doc;
    private GbStandard gbStandard;
    private GbDocStructure structure;

    @BeforeEach
    void setUp() {
        statusSequence.clear();

        doc = new AiragKnowledgeDoc();
        doc.setId(DOC_ID);
        doc.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
        // 文本类文档：resolveMarkdownContent 直接返回 content，避免触发文件读取
        doc.setType("text");
        doc.setContent("# GB 31241\n9.2 过压充电保护\n");

        gbStandard = new GbStandard();
        gbStandard.setId("std-1");
        gbStandard.setDocId(DOC_ID);
        gbStandard.setMarkdownContent(doc.getContent());

        structure = new GbDocStructure();
        structure.setTotalClauseCount(1);

        // 全局默认桩（lenient 允许某些用例不调用）
        when(gbStandardProperties.isEnabled()).thenReturn(true);
        when(airagKnowledgeDocMapper.selectById(DOC_ID)).thenReturn(doc);
        when(gbStandardMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(gbStandard);
        when(structureParser.parse(anyString())).thenReturn(structure);
        // pipeline.run 默认返回 true（成功）；失败用例会覆盖此桩
        when(ingestionPipeline.run(any(), any())).thenReturn(true);
        // 关键：记录每次 updateById 调用时刻的状态（doc 是原地修改，必须按调用时刻快照）
        when(airagKnowledgeDocMapper.updateById(any(AiragKnowledgeDoc.class))).thenAnswer(inv -> {
            AiragKnowledgeDoc d = inv.getArgument(0);
            statusSequence.add(d.getParseStatus());
            return 1;
        });
    }

    // ==================== Happy path ====================

    @Test
    void confirmHappyPathRunsPipelineAndSetsCompleted() {
        // pipeline.run 成功（默认 doNothing）

        Result<String> result = controller.confirm(DOC_ID);

        assertTrue(result.isSuccess(), "happy path 应返回成功");
        // 状态机终态：COMPLETED
        assertEquals(LLMConsts.PARSE_STATUS_COMPLETED, doc.getParseStatus(),
                "成功路径应将文档状态置为 COMPLETED");
        // 完整状态机顺序：CONFIRMED → INDEXING → COMPLETED
        assertEquals(Arrays.asList(
                        LLMConsts.PARSE_STATUS_CONFIRMED,
                        LLMConsts.PARSE_STATUS_INDEXING,
                        LLMConsts.PARSE_STATUS_COMPLETED),
                statusSequence,
                "happy path 状态机应依次经过 CONFIRMED → INDEXING → COMPLETED");
        // pipeline 被调用一次，传入匹配的 GbStandard + structure
        verify(ingestionPipeline, times(1)).run(eq(gbStandard), eq(structure));
    }

    // ==================== Failure path ====================

    @Test
    void confirmFailureRollsBackToConfirmed() {
        // pipeline 抛异常
        doThrow(new RuntimeException("LLM 抽取失败")).when(ingestionPipeline).run(any(), any());

        Result<String> result = controller.confirm(DOC_ID);

        assertFalse(result.isSuccess(), "失败路径应返回失败 Result");
        // 回滚终态：CONFIRMED
        assertEquals(LLMConsts.PARSE_STATUS_CONFIRMED, doc.getParseStatus(),
                "失败路径应将文档状态回滚到 CONFIRMED");
        // 状态机顺序：CONFIRMED → INDEXING → 回滚 CONFIRMED（不出现 COMPLETED）
        assertEquals(Arrays.asList(
                        LLMConsts.PARSE_STATUS_CONFIRMED,
                        LLMConsts.PARSE_STATUS_INDEXING,
                        LLMConsts.PARSE_STATUS_CONFIRMED),
                statusSequence,
                "失败路径状态机应为 CONFIRMED → INDEXING → 回滚 CONFIRMED，不可出现 COMPLETED");
        // pipeline 仍被调用一次（异常就是它抛的）
        verify(ingestionPipeline, times(1)).run(any(), any());
        // statusSequence 不包含 COMPLETED（防御性断言，证明从未到达成功终态）
        assertFalse(statusSequence.contains(LLMConsts.PARSE_STATUS_COMPLETED),
                "失败路径不应到达 COMPLETED");
    }

    // ==================== Pipeline-swallowed failure path ====================

    @Test
    void confirmFailureRollsBackWhenPipelineReturnsFalse() {
        // pipeline.run 返回 false（内部异常被吞掉，仅记审计）—— 控制器仍应回滚
        when(ingestionPipeline.run(any(), any())).thenReturn(false);

        Result<String> result = controller.confirm(DOC_ID);

        assertFalse(result.isSuccess(), "pipeline 返回 false 时应返回失败 Result");
        assertEquals(LLMConsts.PARSE_STATUS_CONFIRMED, doc.getParseStatus(),
                "pipeline 返回 false 应回滚到 CONFIRMED");
        assertFalse(statusSequence.contains(LLMConsts.PARSE_STATUS_COMPLETED),
                "pipeline 返回 false 不应到达 COMPLETED");
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbStandardController.confirm 状态机接入入库管线测试-----------
