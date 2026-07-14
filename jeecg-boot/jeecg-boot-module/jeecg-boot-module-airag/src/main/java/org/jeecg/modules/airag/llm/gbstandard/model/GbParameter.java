//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameter 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 国标参数/公式实体
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标参数/公式实体")
@Data
@TableName("gb_parameter")
public class GbParameter implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "所属标准 ID")
    private String standardId;

    @Schema(description = "所属条款 ID")
    private String clauseId;

    @Schema(description = "参数名称")
    private String paramName;

    @Schema(description = "公式表达式")
    private String formula;

    @Schema(description = "参数值")
    private BigDecimal paramValue;

    @Schema(description = "单位: V/A/℃/min")
    private String unit;

    @Schema(description = "条件表达式")
    private String conditionExpr;

    @Schema(description = "原始文本片段")
    private String sourceText;

    @Schema(description = "扩展属性 (JSON)")
    private String metadata;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameter 实体-----------
