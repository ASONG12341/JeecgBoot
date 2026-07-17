//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameterMapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;

/**
 * 国标参数/公式 Mapper
 *
 * @author song
 * @date 2026-07-14
 */
public interface GbParameterMapper extends BaseMapper<GbParameter> {
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbParameterMapper 加 deleteByStandardId（saveBatch 前置清理）-----------
    /**
     * 按标准 ID 删除该标准下的全部参数（saveBatch 全量替换前清理旧数据）。
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM gb_parameter WHERE standard_id = #{standardId}")
    int deleteByStandardId(@org.apache.ibatis.annotations.Param("standardId") String standardId);
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbParameterMapper 加 deleteByStandardId-----------
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbParameterMapper-----------
