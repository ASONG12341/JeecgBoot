# 08 · `/airag/video/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/video/voiceover`**（视频配音管线：LLM 旁白文案 + TTS 合成 + FFmpeg 合并）为主线，从 HTTP 落到 `IVideoGenerationService.addVoiceover` 完整解读。

---

## 一、入口（`VideoGenerationController.java`，91 行）

| 方法 | 路径 | 来源 |
|------|------|------|
| `POST` | `/airag/video/submit` | `submitTask` |
| `GET` | `/airag/video/query/{taskId}` | `queryTask` |
| `POST` | `/airag/video/voiceover` | `addVoiceover` |
| `GET` | `/airag/video/prompts` | `getPresetPrompts` |
| `GET` | `/airag/video/listByUser` | `getVideoRecords` |
| `DELETE` | `/airag/video/deleteVideoRecord` | `deleteVideoRecord` |

---

## 二、实体 `VideoGenerateVo` 与 `VideoTaskResultVo`

```java
@Data
public class VideoGenerateVo {
    private String userId;
    private String modelName;           // "cogvideox"、"vidu"、"runway" 等
    private String prompt;
    private String imageUrl;            // 图生视频的源图
    private String taskId;              // 仅 voiceover 用
    private String videoUrl;            // 仅 upload 用
    private Integer duration;            // 时长（秒）
    private String aspectRatio;          // "16:9"、"9:16" 等
}

@Data
public class VideoTaskResultVo {
    private String taskId;
    private String status;               // "PENDING" / "RUNNING" / "SUCCESS" / "FAIL"
    private String message;
    private String videoUrl;
    private String audioUrl;
    private String coverUrl;
    private Integer progress;
}
```

`status="FAIL"` 字符串是 Service 层约定（区别于后端"SUCCESS"返回给前端时统一大写）。

---

## 三、`POST /airag/video/voiceover` —— 配音管线（主线）

### 3.1 控制器

```java
@PostMapping("/voiceover")
public Result<VideoTaskResultVo> addVoiceover(@RequestBody VideoGenerateVo vo) {
    if (vo.getTaskId() == null || vo.getTaskId().isBlank()) {
        return Result.error("taskId不能为空");
    }
    if (vo.getPrompt() == null || vo.getPrompt().isBlank()) {
        return Result.error("prompt不能为空");
    }
    VideoTaskResultVo result = videoGenerationService.addVoiceover(vo.getTaskId(), vo.getPrompt());
    if ("FAIL".equals(result.getStatus())) {
        return Result.error(result.getMessage());
    }
    return Result.OK(result);
}
```

**校验**：taskId 与 prompt 非空。Service 失败 → `FAIL` 时把错误抛给前端。

### 3.2 Service `addVoiceover`（`VideoGenerationServiceImpl`）

完整服务实现未全部读到，骨架流程：

```
addVoiceover(taskId, prompt)
    ├─ 1. 查 taskId 对应的视频记录 → videoUrl
    ├─ 2. LLM 生成旁白文案
    │     aiChatHandler.completionsByDefaultModel(
    │         messages=[SystemMessage("你是视频配音师"), UserMessage(prompt)],
    │         params={temperature=0.7})
    │     → 返回文案字符串（控制语速和分镜节奏）
    ├─ 3. 调 TTS 服务合成语音
    │     voiceService.textToSpeech(VoiceGenerateVo(content=text))
    │     → 异步：taskId 立即返回
    │       或者同步：audioUrl 直接返回
    ├─ 4. ★ FFmpeg 合并：原视频 + 新音频
    │     ffmpeg -i video.mp4 -i voice.mp3 -c:v copy -c:a aac -map 0:v -map 1:a output.mp4
    │     （纯命令行调用，常见做法）
    ├─ 5. 持久化记录（Redis 或 DB）
    └─ 6. 返回 VideoTaskResultVo { videoUrl, status="SUCCESS" }
```

**配音管线的关键设计**：
- 步骤 2 是创意生成（LLM）
- 步骤 3 是音频合成（TTS 模型，可同步或异步）
- 步骤 4 是无音频帧替换技术细节
- 步骤 6 失败 → `status="FAIL"`，前端看到错误信息

### 3.3 其他接口骨架

#### `submitTask` —— 提交视频生成

```java
@PostMapping("/submit")
public Result<VideoTaskResultVo> submitTask(@RequestBody VideoGenerateVo vo) {
    VideoTaskResultVo result = videoGenerationService.submitTask(vo);
    if ("FAIL".equals(result.getStatus())) return Result.error(result.getMessage());
    return Result.OK(result);
}
```

骨架流程：
- 异步提交到第三方视频生成 API（阿里云/CogVideoX/Runway 等）
- 立刻返回 `taskId`，前端轮询 `query/{taskId}`

#### `queryTask` —— 轮询

```java
@GetMapping("/query/{taskId}")
public Result<VideoTaskResultVo> queryTask(@PathVariable String taskId) {
    VideoTaskResultVo result = videoGenerationService.queryTask(taskId);
    return Result.OK(result);
}
```

#### `prompts` —— 预设提示词

```java
@GetMapping("/prompts")
public Result<Map<String, List<String>>> getPresetPrompts() {
    return Result.OK(videoGenerationService.getPresetPrompts());
}
```

返回 `Map<String, List<String>>`：分类 → 提示词列表（如 `"人物动作" → ["人物在海边奔跑", "人物在街道行走"]`）。

#### `listByUser` —— 用户的视频记录

```java
@GetMapping("/listByUser")
public Result<List<JSONObject>> getVideoRecords(@RequestParam String userId) {
    List<JSONObject> records = videoGenerationService.getVideoRecords(userId);
    return Result.OK(records);
}
```

#### `deleteVideoRecord`

```java
@DeleteMapping("/deleteVideoRecord")
public Result<String> deleteVideoRecord(@RequestParam String userId, @RequestParam String recordId) {
    boolean deleted = videoGenerationService.deleteVideoRecord(userId, recordId);
    return deleted ? Result.OK("删除成功") : Result.error("记录不存在");
}
```

---

## 四、`IVideoGenerationService` 接口

```java
public interface IVideoGenerationService {
    VideoTaskResultVo submitTask(VideoGenerateVo vo);
    VideoTaskResultVo queryTask(String taskId);
    VideoTaskResultVo addVoiceover(String taskId, String prompt);
    Map<String, List<String>> getPresetPrompts();
    List<JSONObject> getVideoRecords(String userId);
    boolean deleteVideoRecord(String userId, String recordId);
}
```

---

## 五、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `video/controller/VideoGenerationController.java` | 91 | 6 个端点 |
| `video/service/IVideoGenerationService.java` | 48 | 接口 |
| `video/service/impl/VideoGenerationServiceImpl.java` | — | 实现（包含配音管线） |
| `video/vo/VideoGenerateVo.java` | — | 入参 |
| `video/vo/VideoTaskResultVo.java` | — | 出参 |

---

## 六、下一章

[09-controller-voice.md](09-controller-voice.md) — AI 文生语音。
