//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 前端 API-----------
import { defHttp } from '/@/utils/http/axios';

enum Api {
  preview = '/airag/gb-standard/preview',
  saveStructure = '/airag/gb-standard',
  confirm = '/airag/gb-standard',
}

/**
 * 触发国标文档结构解析，返回预览数据
 * @param docId 知识库文档 ID
 */
export const previewGbStandard = (docId: string) => {
  return defHttp.post({ url: Api.preview, params: { docId } }, { isTransformResponse: false });
};

/**
 * 保存用户修正后的文档结构
 * @param docId 文档 ID
 * @param structure 修正后的结构数据
 */
export const saveGbStructure = (docId: string, structure: any) => {
  return defHttp.put({ url: `${Api.saveStructure}/${docId}/structure`, params: structure }, { isTransformResponse: false });
};

/**
 * 确认解析结果，触发后续管线
 * @param docId 文档 ID
 */
export const confirmGbStandard = (docId: string) => {
  return defHttp.post({ url: `${Api.confirm}/${docId}/confirm` }, { isTransformResponse: false });
};
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 前端 API-----------
