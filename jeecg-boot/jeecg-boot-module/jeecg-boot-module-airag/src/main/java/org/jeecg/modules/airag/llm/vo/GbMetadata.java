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

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】GbMetadata 加 4 键（修复 P3 buildMetadataFilter 读但未写的降级）-----------
    public static final String KEY_STANDARD_NO = "standard_no";
    public static final String KEY_PRIMARY_TYPE = "primary_type";
    public static final String KEY_SECONDARY_TYPE = "secondary_type";
    public static final String KEY_CLAUSE_PATH = "clause_path";
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------

    private String chapter;
    private String testType;
    private String nCellsAlias;
    private String clauseId;
    private String amendment;
    private String status;

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】4 个新键字段-----------
    /** 标准号，如 GB 31241（regex 可抽） */
    private String standardNo;
    /** 槽位1-做什么（领域无关；regex 抽不出，由入库管线从 LLM 槽位注入） */
    private String primaryType;
    /** 槽位2-对谁（领域无关；同上） */
    private String secondaryType;
    /** 条款路径，如 9.2.3（regex 可抽） */
    private String clausePath;
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------

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
        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】写入 4 个新键（让 P3 buildMetadataFilter 精排生效）-----------
        putIfNotEmpty(metadata, KEY_STANDARD_NO, standardNo);
        putIfNotEmpty(metadata, KEY_PRIMARY_TYPE, primaryType);
        putIfNotEmpty(metadata, KEY_SECONDARY_TYPE, secondaryType);
        putIfNotEmpty(metadata, KEY_CLAUSE_PATH, clausePath);
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------
    }

    private void putIfNotEmpty(Metadata metadata, String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, value);
        }
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】GbMetadata 结构化 metadata 值对象-----------