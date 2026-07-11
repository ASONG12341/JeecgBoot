// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】IGbIntentExtractor 接口：移除 AiServices 子接口，改为低级别 ChatModel API 实现-----------
package org.jeecg.modules.airag.common.handler;

import java.util.List;

/**
 * GB 国标意图解析器接口（v3 §4.3 + §4.3.4）
 *
 * @author song-claude
 * @date 2026-07-11
 */
public interface IGbIntentExtractor {

    /**
     * 主入口：优先走 LLM JSON Mode；失败则降级到全字段 null 的 intent
     *
     * @param userQuery 用户问题（从 messages 最后一条 user message 提取）
     * @param knowIds   知识库 ID 列表（用于日志/可观测性）
     * @return 抽取的 intent；失败时返回全字段 null 的 builder().build()
     */
    GbQueryIntent extractWithFallback(String userQuery, List<String> knowIds);
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】IGbIntentExtractor 接口：移除 AiServices 子接口，改为低级别 ChatModel API 实现-----------
