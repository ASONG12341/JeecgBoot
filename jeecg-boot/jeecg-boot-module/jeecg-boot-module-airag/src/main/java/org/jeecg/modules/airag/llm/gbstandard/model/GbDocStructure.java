//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbDocStructure DTO-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * GbDocumentStructureParser 输出的文档结构（DTO）
 * <p>
 * 包含标准基本信息 + 条款层级树 + 置信度统计，
 * 作为 Preview API 的返回值供前端展示。
 * </p>
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标文档结构（解析器输出 DTO）")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbDocStructure implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "标准号")
    private String standardNo;

    @Schema(description = "版本年份")
    private String version;

    @Schema(description = "标准全称")
    private String fullName;

    @Schema(description = "发布日期")
    private String publishDate;

    @Schema(description = "实施日期")
    private String implementationDate;

    @Schema(description = "替代的旧标准")
    @Builder.Default
    private List<String> supersedes = new ArrayList<>();

    @Schema(description = "规范性引用文件清单")
    @Builder.Default
    private List<String> normativeRefs = new ArrayList<>();

    @Schema(description = "条款层级树（根节点列表）")
    @Builder.Default
    private List<GbClauseNode> clauses = new ArrayList<>();

    @Schema(description = "条款总数")
    private int totalClauseCount;

    @Schema(description = "高置信度条款数")
    private int highConfidenceCount;

    @Schema(description = "中置信度条款数")
    private int mediumConfidenceCount;

    @Schema(description = "低置信度条款数")
    private int lowConfidenceCount;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbDocStructure DTO-----------
