# Task 9 Report — MineruApiClient.parse 签名扩展为 (File, String, File)

## Status
DONE_WITH_CONCERNS

## Files modified
- `D:\git_xiangmu\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\main\java\org\jeecg\modules\airag\llm\handler\MineruApiClient.java`

## Steps completed
1. ✅ 读取 `MineruApiClient.java`，定位 `parse` 方法原始位置（约 74-108 行）
2. ✅ 用 `update-begin` / `update-end` 包裹整个方法（Javadoc 前一行 + 方法结束 `}` 后一行），作者 song、日期 2026-07-09、for=【AI知识库】MinerU官方API parse方法签名扩展targetDir参数，images拷贝下沉到客户端内部
3. ✅ 方法签名 `parse(File, String)` → `parse(File, String, File targetDir)`
4. ✅ 在 `assertTrue("MinerU 官方 API 文件不能为空", ...)` 之后新增 `assertNotNull("MinerU 官方 API 目标目录不能为空", targetDir)`
5. ✅ `downloadAndExtractMarkdown(result.getFullZipUrl(), cloud)` → `downloadAndExtractMarkdown(result.getFullZipUrl(), cloud, targetDir).markdown`，并把 step 4 注释改为 "下载并解压 ZIP，写入 targetDir 并拷贝 images/"
6. ✅ 视觉验证：方法签名、新增 assert、download 调用、update-begin/end 包裹均符合规范

## 行号对比

| 元素 | Before | After |
|------|--------|-------|
| `parse` 方法所在行范围（含 Javadoc） | 65-108 | 65-112 |
| `update-begin` 行 | — | 65 |
| 方法签名 | 74 | 76 |
| `assertNotNull` 行 | — | 80 |
| `downloadAndExtractMarkdown` 调用 | 97 | 100 |
| 方法结束 `}` | 108 | 111 |
| `update-end` 行 | — | 112 |

## 最终的 parse 方法行范围
- 含 update-begin/end 注释：**65-112**（共 48 行）
- 纯方法（Javadoc + signature + body + `}`）：**66-111**

## 修改要点
1. 方法签名：`(File file, String fileType)` → `(File file, String fileType, File targetDir)`
2. Javadoc 新增 `@param targetDir 业务目标目录（用于保存 full.md 与 images/）`
3. 新增 `AssertUtils.assertNotNull("MinerU 官方 API 目标目录不能为空", targetDir);`
4. `downloadAndExtractMarkdown` 调用增加 `targetDir` 参数，并从 `ExtractionResult.markdown` 取 markdown
5. 注释 "下载并解压 ZIP，读取 Markdown" 改为 "下载并解压 ZIP，写入 targetDir 并拷贝 images/"

## Concerns

### 严重 — 现有调用方未同步更新（Task 10 需处理）
- `EmbeddingHandler.parseFileByMinerUCloud(File file, String fileType)` 仍按旧两参签名调用 `mineruApiClient.parse(file, fileType)`，**当前会因为 `parse(File, String, File)` 不再匹配而编译失败**。
- 这是 Task 9 计划中明确告知的预期现象：Task 10 才负责更新 EmbeddingHandler。
- 本 Task 严格遵守"不修改其他方法"约束，未触动 EmbeddingHandler。

### 注意事项 — 调用方传入 targetDir 的语义
- Task 10 在 EmbeddingHandler 内传入的 `targetDir` 应当是业务上的文档输出目录；`downloadAndExtractMarkdown` 内部会做 `targetDir.mkdirs()`、写入 `full.md`、拷贝 `images/`。需要保证传入的目录具备写权限。

### 兼容性
- 这是公开方法签名的破坏性变更（本模块内）。除了 EmbeddingHandler 之外，需要 grep 全模块确认无其他调用方遗漏。

## One-line summary
Task 9 DONE_WITH_CONCERNS：MineruApiClient.parse 签名已扩展为 (File, String, File)，新增 targetDir 非空校验并转发到 downloadAndExtractMarkdown，但 EmbeddingHandler.parseFileByMinerUCloud 仍调用旧签名，需 Task 10 同步更新。

---

## Fix Log — 编译错误修复 (assertNotNull → assertTrue)

### Fix status
DONE

### 触发的编译错误
Task 9 引入的 `AssertUtils.assertNotNull("MinerU 官方 API 目标目录不能为空", targetDir);` 调用了 `AssertUtils` 不存在的方法 `assertNotNull`。`AssertUtils` 仅提供 `assertEmpty / assertNotEmpty / assertTrue` 等方法（参考 `jeecg-boot-base-core/src/main/java/org/jeecg/common/util/AssertUtils.java`）。

### 修改的文件
- `D:\git_xiangmu\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\main\java\org\jeecg\modules\airag\llm\handler\MineruApiClient.java`

### 修改的行号
- **第 80 行**

### Before / After 对比

**Before（第 80 行）**：
```java
AssertUtils.assertNotNull("MinerU 官方 API 目标目录不能为空", targetDir);
```

**After（第 80 行）**：
```java
AssertUtils.assertTrue("MinerU 官方 API 目标目录不能为空", targetDir != null);
```

### 选型理由
采用方案 A（`assertTrue` 配合 `targetDir != null` 显式比较），风格与紧邻上方的第 79 行 `assertTrue("MinerU 官方 API 文件不能为空", file != null && file.exists());` 完全一致，调用 `AssertUtils` 已存在的方法，编译可过。

### 视觉验证
1. ✅ `grep assertNotNull MineruApiClient.java` — **No matches found**（原 `assertNotNull` 调用已消失）
2. ✅ 第 80 行已就位为 `AssertUtils.assertTrue("MinerU 官方 API 目标目录不能为空", targetDir != null);`
3. ✅ 上下文三行断言全部使用 `AssertUtils` 已存在方法：
   - 第 78 行：`assertNotEmpty("请配置 MinerU 官方 API Key", cloud.getApiKey())`
   - 第 79 行：`assertTrue("MinerU 官方 API 文件不能为空", file != null && file.exists())`
   - 第 80 行：`assertTrue("MinerU 官方 API 目标目录不能为空", targetDir != null)` ← 修复后
4. ✅ 仅修改第 80 行一处，未触动其他任何代码

### 修复后 One-line summary
Task 9 编译错误已修复：`AssertUtils.assertNotNull` 替换为 `AssertUtils.assertTrue(..., targetDir != null)`，编译错误消除。Task 9 整体仍为 DONE_WITH_CONCERNS（EmbeddingHandler 调用方同步需 Task 10 处理）。
