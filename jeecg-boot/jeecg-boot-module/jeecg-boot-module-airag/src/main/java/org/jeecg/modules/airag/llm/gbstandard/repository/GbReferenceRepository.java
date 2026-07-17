//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbReferenceRepository接口-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;

import java.util.List;

/**
 * 国标引用关系仓库接口
 * 
 * 提供引用关系数据的检索和查询能力，支持：
 * 1. 按标准查询引用关系
 * 2. 查询被引用关系
 * 3. 引用链路分析
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
public interface GbReferenceRepository {

    /**
     * 查询标准的所有引用关系（引用其他标准）
     * 
     * @param standardId 标准ID
     * @return 引用关系列表
     */
    List<GbReference> findBySourceStandardId(String standardId);

    /**
     * 查询条款的所有引用关系
     * 
     * @param standardId 标准ID
     * @param clausePath 条款路径
     * @return 引用关系列表
     */
    List<GbReference> findBySourceClause(String standardId, String clausePath);

    /**
     * 查询被引用关系（被其他标准引用）
     * 
     * @param standardId 标准ID
     * @return 被引用关系列表
     */
    List<GbReference> findReferencedBy(String standardId);

    /**
     * 查询标准间的引用关系
     * 
     * @param sourceStandardId 源标准ID
     * @param targetStandardId 目标标准ID
     * @return 引用关系列表
     */
    List<GbReference> findBetweenStandards(String sourceStandardId, String targetStandardId);

    /**
     * 按引用类型查询
     * 
     * @param standardId 标准ID
     * @param refType 引用类型（normative_reference/prerequisite/informative）
     * @return 引用关系列表
     */
    List<GbReference> findByRefType(String standardId, String refType);

    /**
     * 查询标准内引用
     * 
     * @param standardId 标准ID
     * @return 标准内引用列表
     */
    List<GbReference> findIntraReferences(String standardId);

    /**
     * 查询跨标准引用
     * 
     * @param standardId 标准ID
     * @return 跨标准引用列表
     */
    List<GbReference> findInterReferences(String standardId);

    /**
     * 按目标标准号查询
     * 
     * @param targetStandardNo 目标标准号
     * @return 引用关系列表
     */
    List<GbReference> findByTargetStandardNo(String targetStandardNo);

    /**
     * 查询引用链路（递归查询所有相关引用）
     * 
     * @param standardId 标准ID
     * @param maxDepth 最大递归深度
     * @return 引用关系列表（包含直接和间接引用）
     */
    List<GbReference> findReferenceChain(String standardId, int maxDepth);

    /**
     * 批量查询引用关系
     * 
     * @param referenceIds 引用ID列表
     * @return 引用关系列表
     */
    List<GbReference> findByIds(List<String> referenceIds);

    /**
     * 统计标准的引用数量
     * 
     * @param standardId 标准ID
     * @return 引用数量
     */
    int countBySourceStandardId(String standardId);

    /**
     * 统计标准的被引用次数
     * 
     * @param standardId 标准ID
     * @return 被引用次数
     */
    int countReferencedBy(String standardId);

    /**
     * 查询引用最多的标准（热门标准）
     * 
     * @param limit 返回数量
     * @return 标准ID列表（按被引用次数降序）
     */
    List<String> findMostReferenced(int limit);

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbReferenceRepository 加 saveBatch（连通 gb_reference 数据源，让 ContextAssembler 有数据可查）-----------
    /**
     * 批量保存引用关系（先删该 standardId 作为 source 的旧数据，再全量插入）。
     *
     * @param standardId 引用方标准 ID（source standard）
     * @param references 引用关系列表
     * @return 插入条数
     */
    int saveBatch(String standardId, List<GbReference> references);
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbReferenceRepository接口-----------