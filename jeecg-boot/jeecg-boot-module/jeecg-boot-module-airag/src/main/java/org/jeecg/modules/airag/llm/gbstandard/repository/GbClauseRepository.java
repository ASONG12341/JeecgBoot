//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbClauseRepository接口-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;

import java.util.List;
import java.util.Map;

/**
 * 国标条款仓库接口
 * 
 * 提供条款数据的检索和查询能力，支持：
 * 1. 条款树查询（按层级结构）
 * 2. 关键词检索
 * 3. 条款编号检索
 * 4. 向量相似度检索
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
public interface GbClauseRepository {

    /**
     * 查询标准下的完整条款树
     * 
     * @param standardId 标准ID
     * @return 条款列表（按 clausePath 排序）
     */
    List<GbClause> findClauseTree(String standardId);

    /**
     * 查询标准下指定深度的条款
     * 
     * @param standardId 标准ID
     * @param depth 深度（1=章, 2=条, 3=款）
     * @return 条款列表
     */
    List<GbClause> findByDepth(String standardId, Integer depth);

    /**
     * 按条款路径查询（精确匹配）
     * 
     * @param standardId 标准ID
     * @param clausePath 条款路径（如 "9.2.3"）
     * @return 条款对象
     */
    GbClause findByClausePath(String standardId, String clausePath);

    /**
     * 按条款路径前缀查询（查询子条款）
     * 
     * @param standardId 标准ID
     * @param parentPath 父条款路径（如 "9.2"）
     * @return 子条款列表
     */
    List<GbClause> findByParentPath(String standardId, String parentPath);

    /**
     * 关键词检索条款
     * 
     * 使用全文检索查询条款标题和文本
     * 
     * @param standardId 标准ID（可选，为null时搜索所有标准）
     * @param keyword 关键词
     * @param limit 返回数量限制
     * @return 匹配的条款列表
     */
    List<GbClause> searchByKeyword(String standardId, String keyword, int limit);

    /**
     * 按条款类型检索
     * 
     * @param standardId 标准ID
     * @param clauseType 条款类型（normative/informative/scope/reference/definition）
     * @return 条款列表
     */
    List<GbClause> findByClauseType(String standardId, String clauseType);

    /**
     * 按规范强度检索
     * 
     * @param standardId 标准ID
     * @param requirementStrength 规范强度（mandatory/recommended/permissible）
     * @return 条款列表
     */
    List<GbClause> findByRequirementStrength(String standardId, String requirementStrength);

    /**
     * 查询范围条款（第1章）
     * 
     * @param standardId 标准ID
     * @return 范围条款
     */
    GbClause findScopeClause(String standardId);

    /**
     * 查询附录条款
     * 
     * @param standardId 标准ID
     * @param appendixLabel 附录编号（A/B/C，为null时查询所有附录）
     * @return 附录条款列表
     */
    List<GbClause> findAppendixClauses(String standardId, String appendixLabel);

    /**
     * 查询例外条款
     * 
     * @param standardId 标准ID
     * @return 例外条款列表
     */
    List<GbClause> findExceptionClauses(String standardId);

    /**
     * 向量相似度检索（需要集成向量数据库）
     * 
     * @param standardId 标准ID（可选）
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @return 相似条款列表（包含相似度分数）
     */
    List<Map<String, Object>> searchByVector(String standardId, float[] queryVector, int topK);

    /**
     * 批量查询条款
     * 
     * @param clauseIds 条款ID列表
     * @return 条款列表
     */
    List<GbClause> findByIds(List<String> clauseIds);

    /**
     * 统计标准下的条款数量
     * 
     * @param standardId 标准ID
     * @return 条款数量
     */
    int countByStandardId(String standardId);

    /**
     * 统计各类型条款数量
     * 
     * @param standardId 标准ID
     * @return 类型 -> 数量映射
     */
    Map<String, Integer> countByClauseType(String standardId);

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository 加 saveBatch（全量替换写入）-----------
    /**
     * 批量保存条款（先删该 standardId 下旧数据，再全量插入）。
     * 用于入库管线：用户确认结构后，把解析+抽取后的条款树一次性写入。
     *
     * @param standardId 标准 ID
     * @param clauses    条款列表（standardId 缺失时由本方法回填）
     * @return 插入条数
     */
    int saveBatch(String standardId, List<GbClause> clauses);
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseRepository 加 saveBatch（全量替换写入）-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbClauseRepository接口-----------
