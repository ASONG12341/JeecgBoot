//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReferenceMapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;

/**
 * 国标引用关系 Mapper
 *
 * @author song
 * @date 2026-07-14
 */
public interface GbReferenceMapper extends BaseMapper<GbReference> {
    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbReferenceMapper 加 deleteByStandardId（saveBatch 前置清理，按 source_standard_id）-----------
    /**
     * 按 source standard ID 删除该标准作为引用方的全部引用关系。
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM gb_reference WHERE source_standard_id = #{standardId}")
    int deleteByStandardId(@org.apache.ibatis.annotations.Param("standardId") String standardId);
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】GbReferenceMapper 加 deleteByStandardId-----------
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbReferenceMapper-----------
