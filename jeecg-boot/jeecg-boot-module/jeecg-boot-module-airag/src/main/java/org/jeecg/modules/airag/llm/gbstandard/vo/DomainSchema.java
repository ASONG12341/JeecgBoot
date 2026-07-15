//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】domain_schema Java POJO（4 槽位的 label/enum/unit，描述性，不参与检索过滤）-----------
package org.jeecg.modules.airag.llm.gbstandard.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 国标领域 Schema（领域无关骨架）
 * <p>
 * 描述 4 个固定语义槽位在本标准中的标签(label)、取值枚举(enumValues)、单位(unit)。
 * 存于 gb_standard.domain_schema JSONB。仅服务 LLM 抽取校验和前端展示，
 * 不参与检索过滤（过滤走 gb_clause 的 4 个固定列 + Skip Scan）。
 * </p>
 *
 * @author song
 * @date 2026-07-15
 */
@Schema(description = "国标领域 Schema（4 槽位的语义描述）")
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DomainSchema implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "槽位1-做什么 的语义描述")
    private Slot primaryType;

    @Schema(description = "槽位2-对谁 的语义描述")
    private Slot secondaryType;

    @Schema(description = "槽位3-多少 的语义描述")
    private Slot quantityValue;

    @Schema(description = "槽位4-什么条件 的语义描述")
    private Slot conditionText;

    /**
     * 单个槽位的语义描述。
     * label: 展示名（如"测试类型"）
     * enumValues: 合法取值列表（可空，表示自由填值）
     * unit: 单位（如"V"/"℃"）
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Slot implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "槽位展示标签")
        private String label;

        @Schema(description = "合法取值枚举（可空）")
        private List<String> enumValues;

        @Schema(description = "单位")
        private String unit;
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】domain_schema Java POJO（4 槽位的 label/enum/unit，描述性，不参与检索过滤）-----------
