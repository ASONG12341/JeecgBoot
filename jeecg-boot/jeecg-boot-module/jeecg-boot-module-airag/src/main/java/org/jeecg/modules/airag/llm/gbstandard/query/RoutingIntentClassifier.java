//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】规则路由分类器（领域无关词，<1ms）-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 路由意图分类器（规则，<1ms，领域无关）。
 * <p>
 * 第一层意图：决定激活哪些检索通道。与 LLM 槽位抽取（第二层）分离。
 * 关键约束：关键词必须领域无关（数值/公式/计算等跨领域通用词），
 * 不得包含任何特定领域的术语。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component
public class RoutingIntentClassifier {

    /** 条款号正则：4.1.2 / 第4章 / 第四章 / 附录A（领域无关的规范文档通用格式） */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "(第[0-9一二三四五六七八九十百千万]+[章节条])|" +
            "([\\d]+\\.[\\d]+(\\.[\\d]+)?条?)|" +
            "(附录[A-Z])",
            Pattern.CASE_INSENSITIVE
    );

    /** 参数/数值关键词（领域无关：任何技术规范都有数值/公式/计算） */
    private static final List<String> PARAM_KEYWORDS = List.of(
            "数值", "公式", "计算", "等于", "大于", "小于", "范围", "单位", "阈值", "系数", "倍率"
    );

    public RoutingIntent classify(String query) {
        if (query == null || query.trim().isEmpty()) {
            return RoutingIntent.SEMANTIC_SEARCH;
        }
        String normalized = query.trim();
        // 1. 条款查询（正则）
        if (CLAUSE_PATTERN.matcher(normalized).find()) {
            log.debug("[RoutingIntent] CLAUSE_LOOKUP: {}", query);
            return RoutingIntent.CLAUSE_LOOKUP;
        }
        // 2. 参数查询（领域无关关键词）
        for (String kw : PARAM_KEYWORDS) {
            if (normalized.contains(kw)) {
                log.debug("[RoutingIntent] PARAM_QUERY: {}", query);
                return RoutingIntent.PARAM_QUERY;
            }
        }
        // 3. 默认语义搜索
        return RoutingIntent.SEMANTIC_SEARCH;
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】规则路由分类器（领域无关词，<1ms）-----------
