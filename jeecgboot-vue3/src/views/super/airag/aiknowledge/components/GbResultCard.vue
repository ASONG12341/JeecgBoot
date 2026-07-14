<template>
  <div class="gb-result-card" @click="handleClick">
    <!-- 头部：排名 + 来源标签 -->
    <div class="card-header">
      <div class="rank-badge">
        <span class="rank-number">#{{ rank }}</span>
      </div>
      <div class="source-tags">
        <a-tag v-if="result.clausePath" color="blue">
          {{ result.clausePath }}
        </a-tag>
        <a-tag v-if="result.parameters && result.parameters.length > 0" color="green">
          {{ result.parameters[0].paramName }}
        </a-tag>
        <a-tag :color="getSourceColor(result.sourceChannel)">
          {{ getSourceLabel(result.sourceChannel) }}
        </a-tag>
      </div>
      <div class="score-badge">
        <StarOutlined />
        <span>{{ (result.score * 100).toFixed(1) }}%</span>
      </div>
    </div>

    <!-- 内容区 -->
    <div class="card-content">
      <!-- 条款标题 -->
      <div v-if="result.title" class="clause-title">
        {{ result.title }}
      </div>

      <!-- 主要内容 -->
      <div class="content-text">
        {{ truncateContent(result.text, 300) }}
      </div>

      <!-- 参数信息 -->
      <div v-if="result.parameters && result.parameters.length > 0" class="parameter-info">
        <a-descriptions :column="3" size="small">
          <a-descriptions-item label="参数名称">
            {{ result.parameters[0].paramName }}
          </a-descriptions-item>
          <a-descriptions-item label="参数值">
            {{ result.parameters[0].paramValue }}
          </a-descriptions-item>
          <a-descriptions-item v-if="result.parameters[0].unit" label="单位">
            {{ result.parameters[0].unit }}
          </a-descriptions-item>
        </a-descriptions>
      </div>

      <!-- 引用信息 -->
      <div v-if="result.references && result.references.length > 0" class="reference-info">
        <LinkOutlined />
        <span>引用标准：{{ result.references[0].targetStandardNo }}</span>
      </div>
    </div>

    <!-- 底部：元数据 -->
    <div v-if="showMetadata" class="card-footer">
      <div class="metadata">
        <span v-if="result.standardName">
          <FileTextOutlined />
          {{ result.standardName }}
        </span>
        <span v-if="result.metadata && result.metadata.chunkIndex !== undefined">
          <BlockOutlined />
          Chunk #{{ result.metadata.chunkIndex }}
        </span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
//update-begin---author:song ---date:2026-07-14  for：【GB检索P2】修复 GbResultCard 类型字段引用-----------
import { StarOutlined, LinkOutlined, FileTextOutlined, BlockOutlined } from '@ant-design/icons-vue';
import type { RetrievalResult } from '../GbRetrieval.api';

const props = defineProps<{
  result: RetrievalResult;
  rank: number;
  showMetadata?: boolean;
}>();

const emit = defineEmits<{
  (e: 'click', result: RetrievalResult): void;
}>();

const truncateContent = (content: string | undefined, maxLength: number): string => {
  if (!content) return '';
  if (content.length <= maxLength) return content;
  return content.substring(0, maxLength) + '...';
};

const getSourceColor = (source: string): string => {
  const colorMap: Record<string, string> = {
    VECTOR: 'purple',
    STRUCTURE: 'cyan',
    TERM: 'orange',
    FUSION_RRF: 'gold',
    FUSION_WEIGHTED: 'lime',
  };
  return colorMap[source] || 'default';
};

const getSourceLabel = (source: string): string => {
  const labelMap: Record<string, string> = {
    VECTOR: '向量检索',
    STRUCTURE: '结构化检索',
    TERM: '术语检索',
    FUSION_RRF: 'RRF融合',
    FUSION_WEIGHTED: '加权融合',
  };
  return labelMap[source] || source;
};

const handleClick = () => {
  emit('click', props.result);
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P2】修复 GbResultCard 类型字段引用-----------
</script>

<style lang="less" scoped>
.gb-result-card {
  padding: 16px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;

  &:hover {
    border-color: #1890ff;
    box-shadow: 0 2px 8px rgba(24, 144, 255, 0.15);
  }

  .card-header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;

    .rank-badge {
      .rank-number {
        display: inline-block;
        padding: 4px 12px;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: #fff;
        border-radius: 12px;
        font-weight: bold;
        font-size: 14px;
      }
    }

    .source-tags {
      flex: 1;
      display: flex;
      gap: 8px;
    }

    .score-badge {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 4px 12px;
      background: #fff7e6;
      border: 1px solid #ffd591;
      border-radius: 12px;
      color: #fa8c16;
      font-weight: 500;
    }
  }

  .card-content {
    .clause-title {
      margin-bottom: 8px;
      font-size: 16px;
      font-weight: 500;
      color: #262626;
    }

    .content-text {
      margin-bottom: 12px;
      color: #595959;
      line-height: 1.6;
    }

    .parameter-info {
      margin-bottom: 12px;
      padding: 12px;
      background: #f6ffed;
      border: 1px solid #b7eb8f;
      border-radius: 4px;
    }

    .reference-info {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background: #e6f7ff;
      border: 1px solid #91d5ff;
      border-radius: 4px;
      color: #096dd9;
    }
  }

  .card-footer {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid #f0f0f0;

    .metadata {
      display: flex;
      gap: 16px;
      color: #8c8c8c;
      font-size: 12px;

      span {
        display: flex;
        align-items: center;
        gap: 4px;
      }
    }
  }
}
</style>