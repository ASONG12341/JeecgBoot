//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbAuditLogMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 国标审计日志 Repository 实现
 *
 * @author song
 * @date 2026-07-15
 */
@Repository
public class GbAuditLogRepositoryImpl implements GbAuditLogRepository {

    /** 默认时间窗查询上限（避免大返回）。 */
    private static final int DEFAULT_TIME_RANGE_LIMIT = 1000;

    /** 路由意图缺失时使用的分组桶名（评估 fallback 触发率）。 */
    private static final String FALLBACK_BUCKET = "FALLBACK";

    @Autowired
    private GbAuditLogMapper mapper;

    @Override
    public int save(GbAuditLog logEntry) {
        if (logEntry.getCreatedAt() == null) {
            logEntry.setCreatedAt(new Date());
        }
        return mapper.insert(logEntry);
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（L5 评估仪表盘观测层）-----------
    @Override
    public List<GbAuditLog> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<GbAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbAuditLog::getSessionId, sessionId)
                .orderByDesc(GbAuditLog::getCreatedAt);
        return mapper.selectList(wrapper);
    }

    @Override
    public List<GbAuditLog> findByTimeRange(Date start, Date end, int limit) {
        int safeLimit = limit > 0 ? limit : DEFAULT_TIME_RANGE_LIMIT;
        LambdaQueryWrapper<GbAuditLog> wrapper = new LambdaQueryWrapper<>();
        applyTimeRange(wrapper, start, end);
        wrapper.orderByDesc(GbAuditLog::getCreatedAt)
                .last("LIMIT " + safeLimit);
        return mapper.selectList(wrapper);
    }

    @Override
    public long countBySuccess(boolean success, Date start, Date end) {
        LambdaQueryWrapper<GbAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbAuditLog::getSuccess, success);
        applyTimeRange(wrapper, start, end);
        return mapper.selectCount(wrapper);
    }

    @Override
    public Map<String, Long> countByRoutingIntent(Date start, Date end) {
        // 时间窗内全量拉取（受 DEFAULT_TIME_RANGE_LIMIT 限制），内存分组
        // （参考 GbClauseRepositoryImpl.countByClauseType 的 in-memory 聚合模式）
        List<GbAuditLog> logs = findByTimeRange(start, end, DEFAULT_TIME_RANGE_LIMIT);
        return logs.stream()
                .collect(Collectors.groupingBy(
                        this::routingBucketKey,
                        Collectors.counting()));
    }

    /**
     * 路由桶 key：空 / null 的 routingIntent 归入 {@link #FALLBACK_BUCKET}，
     * 用于评估路由稳定性（fallback 比例高 = 路由分类失效）。
     */
    private String routingBucketKey(GbAuditLog log) {
        String intent = log.getRoutingIntent();
        return (intent == null || intent.isEmpty()) ? FALLBACK_BUCKET : intent;
    }

    /**
     * 为 wrapper 追加时间窗条件：start/end 任一为 null 时仅约束存在的端，
     * 避免使用 {@code between}（要求两端非 null）。
     */
    private void applyTimeRange(LambdaQueryWrapper<GbAuditLog> wrapper, Date start, Date end) {
        if (start != null) {
            wrapper.ge(GbAuditLog::getCreatedAt, start);
        }
        if (end != null) {
            wrapper.le(GbAuditLog::getCreatedAt, end);
        }
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计日志查询方法（L5 评估仪表盘观测层）-----------
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】GbAuditLog RepositoryImpl-----------
