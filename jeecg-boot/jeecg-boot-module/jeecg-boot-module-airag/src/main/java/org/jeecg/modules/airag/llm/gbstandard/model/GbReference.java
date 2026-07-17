//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReference 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 国标引用关系实体（标准内 + 跨标准）
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标引用关系实体")
@Data
@TableName("gb_reference")
public class GbReference implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "引用方标准 ID")
    private String sourceStandardId;

    @Schema(description = "引用方条款路径")
    private String sourceClausePath;

    @Schema(description = "引用类型: intra=标准内 / inter=跨标准")
    private String targetType;

    @Schema(description = "目标标准 ID (跨标准时)")
    private String targetStandardId;

    @Schema(description = "目标标准号")
    private String targetStandardNo;

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 延后项 F6】GbReference 加 targetVersion + targetPageNo（让 ContextAssembler 引用格式完整 [标准号 版本] §条款号 (页码)）-----------
    @Schema(description = "目标标准版次/年份，如 2022；可空")
    private String targetVersion;

    @Schema(description = "目标条款页码；可空")
    private Integer targetPageNo;
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 延后项 F6】-----------

    @Schema(description = "目标条款路径")
    private String targetClausePath;

    @Schema(description = "引用关系类型: normative_reference/prerequisite/informative")
    private String refType;

    @Schema(description = "引用上下文原文")
    private String refText;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReference 实体-----------
