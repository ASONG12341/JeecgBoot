<template>
  <div class="gb-retrieval-page">
    <div class="page-header">
      <h2>GB 知识检索</h2>
      <p class="description" v-if="knowledgeName">
        知识库：{{ knowledgeName }}
      </p>
      <p class="description" v-else>
        智能检索国家标准知识库，支持条款查询、参数查询、语义搜索等多种模式
      </p>
    </div>

    <div class="main-content">
      <div class="left-panel">
        <GbRetrievalPanel
          :knowledge-id="knowledgeId"
          :knowledge-ids="knowledgeId ? [knowledgeId] : undefined"
          @result-click="handleResultClick"
        />
      </div>

      <div class="right-panel">
        <GbEvidencePanel
          v-if="selectedResult"
          :result="selectedResult"
          @copy-content="handleCopyContent"
          @view-in-document="handleViewInDocument"
        />
        <div v-else class="empty-detail">
          <FileSearchOutlined style="font-size: 64px; color: #d9d9d9" />
          <p>点击左侧结果查看详细信息</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
//update-begin---author:song ---date:2026-07-14  for：【GB检索P2】重构检索页容器，从路由读取参数并接入证据面板-----------
import { ref, computed } from 'vue';
import { useRoute } from 'vue-router';
import { message } from 'ant-design-vue';
import { FileSearchOutlined } from '@ant-design/icons-vue';
import GbRetrievalPanel from './components/GbRetrievalPanel.vue';
import GbEvidencePanel from './components/GbEvidencePanel.vue';
import type { RetrievalResult } from './GbRetrieval.api';

const route = useRoute();

const knowledgeId = computed(() => {
  const id = route.query.knowledgeId;
  return Array.isArray(id) ? id[0] : id;
});

const knowledgeName = computed(() => {
  const name = route.query.name;
  return Array.isArray(name) ? name[0] : name;
});

const selectedResult = ref<RetrievalResult | null>(null);

const handleResultClick = (result: RetrievalResult) => {
  selectedResult.value = result;
};

const handleViewInDocument = () => {
  if (!selectedResult.value) return;
  // P1 预留：文档定位功能在 P2 实现
  message.info('文档定位功能开发中');
};

const handleCopyContent = () => {
  if (!selectedResult.value || !selectedResult.value.text) {
    message.warning('没有可复制的内容');
    return;
  }
  navigator.clipboard.writeText(selectedResult.value.text).then(() => {
    message.success('内容已复制到剪贴板');
  }).catch(() => {
    message.error('复制失败');
  });
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P2】重构检索页容器，从路由读取参数并接入证据面板-----------
</script>

<style lang="less" scoped>
.gb-retrieval-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 16px;

  .page-header {
    margin-bottom: 16px;
    padding: 16px 24px;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border-radius: 8px;
    color: #fff;

    h2 {
      margin: 0 0 8px 0;
      font-size: 24px;
    }

    .description {
      margin: 0;
      opacity: 0.9;
    }
  }

  .main-content {
    flex: 1;
    display: flex;
    gap: 16px;
    overflow: hidden;

    .left-panel {
      flex: 2;
      min-width: 0;
      overflow-y: auto;
    }

    .right-panel {
      flex: 1;
      min-width: 320px;
      overflow-y: auto;

      .empty-detail {
        height: 100%;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        background: #fff;
        border-radius: 8px;
        color: #999;

        p {
          margin-top: 16px;
          font-size: 16px;
        }
      }
    }
  }
}
</style>
