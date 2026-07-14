//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClause 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 国标条款实体（层级树，物化路径）
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标条款实体")
@Data
@TableName("gb_clause")
public class GbClause implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "所属标准 ID")
    private String standardId;

    @Schema(description = "条款路径: 9 / 9.2 / 9.2.3")
    private String clausePath;

    @Schema(description = "父条款路径")
    private String parentPath;

    @Schema(description = "深度: 1=章, 2=条, 3=款")
    private Integer depth;

    @Schema(description = "条款标题")
    private String title;

    @Schema(description = "条款类型: normative/informative/scope/reference/definition")
    private String clauseType;

    @Schema(description = "极性: positive/negative/exception")
    private String polarity;

    @Schema(description = "例外针对的条款路径")
    private String exceptionOf;

    @Schema(description = "规范用语强度: mandatory(应)/recommended(宜)/permissible(可)")
    private String requirementStrength;

    @Schema(description = "是否为范围条款(第1章)")
    private Boolean isScope;

    @Schema(description = "是否为附录条款")
    private Boolean isAppendix;

    @Schema(description = "附录编号: A/B/C")
    private String appendixLabel;

    @Schema(description = "条款完整文本")
    private String text;

    @Schema(description = "原文页码")
    private Integer pageNo;

    @Schema(description = "解析置信度: high/medium/low")
    private String confidence;

    @Schema(description = "关联向量库 chunk ID")
    private String chunkId;

    @Schema(description = "条款级动态属性 (JSON)")
    private String metadata;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClause 实体-----------
