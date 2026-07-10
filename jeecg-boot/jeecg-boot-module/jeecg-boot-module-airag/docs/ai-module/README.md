# AI 模块详解 · 入口索引

> 本目录是 `jeecg-boot-module-airag` 模块的逐接口、逐方法、逐行源码级解读。
> 阅读路径：**先看本文的"模块总览"建立心智地图 → 按目录索引选目标接口 → 进入对应 .md → 顺着一条调用链读到底**。

---

## 一、模块总览

**模块路径**：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/`

**模块作用**：JeecgBoot 平台的 AI / RAG 能力中心。提供模型管理、知识库（向量检索）、AI 应用、对话（流式 SSE）、MCP 工具集成、AI 绘画 / 视频 / 语音 / Word 模板生成、提示词市场、评估器、调用轨迹。

**入口 SpringBoot 应用**：`org.jeecg.JeecgAiRagApplication`（独立可启动）。也可作为 JAR 包被 `jeecg-system-start` 拉起（实际部署模式）。

**与单体的耦合**：
- 通过 `org.jeecg.common.airag.api.IAiragBaseApi`（base-core 跨模块接口）暴露 5 个跨模块能力给其他业务模块调用
- 依赖 `dev.langchain4j` 做 LLM 编排，依赖 PostgreSQL + pgvector 做向量存储
- 会话 / 变量 / 任务进度存 Redis

**gitnexus 视角**：当前仓库索引有 34161 个符号、74940 条关系。本模块贡献 90+ 个 Java 类符号。`route_map /airag` 报告共 **101 个 REST 端点**。

---

## 二、本目录阅读指南

每份 `.md` 文档遵循 **"完整接口开始到结束"** 原则：从 HTTP 请求入口 → 控制器方法 → Service 层 → Handler 层 → 数据层（DB / Redis / 向量库）→ 响应回到前端，**单条调用链不断裂**。

代码块用 `path/to/File.java#行号-行号` 锚点可跳转到 gitnexus / IDE 真实位置。

每个文档末尾都附"对应源码文件列表"，列出本接口涉及的全部 Java 文件路径。

---

## 三、目录索引

### 3.1 通用入口

| 文档 | 内容 |
|------|------|
| [00-overview.md](00-overview.md) | 完整包结构图、调用链全景、模块依赖 |
| [README.md](README.md) | （本文档）总览与阅读路径 |

### 3.2 控制器层（按路由前缀分组）

每篇按 REST 入口 → 控制器方法 → Service → Handler → 数据层 完整展开。

| # | 接口前缀 | 文档 |
|---|---------|------|
| 01 | `/airag/chat/*` | [01-controller-chat.md](01-controller-chat.md) |
| 02 | `/airag/app/*` | [02-controller-app.md](02-controller-app.md) |
| 03 | `/airag/knowledge/*` | [03-controller-knowledge.md](03-controller-knowledge.md) |
| 04 | `/airag/airagModel/*` | [04-controller-model.md](04-controller-model.md) |
| 05 | `/airag/airagMcp/*` | [05-controller-mcp.md](05-controller-mcp.md) |
| 06 | `/airag/prompts/*` + `/airag/extData/*` | [06-controller-prompts.md](06-controller-prompts.md) |
| 07 | `/airag/ocr/*` | [07-controller-ocr.md](07-controller-ocr.md) |
| 08 | `/airag/video/*` | [08-controller-video.md](08-controller-video.md) |
| 09 | `/airag/voice/*` | [09-controller-voice.md](09-controller-voice.md) |
| 10 | `/airag/word/*` | [10-controller-word.md](10-controller-word.md) |
| 11 | `/airag/api/*` | [11-controller-baseapi.md](11-controller-baseapi.md) |

### 3.3 核心 Handler 层

| # | 类 | 文档 |
|---|----|------|
| 12 | `AIChatHandler` | [12-handler-aichat.md](12-handler-aichat.md) |
| 13 | `EmbeddingHandler` | [13-handler-embedding.md](13-handler-embedding.md) |
| 14 | `PluginToolBuilder` | [14-handler-plugin.md](14-handler-plugin.md) |
| 15 | `CommandExecUtil` / `WebPageParser` / `TikaDocumentParser` / `CustomDocumentSplitter` | [15-handler-parsers.md](15-handler-parsers.md) |

### 3.4 数据层 / 横切关注点

| # | 主题 | 文档 |
|---|------|------|
| 16 | Entity + Mapper + 表结构 | [16-data-entities.md](16-data-entities.md) |
| 17 | 常量 + 配置 Bean + Redis Key | [17-data-constants.md](17-data-constants.md) |
| 18 | 安全修复（漏洞编号对应） | [18-security.md](18-security.md) |
| 19 | 跨模块依赖 + 前端入口 | [19-cross-module.md](19-cross-module.md) |

---

## 四、阅读顺序建议

- **新接手 AI 模块的开发者**：`README.md` → `00-overview.md` → `01-controller-chat.md`（最长最复杂的接口，建议先读这条）
- **改知识库功能的**：直接读 `03-controller-knowledge.md` + `13-handler-embedding.md`
- **改 LLM 调用行为的**：`01-controller-chat.md` → `12-handler-aichat.md`
- **改 MCP / 插件机制的**：`05-controller-mcp.md` → `14-handler-plugin.md`
- **改安全漏洞的**：`18-security.md`

---

## 五、维护说明

- 本目录的 .md 与源码同步更新。修改 `.java` 后请同步更新本目录对应文档。
- 引用格式：`文件路径:行号`（如 `AiragChatController.java#57-59`）点击在 IDE 中可跳转。
- 若发现某接口的解读与代码不一致，请直接修改本文档并标注作者 / 日期。

---

## 六、版本与索引来源

- 模块代码版本：`jeecg-boot-module-airag`（随 `main` 分支）
- 索引数据来源：`gitnexus analyze`（项目根目录 `.gitnexus/`）
- 生成日期：2026-07-09
- 模块作者：jeecg-boot 团队（chenrui、scott、zhangdaihao、wangshuai 等）
- 最近修改热点：DeepSeek 推理模型兼容（#9585、#9607）、HTML 表格分段（#9551）、路径遍历防护（#9421-#9431）
