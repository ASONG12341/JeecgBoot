//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;

/**
 * 国标审计日志 Repository
 *
 * @author song
 * @date 2026-07-15
 */
public interface GbAuditLogRepository {

    /**
     * 保存审计日志（自动设置 createdAt）。
     *
     * @param logEntry 审计日志（createdAt 由实现层填充）
     * @return 影响行数
     */
    int save(GbAuditLog logEntry);
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
