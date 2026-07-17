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
import org.jeecg.modules.airag.llm.mapper.AiragKnowledgeDocMapper;
import org.jeecg.common.util.oConvertUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;

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
            GbStandard existing = findGbStandardByDocId(docId);
            if (existing != null) {
                updateGbStandardFromStructure(existing, structure, markdown, doc);
                gbStandardMapper.updateById(existing);
            } else {
                GbStandard gbStandard = createGbStandardFromStructure(structure, markdown, doc);
                gbStandardMapper.insert(gbStandard);
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
                String filePath = metadataJson.getString(LLMConsts.KNOWLEDGE_DOC_METADATA_FILEPATH);
                if (oConvertUtils.isEmpty(filePath)) {
                    return null;
                }
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
            } catch (IOException e) {
                log.error("[GB解析] 读取文档文件失败: {}", e.getMessage(), e);
            } catch (Exception e) {
                log.error("[GB解析] 解析 metadata JSON 失败: {}", e.getMessage(), e);
            }
        }
        return null;
    }

    private GbStandard findGbStandardByDocId(String docId) {
        return gbStandardMapper.selectOne(
                new LambdaQueryWrapper<GbStandard>()
                        .eq(GbStandard::getDocId, docId)
                        .last("LIMIT 1"));
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
