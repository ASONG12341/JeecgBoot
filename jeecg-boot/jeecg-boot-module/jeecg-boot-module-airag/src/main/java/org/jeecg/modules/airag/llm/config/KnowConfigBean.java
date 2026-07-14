package org.jeecg.modules.airag.llm.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库配置
 *
 * @Author: chenrui
 * @Date: 2025-04-01 14:19
 */
@NoArgsConstructor
@Data
@Component
@ConfigurationProperties(prefix = KnowConfigBean.PREFIX)
public class KnowConfigBean {
    public static final String PREFIX = "jeecg.airag.know";

    /**
     * 开启MinerU解析
     */
    private boolean enableMinerU = false;

    /**
     * conda的环境(默认不使用conda)
     */
    private String condaEnv = null;

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU本地部署切换为官方API-----------
    /**
     * MinerU 配置（支持本地部署与官方 API 切换）
     */
    private MineruConfig minerU = new MineruConfig();

    //update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】HYBRID 搜索与 metadata 提取 Kill Switch-----------
    /**
     * 是否启用 PGVector HYBRID 模式（vector + full-text）
     */
    private boolean hybridSearch = false;

    /**
     * PG text search config，仅 HYBRID 生效
     */
    private String textSearchConfig = "simple";

    /**
     * RRF 融合参数 k
     */
    private int rrfK = 60;

    /**
     * 是否启用查询扩展（ExpandingQueryTransformer）
     */
    private boolean queryExpansionEnabled = false;

    /**
     * 是否启用 metadata 提取 LLM fallback
     */
    private boolean metadataLlmFallbackEnabled = false;
    //update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1】HYBRID 搜索与 metadata 提取 Kill Switch-----------

    /**
     * MinerU 配置
     */
    @Data
    @NoArgsConstructor
    public static class MineruConfig {

        /**
         * 运行模式：local=本地 magic-pdf 命令，cloud=官方 API
         */
        private String mode = "local";

        /**
         * 官方 API 配置
         */
        private CloudConfig cloud = new CloudConfig();
    }

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API CloudConfig 字段语义注释补齐-----------
    /**
     * MinerU 官方 API 配置
     */
    @Data
    @NoArgsConstructor
    public static class CloudConfig {

        /**
         * 官方 API 基地址，默认 https://mineru.net
         */
        private String baseUrl = "https://mineru.net";

        /**
         * 官方 API Key（Bearer Token，cloud 模式必填）
         */
        private String apiKey;

        /**
         * HTTP 连接超时（秒），默认 10
         */
        private int connectTimeout = 10;

        /**
         * HTTP 读取超时（秒），默认 60；每次轮询单独计时
         */
        private int readTimeout = 60;

        /**
         * 单次解析总等待上限（秒），默认 300；实际生效，与 retryTimes*retryInterval 取较小者
         */
        private int timeout = 300;

        /**
         * 轮询最大次数，默认 60；仅作语义提示，最终以 timeout 为准
         */
        private int retryTimes = 60;

        /**
         * 轮询结果间隔（秒），默认 2
         */
        private int retryInterval = 2;
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API CloudConfig 字段语义注释补齐-----------
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU本地部署切换为官方API-----------

}
