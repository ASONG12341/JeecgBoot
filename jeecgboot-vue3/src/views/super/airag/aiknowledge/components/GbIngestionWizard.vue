<!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】三步线性向导 UI（上传→核对→入库，两栏核对）----------- -->
<template>
  <BasicModal
    v-bind="$attrs"
    @register="registerModal"
    :title="modalTitle"
    :width="1280"
    :bodyStyle="{ height: 'calc(100vh - 160px)', padding: '0', overflow: 'hidden' }"
    defaultFullscreen
    :footer="null"
    destroyOnClose
    @cancel="handleClose"
  >
    <div class="gb-wiz">
      <!-- 步骤条 -->
      <div class="gb-wiz-steps">
        <a-steps :current="step" size="small">
          <a-step title="上传" />
          <a-step title="核对" />
          <a-step title="入库" />
        </a-steps>
      </div>

      <!-- ① 上传 -->
      <div v-show="step === 0" class="gb-wiz-pane">
        <div class="gb-wiz-upload">
          <div class="gb-wiz-card">
            <a-upload-dragger
              name="file"
              :multiple="false"
              accept=".pdf"
              :showUploadList="true"
              :maxCount="1"
              :beforeUpload="beforeUpload"
              :customRequest="dummyRequest"
              @change="onUploadChange"
            >
              <p class="ant-upload-drag-icon">
                <Icon icon="ant-design:cloud-upload-outlined" size="48" />
              </p>
              <p class="ant-upload-text">点击或拖拽国标 PDF 到此处</p>
              <p class="ant-upload-hint">仅支持 PDF；上传后不会自动向量化，将进入解析与确认流程</p>
            </a-upload-dragger>
            <div v-if="uploadFileName" class="gb-wiz-file">
              已选：{{ uploadFileName }}
              <span v-if="uploadFilePath" class="ok">（已上传）</span>
            </div>
            <div v-if="parsing" class="gb-wiz-parse-state">
              <a-spin size="small" />
              <span>正在解析 PDF 正文，完成后自动进入核对…</span>
            </div>
            <div v-if="parseFailedReason && !parsing" class="gb-wiz-parse-fail">
              <a-alert type="error" show-icon :message="'解析失败：' + parseFailedReason" />
            </div>
            <div class="gb-wiz-upload-actions">
              <a-button @click="handleClose">取消</a-button>
              <a-button v-if="parseFailedReason && !parsing" :loading="parsing" @click="startParse(true)">
                重试解析
              </a-button>
              <a-button
                type="primary"
                :loading="uploadSaving || parsing"
                :disabled="!uploadFilePath && !docId"
                @click="submitUpload"
              >
                {{ parsing ? '解析中…' : '开始解析' }}
              </a-button>
            </div>
          </div>
        </div>
      </div>

      <!-- ② 核对：Markdown（规范文本）| 结构确认，两栏可拖宽 -->
      <div v-show="step === 1" class="gb-wiz-pane gb-wiz-check">
        <!-- 顶部工具条 -->
        <div class="gb-wiz-check-bar">
          <span class="gb-wiz-check-bar-title">核对条款</span>
          <span class="gb-wiz-check-bar-hint">核对规范 Markdown 与条款结构，确认无误后入库</span>
          <a v-if="pdfUrl" class="gb-wiz-pdf-link" @click="openPdf">
            <Icon icon="ant-design:file-pdf-outlined" size="16" />
            <span class="gb-wiz-pdf-name">{{ pdfFileName || '查看 PDF 原文' }}</span>
          </a>
        </div>

        <div ref="splitBodyEl" class="gb-wiz-check-body">
          <!-- 左：Markdown（规范文本） -->
          <div class="gb-wiz-col" :style="{ width: mdPct + '%', flexShrink: 0 }">
            <div class="gb-wiz-panel-h">
              <span>Markdown（规范文本）</span>
              <a-tag v-if="parsing" color="processing">解析中</a-tag>
              <a-tag v-else-if="hasMarkdown" color="success">已就绪</a-tag>
              <a-tag v-else-if="parseFailedReason" color="error">失败</a-tag>
              <a-radio-group v-model:value="mdViewMode" size="small" button-style="solid" style="margin-left: auto">
                <a-radio-button value="preview">预览</a-radio-button>
                <a-radio-button value="source">源码</a-radio-button>
              </a-radio-group>
            </div>
            <div v-if="parseFailedReason && !parsing" class="gb-wiz-error">{{ parseFailedReason }}</div>
            <div v-show="mdViewMode === 'preview'" ref="mdPreviewEl" class="gb-wiz-md-preview">
              <div v-if="parsing && !hasMarkdown" class="gb-wiz-empty"><a-spin tip="正在解析，请稍候…" /></div>
              <div v-else-if="hasMarkdown" class="gb-wiz-md-body markdown-body" v-html="markdownHtml"></div>
              <div v-else class="gb-wiz-empty">解析结果将显示在这里</div>
            </div>
            <div v-show="mdViewMode === 'source'" class="gb-wiz-source-wrap">
              <GbMdFormulaTools
                ref="formulaToolsRef"
                :markdown-text="markdownText"
                :get-textarea="getTextareaEl"
                @update:markdown-text="markdownText = $event"
              >
                <template #actions>
                  <a-button size="small" :loading="savingMd" :disabled="!hasMarkdown || parsing || !docId" @click="saveMarkdownEdits">
                    保存修正
                  </a-button>
                  <a-button
                    size="small"
                    :loading="structureLoading"
                    :disabled="!hasMarkdown || parsing || !docId"
                    @click="regenerateStructure"
                  >
                    保存并重生成条款树
                  </a-button>
                </template>
              </GbMdFormulaTools>
              <a-textarea
                ref="mdTextareaRef"
                v-model:value="markdownText"
                class="gb-wiz-md-source"
                :placeholder="parsing ? '正在解析，请稍候…' : '规范 Markdown 源码（可编辑）'"
                :disabled="parsing"
                @select="notifyFormulaSelection"
                @mouseup="notifyFormulaSelection"
                @keyup="notifyFormulaSelection"
              />
            </div>
          </div>

          <div class="gb-wiz-divider" @mousedown="startSplitDrag"></div>

          <!-- 右：结构确认 -->
          <div class="gb-wiz-col gb-wiz-col-right">
            <div class="gb-wiz-panel-h">
              <span>结构确认</span>
              <a-tag v-if="structureLoading" color="processing">生成中</a-tag>
              <a-tag v-else-if="structure" color="success">共 {{ totalClauseCount }} 条</a-tag>
            </div>

            <!-- 区块一：标注规范（默认收起） -->
            <a-collapse v-model:activeKey="rulesActiveKey" class="gb-wiz-rules" ghost>
              <a-collapse-panel key="rules">
                <template #header>
                  <span class="gb-wiz-rules-header">
                    <Icon icon="ant-design:book-outlined" size="14" />
                    国标 Markdown 标注规范
                  </span>
                </template>
                <table class="gb-wiz-rules-table">
                  <thead>
                    <tr>
                      <th>标记</th>
                      <th>含义</th>
                      <th>示例</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td><code># 编号 标题</code></td>
                      <td>章（一级）</td>
                      <td><code># 1 范围</code></td>
                    </tr>
                    <tr>
                      <td><code>## 编号 标题</code></td>
                      <td>条（二级）</td>
                      <td><code>## 6.2 数据采集</code></td>
                    </tr>
                    <tr>
                      <td><code>### 编号 标题</code></td>
                      <td>款（三级）</td>
                      <td><code>### 6.4.1 过压充电</code></td>
                    </tr>
                    <tr>
                      <td><code>#### 编号 标题</code></td>
                      <td>四级</td>
                      <td><code>#### 6.4.1.1 xxx</code></td>
                    </tr>
                    <tr>
                      <td><code># 附录X（性质）标题</code></td>
                      <td>附录</td>
                      <td><code># 附录A（规范性）试验顺序</code></td>
                    </tr>
                    <tr>
                      <td>附录内 <code>##</code> / <code>###</code></td>
                      <td>附录条款</td>
                      <td><code>## A.1</code>、<code>### A.1.1</code></td>
                    </tr>
                    <tr>
                      <td>普通行</td>
                      <td>正文 / 注 / 图片 / 表格 / 来源，自动归属当前条款，无需标记</td>
                      <td><code>注：</code>、<code>![]()</code>、<code>&lt;table&gt;</code>、<code>[来源：…]</code></td>
                    </tr>
                    <tr>
                      <td>无需处理</td>
                      <td>目次 / 前言 / 引言 / 参考文献，解析器自动跳过</td>
                      <td>—</td>
                    </tr>
                  </tbody>
                </table>
                <div class="gb-wiz-rules-key">
                  核对要点：只需保证标题行的 <code>#</code> 数量与编号正确，编号必须连续递增（章 1,2,3…、条
                  x.1,x.2…），其余内容无需处理
                </div>
              </a-collapse-panel>
            </a-collapse>

            <!-- 区块二：标准信息 -->
            <div class="gb-wiz-info">
              <span v-if="structure?.standardNo" class="gb-wiz-info-no">{{ structure.standardNo }}</span>
              <span v-else class="gb-wiz-info-no unknown">未识别</span>
              <a-tag v-if="structure?.version" color="blue">{{ structure.version }}</a-tag>
              <span class="gb-wiz-info-total">条款 {{ totalClauseCount }}</span>
              <span class="gb-wiz-info-conf">
                <i class="gb-dot high"></i>{{ confCount.high }}
                <i class="gb-dot mid"></i>{{ confCount.mid }}
                <i class="gb-dot low"></i>{{ confCount.low }}
              </span>
              <span class="gb-wiz-info-filter">
                <a-switch v-model:checked="onlyLow" size="small" />
                只看低置信度
              </span>
            </div>

            <!-- 区块三：条款树 -->
            <div class="gb-wiz-tree-toolbar">
              <a-input
                v-model:value="treeSearch"
                size="small"
                allow-clear
                placeholder="搜索条款号 / 标题"
                class="gb-wiz-tree-search"
              >
                <template #prefix>
                  <Icon icon="ant-design:search-outlined" size="12" />
                </template>
              </a-input>
              <a-button type="link" size="small" @click="expandTreeLevel(1)">一层</a-button>
              <a-button type="link" size="small" @click="expandTreeLevel(2)">两层</a-button>
              <a-button type="link" size="small" @click="expandAllTree">展开</a-button>
              <a-button type="link" size="small" @click="collapseAllTree">折叠</a-button>
            </div>
            <div class="gb-wiz-tree-scroll">
              <a-spin :spinning="structureLoading" class="gb-wiz-tree-spin">
                <div v-if="displayTreeData.length" class="gb-wiz-tree-inner">
                  <a-tree
                    v-model:expandedKeys="treeExpandedKeys"
                    v-model:selectedKeys="selectedKeys"
                    :tree-data="displayTreeData"
                    show-line
                    block-node
                    class="gb-wiz-tree"
                    @select="onClauseSelect"
                  >
                    <template #title="{ clausePath, title, confidence, parseRule, depth }">
                      <div class="gb-tree-node" :class="'depth-' + (depth || 1)">
                        <i class="gb-dot" :class="confDotClass(confidence)" :title="confLabel(confidence)"></i>
                        <span class="gb-tree-path">{{ clausePath }}</span>
                        <span class="gb-tree-title">{{ title }}</span>
                        <a-tooltip v-if="parseRule" :title="ruleDesc(parseRule)">
                          <a-tag class="gb-rule-tag" :color="ruleColor(parseRule)">{{ ruleLabel(parseRule) }}</a-tag>
                        </a-tooltip>
                      </div>
                    </template>
                  </a-tree>
                </div>
                <a-empty v-else :description="treeEmptyText">
                  <a-button
                    v-if="!onlyLow && !treeSearch"
                    type="primary"
                    :loading="structureLoading"
                    @click="regenerateStructure"
                  >
                    生成条款树
                  </a-button>
                </a-empty>
              </a-spin>
            </div>

            <!-- 区块四：选中条款详情 -->
            <div v-if="selectedClause" class="gb-wiz-clause">
              <div class="clause-h">{{ selectedClause.clausePath }} {{ selectedClause.title }}</div>
              <div class="clause-tags">
                <a-tag :color="confTagColor(selectedClause.confidence)">{{ confLabel(selectedClause.confidence) }}</a-tag>
                <a-tag v-if="selectedClause.parseRule" :color="ruleColor(selectedClause.parseRule)" class="gb-rule-tag">
                  {{ ruleLabel(selectedClause.parseRule) }}
                </a-tag>
                <a-tag v-if="selectedClause.clauseType">{{ selectedClause.clauseType }}</a-tag>
                <a-tag v-if="selectedClause.pageNo" color="blue">P{{ selectedClause.pageNo }}</a-tag>
              </div>
              <div
                v-if="selectedClause.text"
                class="clause-body gb-wiz-md-body markdown-body"
                v-html="selectedClauseHtml"
              ></div>
              <div v-else class="clause-body clause-empty">（无正文，可在中栏编辑 MD 后重新生成）</div>
            </div>
          </div>
        </div>

        <!-- 底部操作栏 -->
        <div class="gb-wiz-footer">
          <div class="gb-wiz-footer-left">
            <a-button @click="step = 0" :disabled="fromList">
              <template #icon><Icon icon="ant-design:left-outlined" size="14" /></template>
              上一步
            </a-button>
            <a-popconfirm
              title="重新解析将重新生成 Markdown，未保存的修改会丢失，继续？"
              @confirm="startParse(true)"
            >
              <a-button :loading="parsing">
                <template #icon><Icon icon="ant-design:reload-outlined" size="14" /></template>
                重新解析
              </a-button>
            </a-popconfirm>
          </div>
          <div class="gb-wiz-footer-right">
            <span v-if="confCount.low > 0" class="gb-wiz-low-warn">
              <Icon icon="ant-design:warning-filled" size="14" />
              还有 {{ confCount.low }} 条低置信度条款，建议核对后再入库
            </span>
            <a-button
              :loading="structureLoading || savingMd"
              :disabled="!hasMarkdown || parsing || !docId"
              @click="regenerateStructure"
            >
              <template #icon><Icon icon="ant-design:save-outlined" size="14" /></template>
              保存并重生成条款树
            </a-button>
            <a-button
              type="primary"
              :disabled="!treeData.length || parsing"
              :loading="confirmLoading"
              @click="doConfirm"
            >
              <template #icon><Icon icon="ant-design:check-outlined" size="14" /></template>
              确认并入库
            </a-button>
          </div>
        </div>
      </div>

      <!-- ③ 入库进度 -->
      <div v-show="step === 2" class="gb-wiz-pane gb-wiz-process">
        <div class="gb-wiz-card gb-wiz-process-card">
          <a-result :status="processStatus" :title="processTitle" :sub-title="processSub">
            <template #extra>
              <a-space>
                <a-button v-if="processDone" type="primary" @click="finishOk">返回文档列表</a-button>
                <a-button v-if="processFailed" @click="step = 1">返回核对</a-button>
                <a-button v-if="processFailed" type="primary" :loading="confirmLoading" @click="doConfirm">
                  重试入库
                </a-button>
              </a-space>
            </template>
          </a-result>
          <a-timeline class="gb-wiz-timeline">
            <a-timeline-item :color="tlColor(0)">确认结构</a-timeline-item>
            <a-timeline-item :color="tlColor(1)">推导 domain_schema / 批量抽取</a-timeline-item>
            <a-timeline-item :color="tlColor(2)">写入条款 / 参数 / 引用</a-timeline-item>
            <a-timeline-item :color="tlColor(3)">条款向量化</a-timeline-item>
            <a-timeline-item :color="tlColor(4)">完成</a-timeline-item>
          </a-timeline>
        </div>
      </div>
    </div>
  </BasicModal>
</template>

<script lang="ts" setup>
  import { ref, computed, nextTick, onBeforeUnmount } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import Icon from '@/components/Icon';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getFileAccessHttpUrl, getHeaders } from '@/utils/common/compUtils';
  import { useGlobSetting } from '/@/hooks/setting';
  import GbMdFormulaTools, { renderMarkdownWithKatex, escapeHtml } from './GbMdFormulaTools.vue';
  import {
    saveGbDocUploadOnly,
    parseGbDocument,
    getGbDocStatus,
    getGbMarkdown,
    saveGbMarkdown,
    previewGbStructure,
    confirmGbIngestion,
  } from '../GbIngestion.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const globSetting = useGlobSetting();

  const step = ref(0);
  const knowledgeId = ref('');
  const docId = ref('');
  const fromList = ref(false);
  const modalTitle = computed(() => {
    const t = ['上传国标 PDF', '核对条款', '入库处理'];
    return `国标入库 · ${t[step.value] || ''}`;
  });

  // ① upload
  const uploadFileName = ref('');
  const uploadFilePath = ref('');
  const uploadFileObj = ref<File | null>(null);
  const uploadSaving = ref(false);
  const headers = getHeaders();

  // ② 核对（Markdown 是唯一事实源：preview 返回的 normalizedMarkdown 填进左栏编辑器）
  const parsing = ref(false);
  const markdownText = ref('');
  const hasMarkdown = computed(() => !!markdownText.value?.trim());
  const parseFailedReason = ref('');
  const mdViewMode = ref<'preview' | 'source'>('preview');
  const savingMd = ref(false);
  const mdTextareaRef = ref<any>(null);
  const mdPreviewEl = ref<HTMLElement | null>(null);
  const formulaToolsRef = ref<any>(null);
  let pollTimer: any = null;

  /** 预览 HTML：图片相对路径补域名只在展示层做，不回写编辑器（保持编辑器为唯一事实源） */
  const markdownHtml = computed(() => {
    if (!markdownText.value) return '';
    try {
      return renderMarkdownWithKatex(rewriteImageUrlsForFrontend(markdownText.value));
    } catch {
      return `<pre>${escapeHtml(markdownText.value)}</pre>`;
    }
  });

  // PDF：不再内嵌渲染，顶部工具条提供新页签打开链接
  const pdfUrl = ref('');
  const docTitle = ref('');
  const pdfFileName = computed(() => {
    if (docTitle.value) return docTitle.value;
    if (uploadFileName.value) return uploadFileName.value;
    const u = (pdfUrl.value || '').split('?')[0];
    const m = u.match(/([^/]+)$/);
    return m ? decodeURIComponent(m[1]) : '';
  });

  function openPdf() {
    if (pdfUrl.value) {
      window.open(pdfUrl.value, '_blank');
    }
  }

  // 两栏宽度（%），右栏占剩余；自实现拖拽（antd-vue 4.2.6 无 Splitter 组件）
  const splitBodyEl = ref<HTMLElement | null>(null);
  const mdPct = ref(58);

  function clamp(v: number, min: number, max: number) {
    return Math.min(max, Math.max(min, v));
  }

  function startSplitDrag(e: MouseEvent) {
    e.preventDefault();
    const body = splitBodyEl.value;
    if (!body) return;
    const total = body.getBoundingClientRect().width;
    if (!total) return;
    const startX = e.clientX;
    const startMd = mdPct.value;
    const onMove = (ev: MouseEvent) => {
      const delta = ((ev.clientX - startX) / total) * 100;
      mdPct.value = clamp(startMd + delta, 30, 75);
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.classList.remove('gb-wiz-split-dragging');
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    document.body.classList.add('gb-wiz-split-dragging');
  }

  // 结构确认（右栏）
  const structureLoading = ref(false);
  const structure = ref<any>(null);
  const selectedKeys = ref<string[]>([]);
  const selectedClause = ref<any>(null);
  const confirmLoading = ref(false);
  const treeExpandedKeys = ref<string[]>([]);
  const onlyLow = ref(false);
  const treeSearch = ref('');
  /** 规范面板默认收起，需要时展开 */
  const rulesActiveKey = ref<string[]>([]);

  /** parseRule 徽标说明（解析器与人工核对共用的规则契约） */
  const PARSE_RULES: Record<string, { label: string; desc: string; color: string }> = {
    HEADING_OK: { label: '规范标题', desc: 'HEADING_OK：规范标题命中', color: 'green' },
    SPLIT_JOINED: { label: '分行合并', desc: 'SPLIT_JOINED：编号标题分行合并', color: 'blue' },
    PROMOTED: { label: '编号提升', desc: 'PROMOTED：正文编号提升', color: 'orange' },
    OCR_REPAIRED: { label: 'OCR修复', desc: 'OCR_REPAIRED：OCR退化修复，需重点核对', color: 'red' },
    APPENDIX: { label: '附录', desc: 'APPENDIX：附录', color: 'purple' },
  };

  function ruleLabel(rule: string) {
    return PARSE_RULES[rule]?.label || rule;
  }
  function ruleDesc(rule: string) {
    return PARSE_RULES[rule]?.desc || rule;
  }
  function ruleColor(rule: string) {
    return PARSE_RULES[rule]?.color || 'default';
  }
  function confDotClass(c: string) {
    if (c === 'high') return 'high';
    if (c === 'medium') return 'mid';
    return 'low';
  }
  function confLabel(c: string) {
    if (c === 'high') return '高置信度';
    if (c === 'medium') return '中置信度';
    return '低置信度';
  }
  function confTagColor(c: string) {
    if (c === 'high') return 'success';
    if (c === 'medium') return 'warning';
    return 'error';
  }

  // ③ process
  const processPhase = ref(0); // 0-4
  const processDone = ref(false);
  const processFailed = ref(false);
  const processMsg = ref('');
  const processStatus = computed(() => {
    if (processFailed.value) return 'error';
    if (processDone.value) return 'success';
    return 'info';
  });
  const processTitle = computed(() => {
    if (processFailed.value) return '入库失败';
    if (processDone.value) return '入库完成';
    return '正在处理数据…';
  });
  const processSub = computed(() => processMsg.value || '请稍候，正在抽取并写入结构化数据与向量');

  const treeData = computed(() => {
    if (!structure.value?.clauses) return [];
    return structure.value.clauses.map((c: any) => convertNode(c, 1));
  });

  /**
   * 树过滤：「只看低置信度」保留 🟡🔴 节点及祖先链；搜索按条款号/标题匹配并保留祖先链。
   */
  const displayTreeData = computed(() => {
    let nodes = treeData.value;
    if (onlyLow.value) {
      const keep = (list: any[]): any[] =>
        (list || [])
          .map((n) => ({ ...n, children: keep(n.children || []) }))
          .filter((n) => n.confidence !== 'high' || n.children.length > 0);
      nodes = keep(nodes);
    }
    const kw = treeSearch.value.trim();
    if (kw) {
      const hit = (n: any) => (n.clausePath || '').includes(kw) || (n.title || '').includes(kw);
      const filt = (list: any[]): any[] =>
        (list || [])
          .map((n) => ({ ...n, children: filt(n.children || []) }))
          .filter((n) => hit(n) || n.children.length > 0);
      nodes = filt(nodes);
    }
    return nodes;
  });

  const treeEmptyText = computed(() => {
    if (treeSearch.value.trim()) return '未找到匹配条款';
    if (onlyLow.value) return '没有待核对的低/中置信度条款';
    return '暂无条款树';
  });

  const treeFlatCount = computed(() => {
    const count = (nodes: any[]): number => (nodes || []).reduce((n, x) => n + 1 + count(x.children || []), 0);
    return count(treeData.value);
  });

  const totalClauseCount = computed(() => structure.value?.totalClauseCount ?? treeFlatCount.value);

  /** 高/中/低置信度统计（以前端实际树为准，保证与过滤一致） */
  const confCount = computed(() => {
    const acc = { high: 0, mid: 0, low: 0 };
    const walk = (nodes: any[]) => {
      for (const n of nodes || []) {
        if (n.confidence === 'high') acc.high++;
        else if (n.confidence === 'medium') acc.mid++;
        else acc.low++;
        walk(n.children || []);
      }
    };
    walk(structure.value?.clauses || []);
    return acc;
  });

  /** 条款详情正文：渲染后的 markdown（图片补域名、公式 KaTeX），失败时退化为转义纯文本 */
  const selectedClauseHtml = computed(() => {
    const t = selectedClause.value?.text || '';
    if (!t) return '';
    try {
      return renderMarkdownWithKatex(rewriteImageUrlsForFrontend(t));
    } catch {
      return `<pre>${escapeHtml(t)}</pre>`;
    }
  });

  function convertNode(clause: any, depth = 1): any {
    return {
      key: clause.clausePath || `${depth}-${clause.title || Math.random()}`,
      clausePath: clause.clausePath,
      title: clause.title || '(无标题)',
      confidence: clause.confidence,
      parseRule: clause.parseRule,
      clauseType: clause.clauseType,
      text: clause.text,
      pageNo: clause.pageNo,
      depth,
      children: (clause.children || []).map((ch: any) => convertNode(ch, depth + 1)),
    };
  }

  function collectKeysByMaxDepth(nodes: any[], maxDepth: number, acc: string[] = []): string[] {
    for (const n of nodes || []) {
      if ((n.depth || 1) <= maxDepth) {
        acc.push(n.key);
        if ((n.depth || 1) < maxDepth) {
          collectKeysByMaxDepth(n.children || [], maxDepth, acc);
        }
      }
    }
    return acc;
  }

  function collectAllKeys(nodes: any[], acc: string[] = []): string[] {
    for (const n of nodes || []) {
      acc.push(n.key);
      collectAllKeys(n.children || [], acc);
    }
    return acc;
  }

  function expandTreeLevel(level: number) {
    treeExpandedKeys.value = collectKeysByMaxDepth(treeData.value, level);
  }

  function expandAllTree() {
    treeExpandedKeys.value = collectAllKeys(treeData.value);
  }

  function collapseAllTree() {
    treeExpandedKeys.value = [];
  }

  function tlColor(i: number) {
    if (processFailed.value && processPhase.value === i) return 'red';
    if (processDone.value || processPhase.value > i) return 'green';
    if (processPhase.value === i) return 'blue';
    return 'gray';
  }

  const [registerModal, { closeModal, setModalProps }] = useModalInner(async (data) => {
    resetAll();
    knowledgeId.value = data?.knowledgeId || '';
    fromList.value = data?.fromList ?? !!data?.docId;
    if (data?.docId) {
      docId.value = data.docId;
      // 从列表续跑：按状态跳步
      await resumeFromDoc(data.docId, data.pdfUrl);
    } else {
      step.value = 0;
    }
    setModalProps({ confirmLoading: false });
  });

  function resetAll() {
    step.value = 0;
    docId.value = '';
    docTitle.value = '';
    uploadFileName.value = '';
    uploadFilePath.value = '';
    uploadFileObj.value = null;
    parsing.value = false;
    markdownText.value = '';
    parseFailedReason.value = '';
    mdViewMode.value = 'preview';
    structure.value = null;
    selectedClause.value = null;
    selectedKeys.value = [];
    treeExpandedKeys.value = [];
    onlyLow.value = false;
    treeSearch.value = '';
    processPhase.value = 0;
    processDone.value = false;
    processFailed.value = false;
    processMsg.value = '';
    stopPoll();
  }

  async function resumeFromDoc(id: string, url?: string) {
    const st = await getGbDocStatus(id);
    if (!st?.success) {
      createMessage.error(st?.message || '加载文档状态失败');
      return;
    }
    const r = st.result || {};
    docTitle.value = r.title || r.docTitle || '';
    // PDF 链接：优先外部传入 → originalFilePath → 仅当 filePath 是 pdf
    if (url && /\.pdf($|\?)/i.test(url)) {
      pdfUrl.value = url;
    } else if (r.originalFilePath) {
      pdfUrl.value = getFileAccessHttpUrl(r.originalFilePath);
    } else if (r.filePath && /\.pdf($|\?)/i.test(r.filePath)) {
      pdfUrl.value = getFileAccessHttpUrl(r.filePath);
    } else {
      pdfUrl.value = url || '';
    }

    if (r.parseStatus === 'COMPLETED' || r.parseStatus === 'INDEXING' || r.parseStatus === 'CONFIRMED') {
      step.value = 2;
      if (r.parseStatus === 'COMPLETED') {
        processDone.value = true;
        processPhase.value = 4;
        processMsg.value = '已完成';
      } else {
        processPhase.value = 1;
        processMsg.value = '入库处理中…';
        startStatusPoll();
      }
      return;
    }
    // 已有 Markdown 未确认 → 核对步（树 + 规范化 MD 由 preview 一次带回）
    if (r.parseStatus === 'PARSED' || r.hasMarkdown || r.markdownReady) {
      await enterCheckStep();
      return;
    }
    // 解析中/未解析 → 留在上传步轮询，就绪后自动进核对
    step.value = 0;
    if (r.parsing || r.parseStatus === 'PARSING') {
      parsing.value = true;
      startStatusPoll();
    } else {
      await startParse(false);
    }
  }

  function beforeUpload(file: File) {
    uploadFileObj.value = file;
    uploadFileName.value = file.name;
    return true;
  }

  function dummyRequest(options: any) {
    // 使用系统通用上传接口
    const form = new FormData();
    form.append('file', options.file);
    form.append('biz', 'airag');
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${globSetting.domainUrl}/sys/common/upload`);
    const h = headers as any;
    Object.keys(h || {}).forEach((k) => xhr.setRequestHeader(k, h[k]));
    xhr.onload = () => {
      try {
        const res = JSON.parse(xhr.responseText);
        if (res.success) {
          uploadFilePath.value = res.message || res.result;
          options.onSuccess(res);
        } else {
          options.onError(new Error(res.message || '上传失败'));
        }
      } catch (e) {
        options.onError(e);
      }
    };
    xhr.onerror = () => options.onError(new Error('上传失败'));
    xhr.send(form);
  }

  function onUploadChange(info: any) {
    if (info.file.status === 'done') {
      createMessage.success('文件上传成功');
    } else if (info.file.status === 'error') {
      createMessage.error('文件上传失败');
    }
  }

  async function submitUpload() {
    if (!knowledgeId.value || !uploadFilePath.value) {
      createMessage.warning('请先选择知识库并上传 PDF');
      return;
    }
    uploadSaving.value = true;
    try {
      const title = uploadFileName.value || '国标文档';
      const metadata = JSON.stringify({
        filePath: uploadFilePath.value,
        autoEmbed: false,
      });
      const res = await saveGbDocUploadOnly({
        knowledgeId: knowledgeId.value,
        title,
        type: 'file',
        metadata,
        parseStatus: 'UPLOADED',
      });
      if (!res?.success) {
        createMessage.error(res?.message || '保存文档失败');
        return;
      }
      // 兼容多种返回：Result.OK(doc) / 直接 doc / result 为字符串 id
      const saved = res.result ?? res;
      let id = '';
      if (typeof saved === 'string') {
        id = saved;
      } else if (saved && typeof saved === 'object') {
        id = saved.id || saved.docId || '';
      }
      docId.value = id;
      if (!docId.value) {
        createMessage.warning('未获取到文档ID，请关闭后在列表点击「开始解析」继续');
        emit('success');
        closeModal();
        return;
      }
      emit('success');
      docTitle.value = title;
      pdfUrl.value = getFileAccessHttpUrl(uploadFilePath.value);
      // 留在上传步轮询，markdownReady 后自动进核对步
      await startParse(false);
    } finally {
      uploadSaving.value = false;
    }
  }

  async function startParse(force: boolean) {
    if (!docId.value) return;
    parsing.value = true;
    parseFailedReason.value = '';
    try {
      const res = await parseGbDocument(docId.value, force);
      if (!res?.success) {
        createMessage.error(res?.message || '启动解析失败');
        parsing.value = false;
        return;
      }
      startStatusPoll();
    } catch (e: any) {
      parsing.value = false;
      createMessage.error(e?.message || '启动解析失败');
    }
  }

  function startStatusPoll() {
    stopPoll();
    pollTimer = setInterval(async () => {
      if (!docId.value) return;
      const res = await getGbDocStatus(docId.value);
      if (!res?.success) return;
      const r = res.result || {};
      if (r.parseFailedReason) {
        parseFailedReason.value = r.parseFailedReason;
      }
      // 解析阶段：step0 等待自动进核对；step1 为「重新解析」后刷新树与规范化 MD
      if (step.value === 0 || step.value === 1) {
        if (r.hasMarkdown || r.markdownReady) {
          parsing.value = false;
          if (!r.parsing) stopPoll();
          if (step.value === 0) {
            await enterCheckStep();
          } else {
            await loadPreview(true, false);
          }
        } else if (r.parsing) {
          parsing.value = true;
        } else if (r.parseFailedReason) {
          parsing.value = false;
          stopPoll();
        }
      }
      // 入库阶段
      if (step.value === 2) {
        if (r.parseStatus === 'COMPLETED') {
          processDone.value = true;
          processFailed.value = false;
          processPhase.value = 4;
          processMsg.value = '入库完成';
          stopPoll();
        } else if (r.parseStatus === 'CONFIRMED' && !r.indexing) {
          // 失败回滚到 CONFIRMED
          processFailed.value = true;
          processMsg.value = '入库失败，可返回核对后重试';
          stopPoll();
        } else if (r.parseStatus === 'INDEXING' || r.parseStatus === 'CONFIRMED') {
          processPhase.value = Math.min(3, processPhase.value + 1);
          processMsg.value = '处理中：' + r.parseStatus;
        }
      }
    }, 2000);
  }

  function stopPoll() {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  /** 进入核对步：拉 preview（条款树 + 规范化 Markdown） */
  async function enterCheckStep() {
    step.value = 1;
    await nextTick();
    await loadPreview(true, false);
  }

  /**
   * 拉取结构预览：条款树 + normalizedMarkdown（阶段一产物，幂等）。
   * @param fillMarkdown true=用 normalizedMarkdown 覆盖左栏编辑器（进核对步/重新解析后）
   * @param notify 是否提示刷新结果（手动「保存并重生成」时）
   */
  async function loadPreview(fillMarkdown: boolean, notify: boolean) {
    if (!docId.value) {
      createMessage.error('缺少文档 ID');
      return;
    }
    structureLoading.value = true;
    try {
      const res = await previewGbStructure(docId.value);
      if (res?.success) {
        structure.value = res.result;
        if (fillMarkdown) {
          const nmd = res.result?.normalizedMarkdown || '';
          if (nmd) {
            markdownText.value = nmd;
          } else if (!markdownText.value) {
            await loadMarkdownFallback();
          }
        }
        await nextTick();
        expandTreeLevel(1);
        if (notify) {
          createMessage.success(
            `条款树已刷新（共 ${totalClauseCount.value} 条，低置信度 ${confCount.value.low} 条）`,
          );
        }
      } else {
        createMessage.error(res?.message || '结构解析失败');
      }
    } catch (e: any) {
      createMessage.error(e?.message || '结构解析失败');
    } finally {
      structureLoading.value = false;
    }
  }

  /** 兜底：preview 未回 normalizedMarkdown 时，取原文 Markdown（不施加展示层改写，保持事实源） */
  async function loadMarkdownFallback() {
    if (!docId.value) return;
    const res = await getGbMarkdown(docId.value);
    if (res?.success) {
      markdownText.value = res.result?.markdown || '';
    }
  }

  /**
   * 把 /sys/common/static/... 补成后端完整 URL（仅展示层使用）。
   * 图片在 upload 目录，必须请求 8080，不能请求 Vite 3100。
   */
  function rewriteImageUrlsForFrontend(md: string): string {
    if (!md) return md;
    const base = (globSetting.domainUrl || '').replace(/\/$/, '');
    if (!base) return md;
    // ![alt](/sys/common/static/xxx) 或 ![alt](sys/common/static/xxx)
    return md.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (_m, alt, src) => {
      let url = String(src).trim();
      // 去掉 title
      const sp = url.indexOf(' ');
      if (sp > 0) url = url.substring(0, sp);
      if (/^https?:\/\//i.test(url)) {
        return `![${alt}](${url})`;
      }
      if (url.startsWith('/sys/common/static/') || url.startsWith('sys/common/static/')) {
        const path = url.startsWith('/') ? url : `/${url}`;
        return `![${alt}](${base}${path})`;
      }
      // 仍是相对 images/ 时，无法可靠补全（缺 sourcesPath），保持原样
      return `![${alt}](${url})`;
    });
  }

  /**
   * 「保存并重生成条款树」：先保存当前 MD，再按磁盘 md 重新生成条款树。
   * 不覆盖编辑器内容（用户文本即事实源）。
   */
  async function regenerateStructure() {
    if (!docId.value) {
      createMessage.error('缺少文档 ID');
      return;
    }
    if (!markdownText.value?.trim()) {
      createMessage.warning('Markdown 为空，无法生成条款树');
      return;
    }
    // 必须先保存，后端 preview 读的是磁盘 md
    savingMd.value = true;
    try {
      const saveRes = await saveGbMarkdown(docId.value, markdownText.value || '');
      if (!saveRes?.success) {
        createMessage.error(saveRes?.message || '保存 Markdown 失败，无法生成结构');
        return;
      }
    } catch (e: any) {
      createMessage.error(e?.message || '保存 Markdown 失败');
      return;
    } finally {
      savingMd.value = false;
    }
    await loadPreview(false, true);
  }

  /** 保存左栏修正（写磁盘 md） */
  async function saveMarkdownEdits() {
    if (!docId.value) return;
    savingMd.value = true;
    try {
      const res = await saveGbMarkdown(docId.value, markdownText.value || '');
      if (res?.success) {
        createMessage.success(res.result?.message || 'Markdown 已保存');
      } else {
        createMessage.error(res?.message || '保存失败');
      }
    } catch (e: any) {
      createMessage.error(e?.message || '保存失败');
    } finally {
      savingMd.value = false;
    }
  }

  function onClauseSelect(keys: string[], info: any) {
    selectedKeys.value = keys;
    selectedClause.value = info?.node || null;
    const node = info?.node;
    const path = node?.clausePath || keys?.[0];
    const title = node?.title;
    if (path) {
      // 联动左栏 MD 定位
      scrollMdToClause(String(path), title ? String(title) : '');
    }
  }

  /**
   * 根据条款号/标题，在左栏 Markdown（预览或源码）中定位。
   * 预览：滚到含条款号的标题节点并高亮；
   * 源码：选中对应源码行并滚动到可视区。
   */
  function scrollMdToClause(clausePath: string, title: string) {
    if (!clausePath && !title) return;
    if (mdViewMode.value === 'preview') {
      scrollPreviewToClause(clausePath, title);
    } else {
      scrollEditToClause(clausePath, title);
    }
  }

  function scrollPreviewToClause(clausePath: string, title: string) {
    const root = mdPreviewEl.value;
    if (!root) return;
    // 清除旧高亮
    root.querySelectorAll('.gb-md-hit').forEach((el) => el.classList.remove('gb-md-hit'));
    const nodes = Array.from(root.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li')) as HTMLElement[];
    const path = clausePath.trim();
    const titlePart = (title || '').replace(/^\(无标题\)$/, '').trim();
    let target: HTMLElement | null = null;
    // 1) 优先：文本以条款号开头（如 "6 电池电安全试验" / "6.1 xxx"）
    const pathRe = new RegExp(`(^|\\s)${escapeRegExp(path)}(?:\\s|[.、．]|$)`);
    for (const el of nodes) {
      const t = (el.textContent || '').trim();
      if (!t) continue;
      if (pathRe.test(t) || t.startsWith(path + ' ') || t.startsWith(path + '　')) {
        target = el;
        break;
      }
    }
    // 2) 附录
    if (!target && path.includes('附录')) {
      for (const el of nodes) {
        const t = (el.textContent || '').trim();
        if (t.includes(path) || (titlePart && t.includes(titlePart))) {
          target = el;
          break;
        }
      }
    }
    // 3) 标题包含
    if (!target && titlePart) {
      for (const el of nodes) {
        const t = (el.textContent || '').trim();
        if (t.includes(titlePart) && t.length < titlePart.length + 40) {
          target = el;
          break;
        }
      }
    }
    if (!target) {
      createMessage.info(`未在左栏文档中定位到「${path}」，可切换源码模式搜索条款号后修改`);
      return;
    }
    target.classList.add('gb-md-hit');
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // 3 秒后去掉高亮
    window.setTimeout(() => target && target.classList.remove('gb-md-hit'), 3000);
  }

  function scrollEditToClause(clausePath: string, title: string) {
    const text = markdownText.value || '';
    if (!text) return;
    const path = clausePath.trim();
    const lines = text.split('\n');
    let lineIdx = -1;
    let charStart = 0;
    let acc = 0;
    const pathRe = new RegExp(`^(#{1,4}\\s*)?${escapeRegExp(path)}(?:\\s|[.、．]|$)`);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      const bare = line.replace(/^#{1,4}\s*/, '');
      if (pathRe.test(line) || pathRe.test(bare) || bare.startsWith(path + ' ') || bare === path) {
        lineIdx = i;
        charStart = acc;
        break;
      }
      // 附录
      if (lineIdx < 0 && path.includes('附录') && (line.includes(path) || (title && line.includes(title)))) {
        lineIdx = i;
        charStart = acc;
        break;
      }
      acc += lines[i].length + 1; // + \n
    }
    if (lineIdx < 0) {
      createMessage.info(`源码中未找到「${path}」，请搜索条款号后修改`);
      return;
    }
    const lineText = lines[lineIdx];
    const charEnd = charStart + lineText.length;
    nextTick(() => {
      const ta = getTextareaEl();
      if (!ta) return;
      ta.focus();
      ta.setSelectionRange(charStart, charEnd);
      // 按行高估算滚动
      const style = window.getComputedStyle(ta);
      const lineHeight = parseFloat(style.lineHeight) || 20;
      const targetTop = Math.max(0, lineIdx * lineHeight - ta.clientHeight / 3);
      ta.scrollTop = targetTop;
    });
  }

  function escapeRegExp(s: string) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function getTextareaEl(): HTMLTextAreaElement | null {
    const r = mdTextareaRef.value;
    // ant-design-vue Textarea 可能挂在 $el 或 textarea ref
    if (!r) return null;
    if (r instanceof HTMLTextAreaElement) return r;
    if (r.$el) {
      const el = r.$el.querySelector ? r.$el.querySelector('textarea') : null;
      if (el) return el as HTMLTextAreaElement;
      if (r.$el.tagName === 'TEXTAREA') return r.$el as HTMLTextAreaElement;
    }
    return document.querySelector('.gb-wiz-md-source') as HTMLTextAreaElement | null;
  }

  function notifyFormulaSelection() {
    formulaToolsRef.value?.notifySelection?.();
  }

  async function doConfirm() {
    if (!docId.value) return;
    confirmLoading.value = true;
    processFailed.value = false;
    processDone.value = false;
    processPhase.value = 0;
    processMsg.value = '已提交确认，开始入库…';
    step.value = 2;
    try {
      const res = await confirmGbIngestion(docId.value);
      if (res?.success) {
        processDone.value = true;
        processPhase.value = 4;
        processMsg.value = res.message || '确认成功，国标结构已入库';
        createMessage.success(processMsg.value);
      } else {
        processFailed.value = true;
        processMsg.value = res?.message || '入库失败';
        createMessage.error(processMsg.value);
      }
    } catch (e: any) {
      processFailed.value = true;
      processMsg.value = e?.message || '入库失败';
      createMessage.error(processMsg.value);
    } finally {
      confirmLoading.value = false;
      // 仍轮询一次状态兜底
      startStatusPoll();
    }
  }

  function finishOk() {
    emit('success');
    closeModal();
  }

  function handleClose() {
    stopPoll();
    closeModal();
  }

  onBeforeUnmount(() => {
    stopPoll();
  });
</script>

<style lang="less" scoped>
  .gb-wiz {
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #f5f5f5;
  }
  .gb-wiz-steps {
    padding: 12px 24px;
    background: #fff;
    border-bottom: 1px solid #f0f0f0;
  }
  .gb-wiz-pane {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .gb-wiz-card {
    background: #fff;
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    padding: 24px;
  }
  .gb-wiz-empty {
    padding: 48px;
    color: #999;
    text-align: center;
  }

  /* —— ① 上传 —— */
  .gb-wiz-upload {
    max-width: 720px;
    margin: 40px auto;
    width: 100%;
    padding: 0 24px;
  }
  .gb-wiz-file {
    margin-top: 12px;
    color: #666;

    .ok {
      color: #52c41a;
    }
  }
  .gb-wiz-parse-state {
    margin-top: 16px;
    display: flex;
    align-items: center;
    gap: 8px;
    color: #595959;
  }
  .gb-wiz-parse-fail {
    margin-top: 16px;
  }
  .gb-wiz-upload-actions {
    margin-top: 24px;
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }

  /* —— ② 核对：顶部工具条 + 两栏 + 底部操作栏 —— */
  .gb-wiz-check {
    overflow: hidden;
  }
  .gb-wiz-check-bar {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 16px;
    background: #fff;
    border-bottom: 1px solid #f0f0f0;
  }
  .gb-wiz-check-bar-title {
    font-weight: 600;
    color: #262626;
  }
  .gb-wiz-check-bar-hint {
    font-size: 12px;
    color: #8c8c8c;
  }
  .gb-wiz-pdf-link {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #1677ff;
    cursor: pointer;
    max-width: 40%;

    :deep(.app-iconify) {
      color: #f5222d;
    }
  }
  .gb-wiz-pdf-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    &:hover {
      text-decoration: underline;
    }
  }
  .gb-wiz-check-body {
    flex: 1;
    min-height: 0;
    display: flex;
    overflow: hidden;
  }
  .gb-wiz-col {
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: #fff;
    overflow: hidden;
  }
  .gb-wiz-col-right {
    flex: 1;
  }
  .gb-wiz-divider {
    flex-shrink: 0;
    width: 5px;
    cursor: col-resize;
    background: #f0f0f0;
    border-left: 1px solid #e8e8e8;

    &:hover {
      background: #91caff;
    }
  }
  .gb-wiz-panel-h {
    padding: 8px 16px;
    border-bottom: 1px solid #f0f0f0;
    font-weight: 500;
    display: flex;
    gap: 8px;
    align-items: center;
    flex-shrink: 0;
  }

  /* 左栏 Markdown */
  .gb-wiz-md-preview {
    flex: 1;
    min-height: 0;
    overflow: auto;
    margin: 0;
    padding: 16px 20px 24px;
    background: #fff;
  }
  .gb-wiz-md-body {
    max-width: 920px;
    margin: 0 auto;
    line-height: 1.75;
    font-size: 14px;
    color: #1f1f1f;
    text-align: left;

    :deep(h1),
    :deep(h2),
    :deep(h3) {
      margin-top: 1.2em;
      margin-bottom: 0.5em;
      font-weight: 600;
    }
    :deep(p) {
      margin: 0.6em 0;
    }
    :deep(table) {
      display: block;
      border-collapse: collapse;
      max-width: 100%;
      margin: 12px 0;
      font-size: 13px;
      overflow-x: auto;
    }
    :deep(th),
    :deep(td) {
      border: 1px solid #e5e5e5;
      padding: 6px 8px;
    }
    :deep(img) {
      max-width: 100%;
      height: auto;
      display: block;
      margin: 12px auto;
      border: 1px solid #f0f0f0;
      border-radius: 4px;
      background: #fafafa;
    }
    /* KaTeX 公式 */
    :deep(.katex) {
      font-size: 1.08em;
    }
    :deep(.katex-block),
    :deep(.katex-display) {
      margin: 12px 0;
      overflow-x: auto;
      text-align: center;
    }
    /* 条款定位高亮 */
    :deep(.gb-md-hit) {
      background: #fff7e6 !important;
      outline: 2px solid #faad14;
      border-radius: 4px;
      scroll-margin: 80px;
    }
  }
  .gb-wiz-source-wrap {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .gb-wiz-md-source {
    flex: 1;
    margin: 0 12px 8px;
    min-height: 0 !important;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 13px;
    resize: none;
  }
  .gb-wiz-source-wrap :deep(textarea.gb-wiz-md-source) {
    height: 100% !important;
    min-height: 0 !important;
  }
  .gb-wiz-error {
    margin: 8px 12px 0;
    color: #ff4d4f;
  }

  /* 右栏区块一：规范面板（默认收起） */
  .gb-wiz-rules {
    flex-shrink: 0;
    border-bottom: 1px solid #f0f0f0;

    :deep(.ant-collapse-header) {
      padding: 8px 16px !important;
      align-items: center !important;
    }
    :deep(.ant-collapse-content-box) {
      padding: 4px 16px 12px !important;
    }
  }
  .gb-wiz-rules-header {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: #595959;
  }
  .gb-wiz-rules-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;
    color: #595959;

    th,
    td {
      border: 1px solid #f0f0f0;
      padding: 4px 8px;
      text-align: left;
      vertical-align: top;
    }
    th {
      background: #fafafa;
      color: #8c8c8c;
      font-weight: 500;
    }
    code {
      background: #f5f5f5;
      border: 1px solid #e8e8e8;
      border-radius: 3px;
      padding: 0 4px;
      font-size: 11px;
    }
  }
  .gb-wiz-rules-key {
    margin-top: 8px;
    font-size: 12px;
    color: #262626;
    background: #fffbe6;
    border: 1px solid #ffe58f;
    border-radius: 4px;
    padding: 4px 8px;

    code {
      background: #fff;
      border: 1px solid #ffe58f;
      border-radius: 3px;
      padding: 0 4px;
    }
  }

  /* 右栏区块二：标准信息条 */
  .gb-wiz-info {
    padding: 10px 16px;
    font-size: 12px;
    color: #595959;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    flex-shrink: 0;
  }
  .gb-wiz-info-no {
    font-weight: 600;
    font-size: 13px;
    color: #1677ff;

    &.unknown {
      color: #fa8c16;
    }
  }
  .gb-wiz-info-total {
    color: #595959;
  }
  .gb-wiz-info-conf {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .gb-wiz-info-filter {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .gb-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    flex-shrink: 0;

    &.high {
      background: #52c41a;
    }
    &.mid {
      background: #faad14;
    }
    &.low {
      background: #ff4d4f;
    }
  }

  /* 右栏区块三：条款树 */
  .gb-wiz-tree-toolbar {
    padding: 8px 16px;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  }
  .gb-wiz-tree-search {
    max-width: 220px;
    margin-right: 8px;
  }
  .gb-wiz-tree-scroll {
    flex: 1 1 auto;
    min-height: 0;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    position: relative;
  }
  .gb-wiz-tree-spin {
    min-height: 100%;

    :deep(.ant-spin-nested-loading),
    :deep(.ant-spin-container) {
      min-height: 100%;
    }
  }
  .gb-wiz-tree-inner {
    padding: 8px 12px 24px;
  }
  .gb-wiz-tree {
    /* 树本身不设死高，由外层 .gb-wiz-tree-scroll 滚动 */
    background: transparent;

    :deep(.ant-tree-node-content-wrapper) {
      min-height: 32px;
      display: flex;
      align-items: center;
      border-radius: 4px;
      transition: background 0.2s;

      &:hover {
        background: #f5f5f5;
      }
      &.ant-tree-node-selected {
        background: #e6f4ff;
        box-shadow: inset 2px 0 0 #1677ff;
      }
    }
  }
  .gb-tree-node {
    display: flex;
    align-items: center;
    gap: 6px;
    line-height: 1.45;
    padding: 2px 0;
    min-width: 0;

    &.depth-1 .gb-tree-title {
      font-weight: 500;
    }
  }
  .gb-tree-path {
    font-weight: 600;
    color: #1677ff;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-variant-numeric: tabular-nums;
    flex-shrink: 0;
  }
  .gb-tree-title {
    color: #262626;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .gb-rule-tag {
    margin-inline-end: 0;
    font-size: 11px;
    line-height: 16px;
    padding: 0 4px;
    flex-shrink: 0;
  }

  /* 右栏区块四：条款详情 */
  .gb-wiz-clause {
    flex-shrink: 0;
    margin: 0 12px 12px;
    padding: 12px;
    background: #fafafa;
    border: 1px solid #f0f0f0;
    border-radius: 8px;
  }
  .clause-h {
    font-weight: 600;
    font-size: 13px;
    color: #262626;
  }
  .clause-tags {
    margin-top: 6px;
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
  }
  .clause-body {
    margin-top: 8px;
    max-height: 40vh;
    overflow: auto;
    background: #fff;
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    padding: 12px;
    font-size: 13px;

    &.gb-wiz-md-body {
      max-width: none;
      margin: 8px 0 0;
    }
  }
  .clause-empty {
    color: #8c8c8c;
    font-size: 12px;
  }

  /* 底部操作栏 */
  .gb-wiz-footer {
    padding: 12px 16px;
    border-top: 1px solid #f0f0f0;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    background: #fff;
    flex-shrink: 0;
  }
  .gb-wiz-footer-left,
  .gb-wiz-footer-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .gb-wiz-low-warn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: #fa8c16;
    font-size: 12px;
    margin-right: 4px;
  }

  /* —— ③ 入库进度 —— */
  .gb-wiz-process {
    padding: 32px 48px;
    overflow: auto;
  }
  .gb-wiz-process-card {
    max-width: 720px;
    margin: 0 auto;
  }
  .gb-wiz-timeline {
    max-width: 480px;
    margin: 24px auto 0;
  }
</style>
<style>
  /* 拖拽分栏时禁止选中文字（全局，拖拽结束移除） */
  body.gb-wiz-split-dragging {
    user-select: none;
    cursor: col-resize;
  }
</style>
<!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】三步线性向导 UI----------- -->
