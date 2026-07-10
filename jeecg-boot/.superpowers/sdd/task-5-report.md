# Task 5 Report — MinerU 官方 API 替换

## 1. Status

**DONE**

## 2. Files modified

- `D:\git_xiangmu\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\main\java\org\jeecg\modules\airag\llm\handler\MineruApiClient.java`
  - 仅修改 `pollBatchResult` 方法（添加 `timeout` 实际生效逻辑 + update-begin/end 包裹）

## 3. Steps completed（6 步全部完成）

- Step 1：Read 工具定位 `pollBatchResult` 方法（原文件第 209 行），确认结构。
- Step 2：在 `int interval = Math.max(cloud.getRetryInterval(), 1);` 之后插入 `startTime` / `maxWaitMillis` 声明（for 循环之外）。
- Step 3：在 for 循环体内 `log.debug` 之前插入第一个超时判断。
- Step 4：在 `try { TimeUnit.SECONDS.sleep(interval);` 之前插入第二个超时判断（避免 sleep 后越界）。
- Step 5：用 `//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效，避免retryTimes计算超出timeout--------` 与对应的 `//update-end---...` 注释包裹整个方法（从 Javadoc 前一行到方法结束 `}` 后一行）。
- Step 6：Read 工具视觉验证全部通过（详见第 6 节）。

## 4. 修改前后行号对比

### Before（原文件）

| 行号 | 内容 |
|------|------|
| 200 | `    /**`（Javadoc 开始） |
| 209 | `    private BatchResult pollBatchResult(String batchId, KnowConfigBean.CloudConfig cloud) {` |
| 215 | `        int maxRetry = Math.max(cloud.getRetryTimes(), 1);` |
| 216 | `        int interval = Math.max(cloud.getRetryInterval(), 1);` |
| 217 | `        for (int i = 0; i < maxRetry; i++) {` |
| 218 | `            log.debug("MinerU 官方 API 轮询批量任务结果, batchId: {}, 第{}次", batchId, i + 1);` |
| 254 | `            try {` |
| 255 | `                TimeUnit.SECONDS.sleep(interval);` |
| 261 | `        throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时，请稍后重试");` |
| 262 | `    }`（方法结束） |

### After（修改后）

| 行号 | 内容 | 性质 |
|------|------|------|
| 200 | `    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效，避免retryTimes计算超出timeout--------` | 新增 |
| 201 | `    /**`（Javadoc 开始） | 保持 |
| 210 | `    private BatchResult pollBatchResult(...)` | 保持 |
| 218 | `        long startTime = System.currentTimeMillis();` | 新增 |
| 219 | `        long maxWaitMillis = cloud.getTimeout() * 1000L;` | 新增 |
| 221 | `            if (System.currentTimeMillis() - startTime >= maxWaitMillis) {` | 新增（第一个超时判断） |
| 222 | `                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");` | 新增 |
| 223 | `            }` | 新增 |
| 224 | `            log.debug("MinerU 官方 API 轮询批量任务结果, batchId: {}, 第{}次", batchId, i + 1);` | 保持 |
| 260 | `            if (System.currentTimeMillis() - startTime + interval * 1000L >= maxWaitMillis) {` | 新增（第二个超时判断） |
| 261 | `                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");` | 新增 |
| 262 | `            }` | 新增 |
| 263 | `            try {` | 保持 |
| 264 | `                TimeUnit.SECONDS.sleep(interval);` | 保持 |
| 270 | `        throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时，请稍后重试");` | 保持 |
| 271 | `    }`（方法结束） | 保持 |
| 272 | `    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效，避免retryTimes计算超出timeout--------` | 新增 |

## 5. 最终的 `pollBatchResult` 方法行范围

- **整体（含 update-begin/end）：第 200 行 — 第 272 行**（共 73 行）
- **方法签名与 Javadoc：第 201 行 — 第 210 行**
- **方法体：第 211 行 — 第 271 行**
- **新增代码行（4 段，共 8 行新增 + 2 行注释）：第 200、218、219、221-223、260-262、272 行**

## 6. 视觉验证（Step 6 通过项）

- [x] `startTime` / `maxWaitMillis` 声明在 `for` 循环外（在 `int interval = ...` 之后，line 218-219）
- [x] for 循环开头（`log.debug` 之前，line 221-223）有第一个超时判断
- [x] sleep 之前（line 260-262）有第二个超时判断
- [x] 两个判断都抛 `JeecgBootException`（错误信息中包含 `cloud.getTimeout()` 实际秒数）
- [x] update-begin/end 完整（作者 song、日期 2026-07-09、for 描述精确）
- [x] 未修改方法签名（仍为 `private BatchResult pollBatchResult(String batchId, KnowConfigBean.CloudConfig cloud)`）
- [x] 未修改既有逻辑（restTemplate 调用、状态判断、done/failed 处理、sleep 全部保留原样）
- [x] `KnowConfigBean.CloudConfig.getTimeout()` 字段已确认存在（默认 300 秒）

## 7. Concerns

无。`getTimeout()` 字段已存在于 `KnowConfigBean.CloudConfig`（默认 300 秒），与本任务计划中"默认 300 秒"描述一致；两个超时判断点（循环开头 + sleep 前）可避免"最后一段 sleep 越过 timeout"和"已完成但下一次轮询前已超时"两种边界情况。

## 8. One-line summary

Task 5 完成 — `pollBatchResult` 已加入 `CloudConfig.timeout` 双重判断（for 循环入口 + sleep 前），让总超时（默认 300 秒）实际生效，并按规范用 update-begin/end 包裹整个方法。
