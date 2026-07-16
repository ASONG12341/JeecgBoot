package org.jeecg.modules.airag.llm.gbstandard.retrieval.intent;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自适应意图提取器
 * <p>
 * 支持的意图类型：
 * 1. CLAUSE_LOOKUP - 条款查询（如："4.1.2条是什么"）
 * 2. PARAM_QUERY - 参数查询（如："钢材的抗拉强度要求"）
 * 3. REF_TRACE - 引用追溯（如："引用了哪些标准"）
 * 4. SEMANTIC_SEARCH - 语义搜索（如："如何进行强度计算"）
 * 5. TERM_LOOKUP - 术语查询（如："什么是屈服强度"）
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component
public class AdaptiveIntentExtractor {

    /**
     * 条款编号正则：匹配 "4.1.2", "第4章", "附录A" 等
     */
    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
            "(第[一二三四五六七八九十百千万]+[章节条])|" +
            "([\\d]+\\.[\\d]+(\\.[\\d]+)?条?)|" +
            "(附录[A-Z])",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 参数关键词
     */
    private static final List<String> PARAM_KEYWORDS = Arrays.asList(
            "强度", "硬度", "韧性", "抗拉", "屈服", "延伸率", "冲击",
            "温度", "压力", "厚度", "直径", "长度", "宽度",
            "含量", "浓度", "密度", "重量", "载荷"
    );

    /**
     * 引用关键词
     */
    private static final List<String> REF_KEYWORDS = Arrays.asList(
            "引用", "参照", "依据", "符合", "按照",
            "参见", "见", "参考", "符合标准"
    );

    /**
     * 术语关键词
     */
    private static final List<String> TERM_KEYWORDS = Arrays.asList(
            "什么是", "定义", "含义", "解释", "意思",
            "指的是", "是指", "称为", "叫做"
    );

    /**
     * 提取意图
     *
     * @param query 用户查询
     * @return 意图类型
     */
    public String extractIntent(String query) {
        log.debug("[AdaptiveIntentExtractor] 开始提取意图, query={}", query);

        if (query == null || query.trim().isEmpty()) {
            return "SEMANTIC_SEARCH";
        }

        String normalizedQuery = query.trim().toLowerCase();

        // 1. 检测条款查询
        if (isClauseLookup(normalizedQuery)) {
            log.info("[AdaptiveIntentExtractor] 检测到条款查询意图");
            return "CLAUSE_LOOKUP";
        }

        // 2. 检测参数查询
        if (isParamQuery(normalizedQuery)) {
            log.info("[AdaptiveIntentExtractor] 检测到参数查询意图");
            return "PARAM_QUERY";
        }

        // 3. 检测引用追溯
        if (isRefTrace(normalizedQuery)) {
            log.info("[AdaptiveIntentExtractor] 检测到引用追溯意图");
            return "REF_TRACE";
        }

        // 4. 检测术语查询
        if (isTermLookup(normalizedQuery)) {
            log.info("[AdaptiveIntentExtractor] 检测到术语查询意图");
            return "TERM_LOOKUP";
        }

        // 5. 默认为语义搜索
        log.info("[AdaptiveIntentExtractor] 使用默认语义搜索意图");
        return "SEMANTIC_SEARCH";
    }

    /**
     * 检测是否为条款查询
     *
     * @param query 查询文本
     * @return true-是条款查询
     */
    private boolean isClauseLookup(String query) {
        Matcher matcher = CLAUSE_PATTERN.matcher(query);
        return matcher.find();
    }

    /**
     * 检测是否为参数查询
     *
     * @param query 查询文本
     * @return true-是参数查询
     */
    private boolean isParamQuery(String query) {
        // 包含参数关键词
        for (String keyword : PARAM_KEYWORDS) {
            if (query.contains(keyword)) {
                return true;
            }
        }

        // 包含数值范围查询（如："大于", "小于", "范围"）
        if (query.contains("大于") || query.contains("小于") || 
            query.contains("范围") || query.contains("要求")) {
            return true;
        }

        return false;
    }

    /**
     * 检测是否为引用追溯
     *
     * @param query 查询文本
     * @return true-是引用追溯
     */
    private boolean isRefTrace(String query) {
        for (String keyword : REF_KEYWORDS) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测是否为术语查询
     *
     * @param query 查询文本
     * @return true-是术语查询
     */
    private boolean isTermLookup(String query) {
        for (String keyword : TERM_KEYWORDS) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取查询参数
     * <p>
     * 从查询中提取结构化信息，如：
     * - 条款编号
     * - 参数名称
     * - 引用标准号
     * </p>
     *
     * @param query 用户查询
     * @return 查询参数映射
     */
    public RetrievalRequest extractQueryParams(String query) {
        RetrievalRequest request = new RetrievalRequest();
        request.setQuery(query);
        request.setIntent(extractIntent(query));

        // 提取条款编号
        Matcher clauseMatcher = CLAUSE_PATTERN.matcher(query);
        if (clauseMatcher.find()) {
            request.setClauseNumber(clauseMatcher.group());
        }

        // 提取参数名称
        for (String keyword : PARAM_KEYWORDS) {
            if (query.contains(keyword)) {
                request.setParameterName(keyword);
                break;
            }
        }

        return request;
    }
}