//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 配置属性类 + Kill Switch-----------
package org.jeecg.modules.airag.llm.gbstandard.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GB 国标知识引擎配置属性
 * <p>
 * Kill Switch: {@code jeecg.airag.gb-standard.enabled=false} 时所有国标逻辑跳过
 * </p>
 *
 * @author song
 * @date 2026-07-14
 */
@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = GbStandardProperties.PREFIX)
public class GbStandardProperties {
    public static final String PREFIX = "jeecg.airag.gb-standard";

    /**
     * 总开关：false 时所有国标增强逻辑跳过，回退到通用 RAG 行为
     */
    private boolean enabled = false;

    /**
     * 用户确认页开关：false 时跳过用户确认直接入库（不推荐）
     */
    private boolean userReviewEnabled = true;

    /**
     * 结构解析器配置
     */
    private StructureParser structureParser = new StructureParser();

    @Data
    @NoArgsConstructor
    public static class StructureParser {
        /**
         * 结构解析器开关
         */
        private boolean enabled = true;

        /**
         * 正则失败时是否用 LLM 补全（Phase 2 启用）
         */
        private boolean llmFallback = false;
    }

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】schema-deriver + clause-metadata-extractor 配置子节-----------
    @Data
    public static class SchemaDeriver {
        /** 是否启用 domain_schema 推导（默认 true） */
        private boolean enabled = true;
        /** 推导用的轻量模型名 */
        private String modelName = "qwen-flash";
        /** 单次调用超时（秒） */
        private int timeoutSeconds = 10;
    }

    @Data
    public static class ClauseMetadataExtractor {
        /** 是否启用条款批量抽取（默认 true） */
        private boolean enabled = true;
        /** 每批打包的条款数 */
        private int batchSize = 10;
        /** 抽取用的模型名 */
        private String modelName = "qwen-flash";
        /** 单次调用超时（秒，批量调用给足时间） */
        private int timeoutSeconds = 30;
    }

    private SchemaDeriver schemaDeriver = new SchemaDeriver();
    private ClauseMetadataExtractor clauseMetadataExtractor = new ClauseMetadataExtractor();
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】schema-deriver + clause-metadata-extractor 配置子节-----------
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 配置属性类 + Kill Switch-----------
