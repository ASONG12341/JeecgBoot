//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbStandard 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 国标标准实体
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标标准实体")
@Data
@TableName("gb_standard")
public class GbStandard implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "标准号: GB 31241 / GB/T 31467.3")
    private String standardNo;

    @Schema(description = "版本年份: 2022")
    private String version;

    @Schema(description = "标准全称")
    private String fullName;

    @Schema(description = "发布日期")
    private Date publishDate;

    @Schema(description = "实施日期")
    private Date implementationDate;

    @Schema(description = "废止日期, null=现行")
    private Date withdrawalDate;

    @Schema(description = "状态: current/superseded/withdrawn")
    private String status;

    @Schema(description = "领域标签")
    private String domain;

    @Schema(description = "关联知识库 ID")
    private String knowledgeId;

    @Schema(description = "关联文档 ID")
    private String docId;

    @Schema(description = "替代的旧标准列表 (JSON 数组)")
    private String supersedes;

    @Schema(description = "规范性引用文件清单 (JSON 数组)")
    private String normativeRefs;

    @Schema(description = "领域属性 Schema (JSON)")
    private String domainSchema;

    @Schema(description = "扩展元数据 (JSON)")
    private String metadata;

    @Schema(description = "解析状态: UPLOADED/PARSING/PARSED/CONFIRMED/INDEXING/COMPLETED")
    private String parseStatus;

    @Schema(description = "PDF 文件路径")
    private String pdfUrl;

    @Schema(description = "MinerU 解析后的 Markdown")
    private String markdownContent;

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbStandard 实体-----------
