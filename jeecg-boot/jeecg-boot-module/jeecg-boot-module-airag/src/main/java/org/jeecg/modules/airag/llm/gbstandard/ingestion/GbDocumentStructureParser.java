//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国标文档层级树解析器
 * <p>
 * 将 MinerU 解析后的 Markdown 文本解析为结构化的条款层级树。
 * 支持三种解析质量：GB 31241（良好）、GB/T 34131（优秀）、GB 38031（严重退化）。
 * </p>
 *
 * <h3>解析策略（优先级从高到低）</h3>
 * <ol>
 *   <li>正则匹配国标编号规范: {@code ^(\d+)(\.\d+)*\s+(.+)$}</li>
 *   <li>Markdown 标题层级: {@code # / ## / ###}</li>
 *   <li>OCR 退化检测: 识别 "31" 可能是 "3.1" 的退化情况</li>
 * </ol>
 *
 * <h3>特殊处理</h3>
 * <ul>
 *   <li>第1章 范围 → isScope=true</li>
 *   <li>第2章 规范性引用文件 → 提取 normativeRefs</li>
 *   <li>第3章 术语和定义 → 标记为 definition 类型</li>
 *   <li>附录 A/B/C → isAppendix=true, appendixLabel=A/B/C</li>
 *   <li>注（Notes）→ 合并到所属条款文本中</li>
 *   <li>表格（HTML）→ 保持在所属条款文本中</li>
 * </ul>
 *
 * @author song
 * @date 2026-07-14
 */
@Slf4j
public class GbDocumentStructureParser {

    // ==================== 正则常量 ====================

    /**
     * 匹配标准条款编号（带小数点）: "9.2.3 过压充电" 或 "4.5 测试用充放电程序"
     * 组1: 条款号（如 9.2.3）  组2: 标题
     */
    private static final Pattern CLAUSE_WITH_DOT_PATTERN = Pattern.compile(
            "^(\\d+\\.\\d+(?:\\.\\d+)*)\\s+(.+)$"
    );

    /**
     * 匹配章级标题（纯数字）: "9 电池组电安全试验" 或 "# 1 范围"
     * 组1: 章号（1-2位数字）  组2: 标题
     */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^(\\d{1,2})\\s+(.+)$"
    );

    /**
     * 匹配 Markdown 标题行: "# 9.2 过压充电" 或 "## 6.2 数据采集"
     * 组1: # 号  组2: 可选的条款号  组3: 标题文本
     */
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile(
            "^(#{1,4})\\s+(?:(\\d+(?:\\.\\d+)*)\\s+)?(.+)$"
    );

    /**
     * 匹配附录标题: "附录 A（资料性） 工作范围示例" 或 "# 附录 B（规范性） 试验顺序"
     * 组1: 附录字母  组2: 性质（资料性/规范性）  组3: 标题
     */
    private static final Pattern APPENDIX_PATTERN = Pattern.compile(
            "附录\\s*([A-Z])\\s*[（(](.*?)[）)]\\s*(.*)"
    );

    /**
     * 匹配标准号: "GB 31241" / "GB/T 31467.3" / "GB 38031-2025" / "GB/T 34131—2023"
     * 组1: 标准号（含可选 /T）  组2: 年份（可选，支持 - 和 —）
     */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile(
            "(GB[/T]?\\s*\\d+(?:\\.\\d+)?)(?:\\s*[-—]\\s*(\\d{4}))?"
    );

    /**
     * 匹配日期: "2022-12-29 发布" / "2024-01-01 实施"
     * 组1: 日期  组2: 发布/实施
     */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s*(发布|实施)"
    );

    /**
     * 匹配跨标准引用: "GB/T 2423.5" / "GB 4943.1—2022"
     * 出现在规范性引用文件章节中
     */
    private static final Pattern REF_STANDARD_PATTERN = Pattern.compile(
            "(GB[/T]?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-—]\\s*\\d{4})?)"
    );

    /**
     * 匹配替代旧标准: "本文件代替 GB 31241—2014" 或 "代替 GB/T 34131—2017"
     */
    private static final Pattern SUPERSEDES_PATTERN = Pattern.compile(
            "代替\\s*(GB[/T]?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-—]\\s*\\d{4})?)"
    );

    /** 需要跳过的非条款标题 */
    private static final Set<String> SKIPPABLE_HEADINGS = Set.of(
            "目次", "前言", "引言", "参考文献", "目录"
    );

    /** 规范性引用文件章节的标识关键词 */
    private static final String NORMATIVE_REF_KEYWORD = "规范性引用文件";

    /** 术语和定义章节的标识关键词 */
    private static final String TERMS_KEYWORD = "术语和定义";

    /** 范围章节的标识关键词 */
    private static final String SCOPE_KEYWORD = "范围";

    // ==================== 主入口 ====================

    /**
     * 解析国标 Markdown 为文档结构
     *
     * @param markdown MinerU 解析后的 Markdown 文本
     * @return 文档结构 DTO（标准基本信息 + 条款层级树 + 置信度统计）
     */
    public GbDocStructure parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return GbDocStructure.builder().build();
        }

        String[] lines = markdown.split("\n");

        // Phase 1: 提取标准基本信息
        GbDocStructure.GbDocStructureBuilder builder = GbDocStructure.builder();
        extractStandardInfo(lines, builder);

        // Phase 2: 逐行解析条款
        List<GbClauseNode> allClauses = new ArrayList<>();
        parseClauses(lines, allClauses);

        // Phase 3: 构建父子关系（flat list → tree）
        List<GbClauseNode> roots = buildHierarchy(allClauses);

        // Phase 4: 统计置信度
        int high = 0, medium = 0, low = 0;
        for (GbClauseNode c : allClauses) {
            switch (c.getConfidence()) {
                case "high" -> high++;
                case "medium" -> medium++;
                case "low" -> low++;
            }
        }

        return builder
                .clauses(roots)
                .totalClauseCount(allClauses.size())
                .highConfidenceCount(high)
                .mediumConfidenceCount(medium)
                .lowConfidenceCount(low)
                .build();
    }

    // ==================== Phase 1: 提取标准基本信息 ====================

    /**
     * 从文档头部（前30行）提取标准号、版本、日期、替代关系
     */
    private void extractStandardInfo(String[] lines, GbDocStructure.GbDocStructureBuilder builder) {
        int scanLimit = Math.min(lines.length, 30);
        StringBuilder headerBuilder = new StringBuilder();
        for (int i = 0; i < scanLimit; i++) {
            headerBuilder.append(lines[i]).append("\n");
        }
        String header = headerBuilder.toString();

        // 提取标准号（优先从标题行提取）
        extractStandardNo(header, builder);

        // 提取日期
        Matcher dateMatcher = DATE_PATTERN.matcher(header);
        while (dateMatcher.find()) {
            String dateStr = dateMatcher.group(1);
            String type = dateMatcher.group(2);
            if ("发布".equals(type)) {
                builder.publishDate(dateStr);
            } else if ("实施".equals(type)) {
                builder.implementationDate(dateStr);
            }
        }

        // 提取替代的旧标准
        List<String> supersedes = new ArrayList<>();
        Matcher supMatcher = SUPERSEDES_PATTERN.matcher(header);
        while (supMatcher.find()) {
            supersedes.add(supMatcher.group(1).replaceAll("\\s+", " ").trim());
        }
        builder.supersedes(supersedes);

        // 提取标准全称（第一个非特殊 # 标题）
        for (int i = 0; i < scanLimit; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#")) continue;
            String title = line.replaceFirst("^#+\\s*", "").trim();
            if (title.isEmpty()) continue;
            // 跳过特殊标题
            if (isSkippableHeading(title)) continue;
            // 跳过纯英文标题（通常是英文翻译名）
            if (title.matches("^[A-Za-z\\s,;:—–·()]+$") && title.length() < 60) continue;
            // 跳过条款标题（带数字编号的）
            if (title.matches("^\\d+(\\.\\d+)*\\s+.*")) continue;
            // 跳过附录标题
            if (title.startsWith("附录")) continue;
            // 这应该是标准全称
            builder.fullName(title);
            break;
        }

        // 提取规范性引用文件清单（扫描全文找第2章）
        List<String> normativeRefs = extractNormativeRefs(lines);
        builder.normativeRefs(normativeRefs);
    }

    /**
     * 从头部文本中提取标准号和版本
     */
    private void extractStandardNo(String header, GbDocStructure.GbDocStructureBuilder builder) {
        Matcher stdMatcher = STANDARD_NO_PATTERN.matcher(header);
        if (stdMatcher.find()) {
            String stdNo = stdMatcher.group(1).replaceAll("\\s+", " ").trim();
            builder.standardNo(stdNo);
            // 版本: 优先从标准号后缀提取，否则从日期提取
            if (stdMatcher.group(2) != null) {
                builder.version(stdMatcher.group(2));
            } else {
                // 尝试从发布日期提取年份作为版本
                Matcher dateMatcher = DATE_PATTERN.matcher(header);
                if (dateMatcher.find()) {
                    String dateStr = dateMatcher.group(1);
                    builder.version(dateStr.substring(0, 4));
                }
            }
        }
    }

    /**
     * 从第2章（规范性引用文件）中提取引用的标准号列表
     */
    private List<String> extractNormativeRefs(String[] lines) {
        List<String> refs = new ArrayList<>();
        boolean inNormativeSection = false;

        for (String line : lines) {
            String trimmed = line.trim().replaceFirst("^#+\\s*", "");

            // 检测进入规范性引用文件章节
            if (trimmed.contains(NORMATIVE_REF_KEYWORD)) {
                inNormativeSection = true;
                continue;
            }

            // 检测离开该章节（遇到下一个章级标题）
            if (inNormativeSection) {
                if (trimmed.matches("^\\d{1,2}\\s+[^\\d].*") && !trimmed.contains("GB")) {
                    // 遇到了新的章级标题
                    break;
                }
                // 提取引用标准号
                Matcher refMatcher = REF_STANDARD_PATTERN.matcher(trimmed);
                while (refMatcher.find()) {
                    String ref = refMatcher.group(1).replaceAll("\\s+", " ").trim();
                    if (!refs.contains(ref)) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }

    // ==================== Phase 2: 解析条款 ====================

    /**
     * 逐行扫描 Markdown，提取所有条款
     */
    private void parseClauses(String[] lines, List<GbClauseNode> allClauses) {
        GbClauseNode currentClause = null;
        StringBuilder currentText = new StringBuilder();
        boolean inNormativeSection = false;
        boolean inTermsSection = false;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String line = rawLine.trim();

            // 空行跳过（但保留换行用于文本拼接）
            if (line.isEmpty()) {
                if (currentClause != null) {
                    currentText.append("\n");
                }
                continue;
            }

            // 尝试匹配附录标题
            String lineWithoutHash = line.replaceFirst("^#+\\s*", "");
            Matcher appendixMatcher = APPENDIX_PATTERN.matcher(lineWithoutHash);
            if (appendixMatcher.find()) {
                // 保存当前条款
                flushClause(currentClause, currentText, allClauses);
                currentClause = null;
                currentText.setLength(0);

                String label = appendixMatcher.group(1);
                String nature = appendixMatcher.group(2);
                String title = appendixMatcher.group(3) != null ? appendixMatcher.group(3).trim() : "";

                currentClause = GbClauseNode.builder()
                        .clausePath("附录 " + label)
                        .parentPath(null)
                        .depth(1)
                        .title(title)
                        .clauseType("normative_appendix".equals(determineAppendixType(nature))
                                ? "normative_appendix" : "informative_appendix")
                        .confidence("high")
                        .isAppendix(true)
                        .appendixLabel(label)
                        .build();
                inNormativeSection = false;
                inTermsSection = false;
                continue;
            }

            // 尝试匹配 Markdown 标题（带 # 前缀）
            if (line.startsWith("#")) {
                Matcher headingMatcher = MD_HEADING_PATTERN.matcher(line);
                if (headingMatcher.find()) {
                    String clauseNum = headingMatcher.group(2);
                    String headingTitle = headingMatcher.group(3) != null ? headingMatcher.group(3).trim() : "";

                    // 跳过特殊标题
                    if (isSkippableHeading(headingTitle) || isSkippableHeading(line.replaceFirst("^#+\\s*", ""))) {
                        flushClause(currentClause, currentText, allClauses);
                        currentClause = null;
                        currentText.setLength(0);
                        continue;
                    }

                    // 检测特殊章节
                    if (headingTitle.contains(NORMATIVE_REF_KEYWORD)) {
                        inNormativeSection = true;
                        inTermsSection = false;
                    } else if (headingTitle.contains(TERMS_KEYWORD)) {
                        inTermsSection = true;
                        inNormativeSection = false;
                    } else {
                        inNormativeSection = false;
                        inTermsSection = false;
                    }

                    // 有明确条款号
                    if (clauseNum != null && isValidClauseNumber(clauseNum)) {
                        flushClause(currentClause, currentText, allClauses);
                        currentText.setLength(0);

                        currentClause = buildClauseNode(clauseNum, headingTitle, "high");
                        continue;
                    }

                    // 章级标题（# 1 范围）
                    Matcher chapterMatcher = CHAPTER_PATTERN.matcher(headingTitle);
                    if (chapterMatcher.find()) {
                        String chapterNum = chapterMatcher.group(1);
                        String chapterTitle = chapterMatcher.group(2).trim();

                        flushClause(currentClause, currentText, allClauses);
                        currentText.setLength(0);

                        currentClause = buildClauseNode(chapterNum, chapterTitle, "high");
                        continue;
                    }

                    // 标题无条款号 — 可能是前言/引言等，跳过
                    flushClause(currentClause, currentText, allClauses);
                    currentClause = null;
                    currentText.setLength(0);
                    continue;
                }
            }

            // 尝试匹配纯文本条款号（无 # 前缀，如 GB/T 34131 风格: "6.2.1 锂离子..."）
            Matcher clauseMatcher = CLAUSE_WITH_DOT_PATTERN.matcher(line);
            if (clauseMatcher.find()) {
                String clauseNum = clauseMatcher.group(1);
                String clauseTitle = clauseMatcher.group(2).trim();

                flushClause(currentClause, currentText, allClauses);
                currentText.setLength(0);

                currentClause = buildClauseNode(clauseNum, clauseTitle, "high");
                continue;
            }

            // 尝试匹配章级纯数字标题（无 # 前缀）
            Matcher chapterMatcher = CHAPTER_PATTERN.matcher(line);
            if (chapterMatcher.find() && line.length() < 50) {
                String chapterNum = chapterMatcher.group(1);
                String chapterTitle = chapterMatcher.group(2).trim();

                // 排除纯数字行（如页码 "31" 或表格数据）
                if (chapterTitle.isEmpty() || chapterTitle.matches("^\\d+$")) {
                    appendToCurrentText(currentClause, currentText, line);
                    continue;
                }

                flushClause(currentClause, currentText, allClauses);
                currentText.setLength(0);

                currentClause = buildClauseNode(chapterNum, chapterTitle, "high");
                continue;
            }

            // OCR 退化检测：检查是否是 "31" / "511" 这类缺少小数点的退化编号
            if (currentClause != null || allClauses.isEmpty()) {
                String ocrFixed = tryFixOcrClauseNumber(line);
                if (ocrFixed != null) {
                    String[] parts = ocrFixed.split("\\|", 2);
                    String clauseNum = parts[0];
                    String clauseTitle = parts.length > 1 ? parts[1] : "";

                    flushClause(currentClause, currentText, allClauses);
                    currentText.setLength(0);

                    currentClause = buildClauseNode(clauseNum, clauseTitle, "low");
                    continue;
                }
            }

            // 普通文本行：追加到当前条款
            appendToCurrentText(currentClause, currentText, line);
        }

        // 保存最后一个条款
        flushClause(currentClause, currentText, allClauses);
    }

    // ==================== Phase 3: 构建层级 ====================

    /**
     * 将 flat 的条款列表构建为父子树结构
     */
    private List<GbClauseNode> buildHierarchy(List<GbClauseNode> allClauses) {
        Map<String, GbClauseNode> byPath = new LinkedHashMap<>();
        for (GbClauseNode c : allClauses) {
            byPath.put(c.getClausePath(), c);
        }

        List<GbClauseNode> roots = new ArrayList<>();
        for (GbClauseNode c : allClauses) {
            String parentPath = c.getParentPath();
            if (parentPath != null && byPath.containsKey(parentPath)) {
                byPath.get(parentPath).getChildren().add(c);
            } else {
                roots.add(c);
            }
        }
        return roots;
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据条款号和标题构建 GbClauseNode
     */
    private GbClauseNode buildClauseNode(String clauseNum, String title, String confidence) {
        int depth = clauseNum.contains(".") ? clauseNum.split("\\.").length : 1;
        String parentPath = null;
        if (depth > 1) {
            parentPath = clauseNum.substring(0, clauseNum.lastIndexOf('.'));
        }

        return GbClauseNode.builder()
                .clausePath(clauseNum)
                .parentPath(parentPath)
                .depth(depth)
                .title(title)
                .clauseType(determineClauseType(clauseNum, title))
                .confidence(confidence)
                .isScope("1".equals(clauseNum) || title.contains(SCOPE_KEYWORD))
                .isAppendix(false)
                .build();
    }

    /**
     * 保存当前条款到列表，并重置文本缓冲区
     */
    private void flushClause(GbClauseNode clause, StringBuilder text, List<GbClauseNode> allClauses) {
        if (clause != null) {
            String clauseText = text.toString().trim();
            // 清理多余换行
            clauseText = clauseText.replaceAll("\n{3,}", "\n\n");
            clause.setText(clauseText);
            allClauses.add(clause);
        }
        text.setLength(0);
    }

    /**
     * 追加文本到当前条款缓冲区
     */
    private void appendToCurrentText(GbClauseNode clause, StringBuilder text, String line) {
        if (clause != null) {
            text.append(line).append("\n");
        }
    }

    /**
     * 验证是否为合法条款编号
     */
    private boolean isValidClauseNumber(String num) {
        // 1-2位纯数字（章号）或 带小数点的多级编号
        return num.matches("\\d{1,2}") || num.matches("\\d+\\.\\d+(?:\\.\\d+)*");
    }

    /**
     * 根据条款号和标题推断条款类型
     */
    private String determineClauseType(String clauseNum, String title) {
        if ("1".equals(clauseNum) || title.contains(SCOPE_KEYWORD)) return "scope";
        if ("2".equals(clauseNum) || title.contains(NORMATIVE_REF_KEYWORD)) return "reference";
        if ("3".equals(clauseNum) || title.contains(TERMS_KEYWORD)) return "definition";
        if (title.contains("试验方法") || title.contains("试验") || title.contains("测试")) return "test_method";
        return "normative";
    }

    private String determineAppendixType(String nature) {
        if (nature != null && nature.contains("规范性")) return "normative_appendix";
        return "informative_appendix";
    }

    private boolean isSkippableHeading(String title) {
        if (title == null) return false;
        for (String skip : SKIPPABLE_HEADINGS) {
            if (title.contains(skip)) return true;
        }
        return false;
    }

    /**
     * OCR 退化修复：尝试将 "511" 修复为 "5.1.1"
     * <p>
     * 检测逻辑：3-4位纯数字开头 + 后面跟中文文本 + 前面没有小数点。
     * 将 "511 电池单体..." → "5.1.1|电池单体..."
     * </p>
     *
     * @return 修复后的 "条款号|标题"，如果无法修复返回 null
     */
    private String tryFixOcrClauseNumber(String line) {
        // 匹配 3-4位纯数字开头 + 空格/中文
        Matcher m = Pattern.compile("^(\\d{3,4})\\s+(.+)").matcher(line);
        if (!m.find()) return null;

        String digits = m.group(1);
        String rest = m.group(2);

        // 排除明显不是条款号的情况（页码、表格数据等）
        if (rest.matches("^\\d.*") || rest.length() < 5) return null;

        // 尝试插入小数点: "511" → "5.1.1", "8122" → "8.12.2"
        String fixed;
        if (digits.length() == 3) {
            // 3位: 第一位是章号，后两位各一级
            fixed = digits.charAt(0) + "." + digits.charAt(1) + "." + digits.charAt(2);
        } else {
            // 4位: 第一位是章号，第二位是条号，后两位是款号
            fixed = digits.charAt(0) + "." + digits.charAt(1) + digits.charAt(2) + "." + digits.charAt(3);
        }

        return fixed + "|" + rest;
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
