package org.jeecg.modules.airag.llm.gbstandard.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;

import java.io.Serializable;
import java.util.List;

/**
 * GB 检索请求
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Schema(description = "GB 检索请求")
@Data
public class RetrievalRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "标准ID（可选，为空时搜索所有标准）")
    private String standardId;

    @Schema(description = "知识库ID列表")
    private List<String> knowledgeIds;

    @Schema(description = "用户查询文本")
    private String query;

    @Schema(description = "检索意图（自动提取或手动指定）")
    private String intent;

    //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RetrievalRequest 增加 queryIntent（LLM 4 槽位精排），与 intent:String（路由用）并存-----------
    @Schema(description = "查询意图（LLM 抽取的 4 槽位 + 通用骨架，供通道精排过滤；与 intent(String，路由用) 分离）")
    private QueryIntent queryIntent;
    //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RetrievalRequest 增加 queryIntent-----------

    @Schema(description = "条款编号（用于条款查询）")
    private String clauseNumber;

    @Schema(description = "参数名称（用于参数查询）")
    private String parameterName;

    @Schema(description = "返回结果数量，默认10")
    private int topK = 10;

    @Schema(description = "相似度阈值，默认0.7")
    private double similarityThreshold = 0.7;

    @Schema(description = "是否启用向量检索")
    private boolean enableVector = true;

    @Schema(description = "是否启用结构化检索")
    private boolean enableStructure = true;

    @Schema(description = "是否启用术语检索")
    private boolean enableTerm = true;

    @Schema(description = "向量检索权重，默认0.4")
    private double vectorWeight = 0.4;

    @Schema(description = "结构化检索权重，默认0.4")
    private double structureWeight = 0.4;

    @Schema(description = "术语检索权重，默认0.2")
    private double termWeight = 0.2;

    @Schema(description = "是否返回详细元数据")
    private boolean includeMetadata = false;

    @Schema(description = "用户ID（用于审计日志）")
    private String userId;

    @Schema(description = "会话ID（用于上下文关联）")
    private String sessionId;
}