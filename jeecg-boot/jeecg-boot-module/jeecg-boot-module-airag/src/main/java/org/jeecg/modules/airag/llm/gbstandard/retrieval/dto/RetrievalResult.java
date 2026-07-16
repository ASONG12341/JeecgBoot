//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索结果DTO-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * GB 检索结果
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Schema(description = "GB 检索结果")
@Data
public class RetrievalResult implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "结果ID")
    private String resultId;

    @Schema(description = "标准ID")
    private String standardId;

    @Schema(description = "标准号")
    private String standardNo;

    @Schema(description = "标准名称")
    private String standardName;

    @Schema(description = "条款ID")
    private String clauseId;

    @Schema(description = "条款路径")
    private String clausePath;

    @Schema(description = "条款标题")
    private String title;

    @Schema(description = "条款文本")
    private String text;

    @Schema(description = "条款类型")
    private String clauseType;

    @Schema(description = "规范强度")
    private String requirementStrength;

    @Schema(description = "综合相似度分数")
    private double score;

    @Schema(description = "各通道分数")
    private Map<String, Double> channelScores;

    @Schema(description = "来源通道")
    private String sourceChannel;

    @Schema(description = "关联参数")
    private List<Map<String, Object>> parameters;

    @Schema(description = "关联引用")
    private List<Map<String, Object>> references;

    @Schema(description = "元数据")
    private Map<String, Object> metadata;
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索结果DTO-----------