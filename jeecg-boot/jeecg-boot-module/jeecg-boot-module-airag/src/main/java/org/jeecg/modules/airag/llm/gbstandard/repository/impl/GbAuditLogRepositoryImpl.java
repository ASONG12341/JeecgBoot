//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import org.jeecg.modules.airag.llm.gbstandard.mapper.GbAuditLogMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 国标审计日志 Repository 实现
 *
 * @author song
 * @date 2026-07-15
 */
@Repository
public class GbAuditLogRepositoryImpl implements GbAuditLogRepository {

    @Autowired
    private GbAuditLogMapper mapper;

    @Override
    public int save(GbAuditLog logEntry) {
        if (logEntry.getCreatedAt() == null) {
            logEntry.setCreatedAt(new Date());
        }
        return mapper.insert(logEntry);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
