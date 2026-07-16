//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索响应DTO-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * GB 检索响应
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Schema(description = "GB 检索响应")
@Data
public class RetrievalResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "查询ID")
    private String queryId;

    @Schema(description = "用户查询")
    private String query;

    @Schema(description = "识别的意图")
    private GbIntentDTO intent;

    @Schema(description = "检索结果列表")
    private List<RetrievalResult> results;

    @Schema(description = "总结果数")
    private int total;

    @Schema(description = "检索耗时（毫秒）")
    private long duration;

    @Schema(description = "元数据")
    private Map<String, Object> metadata;

    //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充编排器设置的响应状态字段-----------
    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "检索统计信息")
    private Map<String, Object> stats;
    //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充编排器设置的响应状态字段-----------

    /**
     * GB 意图 DTO
     */
    @Schema(description = "GB 意图")
    @Data
    public static class GbIntentDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "意图类型")
        private String intentType;

        @Schema(description = "标准号")
        private String standardNo;

        @Schema(description = "条款号")
        private String clauseNo;

        @Schema(description = "参数名")
        private String paramName;

        @Schema(description = "关键词列表")
        private List<String> keywords;

        @Schema(description = "置信度")
        private double confidence;
    }
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索响应DTO-----------