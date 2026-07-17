package org.jeecg.modules.airag.llm.handler;

import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 防御路径测试-----------
/**
 * EmbeddingHandler.embedClauses 防御路径单元测试。
 * <p>
 * embedClauses 的完整向量化路径依赖真实 EmbeddingStore + 真实 EmbeddingModel（PgVector + DashScope），
 * 无法在单测里跑通；本测试只覆盖早返回（early-return）防御路径，确保：
 *  - null/blank knowId 不抛异常（直接返回）
 *  - 空 clause 列表不抛异常（直接返回）
 *  - clauses 为 null 不抛异常（直接返回）
 * 这两条路径不会触碰 embeddingStore / embeddingModel，故无需 mock 它们。
 * 完整路径（resolve model → delete old → build segments → batchEmbedAll → addAll）由集成测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingHandlerEmbedClausesTest {

    @InjectMocks
    private EmbeddingHandler embeddingHandler;

    @Test
    void embedClausesWithBlankKnowIdShouldReturnWithoutThrowing() {
        // null knowId + 非空 clauses：应早返回，不抛异常，不触碰 store
        GbClause clause = new GbClause();
        clause.setStandardId("s1");
        clause.setClausePath("9.2");
        clause.setText("过压充电保护");
        assertThatCode(() -> embeddingHandler.embedClauses(null, "GB 31241", List.of(clause)))
                .as("null knowId 应早返回，不抛异常")
                .doesNotThrowAnyException();

        // blank knowId 同理
        assertThatCode(() -> embeddingHandler.embedClauses("  ", "GB 31241", List.of(clause)))
                .as("blank knowId 应早返回，不抛异常")
                .doesNotThrowAnyException();
    }

    @Test
    void embedClausesWithNullOrEmptyClausesShouldReturnWithoutThrowing() {
        // 空 clause 列表：应早返回，不抛异常，不触碰 store
        assertThatCode(() -> embeddingHandler.embedClauses("know-1", "GB 31241", Collections.emptyList()))
                .as("空 clauses 应早返回，不抛异常")
                .doesNotThrowAnyException();

        // null clauses 同理（防御性：接口契约要求 non-null，但实际可能传 null）
        assertThatCode(() -> embeddingHandler.embedClauses("know-1", "GB 31241", null))
                .as("null clauses 应早返回，不抛异常")
                .doesNotThrowAnyException();
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5 Task 3】embedClauses 防御路径测试-----------
