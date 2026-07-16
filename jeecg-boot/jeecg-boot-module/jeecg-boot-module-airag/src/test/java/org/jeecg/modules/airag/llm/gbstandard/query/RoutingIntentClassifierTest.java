//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RoutingIntentClassifier 规则路由测试-----------
package org.jeecg.modules.airag.llm.gbstandard.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingIntentClassifierTest {

    private RoutingIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RoutingIntentClassifier();
    }

    @ParameterizedTest
    @CsvSource({
            "4.1.2 条是什么, CLAUSE_LOOKUP",
            "第4章的要求, CLAUSE_LOOKUP",
            "附录A的内容, CLAUSE_LOOKUP",
            "9.2.3, CLAUSE_LOOKUP"
    })
    void shouldClassifyClauseLookup(String query, RoutingIntent expected) {
        assertThat(classifier.classify(query)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "这个参数的数值是多少, PARAM_QUERY",
            "公式怎么计算, PARAM_QUERY",
            "大于多少算合格, PARAM_QUERY"
    })
    void shouldClassifyParamQuery(String query, RoutingIntent expected) {
        assertThat(classifier.classify(query)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "电池组的一般要求",
            "如何进行安全测试",
            "这个标准主要讲什么"
    })
    void shouldClassifySemanticSearchByDefault(String query) {
        assertThat(classifier.classify(query)).isEqualTo(RoutingIntent.SEMANTIC_SEARCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emptyQueryShouldDefaultToSemantic(String query) {
        assertThat(classifier.classify(query)).isEqualTo(RoutingIntent.SEMANTIC_SEARCH);
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】RoutingIntentClassifier 规则路由测试-----------
