//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量抽取结果 VO-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 单个条款的批量抽取结果（1 次 LLM 调用产出多个）。
 * 字段与 GbClause 4 槽位 + 极性 + 参数 + 引用对齐。
 *
 * @author song
 * @date 2026-07-15
 */
@Data
public class BatchExtractResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 对应条款路径，用于回填 */
    private String clausePath;

    /** 4 个领域无关槽位 */
    private String primaryType;
    private String secondaryType;
    private BigDecimal quantityValue;
    private String conditionText;

    /** 极性: positive/negative/exception */
    private String polarity;
    /** 例外针对的条款路径（exception 时填） */
    private String exceptionOf;

    /** 抽取的参数（公式/数值），可空 */
    private List<ParamExtract> parameters;
    /** 抽取的引用关系，可空 */
    private List<RefExtract> references;

    @Data
    public static class ParamExtract implements Serializable {
        private static final long serialVersionUID = 1L;
        private String paramName;
        private String formula;       // 展示串，不 eval
        private BigDecimal paramValue;
        private String unit;
    }

    @Data
    public static class RefExtract implements Serializable {
        private static final long serialVersionUID = 1L;
        private String targetType;    // intra/inter
        private String targetStandardNo;
        private String targetClausePath;
        private String refType;       // prerequisite/normative_reference/informative
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】批量抽取结果 VO-----------
