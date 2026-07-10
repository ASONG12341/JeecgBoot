# 19 · 跨模块依赖与前端入口

> 本文档列举 AI 模块对外暴露的接口（被其他业务模块调用）与对其他模块的依赖，以及前端 Vue3 模块结构。

---

## 一、被外部业务模块消费的接口

### 1.1 `IAiragBaseApi`（最常用）

声明位置：`jeecg-module-system/jeecg-system-api/{cloud,local}-api/.../org/jeecg/common/airag/api/IAiragBaseApi.java`

被以下模块隐式调用：
- **Online 表单**（`jeecg-boot-module-online`）：表单提交后入库 AI 知识库
- **流程引擎**（`jeecg-boot-module-bpm-flowable`）：流程节点数据写入知识库
- **BI 大屏**（`jeecg-boot-module-bigscreen`）：AI 应用市场首页展示
- **任何业务模块**：通过 `@Autowired IAiragBaseApi` 注入即可调用

5 个方法：

| 方法 | 用途 |
|------|------|
| `knowledgeWriteTextDocument` | 业务数据写入知识库，让 AI 检索时能找到 |
| `getChatVariable` | 取会话变量（用户偏好、自定义状态） |
| `setChatVariable` | 写会话变量 |
| `getMemoryIdByAppId` | 查 App 关联的记忆库 ID |
| `getPromptContent` | 按 ID 取提示词内容 |

### 1.2 base-core 跨模块工具

| 类 | 位置 | 模块 |
|----|------|------|
| `LLMHandler` | `org.jeecg.ai.handler.LLMHandler` | base-core |
| `AiModelFactory` | `org.jeecg.ai.factory.AiModelFactory` | base-core |
| `AiModelOptions` | `org.jeecg.ai.factory.AiModelOptions` | base-core |
| `AiChatConfig` | `org.jeecg.config.AiChatConfig` | base-core |
| `AiRagConfigBean` | `org.jeecg.config.AiRagConfigBean` | base-core |
| `IAIChatHandler` | `org.jeecg.common.handler.IAIChatHandler` | base-core |
| `IEmbeddingHandler` | `org.jeecg.common.handler.IEmbeddingHandler` | base-core |
| `AIChatParams` | `org.jeecg.common.handler.AIChatParams` | base-core |
| `KnowledgeSearchResult` | `org.jeecg.common.vo.knowledge.KnowledgeSearchResult` | base-core |
| `AiragFlowDTO` | `org.jeecg.common.api.dto.AiragFlowDTO` | base-core |
| `LocalCache` / `AiragLocalCache` | base-core | base-core |
| `JeecgBizToolsProvider`（system-biz 实现） | `org.jeecg.modules.airag.JeecgBizToolsProvider` | system-biz |
| `SsrfFileTypeFilter` | `org.jeecg.common.util.filter.SsrfFileTypeFilter` | base-core |
| `UserTokenContext` / `TenantContext` | base-core | base-core |

---

## 二、AI 模块内部对外部的依赖

### 2.1 框架依赖（pom.xml）

```xml
<dependencies>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-core</artifactId>      <!-- LLM 编排 -->
        <artifactId>langchain4j-open-ai</artifactId>   <!-- OpenAI 兼容 -->
        <artifactId>langchain4j-mcp</artifactId>      <!-- MCP 协议 -->
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
    </dependency>
    <!-- pgvector -->
    <dependency>
        <groupId>com.pgvector</groupId>
        <artifactId>pgvector</artifactId>     <!-- langchain4j-pgvector -->
    </dependency>
    <dependency>
        <groupId>org.apache.poi</groupId>     <!-- Word/PowerPoint/Excel -->
        <artifactId>poi-*</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-core</artifactId>     <!-- PDF/TXT/MD 解析 -->
    </dependency>
    <dependency>
        <groupId>org.jsoup</groupId>          <!-- 网页抓取 -->
        <artifactId>jsoup</artifactId>
    </dependency>
    <dependency>
        <groupId>com.alibaba.fastjson2</groupId>
        <artifactId>fastjson2</artifactId>
    </dependency>
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>  <!-- Redis 客户端 -->
    </dependency>
    <dependency>
        <groupId>cn.hutool</groupId>          <!-- 工具 -->
        <artifactId>hutool-core</artifactId>
    </dependency>
</dependencies>
```

### 2.2 运行时外部服务

| 服务 | 用途 |
|------|------|
| **MySQL** | 9 张业务表 |
| **PostgreSQL + pgvector** | 向量库（`embeddings` 表 + IVFFlat 索引） |
| **Redis** | 会话 / 变量 / 任务状态 |
| **MinIO / AliyunOSS / Local** | 文件存储（uploadpath） |
| **第三方大模型 API** | OpenAI / DeepSeek / 通义千问 / 智普 / Ollama 等 |
| **TTS API** | 文生语音（Azure / CosyVoice / OpenAI TTS） |
| **视频生成 API** | CogVideoX / Vidu / Runway |
| **MCP 服务** | 外部 SSE/HTTP/Stdio MCP 端点 |

---

## 三、前端 Vue3 模块（`jeecgboot-vue3/src/views/super/airag/`）

按业务子领域分为 12 个子目录：

### 3.1 目录结构

```
src/views/super/airag/
├── aiapp/              # AI 应用管理
│   ├── AiAppList.vue                 列表页（编辑/删除/调试）
│   ├── AiApp.api.ts                  后端 API 客户端
│   ├── AiApp.data.ts                 表头 / 字典配置
│   ├── chat/
│   │   ├── chat.vue / AiChat.vue    聊天主界面
│   │   ├── chatMessage.vue          单消息组件
│   │   ├── ThinkText.vue            推理模型思考过程渲染
│   │   ├── presetQuestion.vue       预设问题
│   │   ├── chatText.vue             文本消息
│   │   ├── slide.vue                幻灯片渲染（用于 AI 写作）
│   │   ├── hooks/useChat.ts          ★ SSE 流式处理核心
│   │   ├── hooks/useScroll.ts       滚动到底部
│   │   ├── jeecg-tags/
│   │   │   ├── jeecg-chart/         图表渲染
│   │   │   └── tool-exec/           工具调用展示
│   │   ├── portal/
│   │   │   ├── AppPortal.vue        嵌入第三方系统
│   │   │   └── LeftPortalSession.vue  门户会话列表
│   │   └── js/chat.js               兼容旧版
│   └── components/
│       ├── AiAppModal.vue           编辑模态
│       ├── AiAppAddFlowModal.vue    关联流程
│       ├── AiAppAddKnowledgeModal.vue  关联知识库
│       ├── AiAppAddMcpModal.vue     关联 MCP/插件
│       ├── AiAppGeneratedPromptModal.vue  生成提示词
│       ├── AiAppParamsSettingModal.vue    参数设置
│       ├── AiAppPromptMarketModal.vue     提示词市场选择
│       ├── AiAppQuickCommandModal.vue     快捷指令
│       ├── AiAppSendModal.vue        发送测试
│       ├── AiAppSettingModal.vue     设置
│       └── AiUserVariablesModal.vue 用户变量
│
├── aiknowledge/         # 知识库
│   ├── AiKnowledgeBaseList.vue       列表页
│   ├── AiKnowledgeBase.api.ts         API
│   ├── AiKnowledgeBase.api.util.tsx   工具方法
│   ├── AiKnowledgeBase.data.ts        字典
│   └── components/
│       ├── AiKnowledgeBaseModal.vue  编辑知识库
│       ├── AiTextDescModal.vue       文本描述
│       ├── AiragKnowledgeDocListModal.vue  文档列表
│       └── AiragKnowledgeDocTextModal.vue  文档内容预览
│
├── aimodel/             # 模型配置
│   ├── AiModelList.vue
│   ├── model.api.ts
│   ├── model.data.ts
│   └── components/
│       ├── AiModelModal.vue          编辑
│       └── AiModelSeniorForm.vue     高级参数
│
├── aimcp/               # MCP / 插件
│   ├── AiragMcpList.vue
│   ├── AiragMcp.api.ts
│   ├── AiragMcp.data.ts
│   └── components/
│       ├── AiragMcpAddModal.vue
│       ├── AiragMcpDetailModal.vue
│       └── PluginToolEditModal.vue
│
├── aiposter/             # AI 海报
│   ├── AiPainting.vue
│   ├── AiPoster.vue
│   └── AiPoster.data.ts
│
├── aiccloth/             # AI 换衣
│   ├── AiClothChange.data.ts
│   └── AiClothChange.vue
│
├── aiprompts/            # 提示词 + 评估器
│   ├── AiragPromptsList.vue
│   ├── AiragExtDataList.vue        评估器/轨迹列表
│   ├── AiragExtDataExperiment.vue
│   ├── ...api.ts / ...data.ts
│   └── components/
│       ├── AiPromptSettingModal.vue
│       ├── AiragDataSetModal.vue
│       ├── AiragDataSetDataDrawer.vue
│       ├── EvaluatorDebug.vue
│       └── AiragTrackDetailModal.vue
│
├── aivideo/ + aivideo2/   # AI 视频
├── aivoice/              # AI 语音
├── aiwriter/             # AI 写作
├── ocr/                  # OCR
└── wordtpl/              # Word 模板
```

### 3.2 注册方式

**动态注册**（`src/views/super/registerSuper.ts`）：
```typescript
import.meta.glob('./**/register.ts')   // ★ 自动发现所有子模块的 register.ts
```

每个子模块内的 `register.ts` 注册自己的路由 / 菜单 / 权限标识。

### 3.3 关键 SSE Hook：`useChat.ts`

```typescript
// chat/hooks/useChat.ts 简化示意
import { useEventSource } from '@vueuse/core'

export function useChat(appId: string) {
  const messages = ref<Message[]>([])
  const eventSource = useEventSource('/airag/chat/send', ['message'])
  
  watch(eventSource.data, (data) => {
    const event = JSON.parse(data)
    if (event.type === 'MESSAGE') {
      messages.value[msgIndex].content += event.data.message
    } else if (event.type === 'MESSAGE_END') {
      // 流结束
    }
  })
  
  return { messages, send }
}
```

要点：
- **SSE 自动重连**：`@vueuse/core` 的 `useEventSource` 提供
- **断线重连**：用 `GET /airag/chat/receive/{requestId}` 拿上次的 emitter 重新订阅

---

## 四、API 客户端封装

每个子模块都有一个 `.api.ts`，封装 `defHttp`（项目自定义 Axios 客户端）：

```typescript
// AiKnowledgeBase.api.ts 简化
import { defHttp } from '/@/utils/http/axios'

export const list = (params) => defHttp.get({ url: '/airag/knowledge/list', params })
export const add = (data) => defHttp.post({ url: '/airag/knowledge/add', data })
export const edit = (data) => defHttp.put({ url: '/airag/knowledge/edit', data })
export const del = (id) => defHttp.delete({ url: `/airag/knowledge/delete?id=${id}` })
```

`defHttp` 默认注入 `X-Access-Token`、响应解包为 `{code, result, message, success}`。

---

## 五、模块注册与发现

后端通过 `pom.xml` 把 airag 引入：

```xml
<dependency>
    <groupId>org.jeecg.modules</groupId>
    <artifactId>jeecg-boot-module-airag</artifactId>
    <version>${project.version}</version>
</dependency>
```

模块独立可启动：`JeecgAiRagApplication`，也可作为 JAR 注入 `jeecg-system-start`。

Spring Boot 自动扫描 `org.jeecg.modules.airag.**` 包下所有 `@Component` / `@Service` / `@RestController`。

---

## 六、维护清单

修改 AI 模块前必查：

| 该做的事 | 资源 |
|---------|------|
| 改 Controller 路径 / 入参 | 前端 `.api.ts` 同步改 |
| 改 Service 方法签名 | 检查前端调用方 + 跨模块调用方 |
| 改 Entity 字段 | MyBatis-Plus 自动 DDL（dev）；Flyway 迁移脚本（生产） |
| 改 Redis Key 模式 | 缓存击穿 → 旧数据失访问 |
| 加新 issue 安全修复 | 在 gitnexus 里跑 `detect_changes()` 看影响范围 |

---

## 七、完整文档索引

```
docs/ai-module/
├── README.md                       # 入口
├── 00-overview.md                  # 包结构 + 调用链
├── 01-controller-chat.md           # /airag/chat/*
├── 02-controller-app.md            # /airag/app/*
├── 03-controller-knowledge.md      # /airag/knowledge/*
├── 04-controller-model.md          # /airag/airagModel/*
├── 05-controller-mcp.md            # /airag/airagMcp/*
├── 06-controller-prompts.md       # /airag/prompts/* + extData/*
├── 07-controller-ocr.md            # /airag/ocr/*
├── 08-controller-video.md          # /airag/video/*
├── 09-controller-voice.md          # /airag/voice/*
├── 10-controller-word.md           # /airag/word/*
├── 11-controller-baseapi.md        # /airag/api/* + IAiragBaseApi
├── 12-handler-aichat.md            # AIChatHandler
├── 13-handler-embedding.md         # EmbeddingHandler
├── 14-handler-plugin.md            # PluginToolBuilder + Parsers + Splitters
├── 15-data-entities.md             # 9 张表 + Redis Key + yml
├── 16-constants.md                 # 常量全集
├── 18-security.md                  # 安全修复
└── 19-cross-module.md              # 跨模块 + 前端（本文档）
```
