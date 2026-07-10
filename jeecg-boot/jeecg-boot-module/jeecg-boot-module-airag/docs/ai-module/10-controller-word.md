# 10 · `/airag/word/*` 控制器 · 完整解读

> 本文档以 **`POST /airag/word/generate/word`**（用模板 + 数据生成 Word）为主线，从 HTTP 落到 `AigcWordTemplateServiceImpl.generateWordFromTpl` → `WordTplUtils` → word 文件下载完整解读。

---

## 一、入口（`AigcWordTemplateController.java`，245 行）

| 方法 | 路径 | 来源 |
|------|------|------|
| `GET` | `/airag/word/list` | `queryPageList` |
| `POST` | `/airag/word/add` | `add` |
| `PUT,POST` | `/airag/word/edit` | `edit` |
| `DELETE` | `/airag/word/delete` | `delete` |
| `DELETE` | `/airag/word/deleteBatch` | `deleteBatch` |
| `GET` | `/airag/word/queryById` | `queryById` |
| `GET` | `/airag/word/download` | `downloadTemplate` |
| `POST` | `/airag/word/parse/file` | `parseWOrdFile` |
| `POST` | `/airag/word/generate/word` | `generateWord` |

继承 `JeecgController<AigcWordTemplate, IAigcWordTemplateService>`。

---

## 二、实体 `AigcWordTemplate`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` / 标准审计字段 | | |
| `name` | String | 模板显示名 |
| **`code`** | String | 模板编码，全局唯一（`add` 时校验唯一性） |
| `header` | String | 页眉 |
| `footer` | String | 页脚 |
| **`main`** | String | **主体内容 JSON**（含 WordTableCellDTO / WordImageDTO 等） |
| `margins` | String | 页边距 JSON（如 `{"top":1, "right":1, "bottom":1, "left":1}`） |
| `width` | Integer | 页面宽度（cm） |
| `height` | Integer | 页面高度（cm） |
| `paperDirection` | String | `vertical` / `horizontal` |
| `watermark` | String | 水印文字 |

**主表 `aigc_word_template`** 与众多 DTO 配合：

| DTO | 用途 |
|-----|------|
| `WordTextDTO` | 文本块 |
| `WordImageDTO` | 图片 |
| `WordTableDTO` / `WordTableRowDTO` / `WordTableCellDTO` | 表格 |
| `MergeColDTO` | 合并单元格 |
| `WordTplGenDTO` | 生成 word 的入参（templateId/code + 数据） |

---

## 三、`POST /airag/word/add` —— 创建模板（含 code 唯一校验）

```java
@PostMapping(value = "/add")
public Result<String> add(@RequestBody AigcWordTemplate eoaWordTemplate) {
    AssertUtils.assertNotEmpty("参数异常", eoaWordTemplate);
    AssertUtils.assertNotEmpty("模版名称不能为空", eoaWordTemplate.getName());
    
    // ★ code 唯一性校验
    boolean isCodeExists = eoaWordTemplateService.exists(
        Wrappers.lambdaQuery(AigcWordTemplate.class)
            .eq(AigcWordTemplate::getCode, eoaWordTemplate.getCode()));
    AssertUtils.assertFalse("模版编码已存在", isCodeExists);
    
    eoaWordTemplateService.save(eoaWordTemplate);
    return Result.OK("添加成功！");
}
```

## 四、`POST /airag/word/parse/file` —— 上传 Word 解析为模板

```java
@PostMapping(value = "/parse/file")
public Result<?> parseWOrdFile(@RequestParam("file") MultipartFile file) {
    try {
        InputStream inputStream = file.getInputStream();
        AigcWordTemplate eoaWordTemplate = wordTplUtils.parseWordFile(inputStream);
        log.info("解析的模版信息: {}", eoaWordTemplate);
        return Result.OK("解析成功", eoaWordTemplate);
    } catch (Exception e) {
        throw new RuntimeException("解析word模版失败: " + e.getMessage(), e);
    }
}
```

`wordTplUtils.parseWordFile(inputStream)` 用 Apache POI 解析 Word 文档，提取文本/图片/表格，构造 `AigcWordTemplate.main` JSON。

## 五、`GET /airag/word/download` —— 下载模板

```java
@GetMapping(value = "/download")
public void downloadTemplate(@RequestParam(name = "id", required = true) String id, HttpServletResponse response) {
    AssertUtils.assertNotEmpty("请先选择模版", id);
    AigcWordTemplate template = eoaWordTemplateService.getById(id);
    try (ByteArrayOutputStream wordTemplateOut = new ByteArrayOutputStream();
         BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream())) {
        wordTplUtils.generateWordTemplate(template, wordTemplateOut);    // ★ 生成 word 文件流
        String fileName = template.getName();
        String encodedFileName = URLEncoder.encode(fileName, "UTF-8");
        // ★ 设置响应头：浏览器下载 .docx
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.addHeader("Content-Disposition", "attachment;filename=" + encodedFileName + ".docx");
        response.addHeader("filename", encodedFileName + ".docx");
        byte[] bytes = wordTemplateOut.toByteArray();
        response.setHeader("Content-Length", String.valueOf(bytes.length));
        bos.write(bytes);
    } catch (Exception e) {
        log.error(e.getMessage(), e);
        throw new JeecgBootException("下载word模版失败: " + e.getMessage(), e);
    }
}
```

`wordTplUtils.generateWordTemplate(template, outputStream)` 把模板 JSON 反向构造为 Word `.docx`，写到 `outputStream`。响应头告诉浏览器下载。

---

## 六、`POST /airag/word/generate/word` —— 用模板生成文档（主线）

### 6.1 入参 `WordTplGenDTO`

```java
@Data
public class WordTplGenDTO {
    private String templateId;   // 二选一
    private String templateCode; // 二选一
    private Object data;         // 用于填充的 JSON 数据
}
```

### 6.2 控制器

```java
@PostMapping(value = "/generate/word")
public void generateWord(@RequestBody WordTplGenDTO wordTplGenDTO, HttpServletResponse response) {
    AssertUtils.assertNotEmpty("参数异常", wordTplGenDTO);
    
    AigcWordTemplate template;
    if (oConvertUtils.isNotEmpty(wordTplGenDTO.getTemplateId())) {
        // 方式 1：按 ID 找模板
        template = eoaWordTemplateService.getById(wordTplGenDTO.getTemplateId());
    } else {
        // 方式 2：按 code 找模板
        AssertUtils.assertNotEmpty("请先选择模版", wordTplGenDTO.getTemplateCode());
        template = eoaWordTemplateService.getOne(
            Wrappers.lambdaQuery(AigcWordTemplate.class)
                .eq(AigcWordTemplate::getCode, wordTplGenDTO.getTemplateCode()));
    }
    AssertUtils.assertNotEmpty("未找到对应的模版", template);
    
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
         BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream())) {
        // ★ 调用 Service 把模板 + 数据 生成 word
        eoaWordTemplateService.generateWordFromTpl(wordTplGenDTO, outputStream);
        String fileName = template.getName();
        String encodedFileName = URLEncoder.encode(fileName, "UTF-8");
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.addHeader("Content-Disposition", "attachment;filename=" + encodedFileName + ".docx");
        response.addHeader("filename", encodedFileName + ".docx");
        byte[] bytes = outputStream.toByteArray();
        response.setHeader("Content-Length", String.valueOf(bytes.length));
        bos.write(bytes);
    } catch (Exception e) {
        log.error(e.getMessage(), e);
        throw new JeecgBootException("生成word文档失败: " + e.getMessage(), e);
    }
}
```

### 6.3 完整调用链

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 前端选择模板 + 数据 → 调生成接口                                       │
│    POST /airag/word/generate/word                                         │
│    Body: { templateId | templateCode, data: {...} }                      │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. AigcWordTemplateController.generateWord                                │
│    ├─ 按 templateId 或 templateCode 查 AigcWordTemplate                   │
│    ├─ try (ByteArrayOutputStream outputStream = new ...)                  │
│    └─ eoaWordTemplateService.generateWordFromTpl(WordTplGenDTO, outputStream) │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. AigcWordTemplateServiceImpl.generateWordFromTpl(...)                  │
│    ├─ parse template.main (JSON) → List<WordBlock>                      │
│    ├─ 对每个 WordBlock：                                                 │
│    │     ├─ WordTextDTO   → 用 data.fillText 替换 {{变量}}                 │
│    │     ├─ WordImageDTO  → 用 FreeMarker 模板或 POI 插入                 │
│    │     ├─ WordTableDTO  → POI 构造 XWPFTable                            │
│    │     └─ MergeColDTO  → 合并单元格                                    │
│    ├─ 构造 XWPFDocument（页眉/页脚/水印/页边距都设）                     │
│    ├─ WordTplUtils.generateWordFromMain(...)                              │
│    └─ outputStream 已经写入了完整 .docx 文件                              │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. 回到控制器：response 设置下载头                                         │
│    - Content-Type: application/vnd.openxmlformats-...wordprocessingml... │
│    - Content-Disposition: attachment;filename=xxx.docx                   │
│    - Content-Length: bytes.length                                         │
│    - bos.write(bytes)                                                    │
└────────────────────────┬────────────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. 浏览器接收到下载                                                       │
│    Content-Disposition 触发文件名保存                                    │
└─────────────────────────────────────────────────────────────────────────┘
```

## 七、CRUD 其他端点

与之前 controller 的 CRUD 类似，`edit`、`delete`、`deleteBatch`、`queryById`、`queryPageList`、`exportXls`、`importExcel` 都继承自 `JeecgController`，无特殊逻辑。

注意 `edit`：

```java
@RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
public Result<String> edit(@RequestBody AigcWordTemplate eoaWordTemplate) {
    AssertUtils.assertNotEmpty("参数异常", eoaWordTemplate);
    AssertUtils.assertNotEmpty("模版名称不能为空", eoaWordTemplate.getName());
    // ★ 避免编辑时修改编码
    eoaWordTemplate.setCode(null);
    eoaWordTemplateService.updateById(eoaWordTemplate);
    return Result.OK("编辑成功!");
}
```

`setCode(null)` —— 编辑时把 code 置空防止前端传值导致唯一约束冲突。

## 八、`WordTplUtils.generateWordTemplate` 与 `parseWordFile`

`wordtpl/utils/WordTplUtils.java` 是核心工具类，封装 POI 操作：

| 方法 | 行为 |
|------|------|
| `generateWordTemplate(template, out)` | 把 `AigcWordTemplate` 序列化为 .docx，写到 out |
| `generateWordFromMain(main, out, data)` | 用 data 填充 main JSON 内的模板占位符 |
| `parseWordFile(inputStream)` | 解析 Word → `AigcWordTemplate` |

`WordUtil.java` 提供更低层的 POI 封装（`AddCustomHeadingStyle` 等）。

## 九、对应源码文件

| 文件 | 行数 | 作用 |
|------|------|------|
| `wordtpl/controller/AigcWordTemplateController.java` | 245 | 9 个端点 |
| `wordtpl/service/IAigcWordTemplateService.java` | — | 接口 |
| `wordtpl/service/impl/AigcWordTemplateServiceImpl.java` | — | 实现 |
| `wordtpl/entity/AigcWordTemplate.java` | 127 | 实体 |
| `wordtpl/dto/WordTplGenDTO.java` | — | 生成入参 |
| `wordtpl/dto/WordTextDTO.java` | — | 文本块 |
| `wordtpl/dto/WordImageDTO.java` | — | 图片 |
| `wordtpl/dto/WordTableDTO.java` / `WordTableRowDTO.java` / `WordTableCellDTO.java` | — | 表格 |
| `wordtpl/dto/MergeColDTO.java` | — | 合并单元格 |
| `wordtpl/utils/WordTplUtils.java` | — | 模板解析/生成 |
| `wordtpl/utils/WordUtil.java` | — | POI 工具 |
| `wordtpl/consts/WordTitleEnum.java` | — | 标题级别枚举 |

## 十、下一章

[11-controller-baseapi.md](11-controller-baseapi.md) — 跨模块 BaseApi 接口。
