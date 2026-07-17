//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbParameterRepository接口-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 国标参数仓库接口
 * 
 * 提供参数数据的检索和查询能力，支持：
 * 1. 按标准/条款查询参数
 * 2. 按参数名检索
 * 3. 按参数值范围检索
 * 4. 参数统计分析
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
public interface GbParameterRepository {

    /**
     * 查询标准下的所有参数
     * 
     * @param standardId 标准ID
     * @return 参数列表
     */
    List<GbParameter> findByStandardId(String standardId);

    /**
     * 查询条款下的所有参数
     * 
     * @param clauseId 条款ID
     * @return 参数列表
     */
    List<GbParameter> findByClauseId(String clauseId);

    /**
     * 按参数名检索（模糊匹配）
     * 
     * @param standardId 标准ID（可选）
     * @param paramName 参数名（支持模糊匹配）
     * @return 参数列表
     */
    List<GbParameter> searchByName(String standardId, String paramName);

    /**
     * 按参数名精确查询
     * 
     * @param standardId 标准ID
     * @param paramName 参数名（精确匹配）
     * @return 参数对象
     */
    GbParameter findByName(String standardId, String paramName);

    /**
     * 按参数值范围检索
     * 
     * @param standardId 标准ID
     * @param minValue 最小值
     * @param maxValue 最大值
     * @return 参数列表
     */
    List<GbParameter> searchByValueRange(String standardId, BigDecimal minValue, BigDecimal maxValue);

    /**
     * 按单位查询参数
     * 
     * @param standardId 标准ID
     * @param unit 单位（如 V/A/℃/min）
     * @return 参数列表
     */
    List<GbParameter> findByUnit(String standardId, String unit);

    /**
     * 查询包含公式的参数
     * 
     * @param standardId 标准ID
     * @return 参数列表
     */
    List<GbParameter> findWithFormula(String standardId);

    /**
     * 查询包含条件的参数
     * 
     * @param standardId 标准ID
     * @return 参数列表
     */
    List<GbParameter> findWithCondition(String standardId);

    /**
     * 批量查询参数
     * 
     * @param paramIds 参数ID列表
     * @return 参数列表
     */
    List<GbParameter> findByIds(List<String> paramIds);

    /**
     * 统计标准下的参数数量
     * 
     * @param standardId 标准ID
     * @return 参数数量
     */
    int countByStandardId(String standardId);

    /**
     * 统计各单位的参数数量
     * 
     * @param standardId 标准ID
     * @return 单位 -> 数量映射
     */
    Map<String, Integer> countByUnit(String standardId);

    /**
     * 查询参数的数值范围
     * 
     * @param standardId 标准ID
     * @param paramName 参数名
     * @return [最小值, 最大值]
     */
    BigDecimal[] getValueRange(String standardId, String paramName);

    /**
     * 查询参数的所有可能值（去重）
     * 
     * @param standardId 标准ID
     * @param paramName 参数名
     * @return 参数值列表
     */
    List<BigDecimal> getDistinctValues(String standardId, String paramName);

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbParameterRepository 加 saveBatch（全量替换写入，连通 gb_parameter 数据源）-----------
    /**
     * 批量保存参数（先删该 standardId 下旧数据，再全量插入）。
     * 让 GbCalculationTool / ParamChannel 有数据可查。
     *
     * @param standardId 标准 ID
     * @param parameters 参数列表
     * @return 插入条数
     */
    int saveBatch(String standardId, List<GbParameter> parameters);
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbParameterRepository接口-----------
