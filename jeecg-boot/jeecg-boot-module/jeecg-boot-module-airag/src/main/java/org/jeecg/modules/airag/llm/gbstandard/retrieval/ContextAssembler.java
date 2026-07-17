//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】引用上下文增强器（融合后后处理：注入引用标注 + 追加引用条款上下文，不参与打分）-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbReferenceRepository;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用上下文增强器。
 * <p>
 * 在 RRF 融合结果之上做后处理（编排器在 weightedFuse 之后调用）：
 * 对每个带 standardId + clausePath 的结果，查 gb_reference 找出该条款引用的其他标准/条款，
 * 1. 格式化引用标注 [标准号 版本] §条款号 (页码)（GbReference 无 version/page 字段，这两段省略）；
 * 2. 将被引用条款原文作为 [引用上下文] 追加到 result.text 末尾，供下游 LLM 生成时引用；
 * 3. 同步写入 result.references 结构化字段，便于前端展示。
 * <p>
 * 关键约束：不修改 score（引用上下文只做"增量信息"，不参与相关性排序，避免污染融合排序）。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component
public class ContextAssembler {

    @Autowired
    private GbReferenceRepository referenceRepository;

    /**
     * 对融合后的结果列表做引用上下文增强。
     *
     * @param fused RRF/weightedFuse 融合后的结果（原序，分数已定）
     * @return 增强后的结果列表（同序，仅 text/references 被扩充，score 不变）
     */
    public List<RetrievalResult> enhance(List<RetrievalResult> fused) {
        if (fused == null || fused.isEmpty()) {
            return fused;
        }

        List<RetrievalResult> enhanced = new ArrayList<>(fused.size());
        int totalCitations = 0;

        for (RetrievalResult result : fused) {
            enhanced.add(enhanceOne(result));
            if (result.getReferences() != null && !result.getReferences().isEmpty()) {
                totalCitations += result.getReferences().size();
            }
        }

        log.info("[ContextAssembler] 引用上下文增强完成, 结果数={}, 注入引用条数={}", enhanced.size(), totalCitations);
        return enhanced;
    }

    private RetrievalResult enhanceOne(RetrievalResult result) {
        String standardId = result.getStandardId();
        String clausePath = result.getClausePath();

        // 无定位信息无法查引用，原样返回
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(clausePath)) {
            return result;
        }
        if (referenceRepository == null) {
            return result;
        }

        List<GbReference> references;
        try {
            references = referenceRepository.findBySourceClause(standardId, clausePath);
        } catch (Exception e) {
            log.warn("[ContextAssembler] 查询引用失败 standardId={}, clausePath={}: {}",
                    standardId, clausePath, e.getMessage());
            return result;
        }

        if (references == null || references.isEmpty()) {
            return result;
        }

        // 1. 结构化 references（前端可读）
        List<Map<String, Object>> refMaps = new ArrayList<>();
        List<String> citationLines = new ArrayList<>();
        List<String> contextLines = new ArrayList<>();

        for (GbReference ref : references) {
            String citation = formatCitation(ref);
            String refText = StringUtils.hasText(ref.getRefText()) ? ref.getRefText() : "";

            Map<String, Object> refMap = new HashMap<>();
            refMap.put("citation", citation);
            refMap.put("targetStandardNo", ref.getTargetStandardNo());
            refMap.put("targetClausePath", ref.getTargetClausePath());
            refMap.put("refType", ref.getRefType());
            refMap.put("refText", refText);
            refMaps.add(refMap);

            citationLines.add(citation);
            // 被引用条款原文（若有）作为上下文片段
            if (StringUtils.hasText(refText)) {
                contextLines.add("[引用上下文] " + refText);
            }
        }

        result.setReferences(refMaps);

        // 2. 把引用上下文追加到 text（不改变 score）
        //    格式：在原文本后加分隔区，列引用标注 + 引用上下文片段。
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(result.getText())) {
            text.append(result.getText());
        }
        if (!citationLines.isEmpty()) {
            text.append("\n\n[引用] ");
            text.append(String.join("; ", citationLines));
        }
        for (String ctx : contextLines) {
            text.append("\n").append(ctx);
        }

        result.setText(text.toString());
        return result;
    }

    /**
     * 格式化引用标注：[标准号 版本] §条款号 (页码)。
     * <p>
     * F6 已为 GbReference 增补 targetVersion + targetPageNo 字段，引用格式现在完整。
     * </p>
     */
    private String formatCitation(GbReference ref) {
        String standardNo = StringUtils.hasText(ref.getTargetStandardNo()) ? ref.getTargetStandardNo() : "";
        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 延后项 F6】引用格式补 version + page（GbReference 已增字段）-----------
        String version = StringUtils.hasText(ref.getTargetVersion()) ? ref.getTargetVersion() : "";
        String clauseNo = StringUtils.hasText(ref.getTargetClausePath()) ? ref.getTargetClausePath() : "";
        String page = ref.getTargetPageNo() != null ? String.valueOf(ref.getTargetPageNo()) : "";
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 延后项 F6】-----------

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(standardNo);
        if (StringUtils.hasText(version)) {
            sb.append(" ").append(version);
        }
        sb.append("]");
        if (StringUtils.hasText(clauseNo)) {
            sb.append(" §").append(clauseNo);
        }
        if (StringUtils.hasText(page)) {
            sb.append(" (").append(page).append(")");
        }
        return sb.toString();
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】引用上下文增强器-----------
