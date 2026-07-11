// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbQueryIntent POJO 定义，按 v3.1 §4.3.1（注解 @JsonPropertyDescription → @Description；LangChain4j 1.17.2 schema generator 仅识别 @Description）-----------
package org.jeecg.modules.airag.common.handler;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * GB 国标意图解析结果（v3 §4.3.1，v3.1 patch：@JsonPropertyDescription → @Description）
 *
 * @author song-claude
 * @date 2026-07-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbQueryIntent implements Serializable {
    private static final long serialVersionUID = 1L;

    @Description("GB 标准号，如 GB 31241 / GB 38031 / GB/T 31467.3 / GB 40559；用户未提及则 null")
    private String gbStandard;

    @Description("测试类型，按标准映射（GB 31241/40559: overcharge/external_short_circuit/crush/thermal_shock/drop/nail_penetration；GB/T 31467.3: cycle_life/thermal/vibration/short_circuit；GB 38031: overcharge/over_discharge/short_circuit/heating/crush/nail_penetration）")
    private String testType;

    @Description("电池串数 n_cells。3S / 三串 / 三个电芯 一律抽取为 3；48V/3.7V≈13 则推导出 13（常识推理）")
    @JsonProperty("nCells")
    private Integer nCells;

    @Description("对象类型：cell(单体) / pack(电池组) / system(系统)")
    private String objectType;

    @Description("隐含推导的章号。基于对象类型推断：单体→7章，电池组→9章，系统→10章")
    private String inferredChapter;

    @Description("环境条件，例如 '25±5℃' / 'T=40℃'；若未提及则 null")
    private String environmentCondition;

    @Description("极性：true=用户问'能否/是否'；false=用户问'如何/怎么'")
    private Boolean isBooleanQuery;
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbQueryIntent POJO 定义，按 v3.1 §4.3.1（注解 @JsonPropertyDescription → @Description；LangChain4j 1.17.2 schema generator 仅识别 @Description）-----------
