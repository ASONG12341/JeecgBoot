package org.jeecg.modules.airag.llm.extractor;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.common.handler.GbQueryIntent;
import org.jeecg.modules.airag.common.handler.IGbIntentExtractor;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.jeecg.modules.airag.llm.vo.GbMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GB 标准 metadata 提取器（正则/启发式优先 + 可选 LLM fallback）-----------
/**
 * GB 标准 metadata 提取器。
 * 策略：正则/启发式优先；关键字段缺失且开启 LLM fallback 时，调用 IGbIntentExtractor 补全。
 */
@Slf4j
@Component
public class GbMetadataExtractor {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(?:第\\s*)?(\\d+)(?:\\s*章|\\.\\s*\\d+)");
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern AMENDMENT_PATTERN = Pattern.compile("GB[\\s/]*T?[\\s/]*\\d+(?:[-—](\\d{4}))?");
    private static final Pattern N_CELLS_PATTERN = Pattern.compile("(\\d+)\\s*(?:S|串|个电芯)");

    private static final Set<String> TEST_TYPE_KEYWORDS = new HashSet<>(Arrays.asList(
            "过压充电", "过充电", "过充", "短路", "挤压", "针刺", "跌落",
            "高低温循环", "热冲击", "加热", "振动", "循环寿命", "过放"
    ));

    @Autowired
    private KnowConfigBean knowConfigBean;

    @Autowired(required = false)
    private IGbIntentExtractor gbIntentExtractor;

    public GbMetadata extractFromText(String text) {
        if (text == null || text.isEmpty()) {
            return GbMetadata.builder().status("current").build();
        }

        GbMetadata meta = GbMetadata.builder()
                .chapter(extractChapter(text))
                .clauseId(extractClauseId(text))
                .amendment(extractAmendment(text))
                .status(extractStatus(text))
                .testType(extractTestType(text))
                .nCellsAlias(extractNCellsAlias(text))
                .build();

        if (knowConfigBean.isMetadataLlmFallbackEnabled() && needsLlmFallback(meta) && text.length() <= 2000) {
            try {
                GbQueryIntent intent = gbIntentExtractor.extractWithFallback(text, null);
                if (intent != null) {
                    fillFromIntent(meta, intent);
                }
            } catch (Exception e) {
                log.warn("[GB-RAG] metadata LLM fallback 失败: {}", e.getMessage());
            }
        }

        return meta;
    }

    private boolean needsLlmFallback(GbMetadata meta) {
        return oConvertUtils.isEmpty(meta.getChapter()) || oConvertUtils.isEmpty(meta.getTestType());
    }

    private void fillFromIntent(GbMetadata meta, GbQueryIntent intent) {
        if (oConvertUtils.isEmpty(meta.getChapter()) && oConvertUtils.isNotEmpty(intent.getInferredChapter())) {
            meta.setChapter(intent.getInferredChapter());
        }
        if (oConvertUtils.isEmpty(meta.getTestType()) && oConvertUtils.isNotEmpty(intent.getTestType())) {
            meta.setTestType(intent.getTestType());
        }
        if (oConvertUtils.isEmpty(meta.getNCellsAlias()) && intent.getNCells() != null) {
            meta.setNCellsAlias(String.valueOf(intent.getNCells()));
        }
        if (oConvertUtils.isEmpty(meta.getClauseId()) && oConvertUtils.isNotEmpty(intent.getClauseId())) {
            meta.setClauseId(intent.getClauseId());
        }
        if (oConvertUtils.isEmpty(meta.getAmendment()) && oConvertUtils.isNotEmpty(intent.getAmendment())) {
            meta.setAmendment(intent.getAmendment());
        }
        if (oConvertUtils.isEmpty(meta.getStatus()) && oConvertUtils.isNotEmpty(intent.getStatus())) {
            meta.setStatus(intent.getStatus());
        }
    }

    String extractChapter(String text) {
        Matcher m = CHAPTER_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    String extractClauseId(String text) {
        Matcher m = CLAUSE_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    String extractAmendment(String text) {
        Matcher m = AMENDMENT_PATTERN.matcher(text);
        if (m.find() && m.group(1) != null) {
            return m.group(1);
        }
        return null;
    }

    String extractStatus(String text) {
        if (text.contains("废止") || text.contains("已撤销")) {
            return "superseded";
        }
        return "current";
    }

    String extractTestType(String text) {
        for (String keyword : TEST_TYPE_KEYWORDS) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    String extractNCellsAlias(String text) {
        Matcher m = N_CELLS_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GB 标准 metadata 提取器（正则/启发式优先 + 可选 LLM fallback）-----------
