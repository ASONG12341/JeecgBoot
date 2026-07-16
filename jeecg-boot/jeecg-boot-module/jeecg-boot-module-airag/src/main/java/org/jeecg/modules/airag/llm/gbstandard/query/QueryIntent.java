//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】领域无关意图 POJO（通用骨架 + 4 槽位）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import dev.langchain4j.model.output.structured.Description;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 国标检索意图（领域无关）。
 * <p>
 * 通用骨架字段：所有技术规范共享（standardNo/clauseId/version/objectType/isBooleanQuery）。
 * 4 个固定语义槽位：做什么/对谁/多少/什么条件，值由 LLM 按 domain_schema 填充。
 * 替代旧 GbQueryIntent（电池专属 POJO）。领域差异全部下沉到 domain_schema 数据。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntent implements Serializable {
    private static final long serialVersionUID = 1L;

    // ===== 通用骨架字段（所有标准共享）=====

    @Description("GB 标准号，如 GB 31241 / GB/T 31467.3；未提及则 null")
    private String standardNo;

    @Description("条款号，如 '9.2' / '8.3.1'；未提及则 null")
    private String clauseId;

    @Description("标准版次/年份，如 '2022'；未提及则 null")
    private String version;

    @Description("对象类型（领域无关，具体含义由 domain_schema 定义）；未提及则 null")
    private String objectType;

    @Description("极性：true=用户问'能否/是否'；false=用户问'如何/怎么'；未提及则 null")
    private Boolean isBooleanQuery;

    // ===== 4 个固定语义槽位（领域无关）=====

    @Description("槽位1-做什么：测试类型/功能类别/操作类别（领域无关，具体取值由 domain_schema 约束）")
    private String primaryType;

    @Description("槽位2-对谁：对象/适用物/被测组件（领域无关）")
    private String secondaryType;

    @Description("槽位3-多少：量值（领域无关）")
    private BigDecimal quantityValue;

    @Description("槽位4-什么条件：环境/状态/前提条件（领域无关）")
    private String conditionText;
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】领域无关意图 POJO（通用骨架 + 4 槽位）-----------
