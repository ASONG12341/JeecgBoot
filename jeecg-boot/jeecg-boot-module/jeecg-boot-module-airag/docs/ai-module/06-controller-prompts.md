# 06 · `/airag/prompts/*` 与 `/airag/extData/*` 控制器 · 完整解读

> 本文档把提示词市场与评估器/轨迹两个 controller 合并，因为它们共享同一张 `airag_ext_data` 表（`biz_type` 区分）与 `airag_prompts` 表。

---

## 一、`/airag/prompts/*` 控制器（`AiragPromptsController.java`）

| 方法 | 路径 | 来源 |
|------|------|------|
| `GET` | `/airag/prompts/list` | `queryPageList` |
| `POST` | `/airag/prompts/add` | `add` |
| `PUT,POST` | `/airag/prompts/edit` | `edit` |
| `DELETE` | `/airag/prompts/delete` | `delete` |
| `DELETE` | `/airag/prompts/deleteBatch` | `deleteBatch` |
| `GET` | `/airag/prompts/queryById` | `queryById` |
| `POST` | `/airag/prompts/experiment` | `promptExperiment` |
| `GET` | `/airag/prompts/exportXls` | `exportXls` |
| `POST` | `/airag/prompts/importExcel` | `importExcel` |

### 1.1 `add` / `edit` 主线

```java
@PostMapping(value = "/add")
public Result<String> add(@RequestBody AiragPrompts airagPrompts) {
    airagPrompts.setDelFlag(CommonConstant.DEL_FLAG_0);   // "0"
    airagPrompts.setStatus("0");                          // "0"=未发布
    airagPromptsService.save(airagPrompts);
    return Result.OK("添加成功！");
}

@RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
public Result<String> edit(@RequestBody AiragPrompts airagPrompts) {
    airagPromptsService.updateById(airagPrompts);
    return Result.OK("编辑成功!");
}
```

**逐行解读**：
- `add` 强制写入 `delFlag=0`（未删除）、`status=0`（未发布）
- `edit` 直接 update

### 1.2 `POST /airag/prompts/experiment` —— Prompt 实验

```java
@PostMapping(value = "/experiment")
public Result<?> promptExperiment(@RequestBody AiragExperimentVo experimentVo, HttpServletRequest request) {
    return airagPromptsService.promptExperiment(experimentVo, request);
}
```

这是核心实验端点。`AiragExperimentVo` 包含：
- 一个或多个 prompt 模板版本
- 测试用例（input）
- 评估器配置

### 1.3 实体 `AiragPrompts`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` / `name` / `delFlag (@TableLogic)` / `status` | | 标准 |
| `promptKey` | String | 唯一标识（用于在代码中引用） |
| `description` | String | 描述 |
| **`content`** | String | **模板内容，支持变量占位符 `{{variable}}`** |
| `category` | String | 提示词分类 |
| `tags` | String | 逗号分隔标签 |
| `modelId` | String | 适配的大模型 ID |
| `modelParam` | String | 大模型参数配置 |
| `version` | String | 版本号（格式 `0.0.1`） |

---

## 二、`/airag/extData/*` 控制器（`AiragExtDataController.java`）

`AiragExtData` 是一张"通用业务数据表"，按 `biz_type` 区分：
- `evaluator`：评估器（用于 `/airag/extData/evaluator/debug`）
- `track`：调用轨迹（用于 `/airag/extData/getTrackList` 等）

| 方法 | 路径 | bizType 强制 | 来源 |
|------|------|------------|------|
| `GET` | `/airag/extData/list` | `evaluator` | `queryPageList` |
| `GET` | `/airag/extData/getTrackList` | `track` | `getTrackList` |
| `POST` | `/airag/extData/add` | `evaluator` | `add` |
| `PUT,POST` | `/airag/extData/edit` | — | `edit` |
| `DELETE` | `/airag/extData/delete` | — | `delete` |
| `DELETE` | `/airag/extData/deleteBatch` | — | `deleteBatch` |
| `GET` | `/airag/extData/queryById` | — | `queryById` |
| `GET` | `/airag/extData/queryTrackById` | — | `queryTrackById` |
| `POST` | `/airag/extData/evaluator/debug` | — | `debugEvaluator` |
| `GET` | `/airag/extData/exportXls` | — | `exportXls` |
| `POST` | `/airag/extData/importExcel` | — | `importExcel` |

### 2.1 `queryPageList` —— 评估器列表

```java
@GetMapping(value = "/list")
public Result<IPage<AiragExtData>> queryPageList(AiragExtData airagExtData, ...) {
    QueryWrapper<AiragExtData> queryWrapper = QueryGenerator.initQueryWrapper(airagExtData, req.getParameterMap());
    Page<AiragExtData> page = new Page<>(pageNo, pageSize);
    // ★ 强制 biz_type=evaluator
    queryWrapper.eq("biz_type", AiPromptsConsts.BIZ_TYPE_EVALUATOR);
    IPage<AiragExtData> pageList = airagExtDataService.page(page, queryWrapper);
    return Result.OK(pageList);
}
```

### 2.2 `getTrackList` —— 调用轨迹列表

```java
@GetMapping(value = "/getTrackList")
public Result<IPage<AiragExtData>> getTrackList(AiragExtData airagExtData, ...) {
    QueryWrapper<AiragExtData> queryWrapper = QueryGenerator.initQueryWrapper(airagExtData, req.getParameterMap());
    Page<AiragExtData> page = new Page<>(pageNo, pageSize);
    // ★ 强制 biz_type=track
    queryWrapper.eq("biz_type", AiPromptsConsts.BIZ_TYPE_TRACK);
    
    String metadata = airagExtData.getMetadata();
    if (oConvertUtils.isEmpty(metadata)) {
        return Result.OK();   // ★ 没传 metadata 直接返回空（避免查全表）
    }
    IPage<AiragExtData> pageList = airagExtDataService.page(page, queryWrapper);
    return Result.OK(pageList);
}
```

### 2.3 `queryTrackById` —— 查单个轨迹

```java
@GetMapping(value = "/queryTrackById")
public Result<List<AiragExtData>> queryTrackById(@RequestParam(name = "id", required = true) String id) {
    AiragExtData airagExtData = airagExtDataService.getById(id);
    String status = airagExtData.getStatus();
    // ★ 任务还在跑就不返回
    if (AiPromptsConsts.STATUS_RUNNING.equals(status)) {
        return Result.error("处理中，请稍后刷新");
    }
    List<AiragExtData> trackList = airagExtDataService.queryTrackById(id);
    return Result.OK(trackList);
}
```

`IAirragExtDataService.queryTrackById(id)` 内部查询整个调用链路（可能是按 `metadata.parentId` 关联多 row）。

### 2.4 `POST /airag/extData/evaluator/debug` —— 评估器调试

```java
@PostMapping(value = "/evaluator/debug")
public Result<?> debugEvaluator(@RequestBody AiragDebugVo debugVo) {
    return airagExtDataService.debugEvaluator(debugVo);
}
```

入参 `AiragDebugVo`：

```java
@Data
public class AiragDebugVo {
    private String evaluatorId;        // 评估器 ID
    private String inputText;          // 被评估的 AI 回答
    private String expectedOutput;     // 期望输出（用于对比）
    private Map<String, Object> config;  // 评分维度
}
```

### 2.5 实体 `AiragExtData`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (ASSIGN_ID) | |
| `bizType` | String | `evaluator` / `track` |
| `name` | String | |
| `descr` | String | |
| `tags` | String | 逗号分隔 |
| `dataValue` | String | **JSON**，实际存储内容（如 evaluator 的评分规则） |
| `metadata` | String | 元数据 JSON |
| `datasetValue` | String | 评测集数据 |
| `status` | String | `run`（进行中）/ `completed` / `failed` |
| `version` | Integer | 版本号 |
| 标准审计 | | createBy/.../tenantId |

---

## 三、`IAirragPromptsService` 与 `IAirragExtDataService`

### 3.1 `IAirragPromptsService`

```java
public interface IAiragPromptsService extends IService<AiragPrompts> {
    Result<?> promptExperiment(AiragExperimentVo experimentVo, HttpServletRequest request);
}
```

空接口，唯一自定义方法 `promptExperiment` 跑实验。详见 [00-overview.md](00-overview.md)。

### 3.2 `IAirragExtDataService`

```java
public interface IAiragExtDataService extends IService<AiragExtData> {
    Result debugEvaluator(AiragDebugVo debugVo);
    List<AiragExtData> queryTrackById(String id);
}
```

---

## 四、常量 `AiPromptsConsts`

```java
public class AiPromptsConsts {
    public static final String STATUS_RUNNING = "run";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String BIZ_TYPE_EVALUATOR = "evaluator";
    public static final String BIZ_TYPE_TRACK = "track";
}
```

---

## 五、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `prompts/controller/AiragPromptsController.java` | 167 | 9 个 `/airag/prompts/*` 端点 |
| `prompts/controller/AiragExtDataController.java` | 213 | 11 个 `/airag/extData/*` 端点 |
| `prompts/service/IAiragPromptsService.java` | 18 | 接口 |
| `prompts/service/IAirragExtDataService.java` | 21 | 接口 |
| `prompts/service/impl/AiragPromptsServiceImpl.java` | — | 实现 |
| `prompts/service/impl/AirragExtDataServiceImpl.java` | — | 实现 |
| `prompts/entity/AiragPrompts.java` | 108 | 提示词实体 |
| `prompts/entity/AiragExtData.java` | 99 | 评估器/轨迹实体 |
| `prompts/consts/AiPromptsConsts.java` | 30 | 常量 |
| `prompts/vo/AiragExperimentVo.java` | — | 实验 VO |
| `prompts/vo/AiragDebugVo.java` | — | 调试 VO |
| `prompts/mapper/AiragPromptsMapper.java` | — | mapper |
| `prompts/mapper/AiragExtDataMapper.java` | — | mapper |

---

## 六、下一章

[07-controller-ocr.md](07-controller-ocr.md) — `/airag/ocr/*` 控制器（Redis 存储）。
