//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Preview/Confirm API-----------
package org.jeecg.modules.airag.llm.gbstandard.controller;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.llm.consts.LLMConsts;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.gbstandard.config.GbStandardProperties;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.GbDocumentStructureParser;
import org.jeecg.modules.airag.llm.gbstandard.ingestion.GbIngestionPipeline;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeDocMapper;
import org.jeecg.common.util.oConvertUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GB 国标知识引擎 — 预览/确认 API
 * <p>
 * 提供国标文档结构解析预览、用户修正保存、确认入库三个端点。
 * 受 {@link GbStandardProperties#isEnabled()} Kill Switch 控制。
 * </p>
 *
 * @author song
 * @date 2026-07-14
 */
@Tag(name = "GB国标知识引擎-预览确认")
@RestController
@RequestMapping("/airag/gb-standard")
@Slf4j
public class GbStandardController {

    @Autowired
    private GbStandardProperties gbStandardProperties;

    @Autowired
    private GbStandardMapper gbStandardMapper;

    @Autowired
    private AiragKnowledgeDocMapper airagKnowledgeDocMapper;

    @Value(value = "${jeecg.path.upload:}")
    private String uploadpath;

    @Autowired
    private GbDocumentStructureParser structureParser;

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】注入入库管线，confirm 触发真实抽取/向量化-----------
    @Autowired
    private GbIngestionPipeline ingestionPipeline;
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】注入入库管线，confirm 触发真实抽取/向量化-----------

    //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】向导②解析正文 API（不写向量）-----------
    @Autowired
    private EmbeddingHandler embeddingHandler;

    /** 解析任务去重：同一 docId 并发 parse 时复用 */
    private static final ConcurrentHashMap<String, Boolean> PARSE_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ExecutorService PARSE_EXECUTOR = Executors.newFixedThreadPool(4);
    //update-end---author:song ---date:2026-07-18  for：【GB线性入库】向导②解析正文 API（不写向量）-----------

    // ==================== 线性向导：解析正文 / 状态 / Markdown ====================

    //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】parse/status/markdown 接口-----------
    /**
     * 步骤②：仅 MinerU/Tika 解析正文，回写 content，不向量化。
     * 异步执行；前端轮询 {@link #parseStatus(String)}。
     */
    @Operation(summary = "国标文档正文解析（不向量化）")
    @RequiresPermissions("airag:knowledge:edit")
    @PostMapping("/{docId}/parse")
    public Result<?> parseDocumentOnly(@PathVariable String docId,
                                       @RequestParam(name = "force", defaultValue = "false") boolean force) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用，请设置 jeecg.airag.gb-standard.enabled=true");
        }
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }
        // 已有正文且非强制：直接返回
        if (!force && oConvertUtils.isNotEmpty(resolveMarkdownContent(doc))) {
            if (oConvertUtils.isEmpty(doc.getParseStatus())
                    || LLMConsts.PARSE_STATUS_UPLOADED.equals(doc.getParseStatus())
                    || LLMConsts.PARSE_STATUS_PARSING.equals(doc.getParseStatus())) {
                // 仅正文就绪，结构未确认前用 UPLOADED/保持；有 content 时标记为可进入结构步的中间态
                // 这里不置 PARSED（PARSED 专指结构解析完成）
            }
            Map<String, Object> ok = new HashMap<>();
            ok.put("docId", docId);
            ok.put("parseStatus", doc.getParseStatus());
            ok.put("hasMarkdown", true);
            ok.put("message", "已有解析正文");
            return Result.OK(ok);
        }
        if (PARSE_IN_FLIGHT.putIfAbsent(docId, Boolean.TRUE) != null) {
            Map<String, Object> busy = new HashMap<>();
            busy.put("docId", docId);
            busy.put("parseStatus", LLMConsts.PARSE_STATUS_PARSING);
            busy.put("message", "解析进行中");
            return Result.OK(busy);
        }
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_PARSING);
        final String finalDocId = docId;
        CompletableFuture.runAsync(() -> {
            try {
                AiragKnowledgeDoc latest = airagKnowledgeDocMapper.selectById(finalDocId);
                if (latest == null) {
                    return;
                }
                String text = embeddingHandler.extractDocumentTextOnly(latest);
                if (oConvertUtils.isEmpty(text)) {
                    latest.setParseStatus(LLMConsts.PARSE_STATUS_UPLOADED);
                    // 失败原因写入 metadata
                    JSONObject meta = oConvertUtils.isEmpty(latest.getMetadata())
                            ? new JSONObject() : JSONObject.parseObject(latest.getMetadata());
                    if (meta == null) {
                        meta = new JSONObject();
                    }
                    meta.put("parseFailedReason", "解析结果为空");
                    meta.put("markdownReady", false);
                    latest.setMetadata(meta.toJSONString());
                    // 禁止把超长正文写入 content（TEXT 约 64KB）
                    latest.setContent(null);
                    airagKnowledgeDocMapper.updateById(latest);
                    return;
                }
                // 正文解析成功：全文只在磁盘 md（metadata.filePath），库表不存全文
                JSONObject meta = oConvertUtils.isEmpty(latest.getMetadata())
                        ? new JSONObject() : JSONObject.parseObject(latest.getMetadata());
                if (meta == null) {
                    meta = new JSONObject();
                }
                meta.put("markdownReady", true);
                meta.put("markdownLength", text.length());
                meta.remove("parseFailedReason");
                latest.setMetadata(meta.toJSONString());
                //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】content 列 TEXT 放不下国标全文，强制不写 content-----------
                latest.setContent(null);
                //update-end---author:song ---date:2026-07-18  for：【GB线性入库】content 列 TEXT 放不下国标全文，强制不写 content-----------
                // 不置 PARSED：PARSED 留给结构 preview
                latest.setParseStatus(LLMConsts.PARSE_STATUS_UPLOADED);
                airagKnowledgeDocMapper.updateById(latest);
                log.info("[GB线性入库] 正文解析完成, docId={}, contentLen={}, filePath={}",
                        finalDocId, text.length(), meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH));
            } catch (Exception e) {
                log.error("[GB线性入库] 正文解析失败, docId={}: {}", finalDocId, e.getMessage(), e);
                try {
                    AiragKnowledgeDoc latest = airagKnowledgeDocMapper.selectById(finalDocId);
                    if (latest != null) {
                        latest.setParseStatus(LLMConsts.PARSE_STATUS_UPLOADED);
                        JSONObject meta = oConvertUtils.isEmpty(latest.getMetadata())
                                ? new JSONObject() : JSONObject.parseObject(latest.getMetadata());
                        if (meta == null) {
                            meta = new JSONObject();
                        }
                        meta.put("parseFailedReason", e.getMessage());
                        meta.put("markdownReady", false);
                        latest.setMetadata(meta.toJSONString());
                        airagKnowledgeDocMapper.updateById(latest);
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            } finally {
                PARSE_IN_FLIGHT.remove(finalDocId);
            }
        }, PARSE_EXECUTOR);

        Map<String, Object> started = new HashMap<>();
        started.put("docId", docId);
        started.put("parseStatus", LLMConsts.PARSE_STATUS_PARSING);
        started.put("message", "已开始解析");
        return Result.OK(started);
    }

    /**
     * 轮询文档国标解析/入库状态（向导②④）
     */
    @Operation(summary = "查询国标文档解析状态")
    @RequiresPermissions("airag:knowledge:edit")
    @GetMapping("/{docId}/status")
    public Result<?> parseStatus(@PathVariable String docId) {
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("docId", docId);
        data.put("status", doc.getStatus());
        data.put("parseStatus", doc.getParseStatus());
        data.put("title", doc.getTitle());
        boolean hasMarkdown = oConvertUtils.isNotEmpty(resolveMarkdownContent(doc));
        data.put("hasMarkdown", hasMarkdown);
        data.put("parsing", PARSE_IN_FLIGHT.containsKey(docId)
                || LLMConsts.PARSE_STATUS_PARSING.equals(doc.getParseStatus()));
        data.put("indexing", LLMConsts.PARSE_STATUS_INDEXING.equals(doc.getParseStatus())
                || LLMConsts.PARSE_STATUS_CONFIRMED.equals(doc.getParseStatus()));
        if (oConvertUtils.isNotEmpty(doc.getMetadata())) {
            try {
                JSONObject meta = JSONObject.parseObject(doc.getMetadata());
                if (meta != null) {
                    data.put("markdownReady", meta.getBooleanValue("markdownReady") || hasMarkdown);
                    data.put("parseFailedReason", meta.getString("parseFailedReason"));
                    data.put("filePath", meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH));
                    // 原始 PDF 路径（解析后 filePath 可能已变为 md）
                    data.put("originalFilePath", meta.getString("originalFilePath"));
                    data.put("markdownLength", meta.get("markdownLength"));
                }
            } catch (Exception ignore) {
                // ignore
            }
        } else {
            data.put("markdownReady", hasMarkdown);
        }
        return Result.OK(data);
    }

    /**
     * 获取解析正文 Markdown（向导②展示）
     * <p>会把相对图片路径改写为 /sys/common/static/... 以便前端预览直接显示。</p>
     */
    @Operation(summary = "获取国标文档解析 Markdown")
    @RequiresPermissions("airag:knowledge:edit")
    @GetMapping("/{docId}/markdown")
    public Result<?> getMarkdown(@PathVariable String docId) {
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }
        String md = resolveMarkdownContent(doc);
        String sourcesPath = null;
        String filePath = null;
        if (oConvertUtils.isNotEmpty(doc.getMetadata())) {
            try {
                JSONObject meta = JSONObject.parseObject(doc.getMetadata());
                if (meta != null) {
                    sourcesPath = meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH);
                    filePath = meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
        // 相对图片 → 静态访问 URL，预览才能显示图
        if (oConvertUtils.isNotEmpty(md)) {
            md = rewriteMarkdownImagesForPreview(md, sourcesPath, filePath);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("docId", docId);
        data.put("markdown", md == null ? "" : md);
        data.put("hasMarkdown", oConvertUtils.isNotEmpty(md));
        data.put("parseStatus", doc.getParseStatus());
        data.put("sourcesPath", sourcesPath);
        data.put("filePath", filePath);
        return Result.OK(data);
    }

    /**
     * 将 md 中本地相对图片路径改写为可通过 /sys/common/static/ 访问的路径。
     * MinerU 典型写法：![x](images/abc.jpg)，文件在 sourcesPath/images/ 下。
     */
    private String rewriteMarkdownImagesForPreview(String markdown, String sourcesPath, String filePath) {
        if (oConvertUtils.isEmpty(markdown)) {
            return markdown;
        }
        String baseDir = sourcesPath;
        if (oConvertUtils.isEmpty(baseDir) && oConvertUtils.isNotEmpty(filePath)) {
            // 从 md 文件路径推目录
            int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (slash > 0) {
                baseDir = filePath.substring(0, slash + 1);
            }
        }
        if (oConvertUtils.isEmpty(baseDir)) {
            return markdown;
        }
        String base = baseDir.replace("\\", "/");
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        // 去掉开头 /
        while (base.startsWith("/")) {
            base = base.substring(1);
        }
        final String staticPrefix = "/sys/common/static/" + base;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
        java.util.regex.Matcher m = p.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String alt = m.group(1);
            String src = m.group(2).trim();
            // 去掉 title 部分: url "title"
            int sp = src.indexOf(' ');
            if (sp > 0) {
                src = src.substring(0, sp);
            }
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("/sys/common/static/")) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            // 相对路径
            String rel = src.replace("\\", "/");
            if (rel.startsWith("./")) {
                rel = rel.substring(2);
            }
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            String abs = staticPrefix + rel;
            abs = abs.replaceAll("(?<!https:)(?<!http:)//+", "/");
            String rep = "![" + alt + "](" + abs + ")";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 保存用户修正后的 Markdown 正文（写回磁盘 .md，不写 content 列）。
     * 用于向导②：公式/错字对照 PDF 复制后修正。
     */
    @Operation(summary = "保存国标解析 Markdown（人工修正）")
    @RequiresPermissions("airag:knowledge:edit")
    @PutMapping("/{docId}/markdown")
    public Result<?> saveMarkdown(@PathVariable String docId, @RequestBody Map<String, Object> body) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用");
        }
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }
        Object mdObj = body != null ? body.get("markdown") : null;
        if (mdObj == null) {
            return Result.error("markdown 不能为空");
        }
        String markdown = String.valueOf(mdObj);
        try {
            persistMarkdownToDisk(doc, markdown);
            // 人工改过后结构可能失效，回到可重新结构确认
            if (LLMConsts.PARSE_STATUS_PARSED.equals(doc.getParseStatus())
                    || LLMConsts.PARSE_STATUS_CONFIRMED.equals(doc.getParseStatus())) {
                doc.setParseStatus(LLMConsts.PARSE_STATUS_UPLOADED);
            }
            doc.setContent(null);
            airagKnowledgeDocMapper.updateById(doc);
            Map<String, Object> data = new HashMap<>();
            data.put("docId", docId);
            data.put("markdownLength", markdown.length());
            data.put("message", "已保存修正");
            return Result.OK(data);
        } catch (Exception e) {
            log.error("[GB线性入库] 保存 markdown 失败, docId={}: {}", docId, e.getMessage(), e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }

    /**
     * 将 markdown 写回 metadata.filePath 指向的 .md；若当前仍是 PDF 路径则新建 md 文件。
     */
    private void persistMarkdownToDisk(AiragKnowledgeDoc doc, String markdown) throws IOException {
        JSONObject meta = oConvertUtils.isEmpty(doc.getMetadata())
                ? new JSONObject() : JSONObject.parseObject(doc.getMetadata());
        if (meta == null) {
            meta = new JSONObject();
        }
        String filePath = meta.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
        File mdFile;
        if (oConvertUtils.isNotEmpty(filePath) && isTextLikePath(filePath)) {
            mdFile = new File(uploadpath + File.separator + filePath);
            if (!mdFile.exists()) {
                mdFile = new File(filePath);
            }
            if (!mdFile.getParentFile().exists() && !mdFile.getParentFile().mkdirs()) {
                throw new IOException("无法创建目录: " + mdFile.getParent());
            }
        } else {
            // 保留原 PDF 路径
            if (oConvertUtils.isNotEmpty(filePath) && !meta.containsKey("originalFilePath")) {
                meta.put("originalFilePath", filePath);
            }
            String baseName = oConvertUtils.isNotEmpty(doc.getTitle()) ? doc.getTitle() : "doc";
            baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (baseName.length() > 80) {
                baseName = baseName.substring(0, 80);
            }
            String relativeDir = "mineru" + File.separator + java.util.UUID.randomUUID()
                    + File.separator + baseName + File.separator + "auto" + File.separator;
            String relativeMd = relativeDir + baseName + ".md";
            mdFile = new File(uploadpath + File.separator + relativeMd);
            if (!mdFile.getParentFile().exists() && !mdFile.getParentFile().mkdirs()) {
                throw new IOException("无法创建目录: " + mdFile.getParent());
            }
            meta.put(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH, relativeMd);
            meta.put(LLMConsts.KNOWLEDGE_DOC_METADATA_SOURCES_PATH, relativeDir);
        }
        Files.writeString(mdFile.toPath(), markdown, StandardCharsets.UTF_8);
        meta.put("markdownReady", true);
        meta.put("markdownLength", markdown.length());
        meta.put("markdownEdited", true);
        meta.remove("parseFailedReason");
        doc.setMetadata(meta.toJSONString());
        log.info("[GB线性入库] markdown 已保存, docId={}, path={}, len={}",
                doc.getId(), mdFile.getAbsolutePath(), markdown.length());
    }
    //update-end---author:song ---date:2026-07-18  for：【GB线性入库】parse/status/markdown 接口-----------

    // ==================== Preview API ====================

    /**
     * 触发国标文档结构解析，返回预览数据
     * <p>
     * 流程: 查文档 → 获取 Markdown → 执行结构解析 → 创建/更新 gb_standard 记录 → 返回结构 DTO
     * </p>
     *
     * @param docId 知识库文档 ID
     * @return 解析后的文档结构（条款层级树 + 标准信息 + 置信度统计）
     */
    @Operation(summary = "预览国标解析结果")
    @RequiresPermissions("airag:knowledge:edit")
    @PostMapping("/preview")
    public Result<GbDocStructure> preview(@RequestParam String docId) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用，请设置 jeecg.airag.gb-standard.enabled=true");
        }

        // 1. 查文档
        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }

        // 2. 获取 Markdown 内容
        //    - 文本类型文档: content 字段直接可用
        //    - 文件类型文档: content 为 null（EmbeddingHandler.parseFile 不回写），需要从 MinerU 解析后的文件读取
        String markdown = resolveMarkdownContent(doc);
        if (markdown == null || markdown.isBlank()) {
            return Result.error("文档内容为空，请先上传并解析文档（MinerU 解析）");
        }

        // 3. 更新状态为 PARSING
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_PARSING);

        try {
            // 4. 执行结构解析
            GbDocStructure structure = structureParser.parse(markdown);

            // 5. 创建或更新 gb_standard 记录
            //    gb_standard 按 (standard_no, version) 唯一：同一标准重复上传（新 docId）时
            //    必须更新既有记录而非插入，否则撞 uk_standard_no_version 唯一键
            GbStandard existing = findGbStandardByDocId(docId);
            if (existing == null && oConvertUtils.isNotEmpty(structure.getStandardNo())) {
                existing = findGbStandardByNoAndVersion(structure.getStandardNo(), structure.getVersion());
                if (existing != null) {
                    log.warn("[GB解析] 标准 {}-{} 已有记录(id={})，当前文档 docId={} 关联到该记录并刷新内容",
                            structure.getStandardNo(), structure.getVersion(), existing.getId(), docId);
                    existing.setDocId(doc.getId());
                    existing.setKnowledgeId(doc.getKnowledgeId());
                }
            }
            if (existing != null) {
                updateGbStandardFromStructure(existing, structure, markdown, doc);
                gbStandardMapper.updateById(existing);
            } else {
                try {
                    GbStandard gbStandard = createGbStandardFromStructure(structure, markdown, doc);
                    gbStandardMapper.insert(gbStandard);
                } catch (DuplicateKeyException e) {
                    // 并发等极端情况下仍撞唯一键：按 (standard_no, version) 找既有记录更新
                    GbStandard dup = findGbStandardByNoAndVersion(structure.getStandardNo(), structure.getVersion());
                    if (dup == null) {
                        throw e;
                    }
                    log.warn("[GB解析] 唯一键冲突转为更新, standardId={}, docId={}", dup.getId(), docId);
                    dup.setDocId(doc.getId());
                    dup.setKnowledgeId(doc.getKnowledgeId());
                    updateGbStandardFromStructure(dup, structure, markdown, doc);
                    gbStandardMapper.updateById(dup);
                }
            }

            // 6. 更新文档状态为 PARSED
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_PARSED);

            log.info("[GB解析] 解析成功, docId={}, 标准号={}, 条款数={}, 置信度: 高{}/中{}/低{}",
                    docId, structure.getStandardNo(), structure.getTotalClauseCount(),
                    structure.getHighConfidenceCount(), structure.getMediumConfidenceCount(),
                    structure.getLowConfidenceCount());

            return Result.OK(structure);

        } catch (Exception e) {
            log.error("[GB解析] 结构解析失败, docId={}: {}", docId, e.getMessage(), e);
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_UPLOADED);

            // 清理残留的 gb_standard 记录，防止下次解析时命中脏数据
            GbStandard stale = findGbStandardByDocId(docId);
            if (stale != null) {
                gbStandardMapper.deleteById(stale.getId());
                log.info("[GB解析] 已清理残留 gb_standard 记录, id={}, docId={}", stale.getId(), docId);
            }

            return Result.error("结构解析失败: " + e.getMessage());
        }
    }

    // ==================== Structure Save API ====================

    /**
     * 保存用户修正后的文档结构
     *
     * @param docId     文档 ID
     * @param structure 用户修正后的结构
     * @return 保存结果
     */
    @Operation(summary = "保存用户修正的国标结构")
    @RequiresPermissions("airag:knowledge:edit")
    @PutMapping("/{docId}/structure")
    public Result<String> saveStructure(@PathVariable String docId,
                                         @RequestBody GbDocStructure structure) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用");
        }

        GbStandard existing = findGbStandardByDocId(docId);
        if (existing == null) {
            return Result.error("未找到对应的国标记录，请先执行预览（POST /airag/gb-standard/preview）");
        }

        // 仅更新非空字段，防止用户误清空
        if (oConvertUtils.isNotEmpty(structure.getStandardNo())) {
            existing.setStandardNo(structure.getStandardNo());
        }
        if (oConvertUtils.isNotEmpty(structure.getVersion())) {
            existing.setVersion(structure.getVersion());
        }
        if (structure.getFullName() != null) {
            existing.setFullName(structure.getFullName());
        }
        gbStandardMapper.updateById(existing);

        // Phase 2: 此处将保存修正后的条款树到 gb_clause 表
        // Phase 1 仅更新标准基本信息

        log.info("[GB解析] 结构已保存, docId={}, 标准号={}", docId, structure.getStandardNo());
        return Result.OK("结构已保存");
    }

    // ==================== Confirm API ====================

    /**
     * 确认解析结果，触发后续管线
     * <p>
     * 状态机: PARSED → CONFIRMED → INDEXING → 触发 {@code GbIngestionPipeline}
     * （推导 domain_schema → 批量抽取槽位/参数/引用 → 持久化条款 → 审计埋点）→ 成功置 COMPLETED。<br>
     * 任一阶段抛异常：记日志 + 回滚到 CONFIRMED（用户修正后可重试 confirm）。
     * </p>
     *
     * @param docId 文档 ID
     * @return 确认结果
     */
    @Operation(summary = "确认国标解析结果")
    @RequiresPermissions("airag:knowledge:edit")
    @PostMapping("/{docId}/confirm")
    public Result<String> confirm(@PathVariable String docId) {
        if (!gbStandardProperties.isEnabled()) {
            return Result.error("GB国标知识引擎未启用");
        }

        AiragKnowledgeDoc doc = airagKnowledgeDocMapper.selectById(docId);
        if (doc == null) {
            return Result.error("文档不存在: " + docId);
        }

        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】放宽 confirm 守卫：允许 PARSED 或 COMPLETED 状态重新确认（调参/改 prompt 后可直接重跑入库管线）-----------
        // 验证状态: PARSED（首次确认）或 COMPLETED（重新确认，重跑管线以应用新的抽取/嵌入逻辑）
        String currentStatus = doc.getParseStatus();
        if (!LLMConsts.PARSE_STATUS_PARSED.equals(currentStatus)
                && !LLMConsts.PARSE_STATUS_COMPLETED.equals(currentStatus)) {
            return Result.error("文档状态不正确，当前: " + currentStatus
                    + "。需要为 " + LLMConsts.PARSE_STATUS_PARSED + "（先执行预览）或 "
                    + LLMConsts.PARSE_STATUS_COMPLETED + "（重新确认）");
        }
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------

        // 更新为 CONFIRMED
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_CONFIRMED);

        // update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】confirm 接入入库管线（CONFIRMED→INDEXING→pipeline→COMPLETED）-----------
        // 进入 INDEXING
        updateParseStatus(doc, LLMConsts.PARSE_STATUS_INDEXING);
        try {
            // 重新解析结构树（confirm 时用户可能已 saveStructure 修正过；此处重解析保证 structure 与 markdown 一致）
            GbStandard gbStandard = gbStandardMapper.selectOne(
                    new LambdaQueryWrapper<GbStandard>().eq(GbStandard::getDocId, docId));
            if (gbStandard == null) {
                throw new IllegalStateException("gb_standard 记录不存在, docId=" + docId);
            }
            String markdown = resolveMarkdownContent(doc);
            GbDocStructure structure = structureParser.parse(markdown);

            // 触发入库管线（推导 domain_schema → 批量抽取槽位/参数/引用 → 持久化条款 → 审计埋点）
            // pipeline 内部异常被吞掉并以返回值表示成败；失败时此处同样回滚到 CONFIRMED
            boolean ok = ingestionPipeline.run(gbStandard, structure);
            if (!ok) {
                throw new IllegalStateException("入库管线执行失败（详见 gb_audit_log）");
            }

            // 成功 → COMPLETED
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_COMPLETED);
            log.info("[GB知识引擎] 文档入库完成, docId={}", docId);
            return Result.OK("确认成功，国标结构已入库");
        } catch (Exception e) {
            log.error("[GB知识引擎] 文档入库失败, docId={}: {}", docId, e.getMessage(), e);
            // 失败回滚到 CONFIRMED（用户可修正后重试 confirm）
            updateParseStatus(doc, LLMConsts.PARSE_STATUS_CONFIRMED);
            return Result.error("入库失败: " + e.getMessage());
        }
        // update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】confirm 接入入库管线-----------
    }

    // ==================== 私有方法 ====================

    /**
     * 解析文档的 Markdown 内容
     * <p>
     * 文件类型文档的 content 字段通常为空（EmbeddingHandler.parseFile 不回写 content），
     * 需要从 MinerU 解析后的 markdown 文件读取。
     * </p>
     * <p>
     * 注意：上传后 metadata.filePath 往往是原始 PDF。绝不能把 PDF 当 UTF-8 文本读，
     * 否则会抛 MalformedInputException（Input length = 3）。仅允许读文本类扩展名。
     * </p>
     *
     * @param doc 知识库文档
     * @return Markdown 文本，无法获取时返回 null
     */
    private String resolveMarkdownContent(AiragKnowledgeDoc doc) {
        // 1. 优先使用 content 字段（文本类型文档或已回写的文档）
        String content = doc.getContent();
        if (oConvertUtils.isNotEmpty(content)) {
            return content;
        }

        // 2. 文件类型文档：从 metadata 中获取 MinerU 解析后的文件路径
        if (LLMConsts.KNOWLEDGE_DOC_TYPE_FILE.equals(doc.getType())) {
            String metadataStr = doc.getMetadata();
            if (oConvertUtils.isEmpty(metadataStr)) {
                return null;
            }
            try {
                JSONObject metadataJson = JSONObject.parseObject(metadataStr);
                if (metadataJson == null) {
                    return null;
                }
                String filePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
                if (oConvertUtils.isEmpty(filePath)) {
                    return null;
                }
                //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】禁止把 PDF 当 UTF-8 文本读-----------
                // 仅读取文本类文件（MinerU 产出 .md）；原始 PDF/Office 跳过
                if (!isTextLikePath(filePath)) {
                    log.debug("[GB解析] filePath 非文本文件，跳过按正文读取: {}", filePath);
                    return null;
                }
                //update-end---author:song ---date:2026-07-18  for：【GB线性入库】禁止把 PDF 当 UTF-8 文本读-----------
                // 构建完整文件路径
                File mdFile = new File(uploadpath + File.separator + filePath);
                if (!mdFile.exists()) {
                    // 尝试直接路径（filePath 可能是绝对路径）
                    mdFile = new File(filePath);
                }
                if (mdFile.exists() && mdFile.isFile()) {
                    return Files.readString(mdFile.toPath(), StandardCharsets.UTF_8);
                }
                log.warn("[GB解析] MinerU 解析后的文件不存在: {}", mdFile.getAbsolutePath());
            } catch (java.nio.charset.MalformedInputException e) {
                // 防御：扩展名误判或文件实际为二进制
                log.warn("[GB解析] 文件不是合法 UTF-8 文本，跳过: {}", e.getMessage());
            } catch (IOException e) {
                log.error("[GB解析] 读取文档文件失败: {}", e.getMessage(), e);
            } catch (Exception e) {
                log.error("[GB解析] 解析 metadata JSON 失败: {}", e.getMessage(), e);
            }
        }
        return null;
    }

    //update-begin---author:song ---date:2026-07-18  for：【GB线性入库】文本路径判断-----------
    /**
     * 判断路径是否可能是可按 UTF-8 读取的文本（md/txt 等），排除 pdf/office 等二进制。
     */
    private boolean isTextLikePath(String filePath) {
        if (oConvertUtils.isEmpty(filePath)) {
            return false;
        }
        String lower = filePath.toLowerCase();
        // 去掉 query
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".txt")
                || lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".json")
                || lower.endsWith(".csv");
    }
    //update-end---author:song ---date:2026-07-18  for：【GB线性入库】文本路径判断-----------

    private GbStandard findGbStandardByDocId(String docId) {
        return gbStandardMapper.selectOne(
                new LambdaQueryWrapper<GbStandard>()
                        .eq(GbStandard::getDocId, docId)
                        .last("LIMIT 1"));
    }

    /**
     * 按唯一键 (standard_no, version) 查记录；version 为空时匹配 NULL。
     */
    private GbStandard findGbStandardByNoAndVersion(String standardNo, String version) {
        LambdaQueryWrapper<GbStandard> wrapper = new LambdaQueryWrapper<GbStandard>()
                .eq(GbStandard::getStandardNo, standardNo)
                .last("LIMIT 1");
        if (oConvertUtils.isNotEmpty(version)) {
            wrapper.eq(GbStandard::getVersion, version);
        } else {
            wrapper.isNull(GbStandard::getVersion);
        }
        return gbStandardMapper.selectOne(wrapper);
    }

    private void updateParseStatus(AiragKnowledgeDoc doc, String status) {
        doc.setParseStatus(status);
        airagKnowledgeDocMapper.updateById(doc);
    }

    private void updateGbStandardFromStructure(GbStandard existing, GbDocStructure structure,
                                                String markdown, AiragKnowledgeDoc doc) {
        existing.setStandardNo(structure.getStandardNo());
        existing.setVersion(structure.getVersion());
        existing.setFullName(structure.getFullName());
        existing.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
        existing.setMarkdownContent(markdown);
        existing.setSupersedes(toJsonString(structure.getSupersedes()));
        existing.setNormativeRefs(toJsonString(structure.getNormativeRefs()));
    }

    private GbStandard createGbStandardFromStructure(GbDocStructure structure,
                                                      String markdown, AiragKnowledgeDoc doc) {
        GbStandard gbStandard = new GbStandard();
        gbStandard.setStandardNo(structure.getStandardNo());
        gbStandard.setVersion(structure.getVersion());
        gbStandard.setFullName(structure.getFullName());
        gbStandard.setKnowledgeId(doc.getKnowledgeId());
        gbStandard.setDocId(doc.getId());
        gbStandard.setParseStatus(LLMConsts.PARSE_STATUS_PARSED);
        gbStandard.setMarkdownContent(markdown);
        gbStandard.setStatus("current");
        gbStandard.setSupersedes(toJsonString(structure.getSupersedes()));
        gbStandard.setNormativeRefs(toJsonString(structure.getNormativeRefs()));
        gbStandard.setCreatedAt(new Date());
        return gbStandard;
    }

    /**
     * 将 List 序列化为 JSON 字符串，用于存储到 MySQL JSON 列
     * null 或空列表返回 null（不存储空 JSON 数组，节省空间）
     */
    private String toJsonString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return JSONObject.toJSONString(list);
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 Preview/Confirm API-----------
