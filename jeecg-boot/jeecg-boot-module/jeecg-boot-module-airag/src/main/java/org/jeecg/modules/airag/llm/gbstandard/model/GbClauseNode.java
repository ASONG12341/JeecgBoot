//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseNode DTO-----------
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
 * GbDocumentStructureParser 输出的条款树节点（DTO，不直接映射数据库）
 * <p>
 * 解析器将 Markdown 解析为 {@code List<GbClauseNode>} 树结构，
 * 前端确认后由 Phase 2 管线持久化到 gb_clause 表。
 * </p>
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "条款树节点（解析器输出 DTO）")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GbClauseNode implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "条款路径: 9 / 9.2 / 9.2.3")
    private String clausePath;

    @Schema(description = "父条款路径")
    private String parentPath;

    @Schema(description = "深度")
    private int depth;

    @Schema(description = "条款标题")
    private String title;

    @Schema(description = "条款文本")
    private String text;

    @Schema(description = "条款类型: normative/informative/scope/reference/definition")
    private String clauseType;

    @Schema(description = "解析置信度: high/medium/low")
    private String confidence;

    @Schema(description = "原文页码")
    private Integer pageNo;

    @Schema(description = "是否为范围条款")
    private boolean isScope;

    @Schema(description = "是否为附录")
    private boolean isAppendix;

    @Schema(description = "附录编号")
    private String appendixLabel;

    @Schema(description = "子条款")
    @Builder.Default
    private List<GbClauseNode> children = new ArrayList<>();
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseNode DTO-----------
