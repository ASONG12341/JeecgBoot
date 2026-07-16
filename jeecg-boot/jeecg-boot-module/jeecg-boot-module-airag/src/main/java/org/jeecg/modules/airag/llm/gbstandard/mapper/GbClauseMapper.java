//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseMapper-----------
package org.jeecg.modules.airag.llm.gbstandard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;

/**
 * 国标条款 Mapper
 *
 * @author song
 * @date 2026-07-14
 */
public interface GbClauseMapper extends BaseMapper<GbClause> {
    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseMapper 加 deleteByStandardId（saveBatch 前置清理）-----------
    /**
     * 按标准 ID 删除该标准下的全部条款（saveBatch 全量替换前清理旧数据）。
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM gb_clause WHERE standard_id = #{standardId}")
    int deleteByStandardId(@org.apache.ibatis.annotations.Param("standardId") String standardId);
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbClauseMapper 加 deleteByStandardId（saveBatch 前置清理）-----------
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 GbClauseMapper-----------
