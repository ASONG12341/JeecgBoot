//update-begin---author:song ---date:2026-07-14  for：【GB检索P1】注册 GB 知识检索路由-----------
import type { AppRouteModule } from '/@/router/types';

const gbRetrieval: AppRouteModule = {
  path: '/super/airag/aiknowledge/gb-retrieval',
  name: 'GbRetrieval',
  component: () => import('/@/views/super/airag/aiknowledge/GbRetrievalPage.vue'),
  meta: {
    title: 'GB 知识检索',
    ignoreKeepAlive: false,
  },
};

export default gbRetrieval;
//update-end---author:song ---date:2026-07-14  for：【GB检索P1】注册 GB 知识检索路由-----------
