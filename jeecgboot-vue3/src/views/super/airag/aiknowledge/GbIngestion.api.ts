//update-begin---author:song ---date:2026-07-18  for：【GB线性入库】向导 API-----------
import { defHttp } from '/@/utils/http/axios';

enum Api {
  parse = '/airag/gb-standard',
  status = '/airag/gb-standard',
  markdown = '/airag/gb-standard',
  preview = '/airag/gb-standard/preview',
  confirm = '/airag/gb-standard',
  saveDoc = '/airag/knowledge/doc/edit',
}

/** 步骤①：仅落库，不向量化 */
export const saveGbDocUploadOnly = (params: Recordable) => {
  return defHttp.post({ url: Api.saveDoc, params }, { isTransformResponse: false });
};

/** 步骤②：触发正文解析（MinerU/Tika，不写向量） */
export const parseGbDocument = (docId: string, force = false) => {
  return defHttp.post(
    { url: `${Api.parse}/${docId}/parse`, params: { force } },
    { isTransformResponse: false },
  );
};

/** 轮询状态 */
export const getGbDocStatus = (docId: string) => {
  return defHttp.get({ url: `${Api.status}/${docId}/status` }, { isTransformResponse: false });
};

/** 获取 Markdown 正文 */
export const getGbMarkdown = (docId: string) => {
  return defHttp.get({ url: `${Api.markdown}/${docId}/markdown` }, { isTransformResponse: false });
};

/** 保存人工修正的 Markdown（写磁盘，不写 content 列） */
export const saveGbMarkdown = (docId: string, markdown: string) => {
  return defHttp.put(
    { url: `${Api.markdown}/${docId}/markdown`, params: { markdown } },
    { isTransformResponse: false },
  );
};

/** 步骤③：结构预览（docId 必须走 query，后端 @RequestParam） */
export const previewGbStructure = (docId: string) => {
  return defHttp.post(
    { url: Api.preview, params: { docId } },
    { joinParamsToUrl: true, isTransformResponse: false },
  );
};

/** 步骤④：确认入库 */
export const confirmGbIngestion = (docId: string) => {
  return defHttp.post({ url: `${Api.confirm}/${docId}/confirm` }, { isTransformResponse: false });
};
//update-end---author:song ---date:2026-07-18  for：【GB线性入库】向导 API-----------
