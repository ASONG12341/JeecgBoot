# MinerU 官方 API 替换实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 JeecgBoot AI 模块下 MinerU 本地 magic-pdf 命令调用替换为 MinerU 官方 API（v4 精准解析 - 批量本地文件上传），同时本地模式保留作为兜底通道，上层业务代码零改动。

**Architecture:** 沿用既有 `MineruApiClient.java` 主体，新增 `MineruErrorCodeMapper`（私有静态内部类）做错误码本地化；扩展 `parse(File, String, File)` 签名把 ZIP 解压与 `images/` 拷贝下沉到客户端内部；调用方 `EmbeddingHandler.parseFileByMinerUCloud` 仅传 `outputDir` 一行变更。

**Tech Stack:** Spring Boot 4.1.0、Java 17、RestTemplate（`HttpComponentsClientHttpRequestFactory`）、FastJSON、commons-io、jeecg-boot-base-core 通用工具类。

## Global Constraints

来自设计文档：

- **工作边界**：仅代码开发；不执行 `mvn build` / `mvn test` / `git` 操作。
- **不引入新第三方依赖**。
- **不修改** `application-dev.yml`、`pom.xml`、`EmbeddingHandler` 之外的任何文件。
- **改动文件清单**：仅 2 个文件 — `MineruApiClient.java`（含内部类）、`EmbeddingHandler.java`（仅 1 行调用变更）。`KnowConfigBean.java` 仅补 Javadoc（不改字段集、不改行为）。
- **风格要求**：异常统一用 `JeecgBootException`；HTTP 头部用 `RestUtil.getHeaderApplicationJson()`；HTTP 客户端用 `HttpComponentsClientHttpRequestFactory`；日志用 `@Slf4j` + `log.info/error/debug/warn`，前缀 `MinerU 官方 API ...`。
- **update-begin/end 注释**：所有新增/修改代码块必须用 `//update-begin---author:song ---date:2026-07-09  for：【AI知识库】xxx-----------` 包裹，作者 song，日期 2026-07-09。
- **关键不变性**：`MineruApiClient` 公开方法 `parse` 接收一个新增的 `File targetDir` 参数；调用方 `EmbeddingHandler.parseFileByMinerUCloud` 仅调用参数多传 `outputDir`；`CloudConfig` 字段集不变；原 `mode=local` 分支、`condaEnv`、`CommandExecUtil` 调用完整保留。

## Files Touched

| 文件 | 类型 | 责任 |
|------|------|------|
| `KnowConfigBean.java` | Modify (仅 Javadoc) | `CloudConfig` 字段语义注释 |
| `MineruApiClient.java` | Modify (5 处补丁) | 错误码映射、timeout 实际生效、images/ 拷贝、签名变更、注释补齐 |
| `EmbeddingHandler.java` | Modify (1 行调用) | 把 `outputDir` 传给 `mineruApiClient.parse(...)` |

不新增 Java 文件；`MineruErrorCodeMapper` 作为 `MineruApiClient` 的 `private static` 内部类。

---

## Task 1: 为 `KnowConfigBean.CloudConfig` 字段补 Javadoc

**Files:**
- Modify: `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/config/KnowConfigBean.java:60-96`

**Interfaces:**
- Consumes: 现有 `CloudConfig` 静态内部类（7 个字段：`baseUrl`、`apiKey`、`connectTimeout`、`readTimeout`、`timeout`、`retryTimes`、`retryInterval`）。
- Produces: 每个字段上方添加中文 Javadoc，明确语义（特别是 `timeout` 是实际生效的总等待秒数，`retryTimes` 仅作语义提示）。

- [ ] **Step 1: 在 `baseUrl` 字段上方添加 Javadoc**

在 `KnowConfigBean.java` 第 63 行（`/**`）之前插入以下 Javadoc（替换原行的 `/**` 块）：

```java
/**
 * 官方 API 基地址，默认 https://mineru.net
 */
private String baseUrl = "https://mineru.net";
```

- [ ] **Step 2: 在 `apiKey` 字段上方添加 Javadoc**

```java
/**
 * 官方 API Key（Bearer Token，cloud 模式必填）
 */
private String apiKey;
```

- [ ] **Step 3: 在 `connectTimeout` 字段上方添加 Javadoc**

```java
/**
 * HTTP 连接超时（秒），默认 10
 */
private int connectTimeout = 10;
```

- [ ] **Step 4: 在 `readTimeout` 字段上方添加 Javadoc**

```java
/**
 * HTTP 读取超时（秒），默认 60；每次轮询单独计时
 */
private int readTimeout = 60;
```

- [ ] **Step 5: 在 `timeout` 字段上方添加 Javadoc（关键：标注实际生效）**

```java
/**
 * 单次解析总等待上限（秒），默认 300；实际生效，与 retryTimes*retryInterval 取较小者
 */
private int timeout = 300;
```

- [ ] **Step 6: 在 `retryTimes` 字段上方添加 Javadoc**

```java
/**
 * 轮询最大次数，默认 60；仅作语义提示，最终以 timeout 为准
 */
private int retryTimes = 60;
```

- [ ] **Step 7: 在 `retryInterval` 字段上方添加 Javadoc**

```java
/**
 * 轮询结果间隔（秒），默认 2
 */
private int retryInterval = 2;
```

- [ ] **Step 8: 用 `update-begin/end` 包裹整个 CloudConfig 类（注释补齐）**

把以下注释放在 `public static class CloudConfig {` 之前一行，并在 `}` 闭合之后一行：

```java
//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API CloudConfig 字段语义注释补齐--------
/**
 * MinerU 官方 API 配置
 */
//update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API CloudConfig 字段语义注释补齐-------
```

实际上当前代码已有 `/** MinerU 官方 API 配置 */`，用 `update-begin/end` 注释包裹整个类更合适：把现有 `public static class CloudConfig {` 前一行加 `//update-begin`，最后 `}` 后一行加 `//update-end`。

- [ ] **Step 9: 视觉验证**

打开文件，确认 7 个字段都各自有 Javadoc，类级别有 `update-begin/end`，没有破坏现有 `@Data @NoArgsConstructor` 注解，没有改变默认值。

---

## Task 2: 新增 `MineruErrorCodeMapper` 私有静态内部类

**Files:**
- Modify: `jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/MineruApiClient.java`（在文件末尾的 `}` 之前插入新内部类）

**Interfaces:**
- Consumes: 官方 MinerU API 错误码文档（A0202/A0211/-500/-10001/-10002/-60001 ~ -60022）。
- Produces: 新内部类 `MineruErrorCodeMapper` 提供 `static String translate(String body)` 方法，输入响应体 JSON 字符串，输出中文业务提示；若无法识别返回 `null`。

- [ ] **Step 1: 在 `MineruApiClient.java` 末尾（第 522 行的 `}` 之前）插入 `MineruErrorCodeMapper`**

完整插入以下代码块，使用 `update-begin/end` 注释包裹：

```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示，复用JeecgBootException-------
    /**
     * MinerU 官方 API 错误码 → 中文业务提示映射表
     */
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
         * @return 翻译后的中文提示；若无法识别则返回 null
         */
        static String translate(String body) {
            if (StringUtils.isEmpty(body)) {
                return null;
            }
            try {
                JSONObject json = JSON.parseObject(body);
                Object code = json.get("code");
                if (code == null) {
                    return null;
                }
                return CODE_MSG.get(String.valueOf(code));
            } catch (Exception e) {
                return null;
            }
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示-------
```

- [ ] **Step 2: 视觉验证**

打开文件，确认：
- 新内部类位于文件末尾，在 `}` 之前
- 引用了现有 import：`com.alibaba.fastjson.JSON`、`com.alibaba.fastjson.JSONObject`、`org.apache.commons.lang3.StringUtils`、`java.util.HashMap`、`java.util.Map`
- 不需要新增 import（这些都是文件中已存在的）
- `translate` 方法处理了空 body、JSON 解析失败、code 字段缺失三种兜底

---

## Task 3: 新增 `resolveErrorMsg` 帮助方法

**Files:**
- Modify: `MineruApiClient.java`（在 `parseErrorMsg` 方法之后插入新方法）

**Interfaces:**
- Consumes: `RestClientResponseException`、`MineruErrorCodeMapper.translate(...)`。
- Produces: `private String resolveErrorMsg(RestClientResponseException e)` 优先返回映射表结果，兜底返回原 `parseErrorMsg(e)`。

- [ ] **Step 1: 在 `parseErrorMsg` 方法之后（约第 454 行后）插入 `resolveErrorMsg`**

```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表，兜底取官方msg字段--------
    /**
     * 解析 RestClientResponseException 中的错误信息，优先使用错误码映射表
     *
     * @param e 异常
     * @return 错误描述
     * @author song
     * @date 2026/7/9
     */
    private String resolveErrorMsg(RestClientResponseException e) {
        if (e == null) {
            return "未知错误";
        }
        String body = e.getResponseBodyAsString();
        String translated = MineruErrorCodeMapper.translate(body);
        if (translated != null) {
            return translated;
        }
        return parseErrorMsg(e);
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表--------
```

- [ ] **Step 2: 视觉验证**

确认 `parseErrorMsg` 原方法完整保留（兜底逻辑）；新方法调用了原 `parseErrorMsg`，未删除原方法。

---

## Task 4: 替换 4 处 `parseErrorMsg(e)` 为 `resolveErrorMsg(e)`

**Files:**
- Modify: `MineruApiClient.java`（4 个 `catch (RestClientResponseException e)` 分支）

**Interfaces:**
- Consumes: Task 3 产出的 `resolveErrorMsg`。
- Produces: 4 处 catch 分支统一使用 `resolveErrorMsg(e)`，让错误码映射生效。

- [ ] **Step 1: 定位 4 处 catch 分支**

打开 `MineruApiClient.java`，搜索 `parseErrorMsg(e)`，应找到以下 4 处：
1. 第 145 行附近：`applyBatchUploadUrl` 中的 `throw new JeecgBootException("MinerU 官方 API 创建批量任务失败: " + parseErrorMsg(e));`
2. 第 194 行附近：`uploadFile` 中的 `throw new JeecgBootException("MinerU 官方 API 文件上传失败: " + parseErrorMsg(e));`
3. 第 223 行附近：`pollBatchResult` 中的 `throw new JeecgBootException("MinerU 官方 API 查询批量任务结果失败: " + parseErrorMsg(e));`
4. 第 320 行附近：`downloadZip` 中的 `throw new JeecgBootException("MinerU 官方 API 结果 ZIP 下载失败: " + parseErrorMsg(e));`

- [ ] **Step 2: 替换所有 `parseErrorMsg(e)` 为 `resolveErrorMsg(e)`**

使用 `Edit` 工具的 `replace_all=true` 参数一次性替换所有 4 处（注意原 `parseErrorMsg` 方法定义本身不能改，只改调用点）。

- [ ] **Step 3: 视觉验证**

搜索 `parseErrorMsg(e)` 调用，应只剩 0 处；搜索 `resolveErrorMsg(e)` 调用，应有 4 处；原 `parseErrorMsg` 方法定义完整保留。

---

## Task 5: 在 `pollBatchResult` 增加 `timeout` 实际生效判断

**Files:**
- Modify: `MineruApiClient.java`，`pollBatchResult` 方法（约第 207-260 行）

**Interfaces:**
- Consumes: `CloudConfig.timeout`（秒）。
- Produces: 轮询循环每轮开始前与 sleep 前都校验 `System.currentTimeMillis() - startTime >= maxWaitMillis`，超时则抛 `JeecgBootException`。

- [ ] **Step 1: 在 `pollBatchResult` 方法体顶部加入 `startTime` 与 `maxWaitMillis` 计算**

找到现有：
```java
int maxRetry = Math.max(cloud.getRetryTimes(), 1);
int interval = Math.max(cloud.getRetryInterval(), 1);
for (int i = 0; i < maxRetry; i++) {
```

在 `for` 之前插入：
```java
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = cloud.getTimeout() * 1000L;
```

- [ ] **Step 2: 在 for 循环开头增加超时判断**

找到现有：
```java
        for (int i = 0; i < maxRetry; i++) {
            log.debug("MinerU 官方 API 轮询批量任务结果, batchId: {}, 第{}次", batchId, i + 1);
```

在 `log.debug` 之前插入：
```java
            if (System.currentTimeMillis() - startTime >= maxWaitMillis) {
                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
            }
```

- [ ] **Step 3: 在 sleep 前增加二次校验**

找到现有：
```java
            try {
                TimeUnit.SECONDS.sleep(interval);
            } catch (InterruptedException e) {
```

在 `try` 之前插入：
```java
            if (System.currentTimeMillis() - startTime + interval * 1000L >= maxWaitMillis) {
                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
            }
```

- [ ] **Step 4: 用 `update-begin/end` 包裹整个 `pollBatchResult` 方法的修改段**

把整个方法的修改部分用注释包裹。原 `pollBatchResult` 已有方法级 Javadoc，在方法声明前一行加 `//update-begin`，方法结束 `}` 后一行加 `//update-end`。

- [ ] **Step 5: 视觉验证**

确认：
- `startTime` / `maxWaitMillis` 声明在 `for` 循环外
- 每轮循环开始前与 sleep 前各有一个超时判断
- 超时时抛 `JeecgBootException`（不是 `RuntimeException`）
- `update-begin/end` 注释完整

---

## Task 6: 新增 `ExtractionResult` 内部类

**Files:**
- Modify: `MineruApiClient.java`（在 `MineruErrorCodeMapper` 内部类之前或之后插入）

**Interfaces:**
- Consumes: 无（纯内部数据结构）。
- Produces: `private static class ExtractionResult { final String markdown; final String imagesDir; ... }`。

- [ ] **Step 1: 在文件末尾的 `MineruErrorCodeMapper` 内部类之前插入 `ExtractionResult`**

```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果封装（含markdown文本与images目录路径）-------
    /**
     * MinerU 官方 API 解压结果
     */
    private static class ExtractionResult {
        final String markdown;
        final String imagesDir;

        ExtractionResult(String markdown, String imagesDir) {
            this.markdown = markdown;
            this.imagesDir = imagesDir;
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果封装-------
```

- [ ] **Step 2: 视觉验证**

确认类声明为 `private static`、字段为 `final`、构造器可见性为 package-private。

---

## Task 7: 重构 `extractMarkdown` 返回 `ExtractionResult`

**Files:**
- Modify: `MineruApiClient.java`，`extractMarkdown` 方法（约第 335-379 行）

**Interfaces:**
- Consumes: ZIP 解压过程中的中间状态。
- Produces: 改为 `private ExtractionResult extractMarkdown(String zipPath, String outDir)`，返回结构包含 markdown 文本与 `images/` 目录路径（解压到 `outDir/images/`）。

- [ ] **Step 1: 修改方法签名**

找到现有：
```java
private String extractMarkdown(String zipPath, String outDir) {
```

替换为：
```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果改返回ExtractionResult（含images目录路径）-------
    private ExtractionResult extractMarkdown(String zipPath, String outDir) {
```

- [ ] **Step 2: 在解压循环中识别 `images/` 目录**

找到现有：
```java
                if (entry.isDirectory()) {
                    FileUtils.forceMkdir(entryFile);
                    continue;
                }
```

替换为：
```java
                if (entry.isDirectory()) {
                    FileUtils.forceMkdir(entryFile);
                    continue;
                }
```

保持原样（不需要在 entry.isDirectory 分支判断，因为 ZIP 中 images/ 的子文件会在后续被解压）。

- [ ] **Step 3: 在循环外初始化 `hasImagesDir` 标志**

找到现有：
```java
        String mdPath = null;
        try (ZipFile zip = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
```

替换为：
```java
        String mdPath = null;
        boolean hasImagesDir = false;
        try (ZipFile zip = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
```

- [ ] **Step 4: 在遍历 entry 时识别 `images/` 路径**

找到现有：
```java
                if (DEFAULT_MD_NAME.equalsIgnoreCase(FilenameUtils.getName(entryName))) {
                    mdPath = entryFile.getAbsolutePath();
                }
```

替换为：
```java
                if (DEFAULT_MD_NAME.equalsIgnoreCase(FilenameUtils.getName(entryName))) {
                    mdPath = entryFile.getAbsolutePath();
                }
                if (entryName.contains("images/") || entryName.startsWith("images" + File.separator)) {
                    hasImagesDir = true;
                }
```

- [ ] **Step 5: 替换方法结尾的返回**

找到现有：
```java
        if (StringUtils.isEmpty(mdPath)) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 中未找到 " + DEFAULT_MD_NAME);
        }

        try {
            return FileUtils.readFileToString(new File(mdPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JeecgBootException("MinerU 官方 API 读取 Markdown 结果失败: " + mdPath, e);
        }
    }
```

替换为：
```java
        if (StringUtils.isEmpty(mdPath)) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 中未找到 " + DEFAULT_MD_NAME);
        }

        String markdown;
        try {
            markdown = FileUtils.readFileToString(new File(mdPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JeecgBootException("MinerU 官方 API 读取 Markdown 结果失败: " + mdPath, e);
        }

        String imagesDir = hasImagesDir ? outDir + File.separator + "images" : null;
        return new ExtractionResult(markdown, imagesDir);
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果改返回ExtractionResult-------
```

- [ ] **Step 6: 视觉验证**

确认：
- 方法签名改为 `ExtractionResult`
- `hasImagesDir` 标志贯穿循环
- 返回 `ExtractionResult` 而非 `String`
- 异常路径（mdPath 空、读取失败）保持 `JeecgBootException`
- `update-begin/end` 包裹整个方法

---

## Task 8: 重构 `downloadAndExtractMarkdown` 接收 `targetDir` 并拷贝 `images/`

**Files:**
- Modify: `MineruApiClient.java`，`downloadAndExtractMarkdown` 方法（约第 271-294 行）

**Interfaces:**
- Consumes: `CloudConfig`、`String fullZipUrl`、`File targetDir`（新增）。
- Produces: 把 ZIP 解压到 `targetDir`（而非临时目录），并把 `images/` 拷贝到 `targetDir/images/`；返回 `ExtractionResult`。

- [ ] **Step 1: 修改方法签名**

找到现有：
```java
    private String downloadAndExtractMarkdown(String fullZipUrl, KnowConfigBean.CloudConfig cloud) {
        // 创建临时目录
        String tmpDir = System.getProperty("java.io.tmpdir") + File.separator + "mineru" + File.separator + UUIDGenerator.generate();
        Path tmpPath = Paths.get(tmpDir);
```

替换为：
```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API直接解压到targetDir并拷贝images目录，避免双写--------
    private ExtractionResult downloadAndExtractMarkdown(String fullZipUrl, KnowConfigBean.CloudConfig cloud, File targetDir) {
        // 创建临时目录
        String tmpDir = System.getProperty("java.io.tmpdir") + File.separator + "mineru" + File.separator + UUIDGenerator.generate();
        Path tmpPath = Paths.get(tmpDir);
```

- [ ] **Step 2: 修改解压后的拷贝逻辑**

找到现有：
```java
        try {
            downloadZip(fullZipUrl, zipPath, cloud);
            return extractMarkdown(zipPath, tmpDir);
        } finally {
            // 清理临时文件
            try {
                FileUtils.deleteDirectory(tmpPath.toFile());
            } catch (IOException e) {
                log.warn("清理 MinerU 临时目录失败: {}", tmpDir, e);
            }
        }
    }
```

替换为：
```java
        try {
            downloadZip(fullZipUrl, zipPath, cloud);
            ExtractionResult result = extractMarkdown(zipPath, tmpDir);

            // 将临时解压结果落到目标目录：写 full.md + 拷贝 images/
            if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
                throw new JeecgBootException("创建 MinerU 官方 API 目标目录失败: " + targetDir);
            }
            File mdFile = new File(targetDir, DEFAULT_MD_NAME);
            try {
                FileUtils.writeStringToFile(mdFile, result.markdown, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new JeecgBootException("写入 MinerU 官方 API Markdown 结果失败: " + mdFile, e);
            }
            if (StringUtils.isNotEmpty(result.imagesDir)) {
                File srcImages = new File(result.imagesDir);
                File destImages = new File(targetDir, "images");
                if (srcImages.isDirectory()) {
                    try {
                        FileUtils.copyDirectory(srcImages, destImages);
                        log.info("MinerU 官方 API 图片资源已拷贝, src: {}, dest: {}", srcImages, destImages);
                    } catch (IOException e) {
                        log.warn("MinerU 官方 API 图片资源拷贝失败: {}", destImages, e);
                    }
                }
            }
            return new ExtractionResult(result.markdown, result.imagesDir);
        } finally {
            // 清理临时文件
            try {
                FileUtils.deleteDirectory(tmpPath.toFile());
            } catch (IOException e) {
                log.warn("清理 MinerU 临时目录失败: {}", tmpDir, e);
            }
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API直接解压到targetDir并拷贝images目录--------
```

- [ ] **Step 3: 视觉验证**

确认：
- 方法签名增加 `File targetDir` 参数
- 返回类型改为 `ExtractionResult`
- ZIP 解压仍在临时目录（避免污染 targetDir）
- `full.md` 写入 `targetDir/full.md`，`images/` 拷贝到 `targetDir/images/`
- `update-begin/end` 完整

---

## Task 9: 修改 `parse` 方法签名并转发到 `downloadAndExtractMarkdown`

**Files:**
- Modify: `MineruApiClient.java`，`parse` 公开方法（约第 72-106 行）

**Interfaces:**
- Consumes: 旧的 `parse(File, String)` 签名。
- Produces: 新的 `parse(File file, String fileType, File targetDir)` 签名，把 Markdown 写入 `targetDir`。

- [ ] **Step 1: 修改方法签名**

找到现有：
```java
    public String parse(File file, String fileType) {
```

替换为：
```java
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse方法签名扩展targetDir参数，images拷贝下沉到客户端内部--------
    public String parse(File file, String fileType, File targetDir) {
```

- [ ] **Step 2: 在方法开头校验 `targetDir`**

找到现有：
```java
        KnowConfigBean.CloudConfig cloud = knowConfigBean.getMinerU().getCloud();
        AssertUtils.assertNotEmpty("请配置 MinerU 官方 API Key", cloud.getApiKey());
        AssertUtils.assertTrue("MinerU 官方 API 文件不能为空", file != null && file.exists());
```

在 `AssertUtils.assertTrue` 之后追加：

```java
        AssertUtils.assertNotNull("MinerU 官方 API 目标目录不能为空", targetDir);
```

- [ ] **Step 3: 替换对 `downloadAndExtractMarkdown` 的调用**

找到现有：
```java
            // 4. 下载并解压 ZIP，读取 Markdown
            String markdown = downloadAndExtractMarkdown(result.getFullZipUrl(), cloud);
```

替换为：
```java
            // 4. 下载并解压 ZIP，写入 targetDir 并拷贝 images/
            String markdown = downloadAndExtractMarkdown(result.getFullZipUrl(), cloud, targetDir).markdown;
```

- [ ] **Step 4: 用 `update-begin/end` 包裹 `parse` 方法**

把整个 `parse` 方法用注释包裹（方法开始 `//update-begin`，方法结束 `}` 后一行 `//update-end`）。

- [ ] **Step 5: 视觉验证**

确认：
- 方法签名增加 `File targetDir`
- 调用 `downloadAndExtractMarkdown` 多传 `targetDir` 参数
- 方法返回 `String markdown`（从 `ExtractionResult.markdown` 取）
- `update-begin/end` 完整

---

## Task 10: 更新 `EmbeddingHandler.parseFileByMinerUCloud` 调用

**Files:**
- Modify: `EmbeddingHandler.java`，`parseFileByMinerUCloud` 方法（约第 966-1003 行）

**Interfaces:**
- Consumes: Task 9 产出的新签名 `parse(File, String, File)`。
- Produces: 调用多传 `outputDir` 参数；删除本地的 markdown 写入逻辑（已下沉到 `MineruApiClient`）。

- [ ] **Step 1: 修改 `parseFileByMinerUCloud` 中的调用**

找到现有：
```java
        long startTime = System.currentTimeMillis();
        String fileType = FilenameUtils.getExtension(docFile.getName());
        String markdown = mineruApiClient.parse(docFile, fileType);

        if (oConvertUtils.isEmpty(markdown)) {
            log.warn("MinerU 官方 API 解析结果为空, file: {}", docFile.getName());
            return;
        }
```

替换为：
```java
        //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir，调用方仅传路径--------
        long startTime = System.currentTimeMillis();
        String fileType = FilenameUtils.getExtension(docFile.getName());

        // 先准备输出目录（images 拷贝与 full.md 写入由 MineruApiClient 统一处理）
        String fileBaseName = FilenameUtils.getBaseName(docFile.getName());
        String relativeDir = "mineru" + File.separator + UUIDGenerator.generate() + File.separator + fileBaseName + File.separator + "auto" + File.separator;
        String outputPath = uploadpath + File.separator + relativeDir;
        File outputDir = new File(outputPath);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new JeecgBootException("创建 MinerU 官方 API 解析结果目录失败: " + outputPath);
        }

        String markdown = mineruApiClient.parse(docFile, fileType, outputDir);

        if (oConvertUtils.isEmpty(markdown)) {
            log.warn("MinerU 官方 API 解析结果为空, file: {}", docFile.getName());
            return;
        }
```

- [ ] **Step 2: 删除 `parseFileByMinerUCloud` 中本地的 markdown 写入逻辑**

找到现有：
```java
        // 将解析结果写入本地临时目录，复用原有 filePath / sourcesPath 回写逻辑
        String fileBaseName = FilenameUtils.getBaseName(docFile.getName());
        String relativeDir = "mineru" + File.separator + UUIDGenerator.generate() + File.separator + fileBaseName + File.separator + "auto" + File.separator;
        String outputPath = uploadpath + File.separator + relativeDir;
        File outputDir = new File(outputPath);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new JeecgBootException("创建 MinerU 官方 API 解析结果目录失败: " + outputPath);
        }

        String mdFilePath = outputPath + fileBaseName + ".md";
        try (FileOutputStream fos = new FileOutputStream(mdFilePath);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(markdown);
        } catch (IOException e) {
            log.error("MinerU 官方 API 解析结果写入失败: {}", mdFilePath, e);
            throw new JeecgBootException("MinerU 官方 API 解析结果保存失败: " + e.getMessage(), e);
        }

        // 回写 metadata，保持与本地模式一致的相对路径约定
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeDir + fileBaseName + ".md");
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
        doc.setMetadata(metadataJson.toJSONString());

        log.info("MinerU 官方 API 解析结果已写入本地, file: {}, mdPath: {}, cost: {}ms",
                docFile.getName(), mdFilePath, System.currentTimeMillis() - startTime);
    }
```

替换为：
```java
        // 回写 metadata，保持与本地模式一致的相对路径约定
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeDir + fileBaseName + ".md");
        metadataJson.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
        doc.setMetadata(metadataJson.toJSONString());

        log.info("MinerU 官方 API 解析结果已写入本地, file: {}, dir: {}, cost: {}ms",
                docFile.getName(), outputPath, System.currentTimeMillis() - startTime);
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解析结果与images目录由客户端统一写入targetDir--------
```

- [ ] **Step 3: 视觉验证**

确认：
- `parse` 调用多传 `outputDir`
- 删除了本地 `FileOutputStream` 写入逻辑（已下沉到 `MineruApiClient`）
- metadata 回写逻辑保留
- 日志保留并更新（`mdPath` 改为 `dir`）
- `update-begin/end` 完整
- `import java.io.FileOutputStream` 等未使用的 import 若有残留可保留（不强制清理，避免超出范围）

---

## Task 11: 整体静态验收

**Files:**
- Re-read: `MineruApiClient.java`、`EmbeddingHandler.java`、`KnowConfigBean.java`

**Interfaces:**
- 验证所有改动符合设计文档的静态验收清单。

- [ ] **Step 1: 验收项 1 — 上层零感知**

打开 `EmbeddingHandler.embeddingDocument` 方法，确认 `parseFileByMinerU` 入口、`parseFileByMinerUCloud` 私有方法签名仍为 `(AiragKnowledgeDoc, File, JSONObject)`，调用方无任何业务逻辑改动。

- [ ] **Step 2: 验收项 2 — 本地模式兜底保留**

打开 `EmbeddingHandler.parseFileByMinerU`，确认：
- `if ("cloud".equalsIgnoreCase(knowConfigBean.getMinerU().getMode()))` 分支仍然存在
- `mode != cloud` 时仍走 `CommandExecUtil.execCommand(magic-pdf ...)`
- `KnowConfigBean.condaEnv` 字段保留

- [ ] **Step 3: 验收项 3 — 总超时实际生效**

打开 `MineruApiClient.pollBatchResult`，确认：
- `startTime` / `maxWaitMillis` 声明在 `for` 循环外
- for 循环开头与 sleep 前各有一个超时判断
- 超时时抛 `JeecgBootException`

- [ ] **Step 4: 验收项 4 — 图片本地化**

打开 `MineruApiClient.downloadAndExtractMarkdown`，确认：
- ZIP 解压到临时目录后，结果通过 `FileUtils.writeStringToFile` 写入 `targetDir/full.md`
- `images/` 通过 `FileUtils.copyDirectory` 拷贝到 `targetDir/images/`
- `imagesDir` 为 null 时跳过拷贝

- [ ] **Step 5: 验收项 5 — 错误码翻译**

打开 `MineruApiClient.MineruErrorCodeMapper`，确认：
- 至少包含 A0202 / A0211 / -500 / -10001 / -10002 / -60002 / -60003 / -60004 / -60005 / -60006 / -60007 / -60018 等高频错误码
- `translate` 方法处理空 body、JSON 解析失败、code 字段缺失三种兜底

- [ ] **Step 6: 验收项 6 — 注释完整**

使用文本搜索 `update-begin---author:song`，确认所有新增/修改代码块均用 `//update-begin/end` 包裹，作者 song，日期 2026-07-09。

- [ ] **Step 7: 验收项 7 — 风格一致**

确认：
- 异常统一用 `JeecgBootException`（不用 `RuntimeException` 或 `Exception`）
- 日志用 `@Slf4j` + `log.info/error/debug/warn`，前缀 `MinerU 官方 API ...`
- HTTP 头部用 `RestUtil.getHeaderApplicationJson()`
- HTTP 客户端用 `HttpComponentsClientHttpRequestFactory`

- [ ] **Step 8: 验收项 8 — 配置前缀**

打开 `KnowConfigBean.java`，确认 `CloudConfig` 仍在 `KnowConfigBean.PREFIX = "jeecg.airag.know"` 之下，字段集未变。

- [ ] **Step 9: 验收项 9 — 文件清单**

确认改动文件仅 3 个：
- `KnowConfigBean.java`（仅 Javadoc）
- `MineruApiClient.java`（含两个新内部类 + 多处方法签名变更）
- `EmbeddingHandler.java`（仅 `parseFileByMinerUCloud` 一处调用变更）

未改动 `application-dev.yml`、`pom.xml` 或其他文件。

---

## Self-Review

**1. Spec coverage:**
- 设计文档 §5.3 补丁 1（timeout 实际生效）→ Task 5 ✅
- 设计文档 §5.3 补丁 2（images/ 拷贝 + 签名变更）→ Task 6/7/8/9/10 ✅
- 设计文档 §5.3 补丁 3（错误码映射）→ Task 2/3/4 ✅
- 设计文档 §5.3 补丁 4（日志规范）→ Task 8/10 日志新增 ✅
- 设计文档 §5.3 补丁 5（update-begin/end）→ 每个 Task 内已嵌入 ✅
- 设计文档 §九 静态验收清单 → Task 11 ✅

**2. Placeholder scan:** 无 "TBD" / "TODO" / "类似 Task N"。每步代码完整。

**3. Type consistency:**
- `ExtractionResult` 在 Task 6 定义为 `private static class`，在 Task 7 中作为 `extractMarkdown` 返回类型，在 Task 8 中作为 `downloadAndExtractMarkdown` 返回类型，在 Task 9 中 `.markdown` 字段被访问——全部一致 ✅
- `MineruErrorCodeMapper` 在 Task 2 定义，`translate(String body)` 在 Task 3 中被调用——签名一致 ✅
- `resolveErrorMsg` 在 Task 3 定义，在 Task 4 中被调用——签名一致 ✅
- `parse(File, String, File)` 在 Task 9 定义，在 Task 10 中被调用——签名一致 ✅
- `downloadAndExtractMarkdown(String, CloudConfig, File)` 在 Task 8 定义，在 Task 9 中被调用——签名一致 ✅

**4. Scope check:** 工作边界（仅代码、不 mvn/git/测试）在每个 Task 中保持一致。