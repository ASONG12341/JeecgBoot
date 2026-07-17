package org.jeecg.modules.airag.llm.extractor;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.vo.GbMetadata;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//update-begin---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GB 标准 metadata 提取器（正则/启发式优先）-----------
//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P3 Task 9】删除旧意图系统：移除 IGbIntentExtractor LLM-fallback 分支（旧 GbQueryIntent 已废弃），仅保留正则/启发式路径-----------
/**
 * GB 标准 metadata 提取器。
 * 策略：仅正则/启发式（GB-RAG v4 P3：原 LLM-fallback 分支依赖已删除的 IGbIntentExtractor/GbQueryIntent，已移除）。
 */
@Slf4j
@Component
public class GbMetadataExtractor {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile("(?:第\\s*)?(\\d+)(?:\\s*章|\\.\\s*\\d+)");
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern AMENDMENT_PATTERN = Pattern.compile("GB[\\s/]*T?[\\s/]*\\d+(?:[-—](\\d{4}))?");
    private static final Pattern N_CELLS_PATTERN = Pattern.compile("(\\d+)\\s*(?:S|串|个电芯)");

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】抽取 standardNo + clausePath（regex 可抽；primaryType/secondaryType 是 LLM 槽位概念，regex 抽不出，由入库管线注入）-----------
    /** 标准号：GB/T 31467.3 / GB 31241-2022 等（含可选 /T 和年份） */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile("GB[\\s/]*T?[\\s/]*\\d+(?:\\.\\d+)?(?:[-—]\\d{4})?");
    /** 条款路径：9 / 9.2 / 9.2.3（章/条/款，复用 CLAUSE_PATTERN 但含单数字章号） */
    private static final Pattern CLAUSE_PATH_PATTERN = Pattern.compile("\\b(\\d+(?:\\.\\d+){0,2})\\b");
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------

    private static final Set<String> TEST_TYPE_KEYWORDS = new HashSet<>(Arrays.asList(
            "过压充电", "过充电", "过充", "短路", "挤压", "针刺", "跌落",
            "高低温循环", "热冲击", "加热", "振动", "循环寿命", "过放"
    ));

    public GbMetadata extractFromText(String text) {
        if (text == null || text.isEmpty()) {
            return GbMetadata.builder().status("current").build();
        }

        //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】builder 加 standardNo/clausePath-----------
        return GbMetadata.builder()
                .chapter(extractChapter(text))
                .clauseId(extractClauseId(text))
                .amendment(extractAmendment(text))
                .status(extractStatus(text))
                .testType(extractTestType(text))
                .nCellsAlias(extractNCellsAlias(text))
                .standardNo(extractStandardNo(text))
                .clausePath(extractClausePath(text))
                .build();
        //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】standardNo + clausePath 抽取方法-----------
    String extractStandardNo(String text) {
        Matcher m = STANDARD_NO_PATTERN.matcher(text);
        return m.find() ? m.group().replaceAll("\\s+", "") : null;
    }

    String extractClausePath(String text) {
        Matcher m = CLAUSE_PATH_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4 I2】-----------

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
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P3 Task 9】删除旧意图系统：移除 IGbIntentExtractor LLM-fallback 分支-----------
//update-end---author:song-claude ---date:2026-07-11  for：【GB-RAG P1.1 Task 4】GB 标准 metadata 提取器（正则/启发式优先）-----------
