<template>
  <div class="gb-retrieval-panel">
    <!-- 查询输入区 -->
    <div class="query-section">
      <a-input
        v-model:value="queryText"
        placeholder="请输入查询内容，如：4.1.2条是什么？钢材的抗拉强度要求？"
        size="large"
        @press-enter="handleQuery"
      >
        <template #prefix>
          <SearchOutlined />
        </template>
        <template #suffix>
          <a-button type="primary" :loading="loading" @click="handleQuery">
            检索
          </a-button>
        </template>
      </a-input>

      <!-- 意图选择器（可选） -->
      <div class="intent-selector">
        <span class="label">检索模式：</span>
        <a-radio-group v-model:value="selectedIntent" button-style="solid" size="small">
          <a-radio-button value="">自动识别</a-radio-button>
          <a-radio-button value="CLAUSE_LOOKUP">条款查询</a-radio-button>
          <a-radio-button value="PARAM_QUERY">参数查询</a-radio-button>
          <a-radio-button value="SEMANTIC_SEARCH">语义搜索</a-radio-button>
        </a-radio-group>
      </div>
    </div>

    <!-- 检索结果区 -->
    <div class="results-section">
      <!-- 统计信息 -->
      <div v-if="response" class="stats-bar">
        <span class="stat-item">
          <CheckCircleOutlined style="color: #52c41a" />
          找到 {{ response.total }} 条结果
        </span>
        <span class="stat-item">
          <ClockCircleOutlined />
          耗时 {{ response.duration }}ms
        </span>
        <span v-if="response.stats?.intent" class="stat-item">
          <TagsOutlined />
          意图：{{ getIntentLabel(response.stats.intent) }}
        </span>
      </div>

      <!-- 结果列表 -->
      <div v-if="response?.results?.length > 0" class="results-list">
        <GbResultCard
          v-for="(result, index) in response.results"
          :key="index"
          :result="result"
          :rank="index + 1"
          @click="handleResultClick(result)"
        />
      </div>

      <!-- 空状态 -->
      <a-empty
        v-else-if="!loading && queryText && response"
        description="未找到相关结果，请尝试调整查询条件"
      />

      <!-- 初始状态 -->
      <div v-else-if="!response" class="empty-state">
        <SearchOutlined style="font-size: 64px; color: #d9d9d9" />
        <p>输入查询内容开始检索</p>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue';
import { message } from 'ant-design-vue';
import { SearchOutlined, CheckCircleOutlined, ClockCircleOutlined, TagsOutlined } from '@ant-design/icons-vue';
import { queryGbStandard, type GbRetrievalRequest, type GbRetrievalResponse, type RetrievalResult } from '../GbRetrieval.api';
import GbResultCard from './GbResultCard.vue';

//update-begin---author:song ---date:2026-07-14 for：【GB检索P1】修正 GbRetrievalPanel 类型与错误处理-----------
const props = defineProps<{
  standardId?: string;
  knowledgeId?: string;
  knowledgeIds?: string[];
}>();
//update-end---author:song ---date:2026-07-14 for：【GB检索P1】修正 GbRetrievalPanel 类型与错误处理-----------

const emit = defineEmits<{
  (e: 'result-click', result: RetrievalResult): void;
}>();

// 状态
const queryText = ref('');
const selectedIntent = ref('');
const loading = ref(false);
const response = ref<GbRetrievalResponse | null>(null);

//update-begin---author:song ---date:2026-07-14 for：【GB检索P1】修正 GbRetrievalPanel 类型与错误处理-----------
/**
 * 执行查询
 */
const handleQuery = async () => {
  if (!queryText.value.trim()) {
    message.warning('请输入查询内容');
    return;
  }

  loading.value = true;

  try {
    const knowledgeIds = props.knowledgeIds || (props.knowledgeId ? [props.knowledgeId] : undefined);
    const request: GbRetrievalRequest = {
      query: queryText.value,
      intent: selectedIntent.value || undefined,
      standardId: props.standardId,
      knowledgeIds,
      topK: 10,
      similarityThreshold: 0.7,
    };

    response.value = await queryGbStandard(request);
  } catch (error: any) {
    console.error('检索失败:', error);
    message.error(error?.message || '检索失败');
  } finally {
    loading.value = false;
  }
};
//update-end---author:song ---date:2026-07-14 for：【GB检索P1】修正 GbRetrievalPanel 类型与错误处理-----------

/**
 * 获取意图标签
 */
const getIntentLabel = (intent: string): string => {
  const intentMap: Record<string, string> = {
    CLAUSE_LOOKUP: '条款查询',
    PARAM_QUERY: '参数查询',
    REF_TRACE: '引用追溯',
    SEMANTIC_SEARCH: '语义搜索',
    TERM_LOOKUP: '术语查询',
  };
  return intentMap[intent] || intent;
};

/**
 * 点击结果
 */
const handleResultClick = (result: RetrievalResult) => {
  emit('result-click', result);
};
</script>

<style lang="less" scoped>
.gb-retrieval-panel {
  padding: 16px;
  background: #fff;
  border-radius: 8px;

  .query-section {
    margin-bottom: 24px;

    .intent-selector {
      margin-top: 12px;
      display: flex;
      align-items: center;

      .label {
        margin-right: 12px;
        color: #666;
      }
    }
  }

  .results-section {
    .stats-bar {
      margin-bottom: 16px;
      padding: 12px;
      background: #f5f5f5;
      border-radius: 4px;
      display: flex;
      gap: 24px;

      .stat-item {
        display: flex;
        align-items: center;
        gap: 4px;
        color: #666;
      }
    }

    .results-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .empty-state {
      text-align: center;
      padding: 64px 0;
      color: #999;

      p {
        margin-top: 16px;
        font-size: 16px;
      }
    }
  }
}
</style>