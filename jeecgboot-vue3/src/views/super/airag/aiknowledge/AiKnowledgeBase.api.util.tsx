import {knowledgeDeleteAllDoc} from "./AiKnowledgeBase.api";
import {useMessage} from "@/hooks/web/useMessage";

const {createConfirmSync} = useMessage();

// 清空文档
export async function doDeleteAllDoc(knowledgeId: string, reload: () => void) {
  const flag = await createConfirmSync({
    title: '清空文档',
    content: () => (
      <p>
        <span>确定要清空所有文档吗？</span>
        <br/>
        <span style="color: #ee0000;">
          此操作会删除所有已录入的文档，并且不能恢复，请谨慎操作
        </span>
      </p>
    ),
  });
  if (!flag) {
    return;
  }
  knowledgeDeleteAllDoc(knowledgeId, reload)
}

/**
 * 解析文档/知识库 metadata JSON，失败或为空时返回 {}
 * @param metadata metadata JSON 字符串或对象
 */
export function parseMetadata(metadata: any): Record<string, any> {
  if (!metadata) {
    return {};
  }
  try {
    return typeof metadata === 'string' ? JSON.parse(metadata) : metadata || {};
  } catch {
    return {};
  }
}

/**
 * 打包 metadata 对象为 JSON 字符串
 * @param metadata metadata 对象
 */
export function stringifyMetadata(metadata: Record<string, any> | null | undefined): string | null {
  if (!metadata) {
    return null;
  }
  return JSON.stringify(metadata);
}
