//update-begin---author:song ---date:2026-07-14  for：【GB检索P1】对齐后端 RetrievalResult / RetrievalResponse 字段-----------
import { defHttp } from '/@/utils/http/axios';

enum Api {
  query = '/airag/gb-standard/retrieval/query',
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
 * 获取来源通道标签颜色
 */
export const getSourceColor = (source: string): string => {
  const colorMap: Record<string, string> = {
    VECTOR: 'purple',
    STRUCTURE: 'cyan',
    TERM: 'orange',
    FUSION_RRF: 'gold',
    FUSION_WEIGHTED: 'lime',
  };
  return colorMap[source] || 'default';
};

/**
 * 获取来源通道显示名称
 */
export const getSourceLabel = (source: string): string => {
  const labelMap: Record<string, string> = {
    VECTOR: '向量检索',
    STRUCTURE: '结构化检索',
    TERM: '术语检索',
    FUSION_RRF: 'RRF融合',
    FUSION_WEIGHTED: '加权融合',
  };
  return labelMap[source] || source;
};
//update-end---author:song ---date:2026-07-14  for：【GB检索P1】对齐后端 RetrievalResult / RetrievalResponse 字段-----------
