# MinerU 本地部署 → 官方 API 替换设计

> 日期：2026-07-09
> 范围：jeecg-boot-module-airag 模块下的 MinerU 本地 magic-pdf 调用，整体替换为 MinerU 官方 API（精准解析 v4）。
> 工作边界：仅代码开发；不执行 mvn build / mvn test / git 操作；不引入新第三方依赖。

---

## 一、背景与目标

JeecgBoot 项目当前的 MinerU 能力通过本地部署 `magic-pdf` 命令调用（conda 环境 + shell 命令）。现需替换为调用 MinerU 官方 API（`https://mineru.net`），保持上层业务调用完全无感知。

约束（来自用户）：
- API Key 仍写死在 `application-dev.yml`（不引入 `${MINERU_API_KEY:}` 占位符）。
- Cloud 模式下，结果 ZIP 中的 `images/` 目录必须本地化到 `auto/{baseName}/images/`，与原本地 magic-pdf 行为一致。
- 本地 magic-pdf 命令（`mode: local`）**保留作为兜底**，不删除。
- 官方 API 错误码必须翻译为中文业务提示，复用 `JeecgBootException` 抛出。
- 仅接入**精准解析 v4 批量本地文件上传**接口（`/api/v4/file-urls/batch`），不引入 URL 模式或 Agent v1 轻量接口。

---

## 二、现状 Review（已写代码）

| 文件 | 状态 | 关键点 |
|------|------|--------|
| `MineruApiClient.java` | 已写完 | 522 行；`@Slf4j @Component`；调用 `/api/v4/file-urls/batch` → PUT 上传 → 轮询 `/api/v4/extract-results/batch/{batch_id}` → 下载 ZIP → 解压 `full.md`；鉴权 `Bearer` token |
| `KnowConfigBean.MineruConfig / CloudConfig` | 已写完 | prefix `jeecg.airag.know`；包含 baseUrl / apiKey / connectTimeout / readTimeout / timeout / retryTimes / retryInterval |
| `EmbeddingHandler.parseFileByMinerU / parseFileByMinerUCloud` | 已写完 | 双通道分流；cloud 模式把 Markdown 写入 `uploadpath/mineru/{uuid}/{baseName}/auto/{baseName}.md`，回写 metadata 的 `FILEPATH` 与 `SOURCES_PATH` |
| `application-dev.yml` | 已写完 | 嵌套 yml 结构，与 `jeecg.ai-chat.*` / `jeecg.airag.embed-store.*` 风格一致；`api-key` 当前为写死真实值（按用户决策保留） |

### 已发现待修复问题

| # | 问题 | 影响 |
|---|------|------|
| 1 | `CloudConfig.timeout` 字段未被使用 | 大文件（>120s）会无限等，无总超时保护 |
| 2 | 解压只读 `full.md`，不处理 `images/` | full.md 中图片相对路径在本地模式失效，能力降级 |
| 3 | 官方错误码（如 `A0211` / `-60005` / `-60018`）直接拼英文/中文 msg | 用户体验差，无法定位问题原因 |
| 4 | 新增/修改代码块未统一补 `update-begin/end` 注释 | 违反项目约定 |

---

## 三、关键决策（用户拍板）

| 决策项 | 选项 | 选择 |
|--------|------|------|
| API Key 处置 | 占位符 + 环境变量 / 启动参数 / 维持现状 | **维持现状**（写死在 yml） |
| 图片资源 | 拷贝 images/ / 不处理 / 上传 OSS | **拷贝 images/ 到 `auto/{baseName}/images/`** |
| Local 兜底 | 完全删除 / 保留 | **保留作为兜底通道** |
| 异常本地化 | 建错误码映射表 / 透传 / 只翻译关键码 | **建错误码映射表** |
| API 入口选型 | 仅批量本地上传 / 同时支持 URL / Agent v1 | **仅保留批量本地上传（`/api/v4/file-urls/batch`）** |
| 实施范围 | 代码 + 测试 + 打包 / 仅代码 | **仅代码**（不执行 mvn / git） |

---

## 四、改动文件清单

| # | 文件 | 改动类型 | 改动范围 |
|---|------|---------|----------|
| 1 | `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/MineruApiClient.java` | 增强 | 5 处补丁（详见第五节） |
| 2 | `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/config/KnowConfigBean.java` | 注释 | `CloudConfig` 字段 Javadoc 补充（不改字段集） |
| 3 | `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/EmbeddingHandler.java` | 无改动 | cloud 分支已就绪，无需改动 |

**不改动** `application-dev.yml`、`pom.xml`、其他任何文件；不新增 Java 文件（`MineruErrorCodeMapper` 作为 `MineruApiClient` 的 `private static` 内部类）。

---

## 五、详细设计

### 5.1 架构与调用关系

```
AiragKnowledgeController.doc/edit
  → IAiragKnowledgeDocService.editDocument(doc)
      → if TYPE_FILE & enableMinerU
          → EmbeddingHandler.parseFileByMinerU(doc)
              ├─ if mode=local  → CommandExecUtil.execCommand(magic-pdf ...)  // 兜底保留
              └─ if mode=cloud  → parseFileByMinerUCloud
                                  → MineruApiClient.parse(file, fileType)
                                      → POST /api/v4/file-urls/batch
                                      → PUT <file_url>
                                      → GET /api/v4/extract-results/batch/{batch_id} (轮询)
                                      → GET <full_zip_url> (下载 ZIP)
                                      → 解压 full.md + images/
                                  → 写入 uploadpath/mineru/{uuid}/{baseName}/auto/{baseName}.md
                                  → 复制 images/* 到 auto/{baseName}/images/
                                  → 回写 metadata.FILEPATH + SOURCES_PATH
```

### 5.2 配置语义（`CloudConfig` 字段 Javadoc 补充）

| 字段 | 类型 | 默认 | 语义 |
|------|------|------|------|
| `baseUrl` | String | `https://mineru.net` | 官方 API 基地址 |
| `apiKey` | String | 空 | Bearer Token，yml 中写死 |
| `connectTimeout` | int | 10 | HTTP 连接超时（秒） |
| `readTimeout` | int | 60 | HTTP 读取超时（秒），每次轮询单独计时 |
| `timeout` | int | 300 | 单次解析总等待上限（秒），实际生效 |
| `retryTimes` | int | 60 | 轮询最大次数（语义提示） |
| `retryInterval` | int | 2 | 轮询间隔（秒） |

`timeout` 与 `retryTimes × retryInterval` 取**较小者**作为实际总时长上限，确保大文件有兜底。

### 5.3 `MineruApiClient` 五处补丁

#### 补丁 1：`timeout` 字段实际生效（修改 `pollBatchResult`）

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效--------
long startTime = System.currentTimeMillis();
long maxWaitMillis = cloud.getTimeout() * 1000L;
int maxRetry = Math.max(cloud.getRetryTimes(), 1);
int interval = Math.max(cloud.getRetryInterval(), 1);

for (int i = 0; i < maxRetry; i++) {
    if (System.currentTimeMillis() - startTime >= maxWaitMillis) {
        throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
    }
    // ...原有轮询逻辑不变
    // 每轮 sleep 前再校验一次总时长
    if (System.currentTimeMillis() - startTime + interval * 1000L >= maxWaitMillis) {
        throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
    }
    TimeUnit.SECONDS.sleep(interval);
}
//update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效--------
```

#### 补丁 2：解压时拷贝 `images/` 到 `auto/{baseName}/images/`

`extractMarkdown` 方法返回结构由 `String` 改为内部结果对象 `ExtractionResult { String markdown; String imagesDir; }`：

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压时同步拷贝images目录，保持与本地模式图片路径一致--------
private static class ExtractionResult {
    final String markdown;
    final String imagesDir;
    ExtractionResult(String markdown, String imagesDir) {
        this.markdown = markdown;
        this.imagesDir = imagesDir;
    }
}

private ExtractionResult extractMarkdown(String zipPath, String outDir) {
    String mdPath = null;
    boolean hasImagesDir = false;
    // ...遍历 ZIP entries 时：
    //   1. 与原逻辑一致，记录 full.md 路径到 mdPath
    //   2. 检测是否存在 images/ 目录条目，置 hasImagesDir = true
    // 解压完成后返回 ExtractionResult(markdown, hasImagesDir ? outDir + "/images" : null)
    return new ExtractionResult(markdown, hasImagesDir ? outDir + File.separator + "images" : null);
}
//update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压时同步拷贝images目录--------
```

`parseFileByMinerUCloud`（位于 `EmbeddingHandler`）拿到 `imagesDir` 后：

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】将MinerU官方API返回的images目录拷贝至与本地模式一致的auto/{baseName}/images/路径下，保持图片相对路径可用--------
// 拷贝 images/ 到目标目录
if (StringUtils.isNotEmpty(imagesDir)) {
    File srcImages = new File(imagesDir);
    File destImages = new File(outputPath + "images");
    if (srcImages.isDirectory()) {
        FileUtils.copyDirectory(srcImages, destImages);
        log.info("MinerU 官方 API 图片资源已拷贝, src: {}, dest: {}", srcImages, destImages);
    }
}
//update-end---author:song ---date:2026-07-09  for：【AI知识库】将MinerU官方API返回的images目录拷贝至与本地模式一致的auto/{baseName}/images/路径下--------
```

注：`EmbeddingHandler.parseFileByMinerUCloud` 当前不返回 `imagesDir`，需要在 `MineruApiClient.parse` 方法签名上扩展，或在 `parseFileByMinerUCloud` 内复用 `MineruApiClient` 的解压逻辑——为保持 `parse` 方法签名对调用方完全不变，采用后者：把解压 + images 拷贝整体下沉到 `MineruApiClient.parse` 内部，输出 markdown 直接覆盖到目标路径（不再返回 imagesDir 给调用方），调用方 `EmbeddingHandler` 仅需在 `parseFileByMinerUCloud` 末尾追加 images 拷贝。

**最终方案**（与上面略有差异，下面为准）：
- `MineruApiClient.parse(File file, String fileType, File targetDir)` —— `targetDir` 为目标目录（如 `uploadpath/mineru/{uuid}/{baseName}/auto/`），方法内部完成 ZIP 解压 + 写入 `targetDir/full.md` + 拷贝 `images/` 到 `targetDir/images/`
- 调用方（`EmbeddingHandler.parseFileByMinerUCloud`）改为：
  ```java
  String markdown = mineruApiClient.parse(docFile, fileType, outputDir);
  metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeDir + fileBaseName + ".md");
  metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
  ```
- 这样 `EmbeddingHandler` 改动**仅一行**（调用参数多传一个 `outputDir`），上层业务零感知

#### 补丁 3：错误码映射表（新增 private static 内部类）

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示，复用JeecgBootException-------
private static class MineruErrorCodeMapper {
    private static final Map<String, String> CODE_MSG = new HashMap<>();
    static {
        // 鉴权类
        CODE_MSG.put("A0202", "MinerU API Key 不正确，请检查 Token 是否包含 Bearer 前缀或重新生成");
        CODE_MSG.put("A0211", "MinerU API Key 已过期，请在 mineru.net 后台重新生成 Token");
        // 通用
        CODE_MSG.put("-500", "MinerU 请求参数错误，请联系管理员检查请求格式");
        CODE_MSG.put("-10001", "MinerU 服务暂时异常，请稍后重试");
        CODE_MSG.put("-10002", "MinerU 请求参数错误，请检查参数格式");
        // 上传类
        CODE_MSG.put("-60001", "MinerU 生成上传链接失败，请稍后重试");
        CODE_MSG.put("-60002", "MinerU 不支持的文件格式，仅支持 PDF/Doc/Docx/Ppt/Pptx/Xls/Xlsx 及常见图片格式");
        CODE_MSG.put("-60003", "MinerU 文件读取失败，文件可能损坏");
        CODE_MSG.put("-60004", "MinerU 不支持空文件");
        CODE_MSG.put("-60005", "MinerU 文件大小超过 200MB 限制，请拆分文件后重试");
        CODE_MSG.put("-60006", "MinerU 文件页数超过 200 页限制，请拆分文件后重试");
        CODE_MSG.put("-60007", "MinerU 模型服务暂时不可用，请稍后重试或联系技术支持");
        CODE_MSG.put("-60008", "MinerU 文件读取超时，请检查 URL 可访问性");
        CODE_MSG.put("-60011", "MinerU 获取有效文件失败，请确保文件已上传");
        // 配额类
        CODE_MSG.put("-60017", "MinerU 重试次数达到上限，请稍后重试");
        CODE_MSG.put("-60018", "MinerU 每日解析任务数量已达上限，请明日再试");
        CODE_MSG.put("-60019", "MinerU html 文件解析额度不足，请明日再试");
    }

    /**
     * 解析官方错误响应为 Jeecg 风格业务提示
     * @param body 响应体（JSON）
     * @return 翻译后的中文提示；若无法识别则返回 null（交给原 parseErrorMsg 兜底）
     */
    static String translate(String body) {
        if (StringUtils.isEmpty(body)) return null;
        try {
            JSONObject json = JSON.parseObject(body);
            Object code = json.get("code");
            if (code == null) return null;
            return CODE_MSG.get(String.valueOf(code));
        } catch (Exception e) {
            return null;
        }
    }
}
//update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示-------
```

接入方式（在 `parseErrorMsg` 调用前优先用映射表）：

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表，兜底取官方msg字段--------
private String resolveErrorMsg(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    String translated = MineruErrorCodeMapper.translate(body);
    if (translated != null) return translated;
    return parseErrorMsg(e);  // 原方法保留作为兜底
}
//update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表--------
```

所有 `catch (RestClientResponseException e)` 分支中 `parseErrorMsg(e)` 改为 `resolveErrorMsg(e)`。

#### 补丁 4：日志规范（保持现有风格，仅补全缺失项）

| 场景 | 级别 | 内容 |
|------|------|------|
| 创建批量任务 | info | url + fileName |
| 文件上传成功 | info | batchId |
| 轮询每轮 | debug | batchId + 第N次 |
| 任务完成 | info | batchId + state + cost |
| 下载 ZIP | info | url + savePath |
| ZIP 解压完成 + images 拷贝 | info | md length + images count |
| 整体完成 | info | batchId + md length + total cost |
| 失败（业务异常） | error | batchId + cost + e |
| 失败（系统异常） | error | batchId + cost + e |
| 临时目录清理失败 | warn | tmpDir + e |

每条日志前缀统一 `MinerU 官方 API ...`，便于 grep 定位。

#### 补丁 5：`update-begin/end` 注释补齐

所有上述新增/修改代码块必须用 `//update-begin---author:song ---date:2026-07-09  for：【xxx】修改说明-----------` 包裹，规则：
- 新增方法：`update-begin` 放在方法声明前一行
- 修改已有方法：只包裹被修改的代码段
- `for` 字段：`【AI知识库】MinerU官方APIxxx` 格式

---

## 六、风格一致性

| 项 | 风格 |
|----|------|
| HTTP 工具 | `RestUtil.getHeaderApplicationJson()` + `HttpComponentsClientHttpRequestFactory` |
| 异常 | `JeecgBootException`（系统异常，非业务提示） |
| 日志 | `@Slf4j` + `log.info/error/debug/warn` |
| JSON | `com.alibaba.fastjson.JSONObject` |
| 工具 | `org.apache.commons.io.FileUtils` / `org.apache.commons.lang3.StringUtils` |
| 路径校验 | `org.jeecg.common.util.filter.SsrfFileTypeFilter.checkPathTraversal` |
| ID 生成 | `org.jeecg.common.util.UUIDGenerator.generate()` |

---

## 七、关键不变性（必须保持）

- `MineruApiClient.parse(File file, String fileType, File targetDir)` 公开方法签名稳定
- `parseFileByMinerUCloud(AiragKnowledgeDoc, File, JSONObject)` 私有方法签名不变
- `CloudConfig` 字段集不变
- 业务调用方 `EmbeddingHandler.embeddingDocument` 零改动（cloud 分支仅调用参数调整一行）
- 原 local 分支 + `CommandExecUtil` + `condaEnv` 字段保留

---

## 八、不在范围内

- ❌ 执行 `mvn clean package` / `mvn test`
- ❌ 执行任何 `git` 命令（commit/push/diff/add）
- ❌ 启动应用做集成验证
- ❌ 编写自动化测试用例
- ❌ 引入新第三方依赖
- ❌ 改动 `application-dev.yml`、`pom.xml`、`EmbeddingHandler` 之外的任何文件
- ❌ 引入 URL 模式（`/api/v4/extract/task`）或 Agent v1 轻量接口

---

## 九、静态验收清单

| # | 验收项 | 通过标准 |
|---|--------|----------|
| 1 | 上层零感知 | `EmbeddingHandler.parseFileByMinerUCloud` 仅调用 `mineruApiClient.parse(...)` 参数多传 `outputDir` |
| 2 | 本地模式兜底保留 | `mode=local` 分支、`condaEnv` 字段、`CommandExecUtil` 调用代码完整保留 |
| 3 | 总超时实际生效 | `pollBatchResult` 内增加 `System.currentTimeMillis() - startTime >= maxWaitMillis` 检查 |
| 4 | 图片本地化 | 解压逻辑内识别 `images/` 路径并通过 `FileUtils.copyDirectory` 拷贝 |
| 5 | 错误码翻译 | 新增 `MineruErrorCodeMapper` private static 类，覆盖至少 A0202/A0211/-60005/-60018 等高频码 |
| 6 | 注释完整 | 所有新增/修改代码块用 `update-begin/end` 包裹，作者 song、日期 2026-07-09 |
| 7 | 风格一致 | 异常 `JeecgBootException`、日志 `@Slf4j`、HTTP 用 `RestUtil.getHeaderApplicationJson()`、HTTP 客户端 `HttpComponentsClientHttpRequestFactory` |
| 8 | 配置前缀 | `CloudConfig` 仍在 `KnowConfigBean.PREFIX = "jeecg.airag.know"` 之下 |
| 9 | 文件清单 | 改动文件仅 2 个：`MineruApiClient.java`（含内部类）、`EmbeddingHandler.java`（仅 1 行调用变更） |

---

## 十、自检

- [x] 无 TBD / TODO / 占位符
- [x] 章节一致（关键决策 ↔ 改动文件 ↔ 验收清单）
- [x] 不超出"纯代码开发"范围
- [x] 无歧义（每个 `for` 注释明确、`timeout` 语义明确）