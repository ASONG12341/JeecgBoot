<!--知识库文档列表（独立路由页面，替代原 AiragKnowledgeDocListModal 全屏弹窗）-->
<template>
  <div class="ai-knowledge-doc-page">
    <div class="page-header">
      <span class="back-btn pointer" @click="goBack">
        <Icon icon="ant-design:arrow-left-outlined" size="14"></Icon>
        <span>返回知识库列表</span>
      </span>
      <span class="page-title">{{ title }}</span>
    </div>
    <a-layout class="page-body">
      <a-layout-sider :style="siderStyle">
        <a-menu v-model:selectedKeys="selectedKeys" mode="vertical" style="border: none" :items="menuItems" @click="handleMenuClick" />
      </a-layout-sider>
      <a-layout-content :style="contentStyle">
        <div v-if="selectedKey === 'document'">
          <div class="search-header" style="text-align: left;">
            <a-space align="center" wrap>
              <a-input
                  class="search-input"
                  v-model:value="searchText"
                  placeholder="请输入文档名称，回车搜索"
                  @press-enter="searchTextEnter"
              />
              <template v-if="selectedRows.length > 0">
                <div>
                  <span>已进入多选模式，当前选中</span>
                  <a style="margin: 0 4px;"> {{ selectedRows.length }} </a>
                  <span>条文档</span>
                </div>
                <div>
                  <a @click="onClearSelected">清空选择</a>
                  <a-divider type="vertical"/>
                  <a @click="onDeleteBatch">批量删除</a>
                </div>
              </template>
            </a-space>
          </div>
          <a-row :span="24" class="knowledge-row">
            <a-col :xxl="4" :xl="6" :lg="6" :md="6" :sm="12" :xs="24" v-if="!type || type==='knowledge'">
              <a-card class="add-knowledge-card" :bodyStyle="cardBodyStyle">
                <span style="line-height: 18px;font-weight: 500;color:#676f83;font-size: 12px">
                  创建文档
                  <a-tooltip title="知识库文档">
                    <a style="color: unset" href="https://help.jeecg.com/aigc/guide/knowledge#4-%E7%9F%A5%E8%AF%86%E5%BA%93%E6%96%87%E6%A1%A3" target="_blank">
                      <Icon style="position:relative;top:1px" icon="ant-design:question-circle-outlined" size="14"></Icon>
                    </a>
                  </a-tooltip>
                </span>
                <div class="add-knowledge-doc" @click="handleCreateText">
                  <Icon icon="ant-design:form-outlined" size="13"></Icon><span>手动录入</span>
                </div>
                <div class="add-knowledge-doc" @click="handleCreateUpload">
                  <Icon icon="ant-design:cloud-upload-outlined" size="13"></Icon><span>文件上传</span>
                </div>
                <!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】主入口：上传国标 PDF----------- -->
                <div class="add-knowledge-doc" @click="handleGbWizardCreate" style="color:#1677ff;font-weight:500;">
                  <Icon icon="ant-design:file-protect-outlined" size="13"></Icon><span>上传国标 PDF</span>
                </div>
                <!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】主入口：上传国标 PDF----------- -->
                <div class="add-knowledge-doc" @click="handleCreateWeb">
                  <Icon icon="ant-design:global-outlined" size="13"></Icon><span>网页录入</span>
                </div>
                <div class="add-knowledge-doc">
                  <a-upload
                      accept=".zip"
                      name="file"
                      :data="{ knowId: knowledgeId }"
                      :showUploadList="false"
                      :headers="headers"
                      :beforeUpload="beforeUpload"
                      :action="uploadUrl"
                      @change="handleUploadChange"
                      style="width: 100%;"
                  >
                      <div style="display: flex;width: 100%">
                        <Icon style="margin-left: 0;color:#676f83" icon="ant-design:project-outlined" size="13"></Icon>
                        <span style="color:#676f83;font-size: 12px">文档库上传</span>
                      </div>
                  </a-upload>
                </div>
                <a-dropdown placement="bottomRight" :trigger="['click']">
                  <div class="ant-dropdown-link pointer operation" @click.prevent.stop>
                    <Icon icon="ant-design:ellipsis-outlined" size="16"></Icon>
                  </div>
                  <template #overlay>
                    <a-menu>
                      <a-menu-item key="delete" @click="onDeleteAll">
                        <Icon icon="ant-design:delete-outlined" size="16"></Icon>
                        清空文档
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </a-card>
            </a-col>
            <a-col :xxl="4" :xl="6" :lg="6" :md="6" :sm="12" :xs="24" v-for="item in knowledgeDocDataList" :key="item.id">
              <!-- 国标文档：整卡点击进入入库向导（续跑），不要进通用「编辑文档」 -->
              <a-card
                :class="['knowledge-card','pointer', {
                checked: item.__checked,
              }]"
                @click="handleCardClick(item)"
              >
                <div class="knowledge-checkbox">
                  <a-checkbox v-model:checked="item.__checked" @click.stop=""/>
                </div>
                <div class="knowledge-header">
                  <div class="header-text flex">
                    <Icon v-if="item.type==='text'" icon="ant-design:file-text-outlined" size="32" color="#00a7d0"></Icon>
                    <Icon v-if="item.type==='web'" icon="ant-design:global-outlined" size="32" color="#1890ff"></Icon>
                    <Icon v-if="item.type==='file' && isGbDoc(item)" icon="ant-design:file-pdf-outlined" size="32" color="rgb(211, 47, 47)"></Icon>
                    <Icon v-else-if="item.type==='file' && getFileSuffix(item.metadata) === 'pdf'" icon="ant-design:file-pdf-outlined" size="32" color="rgb(211, 47, 47)"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === 'docx'" icon="ant-design:file-word-outlined" size="32" color="rgb(68, 138, 255)"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === 'pptx'" icon="ant-design:file-ppt-outlined" size="32" color="rgb(245, 124, 0)"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === 'xlsx'" icon="ant-design:file-excel-outlined" size="32" color="rgb(98, 187, 55)"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === 'txt'" icon="ant-design:file-text-outlined" size="32" color="#00a7d0"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === 'md'" icon="ant-design:file-markdown-outlined" size="32" color="#292929"></Icon>
                    <Icon v-if="item.type==='file' && !isGbDoc(item) && getFileSuffix(item.metadata) === ''" icon="ant-design:file-unknown-outlined" size="32" color="#f5f5dc"></Icon>
                    <span class="ellipsis header-title">{{ item.title }}</span>
                  </div>
                </div>
                <div class="card-description">
                  <span>{{ item.content }}</span>
                </div>
                <div class="flex" style="justify-content: space-between">
                  <div class="card-text">
                    状态：
                    <!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】国标主状态优先展示 parseStatus----------- -->
                    <div v-if="item.parseStatus || isGbDoc(item)" class="card-text-status">
                      <a-tag v-if="item.parseStatus==='PARSING'" color="blue" size="small">解析中</a-tag>
                      <a-tag v-else-if="item.parseStatus==='PARSED'" color="orange" size="small">待确认结构</a-tag>
                      <a-tag v-else-if="item.parseStatus==='CONFIRMED'" color="cyan" size="small">已确认</a-tag>
                      <a-tag v-else-if="item.parseStatus==='INDEXING'" color="processing" size="small">入库中</a-tag>
                      <a-tag v-else-if="item.parseStatus==='COMPLETED'" color="green" size="small">国标已完成</a-tag>
                      <a-tag v-else-if="item.parseStatus==='UPLOADED' && hasMarkdownReady(item)" color="processing" size="small">待核对解析</a-tag>
                      <a-tag v-else-if="item.parseStatus==='UPLOADED'" color="default" size="small">待解析</a-tag>
                      <a-tag v-else-if="hasMarkdownReady(item)" color="processing" size="small">待核对解析</a-tag>
                      <a-tag v-else size="small">{{ item.parseStatus || '国标' }}</a-tag>
                    </div>
                    <div v-else-if="item.status==='complete'" class="card-text-status">
                      <Icon icon="ant-design:check-circle-outlined" size="16" color="#56D1A7"></Icon>
                      <span class="ml-2">已完成</span>
                    </div>
                    <!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】国标主状态优先展示 parseStatus----------- -->
                    <div v-else-if="item.status==='building'" class="card-text-status">
                      <a-spin v-if="item.loading" :spinning="item.loading" :indicator="indicator"></a-spin>
                      <span class="ml-2">构建中</span>
                    </div>
                    <div v-else-if="item.status==='draft'" class="card-text-status">
                      <img src="./icon/draft.png" style="width: 16px;height: 16px" />
                      <span class="ml-2">草稿</span>
                    </div>
                    <a-tooltip v-else-if="item.status==='failed'" :title="getDocFailedReason(item)">
                      <div class="card-text-status">
                        <Icon icon="ant-design:close-circle-outlined" size="16" color="#FF4D4F"></Icon>
                        <span class="ml-2">失败</span>
                      </div>
                    </a-tooltip>
                  </div>
                  <!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】卡片主按钮（按状态续跑；不依赖 filePath 后缀=pdf）----------- -->
                  <div v-if="isGbDoc(item)" class="card-text" style="margin-top: 4px;" @click.stop>
                    <a-button size="small" type="primary" ghost @click="handleGbWizardContinue(item)">
                      {{ gbActionLabel(item) }}
                    </a-button>
                  </div>
                  <!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】卡片主按钮----------- -->
                  <a-dropdown placement="bottomRight" :trigger="['click']">
                    <div class="ant-dropdown-link pointer operation" @click.prevent.stop>
                      <Icon icon="ant-design:ellipsis-outlined" size="16"></Icon>
                    </div>
                    <template #overlay>
                      <a-menu>
                        <!--update-begin---author:song ---date:2026-07-18  for：【GB线性入库】菜单改为打开线性向导----------- -->
                        <a-menu-item v-if="isGbDoc(item)" key="gbWizard" @click="handleGbWizardContinue(item)">
                          <Icon icon="ant-design:file-search-outlined" size="16"></Icon>
                          {{ gbActionLabel(item) }}
                        </a-menu-item>
                        <!--update-end---author:song ---date:2026-07-18  for：【GB线性入库】菜单改为打开线性向导----------- -->
                        <a-menu-item key="vectorization" @click="handleVectorization(item.id)">
                          <Icon icon="ant-design:retweet-outlined" size="16"></Icon>
                          向量化
                        </a-menu-item>
                        <a-menu-item key="edit" @click.stop="handleEdit(item)">
                          <Icon icon="ant-design:edit-outlined" size="16"></Icon>
                          编辑
                        </a-menu-item>
                        <a-menu-item key="delete" @click="handleDelete(item.id)">
                          <Icon icon="ant-design:delete-outlined" size="16"></Icon>
                          删除
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
              </a-card>
            </a-col>
          </a-row>
          <Pagination
            v-if="knowledgeDocDataList.length > 0"
            :current="pageNo"
            :page-size="pageSize"
            :page-size-options="pageSizeOptions"
            :total="total"
            :showQuickJumper="true"
            :showSizeChanger="true"
            @change="handlePageChange"
            class="list-footer"
            size="small"
            :show-total="() => `共${total}条` "
          />
        </div>

        <div v-if="selectedKey === 'hitTest'" style="padding: 16px">
          <a-spin :spinning="spinning">
            <div class="hit-test">
              <h4>命中测试</h4>
              <span>针对用户提问调试段落匹配情况，保障回答效果。</span>
            </div>
            <div class="content">
              <div class="content-title">
                <Avatar v-if="hitShowSearchText" :size="35" :src="avatar" />
                <span>{{ hitShowSearchText }}</span>
              </div>
              <div class="content-card">
                <a-row :span="24" class="knowledge-row" v-if="hitTextList.length>0">
                  <a-col :xxl="6" :xl="6" :lg="6" :md="6" :sm="12" :xs="24" v-for="(item, index) in hitTextList" :key="index">
                    <a-card class="hit-card pointer" style="border-color: #ffffff" @click="hitTextDescClick(item)">
                      <div class="card-title">
                        <div style="display: flex;">
                          <Icon icon="ant-design:appstore-outlined" size="14"></Icon>
                          <span style="margin-left: 4px">Chunk-{{item.chunk}}</span>
                          <span style="margin-left: 10px">{{ item.content.length }} 字符</span>
                        </div>
                        <a-tag class="card-title-tag" color="#a9c8ff">
                          <span>{{ getTagTxt(item.score) }}</span>
                        </a-tag>
                      </div>
                      <div class="card-description">
                        {{ item.content }}
                      </div>
                      <div class="card-footer">
                        {{item.docName}}
                      </div>
                    </a-card>
                  </a-col>
                </a-row>
                <div v-else-if="notHit">
                  <a-empty :image-style="{ margin: '0 auto', height: '160px', verticalAlign: 'middle', borderStyle: 'none' }">
                    <template #description>
                      <div style="margin-top: 26px; font-size: 20px; color: #000; text-align: center !important">
                        没有命中的分段
                      </div>
                    </template>
                  </a-empty>
                </div>
              </div>
            </div>
            <div class="param">
              <span style="font-weight: bold; font-size: 16px">参数配置</span>
              <ul>
                <li>
                  <span>条数：</span>
                  <a-input-number :min="1" v-model:value="topNumber"></a-input-number>
                </li>
                <li>
                  <span>Score阈值：</span>
                  <a-input-number :min="0" :step="0.01" :max="1" v-model:value="similarity"></a-input-number>
                </li>
              </ul>
            </div>
            <div class="hit-test-footer">
              <a-input v-model:value="hitText" size="large" placeholder="请输入" style="width: 100%" @press-enter="hitTestClick">
                <template #suffix>
                  <Icon icon="ant-design:send-outlined" style="transform: rotate(-33deg); cursor: pointer" size="22" @click="hitTestClick"></Icon>
                </template>
              </a-input>
            </div>
          </a-spin>
        </div>
      </a-layout-content>
    </a-layout>
    <Loading tip="上传中，请稍后" :loading="uploadLoading"></Loading>

    <!--  手工录入文本  -->
    <AiragKnowledgeDocTextModal @register="docTextRegister" @success="handleSuccess"></AiragKnowledgeDocTextModal>
    <!--  文本明细  -->
    <AiTextDescModal @register="docTextDescRegister"></AiTextDescModal>
    <!--  GB国标入库向导（上传→三栏核对→入库）  -->
    <GbIngestionWizard @register="gbWizardRegister" @success="handleGbConfirmed" />
  </div>
</template>

<script lang="tsx">
  import { onMounted, onBeforeUnmount, computed, ref, h, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useModal } from '@/components/Modal';
  import { knowledgeDocList, knowledgeDeleteBatchDoc, knowledgeRebuildDoc, knowledgeEmbeddingHitTest, queryById } from './AiKnowledgeBase.api';
  import { doDeleteAllDoc, parseMetadata } from './AiKnowledgeBase.api.util';
  import AiragKnowledgeDocTextModal from './components/AiragKnowledgeDocTextModal.vue';
  import AiTextDescModal from './components/AiTextDescModal.vue';
  import GbIngestionWizard from './components/GbIngestionWizard.vue';
  import { useMessage } from '@/hooks/web/useMessage';
  import { LoadingOutlined } from '@ant-design/icons-vue';
  import { Avatar, Modal, Pagination } from 'ant-design-vue';
  import { useUserStore } from '@/store/modules/user';
  import { getFileAccessHttpUrl, getHeaders } from '@/utils/common/compUtils';
  import defaultImg from '/@/assets/images/header.jpg';
  import Icon from "@/components/Icon";
  import { useGlobSetting } from '/@/hooks/setting';
  import Loading from '@/components/Loading/src/Loading.vue';

  export default {
    name: 'AiKnowledgeDocList',
    components: {
      Icon,
      Pagination,
      Avatar,
      AiragKnowledgeDocTextModal,
      AiTextDescModal,
      GbIngestionWizard,
      Loading,
    },
    setup() {
      const route = useRoute();
      const router = useRouter();

      //标题（显示实际知识库名称）
      const title = ref<string>('知识库文档管理');

      //知识库id（路由 query 传入，刷新不丢失）
      const knowledgeId = ref<string>('');
      //知识库名称
      const knowledgeName = ref<string>('');

      //菜单初始化选中的值
      const selectedKeys = ref(['document']);
      //菜单点击选中的key，用于显示和隐藏table
      const selectedKey = ref<string>('document');
      //定向搜索的文本
      const hitText = ref<string>('');
      //定向显示的文本
      const hitShowSearchText = ref<string>('');
      //加载效果
      const spinning = ref<boolean>(false);
      //最小分数 0-1
      const similarity = ref<number>(0.65);
      //条数
      const topNumber = ref<number>(5);
      //定向返回结果集
      const hitTextList = ref<any>([]);
      //用户头像
      const avatar = ref<any>('');
      const userStore = useUserStore();
      //文档列表
      const knowledgeDocDataList = ref<any>([]);
      //当前页数
      const pageNo = ref<number>(1);
      //每页条数
      const pageSize = ref<number>(10);
      //总条数
      const total = ref<number>(0);
      //可选择的页数
      const pageSizeOptions = ref<any>(['10', '20', '30']);
      //查询参数
      const searchText = ref<string>('');
      //是否没有命中
      const notHit = ref<boolean>(false);
      //定时任务刷新列表
      const timer = ref<any>(null);
      //token
      const headers = getHeaders();
      const globSetting = useGlobSetting();
      //上传路径
      const uploadUrl = ref<string>(globSetting.domainUrl+"/airag/knowledge/doc/import/zip");
      //上传加载
      const uploadLoading = ref<boolean>(false);

      //菜单项
      const menuItems = ref<any>([
        {
          key: 'document',
          icon: '',
          label: '文档',
          title: '文档',
        },
        {
          key: 'hitTest',
          icon: '',
          label: '命中测试',
          title: '命中测试',
        },
      ]);

      // 当前选中的行
      const selectedRows = computed(() => knowledgeDocDataList.value.filter(item => item.__checked))

      //注册子弹窗
      const [docTextRegister, { openModal: docTextOpenModal }] = useModal();
      const [docTextDescRegister, { openModal: docTextDescOpenModal }] = useModal();
      //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】向导 register-----------
      const [gbWizardRegister, { openModal: gbWizardOpenModal }] = useModal();
      //update-end---author:song ---date:2026-07-18  for：【GB线性入库】向导 register-----------
      const type = ref<string>('');
      // 知识库的分段策略 metadata
      const knowledgeMetadata = ref<string>('');

      const contentStyle = {
        textAlign: 'center',
        height: '100%',
        width: '80%',
        background: '#ffffff',
      };

      const siderStyle = {
        textAlign: 'center',
        width: '20%',
        background: '#ffffff',
        borderRight: '1px solid #cecece',
      };

      /**
       * 加载指示符
       */
      const indicator =  h(LoadingOutlined, {
        style: {
          fontSize: '16px',
          marginRight: '2px'
        },
        spin: true,
      });

      const { createMessage, createConfirmSync } = useMessage();

      /**
       * 页面初始化：从路由 query 取 knowledgeId，并查询知识库详情（名称/类型/分段策略）
       */
      async function init() {
        const queryId = route.query.knowledgeId;
        knowledgeId.value = (Array.isArray(queryId) ? queryId[0] : queryId) as string || '';
        const queryName = route.query.knowledgeName;
        knowledgeName.value = (Array.isArray(queryName) ? queryName[0] : queryName) as string || '';
        if (!knowledgeId.value) {
          createMessage.warning('缺少知识库参数，请从知识库列表进入');
          return;
        }
        selectedKeys.value = ['document'];
        selectedKey.value = 'document';
        spinning.value = false;
        notHit.value = false;
        // 查询知识库详情，获取类型与分段策略 metadata
        try {
          const res = await queryById({ id: knowledgeId.value });
          if (res.success && res.result) {
            type.value = res.result.type || '';
            knowledgeMetadata.value = res.result.metadata || '';
            if (!knowledgeName.value) {
              knowledgeName.value = res.result.name || '';
            }
          }
        } catch (e) {
          console.warn('查询知识库详情失败', e);
        }
        title.value = knowledgeName.value ? `知识库文档管理 - ${knowledgeName.value}` : '知识库文档管理';
        await reload();
      }

      /**
       * 返回知识库列表
       */
      function goBack() {
        router.push({ path: '/super/airag/aiknowledge/AiKnowledgeBaseList' });
      }

      /**
       * 手工录入文本
       */
      function handleCreateText() {
        //update-begin---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
        docTextOpenModal(true, { knowledgeId: knowledgeId.value, type: "text", knowledgeMetadata: knowledgeMetadata.value, knowledgeType: type.value });
        //update-end---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
      }

      /**
       * 文件上传
       */
      function handleCreateUpload() {
        //update-begin---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
        docTextOpenModal(true, { knowledgeId: knowledgeId.value, type: "file", knowledgeMetadata: knowledgeMetadata.value, knowledgeType: type.value });
        //update-end---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
      }

      /**
       * web网络地址
       */
      function handleCreateWeb() {
        //update-begin---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
        docTextOpenModal(true, { knowledgeId: knowledgeId.value, type: "web", knowledgeMetadata: knowledgeMetadata.value, knowledgeType: type.value });
        //update-end---wangshuai---date:20260414  for：【QQYUN-14932】创建知识库时，可以创建一个分段策略，知识库里面的文档默认使用知识库的分段策略------------
      }

      /**
       * 卡片点击：国标文档进入库向导续跑；其它走通用编辑
       */
      function handleCardClick(record) {
        if (selectedRows.value.length > 0) {
          record.__checked = !record.__checked;
          return;
        }
        if (isGbDoc(record)) {
          handleGbWizardContinue(record);
          return;
        }
        handleEdit(record);
      }

      /**
       * 编辑（通用文档 / 菜单「编辑」）
       */
      function handleEdit(record) {
        // 判断如果有选中的行，则说明是批量操作模式
        if (selectedRows.value.length > 0) {
          record.__checked = !record.__checked;
          return
        }


        if (record.type === 'text' || record.type === 'file' || record.type === 'web') {
          docTextOpenModal(true, {
            record,
            isUpdate: true,
            knowledgeMetadata: knowledgeMetadata.value,
            knowledgeType: type.value,
          });
        }
      }

      /**
       * 是否国标入库文档（由后端 gbDoc 字段显式标记；parseStatus 仅国标文档会赋值，作为兜底）
       */
      function isGbDoc(item) {
        if (!item || item.type !== 'file') return false;
        return item.gbDoc === true || !!item.parseStatus;
      }

      function hasMarkdownReady(item) {
        const meta = parseMetadata(item?.metadata);
        if (meta.markdownReady) return true;
        const fp = (meta.filePath || '').toLowerCase();
        return fp.endsWith('.md') || fp.includes('mineru');
      }

      /**
       * 删除
       * @param id
       */
      function handleDelete(id) {
        Modal.confirm({
          title: '提示',
          content: '确认要删除该文档吗?',
          okText: '确认',
          cancelText: '取消',
          onOk: () => {
            if(knowledgeDocDataList.value.length == 1 && pageNo.value > 1) {
              pageNo.value = pageNo.value - 1;
            }
            knowledgeDeleteBatchDoc({ ids: id }, reload);
          }
        })
      }

      function getDocFailedReason(doc) {
        const metadata = parseMetadata(doc?.metadata);
        return metadata?.failedReason || '构建失败，原因未知';
      }

      /**
       * 向量化
       *
       * @param id
       */
      async function handleVectorization(id) {
        await knowledgeRebuildDoc({ docIds: id }, handleSuccess);
      }

      //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】打开向导（新建/续跑）-----------
      function handleGbWizardCreate() {
        gbWizardOpenModal(true, { knowledgeId: knowledgeId.value });
      }

      function getPdfUrlFromRecord(record) {
        let pdfUrl = '';
        const meta = parseMetadata(record?.metadata);
        // 解析后 filePath 常为 .md，优先 originalFilePath（原始 PDF）
        const path = meta.originalFilePath || meta.filePath;
        if (path && /\.pdf($|\?)/i.test(path)) {
          pdfUrl = getFileAccessHttpUrl(path);
        } else if (path && meta.originalFilePath) {
          // 非 pdf 时仍尝试 original
          pdfUrl = getFileAccessHttpUrl(meta.originalFilePath);
        }
        return pdfUrl;
      }

      function gbActionLabel(item) {
        const s = item?.parseStatus;
        // markdownReady 但还没 PARSED：可继续解析结果页
        const meta = parseMetadata(item?.metadata);
        const markdownReady = !!meta.markdownReady;
        if (!s || s === 'UPLOADED') {
          return markdownReady ? '继续核对解析' : '开始解析';
        }
        if (s === 'PARSING') return '查看解析进度';
        if (s === 'PARSED') return '结构确认';
        if (s === 'INDEXING' || s === 'CONFIRMED') return '入库进度';
        if (s === 'COMPLETED') return '重新入库/查看';
        return '国标入库向导';
      }

      function handleGbWizardContinue(record) {
        gbWizardOpenModal(true, {
          knowledgeId: knowledgeId.value,
          docId: record.id,
          pdfUrl: getPdfUrlFromRecord(record),
        });
      }
      //update-end---author:song ---date:2026-07-18  for：【GB线性入库】打开向导（新建/续跑）-----------

      /**
       * GB国标解析确认回调 — 刷新文档列表
       */
      function handleGbConfirmed() {
        reload();
      }

      /**
       * 文档新增和编辑成功回调
       */
      function handleSuccess() {
        clearInterval(timer.value);
        timer.value = null;
        reload();
        triggeringTimer();
      }

      /**
       * 触发定时任务
       */
      function triggeringTimer() {
        timer.value = setInterval(() => {
          reload();
        },5000)
      }

      /**
       * 菜单点击事件
       * @param value
       */
      function handleMenuClick(value) {
        if (value.key === 'document') {
          setTimeout(() => {
            pageNo.value = 1;
            pageSize.value = 10;
            searchText.value = "";

            reload();
          });
        } else {
          hitTextList.value = [];
          hitShowSearchText.value = '';
          hitText.value = '';
          avatar.value = '';
          similarity.value = 0.65;
          topNumber.value = 5;
        }
        selectedKey.value = value.key;
      }

      /**
       * 命中测试
       */
      function hitTestClick() {
        if (hitText.value) {
          spinning.value = true;
          knowledgeEmbeddingHitTest({
            queryText: hitText.value,
            knowId: knowledgeId.value,
            topNumber: topNumber.value,
            similarity: similarity.value,
          }).then((res) => {
            if (res.success) {
              if (res.result) {
                hitTextList.value = res.result;
              } else {
                hitTextList.value = [];
              }
            }
            hitShowSearchText.value = hitText.value;
            avatar.value = userStore.getUserInfo.avatar ? getFileAccessHttpUrl(userStore.getUserInfo.avatar) : defaultImg;
            hitText.value = '';
            notHit.value = hitTextList.value.length == 0;
            spinning.value = false;
          }).catch(()=>{
            spinning.value = false;
          });
        }
      }

      /**
       * 获取文本
       * @param value
       */
      function getTagTxt(value) {
        return 'score' + ' ' + value.toFixed(2);
      }

      /**
       * 命中测试卡点击事件
       * @param values
       */
      function hitTextDescClick(values) {
        docTextDescOpenModal(true, { ...values });
      }

      /**
       * 加载表格
       */
      async function reload() {
        if (!knowledgeId.value) {
          return;
        }
        let params = {
          pageNo: pageNo.value,
          pageSize: pageSize.value,
          knowledgeId: knowledgeId.value,
          title: '*' + searchText.value + '*',
          column: 'createTime',
          order: 'desc'
        };
        await knowledgeDocList(params).then((res) => {
          if (res.success) {
            //update-begin---author:wangshuai---date:2025-03-21---for:【QQYUN-11636】向量化功能改成异步---
            if(res.result.records){
              let clearTimer = true;
              for (const item of res.result.records) {
                if(item.status && item.status === 'building' ){
                  clearTimer = false;
                  item.loading = true;
                }else{
                  item.loading = false;
                }
              }
              if(clearTimer){
                clearInterval(timer.value);
              }
            }
            //update-end---author:wangshuai---date:2025-03-21---for:【QQYUN-11636】向量化功能改成异步---
            knowledgeDocDataList.value = res.result.records.map((item)=>{
              item.__checked = false;
              return item;
            });
            total.value = res.result.total;
          } else {
            knowledgeDocDataList.value = [];
            total.value = 0;
          }
        });
      }

      /**
       * 分页改变事件
       * @param page
       * @param current
       */
      function handlePageChange(page, current) {
        pageNo.value = page;
        pageSize.value = current;
        reload();
      }

      /**
       * 获取文件后缀（优先 originalFilePath，避免解析后 filePath 变成 .md 认不出 PDF）
       */
      function getFileSuffix(metadata) {
        if (!metadata) return '';
        const meta = parseMetadata(metadata);
        const filePath = meta.originalFilePath || meta.filePath || '';
        if (!filePath) return '';
        const index = filePath.lastIndexOf('.');
        return index > 0 ? filePath.substring(index + 1).toLowerCase() : '';
      }

      /**
       * 上传前事件
       */
      function beforeUpload(file) {
        let fileType = file.type;
        if (fileType !== 'application/zip' && fileType !== 'application/x-zip-compressed') {
            createMessage.warning('请上传zip文件');
            return false;
        }
        uploadLoading.value = true;
        return true;
      }

      /**
       * 文件上传回调事件
       * @param info
       */
      function handleUploadChange(info) {
        let { file } = info;
        if (file.status === 'error' || (file.response && file.response.code == 500)) {
          createMessage.error(file.response?.message ||`${file.name} 上传失败,请查看服务端日志`);
          uploadLoading.value = false;
          return;
        }
        if (file.status === 'done') {

          if(!file.response.success){
            createMessage.warning(file.response.message);
            uploadLoading.value = false;
            return;
          }
          uploadLoading.value = false;
          createMessage.success(file.response.message);
          handleSuccess();
        }
      }

      function onClearSelected() {
        knowledgeDocDataList.value.forEach(item => {
          item.__checked = false;
        });
      }

      // 清空文档
      async function onDeleteAll() {
        pageNo.value = 1;
        doDeleteAllDoc(knowledgeId.value, reload);
      }

      // 批量删除
      async function onDeleteBatch() {
        const flag = await createConfirmSync({ title: '批量删除', content: `确定要删除这 ${selectedRows.value.length} 条数据吗？` });
        if (!flag) {
          return;
        }
        const ids = selectedRows.value.map(item => item.id)
        let number = knowledgeDocDataList.value.length - ids.length;
        if(number == 0 && pageNo.value > 1) {
          pageNo.value = pageNo.value - 1;
        }
        knowledgeDeleteBatchDoc({ ids }, reload);
      }

      /**
       * 回车搜索
       */
      function searchTextEnter(){
        pageNo.value = 1;
        reload();
      }

      onMounted(() => {
        init();
      });

      // keep-alive 缓存下切换知识库时重新初始化
      watch(() => route.query.knowledgeId, (val) => {
        const id = (Array.isArray(val) ? val[0] : val) as string || '';
        if (id && id !== knowledgeId.value) {
          pageNo.value = 1;
          searchText.value = '';
          init();
        }
      });

      // 页面卸载时清理轮询定时器
      onBeforeUnmount(() => {
        clearInterval(timer.value);
        timer.value = null;
      });

      return {
        title,
        goBack,
        docTextRegister,
        handleCreateText,
        beforeUpload,
        handleCreateUpload,
        handleCreateWeb,
        handleSuccess,
        contentStyle,
        siderStyle,
        selectedKeys,
        menuItems,
        handleMenuClick,
        selectedKey,
        hitTestClick,
        hitText,
        spinning,
        similarity,
        topNumber,
        hitShowSearchText,
        avatar,
        hitTextList,
        getTagTxt,
        docTextDescRegister,
        hitTextDescClick,
        knowledgeDocDataList,
        handleEdit,
        handleCardClick,
        isGbDoc,
        hasMarkdownReady,
        handleDelete,
        getDocFailedReason,
        handleVectorization,
        handleGbConfirmed,
        handleGbWizardCreate,
        handleGbWizardContinue,
        gbActionLabel,
        gbWizardRegister,
        pageNo,
        pageSize,
        pageSizeOptions,
        total,
        handlePageChange,
        searchText,
        reload,
        cardBodyStyle:{ textAlign: 'left', width: '100%' },
        getFileSuffix,
        notHit,
        indicator,
        headers,
        uploadUrl,
        handleUploadChange,
        knowledgeId,
        uploadLoading,
        selectedRows,
        onClearSelected,
        onDeleteAll,
        onDeleteBatch,
        searchTextEnter,
        type,
      };
    },
  };
</script>

<style scoped lang="less">
  @import './styles/knowledge-card.less';

  .ai-knowledge-doc-page {
    height: 100%;
    display: flex;
    flex-direction: column;
    background: #ffffff;

    .page-header {
      display: flex;
      align-items: center;
      padding: 12px 16px;
      border-bottom: 1px solid #f0f0f0;

      .back-btn {
        display: flex;
        align-items: center;
        color: #1677ff;
        font-size: 13px;

        span {
          margin-left: 4px;
        }
      }

      .page-title {
        margin-left: 16px;
        font-size: 16px;
        font-weight: 600;
        color: #1f2329;
      }
    }

    .page-body {
      flex: 1;
      overflow: hidden;
    }
  }

  .pointer {
    cursor: pointer;
  }

  .hit-test {
    box-sizing: border-box;
    flex-wrap: wrap;
    text-align: left;
    display: flex;
    margin-bottom: 10px;

    h4 {
      font-weight: bold;
      font-size: 16px;
      align-self: center;
      margin-bottom: 0;
      color: #1f2329;
    }

    span {
      margin-left: 10px;
      color: #8f959e;
      font-weight: 400;
      align-self: center;
      margin-top: 2px;
    }
  }

  .hit-test-footer {
    margin-top: 10px;
    display: flex;
  }
  .param {
    text-align: left;
    margin-top: 10px;
    ul {
      margin-top: 10px;
      display: flex;
      li {
        align-items: center;
        margin-right: 10px;
        display: flex;
      }
    }
    border-bottom: 1px solid #cec6c6;
  }
  .content {
    height: calc(100vh - 330px);
    padding: 8px;
    border-radius: 10px;
    background-color: #f9fbfd;
    overflow-y: auto;
    .content-title {
      font-size: 16px;
      font-weight: 600;
      text-align: left;
      margin-left: 10px;
      display: flex;
      span {
        margin-left: 4px;
        font-size: 20px;
        align-self: center;
      }
    }
    .content-card {
      margin-top: 20px;
      margin-left: 10px;
      .hit-card {
        height: 160px;
        margin-bottom: 10px;
        margin-right: 10px;
        border-radius: 10px;
        background: #fcfcfd;
        border: 1px solid #f0f0f0;
        box-shadow: 0 2px 4px #e6e6e6;
        transition: all 0.3s ease;
        .card-title {
          justify-content: space-between;
          color: #887a8b;
          font-size: 14px;
          display: flex;
        }
      }
    }
  }
  .hit-card:hover {
    box-shadow: 0 6px 12px #d0d3d8 !important;
  }
  .pointer {
    cursor: pointer;
  }

  .card-description {
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 4;
    height: 6em;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1.5;
    margin-top: 16px;
    text-align: left;
    font-size: 12px;
    color: #676F83;
  }

  .card-title-tag {
    color: #477dee;
  }

  .knowledge-row {
    padding: 16px;
    height: calc(100vh - 200px);
    overflow-y: auto;
  }

  .knowledge-card {
    .knowledge-checkbox {
      position: absolute;
      top: 8px;
      right: 8px;
      z-index: 1;
      align-items: center;
      justify-content: center;
      display: none;
    }

    &:hover, &.checked {
      .knowledge-checkbox {
        display: flex;
      }
    }

    &.checked {
      border: 1px solid @primary-color;
    }

    .knowledge-header {
      position: relative;
      font-size: 14px;
      height: 20px;
      text-align: left;
      .header-img {
        width: 40px;
        height: 40px;
        margin-right: 12px;
      }
      .header-title{
        font-weight: bold;
        color: #354052;
        margin-left: 4px;
        align-self: center;
      }

      .header-text {
        overflow: hidden;
        position: relative;
        display: flex;
        font-size: 16px;
      }
    }
  }

  .ellipsis {
    text-overflow: ellipsis;
    overflow: hidden;
    text-wrap: nowrap;
    width: calc(100% - 30px);
  }

  :deep(.ant-card .ant-card-body) {
    padding: 16px;
  }

  .card-text{
    font-size: 12px;
    display: flex;
    margin-top: 10px;
    align-items: center;
  }

  .search-header {
    margin-top: 10px;
    margin-left: 26px;

    .search-input {
      width: 240px;
      display: block;
    }
  }

  .add-knowledge-doc{
    margin-top: 6px;
    color:#6F6F83;
    font-size: 13px;
    width: 100%;
    cursor: pointer;
    display: flex;
    span{
      margin-left: 4px;
      line-height: 28px;
    }
  }
  .add-knowledge-doc:hover{
    background: #c8ceda33;
  }
  .card-footer{
    margin-top: 4px;
    font-weight: 400;
    color: #1f2329;
    text-align: left;
    font-size: 12px;
  }
  .card-text-status{
    display: flex;
    align-items: center;
  }
  .ml-2{
    margin-left: 2px;
  }
  .list-footer{
    text-align: right;
    margin: 5px 16px 10px 0;
  }
  .add-knowledge-doc {
    :deep(.ant-upload) {
      width: 100%;
    }
  }
</style>
