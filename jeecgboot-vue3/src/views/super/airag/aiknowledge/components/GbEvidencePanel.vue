<template>
  <div class="gb-evidence-panel" v-if="result">
    <div class="panel-header">
      <FileTextOutlined />
      <span>GB 标准证据链</span>
    </div>

    <a-descriptions :column="1" bordered size="small">
      <a-descriptions-item label="标准号">
        {{ result.standardNo || '-' }}
      </a-descriptions-item>
      <a-descriptions-item label="标准名称">
        {{ result.standardName || '-' }}
      </a-descriptions-item>
      <a-descriptions-item label="条款路径">
        {{ result.clausePath || '-' }}
      </a-descriptions-item>
      <a-descriptions-item label="条款标题">
        {{ result.title || '-' }}
      </a-descriptions-item>
      <a-descriptions-item label="来源通道">
        <a-tag :color="getSourceColor(result.sourceChannel)">
          {{ getSourceLabel(result.sourceChannel) }}
        </a-tag>
      </a-descriptions-item>
      <a-descriptions-item label="相似度">
        {{ (result.score * 100).toFixed(2) }}%
      </a-descriptions-item>
    </a-descriptions>

    <div class="section">
      <h4>原文内容</h4>
      <div class="content-box">{{ result.text || '-' }}</div>
    </div>

    <div class="section" v-if="result.parameters && result.parameters.length > 0">
      <h4>相关参数</h4>
      <a-table
        :columns="paramColumns"
        :data-source="result.parameters"
        :pagination="false"
        size="small"
        bordered
      />
    </div>

    <div class="section" v-if="result.references && result.references.length > 0">
      <h4>引用标准</h4>
      <ul class="reference-list">
        <li v-for="(ref, idx) in result.references" :key="idx">
          <LinkOutlined />
          {{ ref.targetStandardNo || result.standardNo }}
          <span v-if="ref.targetClausePath"> §{{ ref.targetClausePath }}</span>
        </li>
      </ul>
    </div>

    <div class="actions">
      <a-button @click="handleCopyContent">
        <CopyOutlined /> 复制内容
      </a-button>
    </div>
  </div>
</template>

<script lang="ts" setup>
//update-begin---author:song ---date:2026-07-14  for：【GB检索P2】新增 GB 标准证据链详情面板 GbEvidencePanel-----------
import {
  FileTextOutlined,
  LinkOutlined,
  CopyOutlined,
} from '@ant-design/icons-vue';
import { getSourceColor, getSourceLabel, type RetrievalResult } from '../GbRetrieval.api';

defineProps<{
  result: RetrievalResult;
}>();

const emit = defineEmits<{
  (e: 'copy-content'): void;
}>();

const paramColumns = [
  { title: '参数名', dataIndex: 'paramName', key: 'paramName' },
  { title: '参数值', dataIndex: 'paramValue', key: 'paramValue' },
  { title: '单位', dataIndex: 'unit', key: 'unit' },
  { title: '公式', dataIndex: 'formula', key: 'formula' },
];

const handleCopyContent = () => {
  emit('copy-content');
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P2】新增 GB 标准证据链详情面板 GbEvidencePanel-----------
</script>

<style lang="less" scoped>
.gb-evidence-panel {
  padding: 16px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  height: 100%;
  overflow-y: auto;

  .panel-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 16px;
    font-size: 18px;
    font-weight: 500;
  }

  .section {
    margin-top: 24px;

    h4 {
      margin: 0 0 12px 0;
      font-size: 16px;
    }

    .content-box {
      padding: 16px;
      background: #f5f5f5;
      border-radius: 4px;
      line-height: 1.8;
      white-space: pre-wrap;
    }

    .reference-list {
      list-style: none;
      padding: 0;
      margin: 0;

      li {
        padding: 8px 12px;
        background: #e6f7ff;
        border: 1px solid #91d5ff;
        border-radius: 4px;
        margin-bottom: 8px;
        color: #096dd9;
        display: flex;
        align-items: center;
        gap: 8px;
      }
    }
  }

  .actions {
    margin-top: 24px;
    display: flex;
    gap: 12px;
  }
}
</style>
