//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbTermDict 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 国标术语同义词表
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标术语同义词表")
@Data
@TableName("gb_term_dict")
public class GbTermDict implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "所属标准 ID, null=全局")
    private String standardId;

    @Schema(description = "规范术语")
    private String canonicalTerm;

    @Schema(description = "同义词列表 (JSON 数组)")
    private String synonyms;

    @Schema(description = "术语定义(从第3章提取)")
    private String definition;

    @Schema(description = "领域标签")
    private String domain;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbTermDict 实体-----------
