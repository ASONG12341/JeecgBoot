//update-begin---author:song ---date:2026-07-14  for：【GB检索P1】对齐后端 RetrievalResult / RetrievalResponse 字段-----------
import { defHttp } from '/@/utils/http/axios';

enum Api {
  query = '/airag/gb-standard/retrieval/query',
  extractIntent = '/airag/gb-standard/retrieval/extract-intent',
  queryClause = '/airag/gb-standard/retrieval/clause',
  queryParameter = '/airag/gb-standard/retrieval/parameter',
}

/**
 * GB 检索请求参数
 */
export interface GbRetrievalRequest {
  standardId?: string;
  knowledgeIds?: string[];
  query: string;
  intent?: string;
  clauseNumber?: string;
  parameterName?: string;
  topK?: number;
  similarityThreshold?: number;
  enableVector?: boolean;
  enableStructure?: boolean;
  enableTerm?: boolean;
  vectorWeight?: number;
  structureWeight?: number;
  termWeight?: number;
  includeMetadata?: boolean;
  userId?: string;
  sessionId?: string;
}

/**
 * 意图 DTO
 */
export interface GbIntentDTO {
  intentType?: string;
  standardNo?: string;
  clauseNo?: string;
  paramName?: string;
  keywords?: string[];
  confidence?: number;
}

/**
 * 相关参数项
 */
export interface GbParameterItem {
  paramName?: string;
  paramValue?: string;
  unit?: string;
  formula?: string;
  conditionExpr?: string;
}

/**
 * 引用关系项
 */
export interface GbReferenceItem {
  targetStandardNo?: string;
  targetClausePath?: string;
  relationType?: string;
}

/**
 * 检索结果项
 */
export interface RetrievalResult {
  resultId?: string;
  standardId?: string;
  standardNo?: string;
  standardName?: string;
  clauseId?: string;
  clausePath?: string;
  title?: string;
  text?: string;
  clauseType?: string;
  requirementStrength?: string;
  score: number;
  channelScores?: Record<string, number>;
  sourceChannel: string;
  parameters?: GbParameterItem[];
  references?: GbReferenceItem[];
  metadata?: Record<string, any>;
}

/**
 * 检索响应
 */
export interface GbRetrievalResponse {
  queryId?: string;
  query?: string;
  intent?: GbIntentDTO;
  results: RetrievalResult[];
  total: number;
  duration: number;
  metadata?: Record<string, any>;
  success?: boolean;
  errorMessage?: string;
  stats?: Record<string, any>;
}

/**
 * 智能检索
 */
export const queryGbStandard = (params: GbRetrievalRequest) => {
  return defHttp.post<GbRetrievalResponse>({ url: Api.query, params });
};

/**
 * 提取意图
 */
export const extractIntent = (query: string) => {
  return defHttp.get<string>({ url: Api.extractIntent, params: { query } });
};

/**
 * 快速条款查询
 */
export const queryClause = (standardId: string, clauseNumber: string) => {
  return defHttp.get<GbRetrievalResponse>({
    url: `${Api.queryClause}/${standardId}/${clauseNumber}`,
  });
};

/**
 * 参数查询
 */
export const queryParameter = (standardId: string, parameterName: string) => {
  return defHttp.get<GbRetrievalResponse>({
    url: `${Api.queryParameter}/${standardId}`,
    params: { parameterName },
  });
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P1】对齐后端 RetrievalResult / RetrievalResponse 字段-----------
