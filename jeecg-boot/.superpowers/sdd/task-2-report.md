# Task 2 Report — MinerU 官方 API 错误码映射内部类

## Status
DONE

## Files Modified
- `D:\git_xiangmu\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\main\java\org\jeecg\modules\airag\llm\handler\MineruApiClient.java`

## Steps Completed
1. **Read & 校验**：读取 `MineruApiClient.java`（522 行）。识别 import 区已存在 `com.alibaba.fastjson.JSON`、`com.alibaba.fastjson.JSONObject`、`org.apache.commons.lang3.StringUtils`，但缺少 `java.util.HashMap` 与 `java.util.Map`。
2. **补 Import**：在 `java.util.Enumeration` 与 `java.util.concurrent.TimeUnit` 之间插入 `import java.util.HashMap;` 与 `import java.util.Map;`，保持字母序。
3. **新增内部类**：在文件末尾最后一个 `}` 之前、`BatchResult` 内部类之后插入 `private static class MineruErrorCodeMapper`，完整代码块按规范用 `//update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示-------` 包裹。
4. **视觉验证**：重读文件确认新内部类位于 `class MineruApiClient { ... }` 闭合 `}`（第 577 行）之前；`translate` 方法覆盖三种兜底（空 body、`JSON.parseObject` 异常、`code` 字段缺失），全部返回 `null` 供上游识别失败时降级。

## Imports 检查结果
- `com.alibaba.fastjson.JSON` —— ✅ 已存在
- `com.alibaba.fastjson.JSONObject` —— ✅ 已存在
- `org.apache.commons.lang3.StringUtils` —— ✅ 已存在
- `java.util.HashMap` —— ❌ 缺失 → 已新增
- `java.util.Map` —— ❌ 缺失 → 已新增
- 无新引入第三方依赖。

## Concerns
无。本 Task 只新增内部类，未触动 `parseErrorMsg`、各 catch 分支等既有逻辑，留待 Task 3 / Task 4 在此之上集成。

## One-line Summary
在 `MineruApiClient` 内新增私有静态内部类 `MineruErrorCodeMapper`，含 17 条高频错误码 → 中文业务提示映射与 `translate(String body)` 方法，已补齐 `HashMap`/`Map` 导入并按规范用 `update-begin/end` 包裹。