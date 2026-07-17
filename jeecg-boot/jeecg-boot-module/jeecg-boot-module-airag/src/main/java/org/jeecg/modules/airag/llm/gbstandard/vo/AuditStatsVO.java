//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计统计 VO（L5 评估仪表盘返回体）-----------
package org.jeecg.modules.airag.llm.gbstandard.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 审计日志统计 VO（L5 评估仪表盘聚合结果）。
 *
 * <p>统计维度：
 * <ul>
 *   <li>总量 + 成功计数 + 成功率</li>
 *   <li>平均端到端延迟（毫秒，null latency 不计入）</li>
 *   <li>按 routingIntent 分组的命中分布（含 FALLBACK 桶）</li>
 *   <li>fallback 计数（routingIntent 为空 = 路由分类失效或缺失）</li>
 * </ul>
 *
 * @author song
 * @date 2026-07-17
 */
@Schema(description = "审计日志统计（L5 评估仪表盘）")
@Data
public class AuditStatsVO {

    @Schema(description = "时间窗内总查询数")
    private long totalQueries;

    @Schema(description = "成功查询数")
    private long successCount;

    @Schema(description = "成功率 = successCount / totalQueries（无数据时为 0.0）")
    private double successRate;

    @Schema(description = "平均端到端延迟（毫秒，null latency 不计入；无样本时为 0.0）")
    private double avgLatencyMs;

    @Schema(description = "按路由意图分组的命中分布（key = routingIntent 或 \"FALLBACK\"）")
    private Map<String, Long> byRoutingIntent;

    @Schema(description = "fallback 计数（routingIntent 为 null / 空的查询数，用于评估路由稳定性）")
    private long fallbackCount;
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 Task7】审计统计 VO（L5 评估仪表盘返回体）-----------
