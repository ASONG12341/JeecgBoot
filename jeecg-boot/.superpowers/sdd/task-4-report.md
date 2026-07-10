# Task 4 Report — 替换 catch 分支内 parseErrorMsg(e) → resolveErrorMsg(e)

## 1. Status

DONE

## 2. Files modified

- `D:\git_xiangmu\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\main\java\org\jeecg\modules\airag\llm\handler\MineruApiClient.java`

## 3. Steps completed（3 步）

### Step 1 — 全文读取并定位 4 处调用点
通过 `Read` 工具完整读取 `MineruApiClient.java`（共 599 行），定位到 4 处 catch 分支内的 `parseErrorMsg(e)` 调用与 1 处 `resolveErrorMsg` 方法体内部的 `parseErrorMsg(e)` 兜底调用。

### Step 2 — 4 次独立 Edit 完成替换
为避免误伤第 476 行 `resolveErrorMsg` 方法体内的 `parseErrorMsg(e)` 兜底调用，**未使用** `replace_all=true` 简单替换，而是采用 4 次独立 Edit，每次 `old_string` 携带 catch 分支独有的中文错误消息前缀，使每次匹配均唯一：

| # | 旧字符串（节选） | 新字符串（节选） |
|---|------------------|------------------|
| 1 | `throw new JeecgBootException("MinerU 官方 API 创建批量任务失败: " + parseErrorMsg(e));` | `... + resolveErrorMsg(e));` |
| 2 | `throw new JeecgBootException("MinerU 官方 API 文件上传失败: " + parseErrorMsg(e));` | `... + resolveErrorMsg(e));` |
| 3 | `throw new JeecgBootException("MinerU 官方 API 查询批量任务结果失败: " + parseErrorMsg(e));` | `... + resolveErrorMsg(e));` |
| 4 | `throw new JeecgBootException("MinerU 官方 API 结果 ZIP 下载失败: " + parseErrorMsg(e));` | `... + resolveErrorMsg(e));` |

4 次 Edit 全部成功返回 `The file ... has been updated successfully.`

### Step 3 — 视觉验证
使用 `Grep` 工具分别搜索两个调用点：
- `parseErrorMsg(e)`：仅 1 处命中（第 476 行，`resolveErrorMsg` 方法体内 `return parseErrorMsg(e);`，作为映射表无匹配时的官方 msg 兜底逻辑）
- `resolveErrorMsg(e)`：4 处命中，分别在第 147、196、225、322 行的 catch 分支 throw 表达式内
- `parseErrorMsg` 方法定义（第 436–456 行）完整保留
- `resolveErrorMsg` 方法定义（第 467–477 行）完整保留
- 未引入新的 `update-begin/update-end` 注释块（本 Task 为机械替换，沿用 Task 3 既有注释）
- 未修改任何方法签名、未修改 catch 分支的 throw 语句格式

## 4. 替换前后行号对比

| # | 所在方法 | catch 行（替换前） | throw 行（替换前） | throw 行（替换后） | 替换后文本 |
|---|----------|--------------------|--------------------|--------------------|------------|
| 1 | `applyBatchUploadUrl` | 145 | 147 | 147 | `throw new JeecgBootException("MinerU 官方 API 创建批量任务失败: " + resolveErrorMsg(e));` |
| 2 | `uploadFile` | 194 | 196 | 196 | `throw new JeecgBootException("MinerU 官方 API 文件上传失败: " + resolveErrorMsg(e));` |
| 3 | `pollBatchResult` | 222 | 225 | 225 | `throw new JeecgBootException("MinerU 官方 API 查询批量任务结果失败: " + resolveErrorMsg(e));` |
| 4 | `downloadZip` | 320 | 322 | 322 | `throw new JeecgBootException("MinerU 官方 API 结果 ZIP 下载失败: " + resolveErrorMsg(e));` |

行号未发生变化（替换发生在已有行内），完全符合"机械替换"要求。

## 5. 调用点统计

- `parseErrorMsg(e)` 调用点：**1**（位于 `resolveErrorMsg` 方法体内第 476 行 `return parseErrorMsg(e);`，作为映射表未命中时的兜底）✓
- `resolveErrorMsg(e)` 调用点：**4**（位于 4 个 catch 分支的 throw 表达式内）✓

## 6. Concerns

无。本次替换为纯机械替换，方法签名、throw 语句格式、注释块均保持不变；Task 3 新增的 `resolveErrorMsg` 帮助方法与 `MineruErrorCodeMapper` 错误码映射表已通过本 Task 在全部 4 个 catch 分支上生效。

## 7. One-line summary

按计划把 `MineruApiClient.java` 内 4 处 catch 分支的 `parseErrorMsg(e)` 全部替换为 `resolveErrorMsg(e)`，调用点数量由 5 减为 1（仅余 `resolveErrorMsg` 内部兜底）、`resolveErrorMsg` 调用点增加为 4，`parseErrorMsg` / `resolveErrorMsg` 方法定义均完整保留，Task 3 引入的错误码映射逻辑在全部 catch 分支统一生效。
