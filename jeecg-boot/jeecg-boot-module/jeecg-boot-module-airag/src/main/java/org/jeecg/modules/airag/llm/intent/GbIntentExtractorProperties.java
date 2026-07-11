// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractor 配置属性类，支持 primary/fallback 双模型 + 对比模式开关-----------
package org.jeecg.modules.airag.llm.intent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GB 国标意图解析器配置属性
 *
 * @author song-claude
 * @date 2026-07-11
 */
@Data
@Component
@ConfigurationProperties(prefix = "jeecg.airag.gb-intent-extractor")
public class GbIntentExtractorProperties {

    /**
     * 抽取模型在 airag_model 表中的 name
     */
    private String primaryModelName = "qwen-flash";

    /**
     * 降级抽取模型在 airag_model 表中的 name（当前版本已废弃，保留字段仅作兼容）
     */
    private String fallbackModelName = "qwen-flash";

    /**
     * 每个模型单次请求超时（秒）
     */
    private int timeoutSeconds = 5;

    /**
     * 每个模型失败后的重试次数（0 表示不重试）
     */
    private int maxRetriesPerModel = 1;

    /**
     * 失败时是否返回 null intent；false 则把异常抛给上层（调试用）
     */
    private boolean fallbackToNullOnFailure = true;

    /**
     * 测试阶段 A/B 对比模式：同时调用 plus 和 flash 输出对比报告
     */
    private boolean comparisonMode = false;
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractor 配置属性类，支持 primary/fallback 双模型 + 对比模式开关-----------
