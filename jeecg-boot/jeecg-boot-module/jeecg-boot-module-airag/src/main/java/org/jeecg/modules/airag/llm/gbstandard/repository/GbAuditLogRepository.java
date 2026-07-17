//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
package org.jeecg.modules.airag.llm.gbstandard.repository;

import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;

import java.util.Date;
import java.util.List;
import java.util.Map;

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

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（L5 评估仪表盘观测层）-----------
    /**
     * 按会话 ID 查询审计日志（按 createdAt 倒序）。
     *
     * @param sessionId 会话 ID
     * @return 该会话的所有审计日志
     */
    List<GbAuditLog> findBySessionId(String sessionId);

    /**
     * 按时间区间查询审计日志（按 createdAt 倒序，限制条数避免大返回）。
     *
     * @param start 起始时间（包含，可为 null 表示不设下界）
     * @param end   结束时间（包含，可为 null 表示不设上界）
     * @param limit 最多返回条数（&lt;=0 时使用默认 1000）
     * @return 时间窗内的审计日志
     */
    List<GbAuditLog> findByTimeRange(Date start, Date end, int limit);

    /**
     * 在时间窗内统计成功 / 失败的审计日志数。
     *
     * @param success 是否成功
     * @param start   起始时间（可为 null）
     * @param end     结束时间（可为 null）
     * @return 命中条数
     */
    long countBySuccess(boolean success, Date start, Date end);

    /**
     * 在时间窗内按 routingIntent 分组统计。
     * <p>
     * 空 / null 的 routingIntent 计入 {@code "FALLBACK"} 桶（用于评估路由稳定性）。
     *
     * @param start 起始时间（可为 null）
     * @param end   结束时间（可为 null）
     * @return key = routingIntent（或 "FALLBACK"），value = 数量
     */
    Map<String, Long> countByRoutingIntent(Date start, Date end);
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（L5 评估仪表盘观测层）-----------
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog Repository-----------
