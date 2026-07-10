# 11 · `/airag/api/*` 与 `IAiragBaseApi` · 完整解读

> 本文档介绍 **跨模块** 的 AI 能力开放接口。任何业务模块（如 online 表单、流程引擎、BI）都可以通过 `IAiragBaseApi` 调用 AI 模块的知识库写入、变量读写、提示词获取、记忆库获取能力。
>
> 接口分两个实现：Feign 版（cloud-api）和 Local 版（local-api），由 `jeecg-module-system/jeecg-system-api/jeecg-system-api-{cloud,local}/.../org/jeecg/common/airag/api/IAiragBaseApi` 提供。

---

## 一、`IAiragBaseApi` 接口定义

文件位置：
- `jeecg-module-system/jeecg-system-api/jeecg-system-cloud-api/src/main/java/org/jeecg/common/airag/api/IAiragBaseApi.java`（Feign 版本）
- `jeecg-module-system/jeecg-system-api/jeecg-system-local-api/src/main/java/org/jeecg/common/airag/api/IAirragBaseApi.java`（本地直调版本）

```java
public interface IAiragBaseApi {
    /**
     * 写入知识库文本文档（用于让其他模块的代码动态写入知识库）
     */
    String knowledgeWriteTextDocument(String knowledgeId, String title, String content, String segmentConfig);
    
    /**
     * 取会话变量
     */
    String getChatVariable(String appId, String username, String name);
    
    /**
     * 写会话变量
     */
    void setChatVariable(String appId, String username, String name, String value);
    
    /**
     * 查 app 关联的记忆库 ID
     */
    String getMemoryIdByAppId(String appId);
    
    /**
     * 查 promptKey 对应的提示词内容
     */
    String getPromptContent(String promptId);
}
```

云端版引入 `@FeignClient` 注解，通过 Nacos 服务发现调远端 `jeecg-system` 服务；本地版直接 `@Autowired` 注入 `AiragBaseApiImpl`（进程内直调，避免 HTTP 跳转）。

**降级链路**：
- Feign：失败触发 `AiragBaseApiFallbackFactory` 生成的 fallback（默认返回 null + 错误日志）
- Local：异常向上抛，由调用方 catch

---

## 二、`AiragBaseApiController` —— HTTP 端点

文件 `jeecg-boot-module-airag/.../airag/llm/controller/AiragBaseApiController.java`（62 行）。

```java
@RestController("airagBaseApiController")
public class AiragBaseApiController implements IAiragBaseApi {
    
    @Autowired
    AiragBaseApiImpl airagBaseApi;
    
    @PostMapping("/airag/api/knowledgeWriteTextDocument")
    public String knowledgeWriteTextDocument(
            @RequestParam("knowledgeId") String knowledgeId,
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam(value = "segmentConfig", required = false) String segmentConfig
    ) {
        return airagBaseApi.knowledgeWriteTextDocument(knowledgeId, title, content, segmentConfig);
    }
    
    @PostMapping("/airag/api/getChatVariable")
    public String getChatVariable(
            @RequestParam("appId") String appId,
            @RequestParam("username") String username,
            @RequestParam("name") String name
    ) {
        return airagBaseApi.getChatVariable(appId, username, name);
    }
    
    @PostMapping("/airag/api/setChatVariable")
    public void setChatVariable(
            @RequestParam("appId") String appId,
            @RequestParam("username") String username,
            @RequestParam("name") String name,
            @RequestParam("value") String value
    ) {
        airagBaseApi.setChatVariable(appId, username, name, value);
    }
    
    @PostMapping("/airag/api/getMemoryIdByAppId")
    public String getMemoryIdByAppId(@RequestParam("appId") String appId) {
        return airagBaseApi.getMemoryIdByAppId(appId);
    }
    
    @PostMapping("/airag/api/getPromptContent")
    public String getPromptContent(@RequestParam("promptId") String promptId) {
        return airagBaseApi.getPromptContent(promptId);
    }
}
```

**逐行解读**：
- 控制器 `implements IAiragBaseApi` —— 接口签名复用
- `@RestController("airagBaseApiController")` —— Bean 名唯一，避免与 cloud-api 的同名 Feign Client 冲突
- 全部 `@PostMapping`，参数用 `@RequestParam`（简单 string）

注意：HTTP 端点只把请求转发给 `airagBaseApi`（实现类），真正的业务逻辑在 §三。

---

## 三、`AiragBaseApiImpl` 实现

文件 `airag/api/AiragBaseApiImpl.java`（108 行）。

```java
@Slf4j
@Primary
@Service("airagBaseApiImpl")
public class AiragBaseApiImpl implements IAiragBaseApi {

    @Autowired
    private IAiragKnowledgeDocService airagKnowledgeDocService;

    @Override
    public String knowledgeWriteTextDocument(String knowledgeId, String title, String content, String segmentConfig) {
        AssertUtils.assertNotEmpty("知识库ID不能为空", knowledgeId);
        AssertUtils.assertNotEmpty("写入内容不能为空", content);
        
        AiragKnowledgeDoc knowledgeDoc = new AiragKnowledgeDoc();
        knowledgeDoc.setKnowledgeId(knowledgeId);
        knowledgeDoc.setTitle(title);
        knowledgeDoc.setType(LLMConsts.KNOWLEDGE_DOC_TYPE_TEXT);   // "text"
        knowledgeDoc.setContent(content);
        // ★ segmentConfig 写到 metadata，传给 EmbeddingHandler 让它按自定义策略分段
        if (oConvertUtils.isNotEmpty(segmentConfig)) {
            knowledgeDoc.setMetadata(segmentConfig);
        }
        
        Result<?> result = airagKnowledgeDocService.editDocument(knowledgeDoc);
        if (!result.isSuccess()) {
            throw new JeecgBootBizTipException(result.getMessage());
        }
        if (knowledgeDoc.getId() == null) {
            throw new JeecgBootBizTipException("知识库文档ID为空");
        }
        log.info("[AI-KNOWLEDGE] 文档写入完成，知识库:{}, 文档ID:{}", knowledgeId, knowledgeDoc.getId());
        return knowledgeDoc.getId();   // 返回新文档 ID
    }

    @Autowired
    private IAiragAppService airagAppService;
    @Autowired
    private IAiragVariableService airagVariableService;
    @Autowired
    private IAiragPromptsService airagPromptsService;

    @Override
    public String getChatVariable(String appId, String username, String name) {
        return airagVariableService.getVariable(username, appId, name);
    }

    @Override
    public void setChatVariable(String appId, String username, String name, String value) {
        AssertUtils.assertNotEmpty("应用ID不能为空", appId);
        AssertUtils.assertNotEmpty("用户名不能为空", username);
        AssertUtils.assertNotEmpty("变量名不能为空", name);
        airagVariableService.updateVariable(username, appId, name, value != null ? value : "");
    }

    @Override
    public String getMemoryIdByAppId(String appId) {
        if (oConvertUtils.isEmpty(appId)) return null;
        // ★ 查 izOpenMemory=1 AND memoryId 非空的 App
        LambdaQueryWrapper<AiragApp> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AiragApp::getId, appId)
                .eq(AiragApp::getIzOpenMemory, 1)    // 记忆开启
                .isNotNull(AiragApp::getMemoryId)
                .ne(AiragApp::getMemoryId, "")
                .select(AiragApp::getMemoryId);     // 只查 memoryId 列
        AiragApp app = airagAppService.getOne(queryWrapper);
        return app != null ? app.getMemoryId() : null;
    }

    @Override
    public String getPromptContent(String promptId) {
        if (oConvertUtils.isEmpty(promptId)) return null;
        AiragPrompts prompt = airagPromptsService.getById(promptId);
        if (prompt == null) {
            log.warn("[AiragBaseApi]提示词不存在，promptId={}", promptId);
            return null;
        }
        return prompt.getContent();
    }
}
```

**逐行解读**：

| 方法 | 关键点 |
|------|------|
| `knowledgeWriteTextDocument` | 1) 强制 type=text；2) `segmentConfig` JSON 直接写到 metadata；3) 走 `AirragKnowledgeDocServiceImpl.editDocument`（异步向量化）；4) 返回新 docId 给调用方，调用方可以后续查询嵌入状态 |
| `getChatVariable` | 直接委托给 `IAirragVariableService`，Redis Hash 读 |
| `setChatVariable` | 委托给 `updateVariable`，Redis Hash 写 |
| `getMemoryIdByAppId` | ★ 关键查询条件：`izOpenMemory=1 AND memoryId非空`。返回的是关联到 App 的记忆库 ID（airag_knowledge.type=memory） |
| `getPromptContent` | 按 promptId 查 `airag_prompts.content`，可能为 null（提示词不存在） |

**`@Primary` 注解**：在 Spring 注入 `IAirragBaseApi` 时，本地版胜出（优先于 Feign 客户端）。

---

## 四、典型使用场景

### 4.1 在线表单（Online）写入知识库

```java
// 在表单提交回调里
@Autowired
private IAiragBaseApi airagBaseApi;

public void onFormSubmit(FormData data) {
    String docId = airagBaseApi.knowledgeWriteTextDocument(
        "knowledge-user-manual",         // knowledgeId
        "用户手册 - " + data.getTitle(), // title
        data.toMarkdownString(),         // content
        null                              // 使用默认分段
    );
    log.info("文档入库: {}", docId);
}
```

让业务表单内容自动沉淀到 AI 知识库，下次 AI 回答时能检索到。

### 4.2 AI 聊天时取记忆库

```java
// 在 chat 流程中
String memoryId = airagBaseApi.getMemoryIdByAppId(appId);
if (memoryId != null) {
    // 调用 embeddingHandler.embeddingSearch(...)
}
```

AI 自动找 App 配的记忆库做个性化检索。

---

## 五、Feign 降级（cloud-api 版）

`jeecg-module-system-cloud-api/.../org/jeecg/common/airag/api/fallback/AiragBaseApiFallback.java`：

```java
@Component
public class AiragBaseApiFallback implements IAiragBaseApi {
    @Override
    public String knowledgeWriteTextDocument(...) {
        log.warn("AI BaseApi 服务调用失败，降级返回null");
        return null;
    }
    @Override
    public String getChatVariable(...) { return null; }
    @Override
    public void setChatVariable(...) {}
    @Override
    public String getMemoryIdByAppId(...) { return null; }
    @Override
    public String getPromptContent(...) { return null; }
}
```

`AiragBaseApiFallbackFactory`：当 Feign 调用失败时，Spring Cloud Circuit Breaker 触发 fallback，返回 null。调用方需要 null 检查。

---

## 六、完整调用链图：`POST /airag/api/knowledgeWriteTextDocument`

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 其他业务模块（如 online 表单）调 IAiragBaseApi 接口                    │
│    airagBaseApi.knowledgeWriteTextDocument(knowId, title, content, config) │
└────────────────────────┬────────────────────────────────────────────────┘
                         │
       ┌─────────────────┴─────────────────┐
       │ 单体模式（local-api）             │ 微服务模式（cloud-api）
       ▼                                   ▼
┌────────────────────────┐      ┌────────────────────────────┐
│ AiragBaseApiImpl       │      │ Feign Client →              │
│ （同进程内直接调用）    │      │ POST jeecg-system 服务   │
│ @Primary 注入          │      │           │                 │
└─────────┬──────────────┘      └───────────┼─────────────────┘
          │                                  │
          │                                  ↓ 网络
          │                       ┌─────────────────────────────┐
          │                       │ jeecg-system 服务            │
          │                       │   AiragBaseApiController     │
          │                       │   ↓ AiragBaseApiImpl          │
          │                       └─────────────┬───────────────┘
          │                                     │
          └─────────────────┬───────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. AiragBaseApiImpl.knowledgeWriteTextDocument                          │
│    ├─ 构造 AiragKnowledgeDoc(type='text', content=...)                  │
│    ├─ metadata=segmentConfig（让 EmbeddingHandler 按此策略分段）       │
│    └─ airagKnowledgeDocService.editDocument(knowledgeDoc)               │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. AirragKnowledgeDocServiceImpl.editDocument                           │
│    （详见 [03-controller-knowledge.md#二](03-controller-knowledge.md)）│
│    └─ status=draft → 异步向量化                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 七、对应源码文件

| 文件 | 路径 | 行数 | 作用 |
|------|------|------|------|
| `controller/AiragBaseApiController.java` | airag | 62 | HTTP 入口 |
| `api/AiragBaseApiImpl.java` | airag | 108 | 本地实现（@Primary） |
| `interface IAiragBaseApi` | `jeecg-module-system/jeecg-system-api/jeecg-system-local-api/.../org/jeecg/common/airag/api/` | — | local-api 接口 |
| `interface IAiragBaseApi` | `jeecg-module-system/jeecg-system-api/jeecg-system-cloud-api/.../org/jeecg/common/airag/api/` | — | cloud-api Feign 接口 |
| `AiragBaseApiFallback` | cloud-api/fallback/ | — | Feign 降级 |
| `AiragBaseApiFallbackFactory` | cloud-api/factory/ | — | 降级工厂 |
| `service/IAiragKnowledgeDocService.java` | airag | 88 | 被调 |
| `service/IAiragAppService.java` | airag | 58 | 被调（getMemoryIdByAppId） |
| `service/IAiragVariableService.java` | airag | 55 | 被调 |
| `service/IAiragPromptsService.java` | airag | 19 | 被调 |
| `entity/AiragKnowledgeDoc.java` | airag | 125 | 实体 |
| `entity/AiragApp.java` | airag | 221 | 实体 |
| `entity/AiragPrompts.java` | airag | 108 | 实体 |

---

## 八、下一章

[12-handler-aichat.md](12-handler-aichat.md) — `AIChatHandler` 完整逐方法解读。
