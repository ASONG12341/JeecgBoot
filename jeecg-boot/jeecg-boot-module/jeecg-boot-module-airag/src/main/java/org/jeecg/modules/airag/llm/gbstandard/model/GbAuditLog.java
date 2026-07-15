//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbAuditLog 实体-----------
package org.jeecg.modules.airag.llm.gbstandard.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 国标审计日志
 *
 * @author song
 * @date 2026-07-14
 */
@Schema(description = "国标审计日志")
@Data
@TableName("gb_audit_log")
public class GbAuditLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private String id;

    @Schema(description = "会话 ID")
    private String sessionId;

    @Schema(description = "用户查询")
    private String userQuery;

    @Schema(description = "抽取的意图 (JSON)")
    private String extractedIntent;

    //update-begin---author:song ---date:2026-07-15 for：【GB-RAG v4 P1】GbAuditLog 字段对齐 DDL ---
    @Schema(description = "路由意图 (CLAUSE_LOOKUP / PARAM_QUERY / SEMANTIC_SEARCH)")
    private String routingIntent;

    @Schema(description = "抽取的槽位 (JSON)")
    private String extractedSlots;
    //update-end---author:song ---date:2026-07-15 for：【GB-RAG v4 P1】GbAuditLog 字段对齐 DDL ---

    @Schema(description = "使用的检索通道 (JSON 数组)")
    private String retrievalChannels;

    @Schema(description = "检索到的条款 (JSON)")
    private String retrievedClauses;

    @Schema(description = "LLM 回答")
    private String llmResponse;

    @Schema(description = "引用的来源 (JSON)")
    private String citedSources;

    @Schema(description = "工具调用记录 (JSON)")
    private String toolCalls;

    //update-begin---author:song ---date:2026-07-15 for：【GB-RAG v4 P1】GbAuditLog 字段对齐 DDL ---
    @Schema(description = "端到端耗时 (毫秒)")
    private Integer latencyMs;

    @Schema(description = "是否成功")
    private Boolean success;
    //update-end---author:song ---date:2026-07-15 for：【GB-RAG v4 P1】GbAuditLog 字段对齐 DDL ---

    @Schema(description = "创建时间")
    private Date createdAt;
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbAuditLog 实体-----------
