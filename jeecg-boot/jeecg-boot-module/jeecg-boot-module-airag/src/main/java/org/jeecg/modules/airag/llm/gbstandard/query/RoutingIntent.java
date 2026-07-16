//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】路由意图枚举（领域无关，规则可判）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

/**
 * 路由意图（规则判断，<1ms，领域无关）。
 * 决定激活哪些检索通道，与 LLM 槽位抽取分离。
 *
 * @author song
 * @date 2026-07-16
 */
public enum RoutingIntent {
    /** 条款查询：用户提到具体条款号（4.1.2 / 第4章 / 附录A） */
    CLAUSE_LOOKUP,
    /** 参数查询：涉及数值/公式/计算（领域无关词） */
    PARAM_QUERY,
    /** 语义搜索：默认 */
    SEMANTIC_SEARCH
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】路由意图枚举（领域无关，规则可判）-----------
