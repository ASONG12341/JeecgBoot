// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractor 配置属性类：单模型 qwen-flash，移除已废弃的 fallback/comparisonMode 字段-----------
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
     * 每个模型单次请求超时（秒）
     */
    private int timeoutSeconds = 5;

    /**
     * 每个模型失败后的重试次数（0 表示不重试）
     */
    private int maxRetriesPerModel = 1;
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentExtractor 配置属性类：单模型 qwen-flash，移除已废弃的 fallback/comparisonMode 字段-----------
