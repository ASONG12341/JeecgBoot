# 07 · `/airag/ocr/*` 控制器 · 完整解读

> 该模块最简单：所有数据存 Redis 而非数据库，4 个端点构成完整 CRUD。

---

## 一、入口（`AiOcrController.java`，95 行）

| 方法 | 路径 | 来源 |
|------|------|------|
| `GET` | `/airag/ocr/list` | `list` |
| `POST` | `/airag/ocr/add` | `add` |
| `PUT` | `/airag/ocr/edit` | `updateById` |
| `DELETE` | `/airag/ocr/deleteById` | `deleteById` |

无 Shiro 鉴权、无 tenant 控制。

---

## 二、Redis 存储模式

**Key**：`airag:ocr`
**Value**：JSON 序列化后的 `List<AiOcr>`
**TTL**：永不过期

注入：

```java
@Autowired
private RedisUtil redisUtil;

private static final String AI_OCR_REDIS_KEY = "airag:ocr";
```

---

## 三、四个端点逐行解读

### 3.1 `list` —— 读全部列表

```java
@GetMapping("/list")
public Result<?> list(){
    Object aiOcr = redisUtil.get(AI_OCR_REDIS_KEY);   // ★ Redis 读
    IPage<AiOcr> page = new Page<>(1,10);
    if(null != aiOcr){
        List<AiOcr> aiOcrList = JSONObject.parseArray(aiOcr.toString(), AiOcr.class);   // fastjson 解析
        page.setRecords(aiOcrList);
        page.setTotal(aiOcrList.size());
        page.setPages(aiOcrList.size());
    }
    return Result.OK(page);
}
```

**逐行解读**：
- L29 `redisUtil.get` 读 String 类型的 Redis 值
- L32-37 若非空 → 解析为 List，包装成 MyBatis-Plus 的 `IPage` 格式返回（固定 10 条，1 页）

### 3.2 `add` —— 添加模型

```java
@PostMapping("/add")
public Result<String> add(@RequestBody AiOcr aiOcr){
    Object aiOcrList = redisUtil.get(AI_OCR_REDIS_KEY);
    aiOcr.setId(UUID.randomUUID().toString().replace("-",""));   // ★ UUID 不带 -
    if(null == aiOcrList){
        // 第一次添加
        List<AiOcr> list = new ArrayList<>();
        list.add(aiOcr);
        redisUtil.set(AI_OCR_REDIS_KEY, JSONObject.toJSONString(list));
    }else{
        // 追加
        List<AiOcr> aiOcrs = JSONObject.parseArray(aiOcrList.toString(), AiOcr.class);
        aiOcrs.add(aiOcr);
        redisUtil.set(AI_OCR_REDIS_KEY,JSONObject.toJSONString(aiOcrs));
    }
    return Result.OK("添加成功");
}
```

**逐行解读**：
- L43 `UUID.randomUUID().toString().replace("-","")` —— 后端生成 ID（前端不传）
- L44-50 第一次添加：直接 `set` 一个 List 单元素；后续添加：GET → parse → add → SET

### 3.3 `edit` —— 更新（按 ID 复制属性）

```java
@PutMapping("/edit")
public Result<String> updateById(@RequestBody AiOcr aiOcr){
    Object aiOcrList = redisUtil.get(AI_OCR_REDIS_KEY);
    if(null != aiOcrList){
        List<AiOcr> aiOcrs = JSONObject.parseArray(aiOcrList.toString(), AiOcr.class);
        aiOcrs.forEach(item->{
            if(item.getId().equals(aiOcr.getId())){
                BeanUtils.copyProperties(aiOcr,item);   // ★ Spring BeanUtils 浅拷贝
            }
        });
        redisUtil.set(AI_OCR_REDIS_KEY,JSONObject.toJSONString(aiOcrs));   // 整 List 回写
    }else{
        return Result.OK("编辑失败，未找到该数据");
    }
    return Result.OK("编辑成功");
}
```

**关键点**：`BeanUtils.copyProperties(aiOcr, item)` —— 把入参的字段值拷到 List 中匹配 ID 的对象。**是浅拷贝**，只覆盖 source 上非 null 的字段。

### 3.4 `deleteById` —— 按 ID 删除

```java
@DeleteMapping("/deleteById")
public Result<String> deleteById(@RequestBody AiOcr aiOcr){
    Object aiOcrObj = redisUtil.get(AI_OCR_REDIS_KEY);
    if(null != aiOcrObj){
        List<AiOcr> aiOcrs = JSONObject.parseArray(aiOcrObj.toString(), AiOcr.class);
        List<AiOcr> aiOcrList = new ArrayList<>();
        for(AiOcr ocr: aiOcrs){
            if(!ocr.getId().equals(aiOcr.getId())){     // ★ 不是这个 ID 就保留
                aiOcrList.add(ocr);
            }
        }
        if(CollectionUtils.isNotEmpty(aiOcrList)){
            redisUtil.set(AI_OCR_REDIS_KEY,JSONObject.toJSONString(aiOcrList));
        }else{
            redisUtil.removeAll(AI_OCR_REDIS_KEY);    // 空了直接删 key
        }
    }else{
        return Result.OK("删除失败，未找到该数据");
    }
    return Result.OK("删除成功");
}
```

**逐行解读**：
- L74-79 filter 出要保留的，写回 Redis
- L80-83 删除完后空 List 直接 `removeAll` 清理 key（不存空 JSON）

---

## 四、实体 `AiOcr`

```java
@Data
public class AiOcr {
    private String id;
    private String title;
    private String prompt;
}
```

仅 3 字段，纯 Redis POJO，不存数据库，不带审计字段。

---

## 五、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `ocr/controller/AiOcrController.java` | 95 | 4 个 Redis 端点 |
| `ocr/entity/AiOcr.java` | 29 | Redis POJO |
| `common/util/RedisUtil.java` | — | base-core 工具类（封装 jedis/lettuce） |

---

## 六、下一章

[08-controller-video.md](08-controller-video.md) — AI 视频生成。
