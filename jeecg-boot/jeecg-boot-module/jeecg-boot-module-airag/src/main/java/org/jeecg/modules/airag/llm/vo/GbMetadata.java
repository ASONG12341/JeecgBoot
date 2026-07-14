package org.jeecg.modules.airag.llm.vo;

import dev.langchain4j.data.document.Metadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】GbMetadata 结构化 metadata 值对象-----------
/**
 * GB 标准结构化 metadata（写入向量库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbMetadata {

    public static final String KEY_CHAPTER = "chapter";
    public static final String KEY_TEST_TYPE = "test_type";
    public static final String KEY_N_CELLS_ALIAS = "n_cells_alias";
    public static final String KEY_CLAUSE_ID = "clause_id";
    public static final String KEY_AMENDMENT = "amendment";
    public static final String KEY_STATUS = "status";

    private String chapter;
    private String testType;
    private String nCellsAlias;
    private String clauseId;
    private String amendment;
    private String status;

    /**
     * 将非空字段写入 LangChain4j Metadata。
     */
    public void applyTo(Metadata metadata) {
        if (metadata == null) {
            return;
        }
        putIfNotEmpty(metadata, KEY_CHAPTER, chapter);
        putIfNotEmpty(metadata, KEY_TEST_TYPE, testType);
        putIfNotEmpty(metadata, KEY_N_CELLS_ALIAS, nCellsAlias);
        putIfNotEmpty(metadata, KEY_CLAUSE_ID, clauseId);
        putIfNotEmpty(metadata, KEY_AMENDMENT, amendment);
        putIfNotEmpty(metadata, KEY_STATUS, status);
    }

    private void putIfNotEmpty(Metadata metadata, String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, value);
        }
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】GbMetadata 结构化 metadata 值对象-----------