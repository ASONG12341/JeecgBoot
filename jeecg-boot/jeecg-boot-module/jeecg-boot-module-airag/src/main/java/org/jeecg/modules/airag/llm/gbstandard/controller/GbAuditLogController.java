//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】GbAuditLogController（L5 评估仪表盘观测层）-----------
package org.jeecg.modules.airag.llm.gbstandard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.gbstandard.model.GbAuditLog;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbAuditLogRepository;
import org.jeecg.modules.airag.llm.gbstandard.vo.AuditStatsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * GB 审计日志控制器 — L5 评估仪表盘观测层。
 * <p>
 * 提供 RAG 管线的可观测性端点，读取 P2（入库）/ P4 Task 5（chat）写入的审计数据。
 * 仅读不写，统计聚合在内存完成（时间窗内全量 + groupBy routingIntent）。
 * </p>
 *
 * <p>所有时间参数以 epoch 毫秒（Long）传入，避免框架 Date 解析不一致；未传 start/end 时
 * 默认取最近 7 天。</p>
 *
 * @author song
 * @date 2026-07-17
 */
@Slf4j
@Tag(name = "GB审计日志", description = "L5 评估仪表盘：RAG 管线审计统计与会话追溯")
@RestController
@RequestMapping("/airag/gb-standard/audit")
public class GbAuditLogController {

    /** 默认时间窗：最近 7 天。 */
    private static final long DEFAULT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    /** 默认 /recent 上限（避免大返回）。 */
    private static final int DEFAULT_RECENT_LIMIT = 50;

    /** 统计聚合时拉取的时间窗硬上限（与 repository 默认值一致）。 */
    private static final int STATS_FETCH_LIMIT = 1000;

    /** 路由意图缺失时使用的分组桶名。 */
    private static final String FALLBACK_BUCKET = "FALLBACK";

    @Autowired
    private GbAuditLogRepository auditLogRepository;

    /**
     * 审计统计聚合（L5 仪表盘主指标）。
     * <p>
     * 返回总量、成功率、平均延迟、路由意图分布、fallback 计数。
     * start / end 任一缺失时按最近 7 天回填。
     *
     * @param start 起始时间（epoch ms，可空）
     * @param end   结束时间（epoch ms，可空）
     * @return 统计 VO
     */
    @Operation(summary = "审计统计", description = "聚合成功率 / 平均延迟 / 路由意图分布等指标，默认最近 7 天")
    @GetMapping("/stats")
    public Result<AuditStatsVO> stats(
            @Parameter(description = "起始时间（epoch ms，默认 7 天前）")
            @RequestParam(required = false) Long start,
            @Parameter(description = "结束时间（epoch ms，默认当前）")
            @RequestParam(required = false) Long end) {

        try {
            long now = System.currentTimeMillis();
            long endMs = (end != null) ? end : now;
            long startMs = (start != null) ? start : (endMs - DEFAULT_WINDOW_MS);
            // 防御：start 不能晚于 end
            if (startMs > endMs) {
                return Result.error("start 不能晚于 end");
            }

            Date startDate = new Date(startMs);
            Date endDate = new Date(endMs);

            List<GbAuditLog> logs = auditLogRepository.findByTimeRange(startDate, endDate, STATS_FETCH_LIMIT);
            long total = logs.size();
            long successCount = auditLogRepository.countBySuccess(true, startDate, endDate);
            long failureCount = auditLogRepository.countBySuccess(false, startDate, endDate);
            // 总量以 count 为准（更准确；findByTimeRange 受 limit 截断）
            if (successCount + failureCount > 0) {
                total = successCount + failureCount;
            }
            double successRate = total > 0 ? (double) successCount / total : 0.0;
            double avgLatency = averageLatency(logs);
            Map<String, Long> byIntent = auditLogRepository.countByRoutingIntent(startDate, endDate);
            long fallback = byIntent.getOrDefault(FALLBACK_BUCKET, 0L);

            AuditStatsVO vo = new AuditStatsVO();
            vo.setTotalQueries(total);
            vo.setSuccessCount(successCount);
            vo.setSuccessRate(successRate);
            vo.setAvgLatencyMs(avgLatency);
            vo.setByRoutingIntent(byIntent);
            vo.setFallbackCount(fallback);

            return Result.OK(vo);
        } catch (Exception e) {
            log.error("[GbAuditLogController] stats 失败: {}", e.getMessage(), e);
            return Result.error("统计失败: " + e.getMessage());
        }
    }

    /**
     * 按会话追溯完整审计轨迹。
     *
     * @param sessionId 会话 ID
     * @return 该会话的所有审计日志（按 createdAt 倒序）
     */
    @Operation(summary = "会话追溯", description = "返回某会话的完整审计轨迹")
    @GetMapping("/session/{sessionId}")
    public Result<List<GbAuditLog>> session(
            @Parameter(description = "会话 ID") @PathVariable String sessionId) {

        if (!StringUtils.hasText(sessionId)) {
            return Result.error("sessionId 不能为空");
        }
        try {
            List<GbAuditLog> logs = auditLogRepository.findBySessionId(sessionId);
            return Result.OK(logs);
        } catch (Exception e) {
            log.error("[GbAuditLogController] session 查询失败: {}", e.getMessage(), e);
            return Result.error("会话追溯失败: " + e.getMessage());
        }
    }

    /**
     * 最近审计日志（默认 50 条，按 createdAt 倒序）。
     * <p>
     * 实质上是无 start/end 限制的最新窗口；通过 limit 控制返回条数。
     * </p>
     *
     * @param limit 返回上限（&lt;=0 时使用默认 50）
     * @return 最近的审计日志列表
     */
    @Operation(summary = "最近审计", description = "返回最近的审计日志（默认 50 条）")
    @GetMapping("/recent")
    public Result<List<GbAuditLog>> recent(
            @Parameter(description = "返回上限（默认 50）")
            @RequestParam(required = false) Integer limit) {

        try {
            int safeLimit = (limit != null && limit > 0) ? limit : DEFAULT_RECENT_LIMIT;
            // recent 不限定时间窗，仅取最新 limit 条（start/end 都不约束 → repository 仅取最新）
            List<GbAuditLog> logs = auditLogRepository.findByTimeRange(null, null, safeLimit);
            return Result.OK(logs);
        } catch (Exception e) {
            log.error("[GbAuditLogController] recent 查询失败: {}", e.getMessage(), e);
            return Result.error("最近审计查询失败: " + e.getMessage());
        }
    }

    /**
     * 计算 latencyMs 平均值（null / 非 Integer 不计入）。
     */
    private double averageLatency(List<GbAuditLog> logs) {
        long sum = 0;
        int count = 0;
        for (GbAuditLog log : logs) {
            Integer latency = log.getLatencyMs();
            if (latency != null) {
                sum += latency;
                count++;
            }
        }
        return count > 0 ? (double) sum / count : 0.0;
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】GbAuditLogController（L5 评估仪表盘观测层）-----------
