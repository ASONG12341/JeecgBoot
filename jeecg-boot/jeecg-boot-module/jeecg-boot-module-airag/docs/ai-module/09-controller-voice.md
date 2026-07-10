# 09 · `/airag/voice/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/voice/generateAsync`**（异步语音生成）为主线，从 HTTP 落到 `IVoiceService.generateAsync` 完整解读，包含同步 / 异步两版本的演进。

---

## 一、入口（`VoiceController.java`，91 行）

| 方法 | 路径 | 来源 |
|------|------|------|
| `POST` | `/airag/voice/generate` | `generate`（同步） |
| `POST` | `/airag/voice/generateAsync` | `generateAsync`（异步） |
| `GET` | `/airag/voice/queryTask/{taskId}` | `queryVoiceTask` |
| `GET` | `/airag/voice/listByUser` | `getVoiceRecords` |
| `DELETE` | `/airag/voice/deleteVoiceRecord` | `deleteVoiceRecord` |

---

## 二、实体

### `VoiceGenerateVo`

```java
@Data
public class VoiceGenerateVo {
    private String userId;
    private String content;     // 待合成文本
    private String voice;       // 音色 ID（如 "zh-CN-XiaoxiaoNeural"）
    private String modelName;   // 模型名（如 "tts-1", "cosyvoice", "azure-tts"）
    private Double speed;       // 倍速 0.25 ~ 4.0
    private String format;      // "mp3" / "wav" / "pcm"
    private String taskId;      // 异步时由 Service 生成
}
```

### `VoiceResultVo`

```java
@Data
public class VoiceResultVo {
    private String taskId;       // 异步：任务 ID；同步：可能为空
    private String status;       // "pending" / "success" / "failed"
    private String audioUrl;     // 同步：直接是音频 URL；异步：成功后才有
    private String message;
    private Long duration;       // 音频时长（毫秒）
    private String modelName;
}
```

---

## 三、`POST /airag/voice/generate`（同步版）

### 3.1 控制器

```java
@PostMapping("/generate")
public Result<VoiceResultVo> generate(@RequestBody VoiceGenerateVo vo) {
    // ★ 参数校验
    if (vo.getContent() == null || vo.getContent().isBlank()) {
        return Result.error("合成文本不能为空");
    }
    if (vo.getSpeed() != null && (vo.getSpeed() < 0.25 || vo.getSpeed() > 4.0)) {
        return Result.error("倍速范围须在0.25~4.0之间");
    }
    
    try {
        VoiceResultVo result = voiceService.textToSpeech(vo);
        return Result.OK(result);
    } catch (Exception e) {
        log.error("文生语音失败", e);
        return Result.error("语音生成失败: " + e.getMessage());
    }
}
```

### 3.2 Service `textToSpeech`

骨架（具体实现需要读 `VoiceServiceImpl`）：

```
textToSpeech(vo)
    ├─ 1. 校验 content 非空
    ├─ 2. 通过 VoiceApiHelper 选择 TTS 提供商
    │     ├─ Azure TTS（credential.azure）
    │     ├─ 阿里 CosyVoice
    │     ├─ OpenAI TTS-1
    │     └─ ...
    ├─ 3. 调用对应 API
    │     POST {provider endpoint}/tts
    │     Body: {text, voice, speed, format}
    │     → 拿到 audio bytes / 远程 URL
    ├─ 4. 上传到 MinIO/OSS 或返回 provider URL
    ├─ 5. 持久化到 airag_voice_records（可能表也可能 Redis）
    └─ 6. 返回 VoiceResultVo { audioUrl, status="success" }
```

---

## 四、`POST /airag/voice/generateAsync`（异步版，QQYUN-14568）

### 4.1 控制器

```java
@PostMapping("/generateAsync")
public Result<String> generateAsync(@RequestBody VoiceGenerateVo vo) {
    if (vo.getContent() == null || vo.getContent().isBlank()) {
        return Result.error("合成文本不能为空");
    }
    if (vo.getSpeed() != null && (vo.getSpeed() < 0.25 || vo.getSpeed() > 4.0)) {
        return Result.error("倍速范围须在0.25~4.0之间");
    }
    String taskId = voiceService.generateAsync(vo);
    return Result.OK(taskId);
}
```

### 4.2 设计动机（QQYUN-14568）

**异步化的原因**：TTS 长文本（如 5 分钟播客脚本）合成需 30s~3min，HTTP 同步等待会触发：
- 前端超时
- 浏览器 SSE/EventSource 断开
- Nginx 反向代理 504
- 用户误以为页面卡死

**改异步后**：
- 立即返回 `taskId`（几 ms）
- 前端轮询 `queryTask/{taskId}`

### 4.3 Service 骨架

```java
public String generateAsync(VoiceGenerateVo vo) {
    String taskId = UUIDGenerator.generate();
    // Redis 存任务初始状态
    redisUtil.set(voice:task:{taskId}, new VoiceTaskVo(taskId, "pending"), 1h);
    
    // ★ 异步线程跑
    CompletableFuture.runAsync(() -> {
        try {
            VoiceResultVo result = textToSpeech(vo);
            // 写 Redis 状态为 completed
            redisUtil.set(voice:task:{taskId}, result, 1h);
        } catch (Exception e) {
            redisUtil.set(voice:task:{taskId}, failedResult, 1h);
        }
    }, voiceThreadPool);
    
    return taskId;
}

public Result<?> getVoiceTaskResult(String taskId) {
    VoiceResultVo result = (VoiceResultVo) redisUtil.get(voice:task:{taskId});
    return Result.OK(result);
}
```

### 4.4 Redis Key 模式

**Key**：`airag:voice:task:{taskId}`
**Value**：`VoiceResultVo` 序列化
**TTL**：1 小时

---

## 五、`GET /airag/voice/listByUser`

```java
@GetMapping("/listByUser")
public Result<List<JSONObject>> getVoiceRecords(@RequestParam String userId) {
    List<JSONObject> records = voiceService.getVoiceRecords(userId);
    return Result.OK(records);
}
```

返回历史语音记录列表。

## 六、`DELETE /airag/voice/deleteVoiceRecord`

```java
@DeleteMapping("/deleteVoiceRecord")
public Result<String> deleteVoiceRecord(@RequestParam String userId, @RequestParam String recordId) {
    boolean deleted = voiceService.deleteVoiceRecord(userId, recordId);
    return deleted ? Result.OK("删除成功") : Result.error("记录不存在");
}
```

---

## 七、`IVoiceService` 接口

```java
public interface IVoiceService {
    VoiceResultVo textToSpeech(VoiceGenerateVo vo);    // 同步
    String generateAsync(VoiceGenerateVo vo);           // 异步返 taskId
    Result<?> getVoiceTaskResult(String taskId);        // 异步查
    List<JSONObject> getVoiceRecords(String userId);
    boolean deleteVoiceRecord(String userId, String recordId);
}
```

注意：`getVoiceTaskResult` 返回 `Result<?>` 而 `getVideoRecords` 返回 `List<JSONObject>`，接口风格略不统一但都能用。

---

## 八、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `voice/controller/VoiceController.java` | 91 | 5 个端点 |
| `voice/service/IVoiceService.java` | 50 | 接口 |
| `voice/service/impl/VoiceServiceImpl.java` | — | 实现 |
| `voice/util/VoiceApiHelper.java` | — | TTS 提供商适配层 |
| `voice/vo/VoiceGenerateVo.java` | — | 入参 |
| `voice/vo/VoiceResultVo.java` | — | 出参 |

---

## 九、下一章

[10-controller-word.md](10-controller-word.md) — Word 模板生成与解析。
