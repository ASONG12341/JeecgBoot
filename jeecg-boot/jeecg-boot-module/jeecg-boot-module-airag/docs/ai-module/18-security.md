# 18 · 安全机制 · 已修复 issue 完整对照

> 本文档汇总 AI 模块所有安全相关代码与对应 issue。全部在源码注释里有 `update-begin---author:xxx---date:xxxxx---for:[issues/xxx]...` 标记。

---

## 一、跨租户数据隔离（多个 issue）

| Issue | 修复位置 | 代码 | 行为 |
|-------|---------|------|------|
| #8337 | `AiragAppController.delete` / `AiragKnowledgeController.delete` / `AiragMcpController.delete` 等多处 | `MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL` 包裹 | 删除 / 编辑前查 DB 比对 `tenantId`，不匹配则 `Result.error` |
| #9462 | `AiragAppController.edit` | 编辑前置校验 + `airagApp.setTenantId(currentTenantId)` 强制覆盖 | 跨租户数据写入漏洞 |

**核心代码模板**：
```java
if (MybatisPlusSaasConfig.OPEN_SYSTEM_TENANT_CONTROL) {
    String currentTenantId = TokenUtils.getTenantIdByRequest(request);
    AiragApp dbApp = airagAppService.getById(id);
    if (dbApp == null || !dbApp.getTenantId().equals(currentTenantId)) {
        return Result.error("删除AI应用失败，不能删除其他租户的AI应用！");
    }
}
```

---

## 二、DeepSeek 推理模型兼容

| Issue | 修复位置 | 代码 |
|-------|---------|------|
| #9585 | `AIChatHandler.mergeParams` + `injectThinkingPlaceholderIfNeeded` + `AIChatHandler.mergeParams` | DeepSeek 推理模型（`deepseek-v4-flash` / `deepseek-v4-pro`）开启 `returnThinking + sendThinking`；注入占位 `thinking="..."` 让历史 AI 消息的 `reasoning_content` 字段被带回去 |
| #9607 | `AIChatHandler.mergeParams` | 联网搜索 + 工具调用多轮场景，确保新模型兼容 |

**核心代码**：
```java
boolean isDsThinking = LLMConsts.isDeepSeekThinkingModel(modelName);
if (isDsThinking) {
    params.setReturnThinking(true);
    params.setSendThinking(true);
}
```

---

## 三、路径遍历 / Shell 注入

| Issue | 修复位置 | 防护 |
|-------|---------|------|
| #9421 | `PluginToolBuilder.buildUrl` | 拒绝 Path 参数 `..` `/` `\` `%2e` `%2f` |
| #9424 / #9425 | `EmbeddingHandler.ensureFile` | `SsrfFileTypeFilter.checkPathTraversal` + JDK `Paths.get(uploadpath).toAbsolutePath().normalize()` 校验目标在 root 下 |
| #9431 | `AIChatHandler.getFirstImageBase64` | `SsrfFileTypeFilter.checkPathTraversal` + `canonicalFile.startsWith(uploadDir.toPath())` |
| #9424 (#9425) | `EmbeddingHandler.parseFileByMinerU` | `CommandExecUtil.validateFilePath` + `validateArg` 阻断 Shell 元字符 |

**核心代码**：
```java
// PluginToolBuilder.buildUrl
if (paramValue.contains("..") || paramValue.contains("/") || paramValue.contains("\\")
    || paramValue.toLowerCase().contains("%2e") || paramValue.toLowerCase().contains("%2f")) {
    throw new IllegalArgumentException("Path参数包含非法字符: " + paramName);
}
```

```java
// EmbeddingHandler.ensureFile (核心 11 行)
SsrfFileTypeFilter.checkPathTraversal(filePath);
Path root = Paths.get(uploadpath).toAbsolutePath().normalize();
String relativePath = filePath.replaceAll("^[\\\\/]+", "");    // ★ 去掉前导 \ 或 / （Windows zip 兼容）
Path target = root.resolve(relativePath).toAbsolutePath().normalize();
if (!target.startsWith(root)) {
    log.error("检测到路径遍历攻击! filePath: {}, 解析后: {}", filePath, target);
    throw new JeecgBootException("文件路径包含非法字符");
}
```

```java
// CommandExecUtil
private static final Pattern SHELL_INJECTION_PATTERN = Pattern.compile("[&|;<>`$!\\\\\\r\\n]");
private static final Pattern FILENAME_INJECTION_PATTERN = Pattern.compile("[&|;<>`$!\"'\\r\\n]");
```

---

## 四、ZIP 炸弹防护（`AirragKnowledgeDocServiceImpl.importDocumentFromZip`）

```java
private static final long MAX_FILE_SIZE    = 150 * 1024 * 1024;     // 单文件 150MB
private static final long MAX_TOTAL_SIZE   = 1024 * 1024 * 1024;    // 总解压 1GB
private static final int  MAX_ENTRY_COUNT  = 10000;                 // 文件数 10000
private static final AtomicInteger fileCount = new AtomicInteger(0);
```

**3 层防护**：
1. **entryCount** —— `if (entryCount > MAX_ENTRY_COUNT) throw IOException("解压文件数量超限")`
2. **totalUnzippedSize** —— `if (totalUnzippedSize > MAX_TOTAL_SIZE) throw IOException("解压总大小超限")`
3. **单文件 copyLimited** —— `if (totalCopied > MAX_FILE_SIZE) throw IOException("单个文件解压超限")`

**其他防护**：
- **`safeResolve`**：防 Zip Slip 路径穿越（`if (!resolvedPath.startsWith(targetDir)) throw IOException("ZIP 路径穿越")`）
- **`shouldSkipZipEntry`**：跳过 macOS 隐藏文件（`__MACOSX/`、`._xxx`、`/.DS_Store`）
- **`SsrfFileTypeFilter.checkUploadFileType(zipFile)`**：上传前检查 MIME 类型

---

## 五、HTML 表格分段（#9551）

`EmbeddingHandler.splitDocumentPreservingHtmlTables`（详见 [13-handler-embedding.md#五](13-handler-embedding.md)）

正则 `(?is)<table\b.*?</table>` 找所有 HTML 表格，每个完整保留为单个段（不切），表格外文本走普通分段。

---

## 六、记忆库用户隔离（#QQYUN-14265）

`EmbeddingHandler.searchEmbedding` + `getQueryRouter`：当 `knowledge.type == "memory"` 时，filter 加上 `metadataKey("username").isEqualTo(currentUsername)`。从 Token 取当前 JWT 用户名。

```java
if (LLMConsts.KNOWLEDGE_TYPE_MEMORY.equalsIgnoreCase(knowledge.getType())) {
    HttpServletRequest request = SpringContextUtils.getHttpServletRequest();
    String token = TokenUtils.getTokenByRequest(request);
    String username = JwtUtil.getUsername(token);
    if (oConvertUtils.isNotEmpty(username)) {
        filter = new And(filter, metadataKey(EMBED_STORE_METADATA_USER_NAME).isEqualTo(username));
    }
}
```

---

## 七、MCP 连接管理（#QQYUN-9234）

`AiragMcpServiceImpl.sync` finally 块：反射调 `mcpClient.close()`，避免连接泄漏（HTTP MCP 长连接特别需要）。

```java
} finally {
    if (mcpClient != null) {
        try {
            Method closeMethod = mcpClient.getClass().getMethod("close");
            closeMethod.invoke(mcpClient);
        } catch (NoSuchMethodException ignore) {
            // langchain4j 版本差异兼容
        } catch (Exception ex) {
            log.warn("关闭MCP客户端失败 id={}, error={}", id, ex.getMessage());
        }
    }
}
```

---

## 八、stdio MCP 安全（#QQYUN-14242）

`AiragMcpServiceImpl.sync`：
```java
if ("stdio".equalsIgnoreCase(type)) {
    String openSafe = aiRagConfigBean.getAllowSensitiveNodes();
    if(oConvertUtils.isNotEmpty(openSafe) && openSafe.toLowerCase().contains("stdio")) {
        // 白名单允许 stdio → 走
    } else {
        return Result.error("stdio 功能已禁用。若需启用，请在 yml 的 jeecg.airag.allow-sensitive-nodes 中加入 stdio。");
    }
}
```

默认禁用 stdio（执行本地命令风险高），要启用必须在 `jeecg.airag.allow-sensitive-nodes: "stdio"` 显式开启。

---

## 九、API 报错友好翻译

`AIChatHandler.translateLlmException` —— 4 级降级（详见 [12-handler-aichat.md#十](12-handler-aichat.md)）

**重点修复**：
- 工具上下文丢失 → "建议增加历史消息数量后重试"
- 关键字匹配（如 `quota exceeded`） → 中文具体提示

---

## 十、其他安全注意点（不在 issue 列表中）

| 防护 | 位置 |
|------|------|
| SQL 注入 | MyBatis-Plus `LambdaQueryWrapper` / `QueryWrapper` 自动防 |
| XSS | prompt / response 不渲染 HTML |
| CSRF | `/airag/chat/*` 接口 `@IgnoreAuth`，设计时不依赖 cookie 鉴权 |
| 接口鉴权 | `/airag/knowledge/*` 等用 Shiro `@RequiresPermissions` |
| TenantId token 来源 | `TokenUtils.getTenantIdByRequest(request)` 从 JWT 解出，无法伪造 |

---

## 十一、对应源码文件

覆盖在所有 handler / service / controller 中，可通过 `grep "issues/"` 在模块下搜索。

---

## 十二、下一章

[19-cross-module.md](19-cross-module.md) — 跨模块依赖与前端入口。
