import type { AppRouteRecordRaw } from '/@/router/types';
import { LAYOUT } from '/@/router/constant';

export const AI_ROUTE: AppRouteRecordRaw = {
  path: '',
  name: 'ai-parent',
  component: LAYOUT,
  meta: {
    title: 'ai',
  },
  children: [
    {
      path: '/ai',
      name: 'ai',
      component: () => import('/@/views/dashboard/ai/index.vue'),
      meta: {
        title: 'AI助手',
      },
    },
  ],
};

//update-begin---author:song ---date:2026-07-14  for：【GB检索P1】BACK 权限模式下注册 GB 检索静态路由，避免 404-----------
export const GB_RETRIEVAL_ROUTE: AppRouteRecordRaw = {
  path: '/super/airag/aiknowledge/gb-retrieval',
  name: 'GbRetrieval',
  component: LAYOUT,
  meta: {
    title: 'GB 知识检索',
  },
  children: [
    {
      path: '',
      name: 'GbRetrievalPage',
      component: () => import('/@/views/super/airag/aiknowledge/GbRetrievalPage.vue'),
      meta: {
        title: 'GB 知识检索',
        ignoreKeepAlive: false,
      },
    },
  ],
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P1】BACK 权限模式下注册 GB 检索静态路由，避免 404-----------

//update-begin---author:song ---date:2026-07-18  for：【知识库文档管理】全屏弹窗改为独立路由页面-----------
export const KNOWLEDGE_DOC_LIST_ROUTE: AppRouteRecordRaw = {
  path: '/super/airag/aiknowledge/doc-list',
  name: 'AiKnowledgeDocList',
  component: LAYOUT,
  meta: {
    title: '知识库文档管理',
  },
  children: [
    {
      path: '',
      name: 'AiKnowledgeDocListPage',
      component: () => import('/@/views/super/airag/aiknowledge/AiKnowledgeDocList.vue'),
      meta: {
        title: '知识库文档管理',
        ignoreKeepAlive: false,
      },
    },
  ],
};
//update-end---author:song ---date:2026-07-18  for：【知识库文档管理】全屏弹窗改为独立路由页面-----------

export const staticRoutesList = [AI_ROUTE, GB_RETRIEVAL_ROUTE, KNOWLEDGE_DOC_LIST_ROUTE];
