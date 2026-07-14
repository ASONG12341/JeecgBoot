<!--update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 国标解析确认页----------- -->
<template>
  <BasicModal
    v-bind="$attrs"
    @register="registerModal"
    :title="'国标解析确认 - ' + (structure?.standardNo || '')"
    :width="1400"
    :bodyStyle="{ height: 'calc(100vh - 200px)', padding: '0' }"
    defaultFullscreen
    :footer="null"
  >
    <a-spin :spinning="loading" tip="正在解析国标文档结构...">
      <div class="gb-preview-container">
        <!-- 左侧：PDF 原文 -->
        <div class="gb-preview-left">
          <div class="gb-panel-header">
            <span>📄 PDF 原文</span>
            <div class="gb-page-nav" v-if="pdfUrl">
              <a-button size="small" @click="prevPage" :disabled="currentPage <= 1">◀</a-button>
              <span class="page-info">{{ currentPage }} / {{ totalPages }}</span>
              <a-button size="small" @click="nextPage" :disabled="currentPage >= totalPages">▶</a-button>
            </div>
          </div>
          <div class="gb-pdf-area">
            <div v-if="pdfUrl" class="gb-pdf-content">
              <!-- PDF.js 渲染区域 — 后续集成 pdf.js -->
              <div class="gb-pdf-placeholder">
                <a-icon type="file-pdf" style="font-size: 48px; color: #ccc;" />
                <p>PDF 预览区域</p>
                <p class="text-gray-400 text-sm">{{ pdfUrl }}</p>
                <p class="text-gray-400 text-xs mt-2">
                  提示: PDF.js 集成将在后续版本完善。<br>
                  当前可使用右侧解析结果进行校对。
                </p>
              </div>
            </div>
            <div v-else class="gb-no-pdf">
              <a-empty description="暂无 PDF 文件路径" />
            </div>
          </div>
        </div>

        <!-- 右侧：解析结果 -->
        <div class="gb-preview-right">
          <div class="gb-panel-header">
            <span>📋 解析结果</span>
            <a-tag v-if="overallConfidence" :color="overallConfidenceColor">
              {{ overallConfidence }}
            </a-tag>
          </div>

          <!-- 标准基本信息 -->
          <div class="gb-info-section" v-if="structure">
            <a-descriptions :column="2" size="small" bordered>
              <a-descriptions-item label="标准号">
                <strong>{{ structure.standardNo || '-' }}</strong>
              </a-descriptions-item>
              <a-descriptions-item label="版本">{{ structure.version || '-' }}</a-descriptions-item>
              <a-descriptions-item label="全称" :span="2">{{ structure.fullName || '-' }}</a-descriptions-item>
              <a-descriptions-item label="发布日期">{{ structure.publishDate || '-' }}</a-descriptions-item>
              <a-descriptions-item label="实施日期">{{ structure.implementationDate || '-' }}</a-descriptions-item>
              <a-descriptions-item label="条款总数">{{ structure.totalClauseCount }}</a-descriptions-item>
              <a-descriptions-item label="解析质量">
                <span class="confidence-stats">
                  🟢 {{ structure.highConfidenceCount }}
                  🟡 {{ structure.mediumConfidenceCount }}
                  🔴 {{ structure.lowConfidenceCount }}
                </span>
              </a-descriptions-item>
            </a-descriptions>

            <!-- 引用文件 -->
            <div class="gb-refs-section" v-if="structure.normativeRefs?.length">
              <a-collapse size="small">
                <a-collapse-panel :header="'规范性引用文件 (' + structure.normativeRefs.length + ')'">
                  <a-tag v-for="(ref, idx) in structure.normativeRefs" :key="idx" class="mb-1">
                    {{ ref }}
                  </a-tag>
                </a-collapse-panel>
              </a-collapse>
            </div>

            <!-- 替代旧标准 -->
            <div class="gb-supersedes" v-if="structure.supersedes?.length">
              <span class="text-gray-500 text-xs">替代: </span>
              <a-tag v-for="(s, idx) in structure.supersedes" :key="idx" color="orange" size="small">
                {{ s }}
              </a-tag>
            </div>
          </div>

          <!-- 条款树 -->
          <div class="gb-tree-section" v-if="treeData.length">
            <a-input
              v-model:value="searchText"
              placeholder="搜索条款号或标题..."
              size="small"
              allowClear
              class="mb-2"
            />
            <a-tree
              :tree-data="filteredTreeData"
              :selectedKeys="selectedKeys"
              @select="onClauseSelect"
              showLine
              :defaultExpandedKeys="defaultExpandedKeys"
            >
              <template #title="{ clausePath, title, confidence }">
                <span>
                  <span class="clause-path">{{ clausePath }}</span>
                  <span class="clause-title">{{ title }}</span>
                  <span class="confidence-badge">{{ getConfidenceEmoji(confidence) }}</span>
                </span>
              </template>
            </a-tree>
          </div>

          <!-- 选中条款详情 -->
          <div class="gb-detail-section" v-if="selectedClause">
            <a-divider style="margin: 8px 0;" />
            <div class="detail-header">
              <h4 class="detail-title">
                {{ selectedClause.clausePath }} {{ selectedClause.title }}
              </h4>
              <div class="detail-tags">
                <a-tag :color="getConfidenceColor(selectedClause.confidence)" size="small">
                  {{ selectedClause.confidence }}
                </a-tag>
                <a-tag v-if="selectedClause.isScope" color="blue" size="small">范围条款</a-tag>
                <a-tag v-if="selectedClause.isAppendix" color="purple" size="small">
                  附录 {{ selectedClause.appendixLabel }}
                </a-tag>
                <a-tag v-if="selectedClause.clauseType" size="small">{{ selectedClause.clauseType }}</a-tag>
              </div>
            </div>
            <div class="detail-text">{{ selectedClause.text || '(无文本)' }}</div>
          </div>
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="gb-footer">
        <a-space size="middle">
          <a-button type="primary" @click="handleConfirm" :loading="confirmLoading">
            ✅ 确认并入库
          </a-button>
          <a-button @click="handleReparse" :loading="loading">
            🔄 重新解析
          </a-button>
          <a-button @click="closeModal">❌ 取消</a-button>
        </a-space>
      </div>
    </a-spin>
  </BasicModal>
</template>

<script lang="ts" setup>
  import { ref, computed, watch } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { previewGbStandard, confirmGbStandard } from '../GbStandardPreview.api';
  import { useMessage } from '/@/hooks/web/useMessage';

  const { createMessage } = useMessage();

  const props = defineProps({
    docId: { type: String, default: '' },
    pdfUrl: { type: String, default: '' },
  });

  const emit = defineEmits(['confirmed']);
  const [registerModal, { closeModal }] = useModalInner();

  // ==================== 状态 ====================
  const loading = ref(false);
  const confirmLoading = ref(false);
  const structure = ref<any>(null);
  const selectedKeys = ref<string[]>([]);
  const selectedClause = ref<any>(null);
  const currentPage = ref(1);
  const totalPages = ref(1);
  const searchText = ref('');

  // ==================== 计算属性 ====================

  /** 将条款树转换为 Ant Design Tree 数据 */
  const treeData = computed(() => {
    if (!structure.value?.clauses) return [];
    return structure.value.clauses.map(convertToTreeNode);
  });

  /** 按搜索文本过滤条款树 */
  const filteredTreeData = computed(() => {
    if (!searchText.value) return treeData.value;
    return filterTree(treeData.value, searchText.value.toLowerCase());
  });

  /** 默认展开的 key（展开第一层） */
  const defaultExpandedKeys = computed(() => {
    return treeData.value.map((n: any) => n.key);
  });

  /** 整体解析质量描述 */
  const overallConfidence = computed(() => {
    if (!structure.value || structure.value.totalClauseCount === 0) return '';
    const ratio = structure.value.highConfidenceCount / structure.value.totalClauseCount;
    if (ratio >= 0.8) return '良好';
    if (ratio >= 0.5) return '一般';
    return '较差（建议人工校对）';
  });

  const overallConfidenceColor = computed(() => {
    const c = overallConfidence.value;
    if (c === '良好') return 'green';
    if (c === '一般') return 'orange';
    return 'red';
  });

  // ==================== 方法 ====================

  function convertToTreeNode(clause: any): any {
    return {
      key: clause.clausePath,
      clausePath: clause.clausePath,
      title: clause.title || '(无标题)',
      confidence: clause.confidence || 'high',
      isScope: clause.isScope,
      isAppendix: clause.isAppendix,
      appendixLabel: clause.appendixLabel,
      clauseType: clause.clauseType,
      text: clause.text,
      pageNo: clause.pageNo,
      children: clause.children?.map(convertToTreeNode) || [],
    };
  }

  function filterTree(nodes: any[], keyword: string): any[] {
    return nodes.reduce((acc: any[], node: any) => {
      const matchSelf =
        (node.clausePath && node.clausePath.toLowerCase().includes(keyword)) ||
        (node.title && node.title.toLowerCase().includes(keyword));
      const filteredChildren = node.children ? filterTree(node.children, keyword) : [];

      if (matchSelf || filteredChildren.length > 0) {
        acc.push({
          ...node,
          children: filteredChildren.length > 0 ? filteredChildren : node.children,
        });
      }
      return acc;
    }, []);
  }

  function onClauseSelect(keys: string[], info: any) {
    selectedKeys.value = keys;
    selectedClause.value = info.node || null;
    if (info.node?.pageNo) {
      currentPage.value = info.node.pageNo;
    }
  }

  function getConfidenceEmoji(confidence: string): string {
    if (confidence === 'high') return '🟢';
    if (confidence === 'medium') return '🟡';
    if (confidence === 'low') return '🔴';
    return '';
  }

  function getConfidenceColor(confidence: string): string {
    if (confidence === 'high') return 'green';
    if (confidence === 'medium') return 'orange';
    if (confidence === 'low') return 'red';
    return 'default';
  }

  function prevPage() {
    if (currentPage.value > 1) currentPage.value--;
  }

  function nextPage() {
    if (currentPage.value < totalPages.value) currentPage.value++;
  }

  async function doPreview() {
    if (!props.docId) return;
    loading.value = true;
    try {
      const res = await previewGbStandard(props.docId);
      if (res.success) {
        structure.value = res.result;
        selectedClause.value = null;
        selectedKeys.value = [];
      } else {
        createMessage.error(res.message || '解析失败');
      }
    } catch (e: any) {
      createMessage.error('解析失败: ' + (e.message || '未知错误'));
    } finally {
      loading.value = false;
    }
  }

  async function handleConfirm() {
    confirmLoading.value = true;
    try {
      const res = await confirmGbStandard(props.docId);
      if (res.success) {
        createMessage.success('确认成功，国标结构已入库');
        emit('confirmed');
        closeModal();
      } else {
        createMessage.error(res.message || '确认失败');
      }
    } catch (e: any) {
      createMessage.error('确认失败: ' + (e.message || '未知错误'));
    } finally {
      confirmLoading.value = false;
    }
  }

  function handleReparse() {
    structure.value = null;
    doPreview();
  }

  // 打开 Modal 时自动执行预览
  watch(
    () => props.docId,
    (newVal) => {
      if (newVal) doPreview();
    },
    { immediate: true },
  );
</script>

<style scoped>
  .gb-preview-container {
    display: flex;
    height: calc(100vh - 240px);
    overflow: hidden;
  }

  .gb-preview-left {
    flex: 1;
    border-right: 1px solid #f0f0f0;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .gb-preview-right {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    min-width: 0;
  }

  .gb-panel-header {
    padding: 8px 16px;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-weight: 500;
    font-size: 14px;
    flex-shrink: 0;
  }

  .gb-page-nav {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .page-info {
    font-size: 13px;
    color: #666;
    min-width: 50px;
    text-align: center;
  }

  .gb-pdf-area {
    flex: 1;
    overflow: auto;
    padding: 16px;
    background: #fafafa;
  }

  .gb-pdf-placeholder {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: #999;
  }

  .gb-no-pdf {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }

  .gb-info-section {
    padding: 12px 16px;
    flex-shrink: 0;
  }

  .gb-refs-section {
    margin-top: 8px;
  }

  .gb-supersedes {
    margin-top: 4px;
  }

  .confidence-stats {
    font-size: 13px;
    letter-spacing: 2px;
  }

  .gb-tree-section {
    flex: 1;
    overflow-y: auto;
    padding: 8px 16px;
  }

  .clause-path {
    font-weight: 600;
    margin-right: 6px;
    color: #1890ff;
    font-size: 13px;
  }

  .clause-title {
    color: #333;
    font-size: 13px;
  }

  .confidence-badge {
    margin-left: 4px;
    font-size: 11px;
  }

  .gb-detail-section {
    padding: 0 16px 12px;
    flex-shrink: 0;
  }

  .detail-header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    margin-bottom: 8px;
  }

  .detail-title {
    margin: 0;
    font-size: 14px;
  }

  .detail-tags {
    flex-shrink: 0;
    margin-left: 8px;
  }

  .detail-text {
    padding: 8px 12px;
    background: #f6f8fa;
    border-radius: 4px;
    white-space: pre-wrap;
    font-size: 13px;
    line-height: 1.6;
    max-height: 200px;
    overflow-y: auto;
    word-break: break-all;
  }

  .gb-footer {
    padding: 12px 16px;
    border-top: 1px solid #f0f0f0;
    text-align: center;
    background: #fff;
    flex-shrink: 0;
  }
</style>
<!--update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 国标解析确认页----------- -->
