//update-begin---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国标文档层级树解析器（两阶段：规范化 → 严格读树）。
 *
 * <h2>国标 Markdown 标注规范（解析器与人工核对共同遵守的契约）</h2>
 * <pre>
 *   # 1 范围                     → 章（一级）
 *   ## 6.2 数据采集               → 条（二级）
 *   ### 6.4.1 报警分级和处理       → 款（三级）
 *   #### 6.4.1.1 xxx              → 四级
 *   # 附录A（规范性）试验顺序       → 附录（一级），附录内条款 ## A.1 / ### A.1.1
 *   普通行                        → 正文，自动归属当前条款
 *   注：… / 注 1：…               → 注，普通行，自动归属当前条款
 *   ![描述](路径)                 → 图片，普通行，自动归属当前条款
 *   &lt;table&gt;…&lt;/table&gt;   → 表格，普通行，自动归属当前条款
 *   [来源：GB/T xxx—2020, 4.4.3]  → 来源引用，普通行，自动归属当前条款
 *   目次/前言/引言/参考文献        → 非条款内容，解析器自动跳过
 * </pre>
 *
 * <h2>为什么是两阶段</h2>
 * MinerU 输出不可控：同一文档内 `#`/`##` 混用、编号与标题分行、OCR 丢失小数点
 * （"316"=3.16、"41"=4.1、"511"=5.1.1）、目录条目与正文标题同形。
 * 直接"逐行正则 → 树"必然混乱。因此：
 * <ol>
 *   <li><b>阶段一 normalize</b>：把 MinerU 原始 md 规范化为上述契约 md。
 *       核心手段是 GB/T 1.1 编号语法 + 序列连续性校验（章严格 1,2,3… 递增、
 *       条款在父级内递增）：候选编号只有命中"序列上合法的下一编号"才被接受，
 *       OCR 退化解码也只接受落在合法集合内的结果 —— 从机制上排除
 *       "6 个月内"、"3.6 倍"这类正文误捕。规范化是幂等的：已符合规范的 md 原样通过。</li>
 *   <li><b>阶段二 buildTree</b>：严格按契约读规范 md 成树。用户在确认页修改的
 *       也是这份规范 md（Markdown 是唯一事实源），改完重新生成树即可。</li>
 * </ol>
 *
 * <h2>解析规则（parseRule，与确认页"解析规则"面板一一对应）</h2>
 * <ul>
 *   <li>{@link #RULE_HEADING_OK} 规范标题命中：原文是标题且编号序列合法（含仅 `#` 数量修正，高置信度）</li>
 *   <li>{@link #RULE_SPLIT_JOINED} 编号/标题分行合并：原文编号单独一行（中）</li>
 *   <li>{@link #RULE_PROMOTED} 正文编号提升：原文无 `#` 但编号序列合法（中）</li>
 *   <li>{@link #RULE_OCR_REPAIRED} OCR 退化修复：丢失小数点的编号按序列解码（低，重点人工核对）</li>
 *   <li>{@link #RULE_APPENDIX} 附录识别（高）</li>
 * </ul>
 *
 * @author song
 * @date 2026-07-14
 */
//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】解析器改为 Spring Bean，便于注入测试-----------
@Slf4j
@Component
public class GbDocumentStructureParser {
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】解析器改为 Spring Bean，便于注入测试-----------

    // ==================== 解析规则 key（前端"解析规则"面板按此展示） ====================

    /** 规范标题直接命中（含 `#` 数量与层级不符的标题修正——仅改标记风格，确定性高） */
    public static final String RULE_HEADING_OK = "HEADING_OK";
    /** 编号/标题分行合并 */
    public static final String RULE_SPLIT_JOINED = "SPLIT_JOINED";
    /** 正文编号提升为标题 */
    public static final String RULE_PROMOTED = "PROMOTED";
    /** OCR 退化修复（丢失小数点） */
    public static final String RULE_OCR_REPAIRED = "OCR_REPAIRED";
    /** 附录识别 */
    public static final String RULE_APPENDIX = "APPENDIX";

    // ==================== 正则常量 ====================

    /** Markdown 标题行：组1=#号 组2=正文 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s*(.*?)\\s*$");

    /** 纯编号（可带点）：3.1 / 316 / 5 */
    private static final Pattern NUMBER_ONLY_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

    /** 带点编号+标题：6.2 数据采集 */
    private static final Pattern DOTTED_WITH_TITLE_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)[\\s　]+(.+)$");

    /** 裸数字+标题：41 符号 / 511 电池单体… / 6 电池电安全试验 */
    private static final Pattern BARE_WITH_TITLE_PATTERN = Pattern.compile("^(\\d{1,6})[\\s　]+(.+)$");

    /** 附录标题：附录 A（资料性）工作范围示例（组1=字母 组2=性质 组3=标题） */
    private static final Pattern APPENDIX_HEAD_PATTERN = Pattern.compile(
            "^附\\s*录\\s*([A-Z])\\s*(?:[（(]\\s*([^）)]*?)\\s*[）)])?\\s*(.*)$");

    /** 附录性质单独成行：（资料性）/（规范性） */
    private static final Pattern APPENDIX_NATURE_ONLY_PATTERN = Pattern.compile("^[（(]\\s*(资料性|规范性)\\s*[）)]$");

    /** 附录内条款：A.1 xxx / A.1.1 xxx（组1=字母 组2=编号 组3=标题） */
    private static final Pattern APPENDIX_CLAUSE_PATTERN = Pattern.compile("^([A-Z])\\.(\\d+(?:\\.\\d+)*)[\\s　]+(.+)$");

    /** 目次/目录标题 */
    private static final Pattern TOC_HEAD_PATTERN = Pattern.compile("^目\\s*[次录]\\s*$");

    /** 目录条目页码尾巴：…… 25 / 空格+数字 / 空格+罗马数字 */
    private static final Pattern PAGE_TAIL_PATTERN = Pattern.compile(
            "(?:[…·•]{2,}|\\.{2,}|\\s)\\s*[0-9０-９]{1,3}\\s*$|\\s+[IVXLCⅠ-Ⅻ]{1,5}\\s*$");

    /** 标准号: "GB 31241" / "GB/T 31467.3" / "GB 38031-2025" / "GB/T 34131—2023" */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile(
            "(GB(?:/T)?\\s*\\d+(?:\\.\\d+)?)(?:\\s*[-—]\\s*(\\d{4}))?");

    /** 日期: "2022-12-29 发布" / "2024-01-01 实施" */
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*(发布|实施)");

    /** 跨标准引用: "GB/T 2423.5" / "GB 4943.1—2022" */
    private static final Pattern REF_STANDARD_PATTERN = Pattern.compile(
            "(GB(?:/T)?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-—]\\s*\\d{4})?)");

    /** 替代旧标准: "本文件代替 GB 31241—2014" */
    private static final Pattern SUPERSEDES_PATTERN = Pattern.compile(
            "代替\\s*(GB(?:/T)?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-—]\\s*\\d{4})?)");

    /** 非条款标题（跳过，不进条款树） */
    private static final Set<String> SKIPPABLE_HEADINGS = Set.of("目次", "目录", "前言", "引言", "参考文献");

    /** 正文编号提升时的标题黑名单开头（防止 "6 个月内"、"3.6 倍" 这类正文被提升） */
    private static final Pattern BAD_PLAIN_TITLE_START = Pattern.compile(
            "^[0-9a-zA-Z%℃°±×÷个倍年月日小时分钟秒次项条款章，,。、）)】\\]…·.<>≤≥=+\\-~～/]");

    private static final String NORMATIVE_REF_KEYWORD = "规范性引用文件";
    private static final String TERMS_KEYWORD = "术语和定义";
    private static final String SCOPE_KEYWORD = "范围";
    private static final String REFERENCE_LIST_KEYWORD = "参考文献";

    // ==================== 主入口 ====================

    /**
     * 解析国标 Markdown 为文档结构（两阶段：规范化 → 严格读树）。
     *
     * @param markdown MinerU 解析后的 Markdown 文本（或用户修正后的规范 md）
     * @return 文档结构 DTO（标准信息 + 条款层级树 + 置信度统计 + 规范化后的 Markdown）
     */
    public GbDocStructure parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return GbDocStructure.builder().build();
        }

        // 阶段一：规范化
        NormalizeResult normalized = normalize(markdown);

        // 阶段二：提取标准信息 + 严格读树
        GbDocStructure.GbDocStructureBuilder builder = GbDocStructure.builder();
        extractStandardInfo(normalized.lines, builder);

        List<GbClauseNode> allClauses = new ArrayList<>();
        List<GbClauseNode> roots = buildTree(normalized.lines, normalized.rules, allClauses);

        int high = 0, medium = 0, low = 0;
        for (GbClauseNode c : allClauses) {
            switch (c.getConfidence() == null ? "high" : c.getConfidence()) {
                case "high" -> high++;
                case "medium" -> medium++;
                case "low" -> low++;
                default -> high++;
            }
        }

        return builder
                .clauses(roots)
                .totalClauseCount(allClauses.size())
                .highConfidenceCount(high)
                .mediumConfidenceCount(medium)
                .lowConfidenceCount(low)
                .normalizedMarkdown(String.join("\n", normalized.lines))
                .build();
    }

    /**
     * 阶段一单独暴露：把 MinerU 原始 md 规范化为契约 md（幂等）。
     * 确认页可用它向用户展示"机器理解后的规范文本"。
     */
    public String normalizeMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return String.join("\n", normalize(markdown).lines);
    }

    // ==================== 阶段一：规范化 ====================

    /** 规范化结果：规范 md 行 + 每个条款命中的解析规则 */
    private static class NormalizeResult {
        List<String> lines;
        /** clausePath → parseRule */
        Map<String, String> rules;
    }

    private NormalizeResult normalize(String markdown) {
        List<String> lines = new ArrayList<>(Arrays.asList(markdown.split("\n", -1)));
        lines = stripTocRegion(lines);
        MergeResult merged = mergeSplitHeadings(lines);
        return sequenceRewrite(merged.lines, merged.splitNums);
    }

    /**
     * 剥离目次区：从"目次/目录"标题起，丢弃到第一个无页码尾巴的标题之前的所有行。
     * 目录条目与正文标题同形（如 {@code # 9 电池组电安全试验…… 19}），不剥离必然产生重复章节。
     * 区域外的残留目录条目（带页码尾巴的标题行）降级为普通文本兜底。
     */
    private List<String> stripTocRegion(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int i = 0;
        boolean chapter1Seen = false;
        while (i < lines.size()) {
            String body = headingBody(lines.get(i));
            if (body != null && TOC_HEAD_PATTERN.matcher(body).matches()) {
                // 丢弃目次区，直到遇到无页码尾巴的标题（真实正文标题）
                i++;
                int guard = 0;
                while (i < lines.size() && guard++ < 500) {
                    String b = headingBody(lines.get(i));
                    if (b != null && !hasPageTail(b)) {
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (body != null && body.matches("^1[\\s　]+.*") && !hasPageTail(body)) {
                chapter1Seen = true;
            }
            // 兜底：正文前的残留目录标题（带页码尾巴）→ 降级为普通文本。
            // 只限正文前：正文中标题以数字结尾（罕见）不应被误降级。
            if (!chapter1Seen && body != null && hasPageTail(body)) {
                out.add(body);
            } else {
                out.add(lines.get(i));
            }
            i++;
        }
        return out;
    }

    /** 判断目录条目页码尾巴 */
    private boolean hasPageTail(String headingText) {
        return PAGE_TAIL_PATTERN.matcher(headingText.trim()).find();
    }

    /** 分行合并结果：合并后的行 + 被合并过的原始编号（用于标记 RULE_SPLIT_JOINED） */
    private static class MergeResult {
        List<String> lines;
        /** 发生过"编号/标题分行合并"的原始编号串 */
        Set<String> splitNums = new HashSet<>();
    }

    /**
     * 分行合并：MinerU 常把编号与标题拆成两行（{@code # 3.1} + {@code # 锂离子电池}，
     * 或附录三行拆分 {@code # 附录 A} + {@code # (资料性)} + {@code # 工作范围示例}）。
     * 只含编号的标题行取下一非空行作标题并消费之。
     */
    private MergeResult mergeSplitHeadings(List<String> lines) {
        MergeResult result = new MergeResult();
        List<String> out = new ArrayList<>(lines.size());
        result.lines = out;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String body = headingBody(line);

            if (body != null) {
                String trimmed = body.trim();

                // 情况1：附录标题拆行（附录 A / (资料性) / 标题）
                Matcher appendixOnly = APPENDIX_HEAD_PATTERN.matcher(trimmed);
                if (appendixOnly.matches()
                        && (appendixOnly.group(2) == null || appendixOnly.group(2).isBlank())
                        && (appendixOnly.group(3) == null || appendixOnly.group(3).isBlank())) {
                    String letter = appendixOnly.group(1);
                    String nature = "";
                    String title = "";
                    int j = nextNonEmpty(lines, i);
                    if (j >= 0) {
                        String nextBody = stripHashes(lines.get(j)).trim();
                        Matcher natureM = APPENDIX_NATURE_ONLY_PATTERN.matcher(nextBody);
                        if (natureM.matches()) {
                            nature = natureM.group(1);
                            j = nextNonEmpty(lines, j);
                            nextBody = j >= 0 ? stripHashes(lines.get(j)).trim() : "";
                        }
                        if (j >= 0 && !nextBody.isEmpty() && !NUMBER_ONLY_PATTERN.matcher(nextBody).matches()
                                && nextBody.length() <= 60) {
                            title = nextBody;
                        } else {
                            j = -1; // 标题不可用，不消费
                        }
                    }
                    String merged = "# 附录 " + letter
                            + (nature.isEmpty() ? "" : "（" + nature + "）")
                            + (title.isEmpty() ? "" : " " + title);
                    out.add(merged);
                    i = (j >= 0) ? j + 1 : i + 1;
                    continue;
                }

                // 情况2：纯编号标题行（# 3.1 / # 316 / # 31）→ 合并下一行标题
                if (NUMBER_ONLY_PATTERN.matcher(trimmed).matches()) {
                    int j = nextNonEmpty(lines, i);
                    if (j >= 0) {
                        String nextBody = stripHashes(lines.get(j)).trim();
                        if (!nextBody.isEmpty() && !NUMBER_ONLY_PATTERN.matcher(nextBody).matches()
                                && nextBody.length() <= 80) {
                            String hashes = line.trim().startsWith("#")
                                    ? line.trim().replaceFirst("^(#{1,6}).*$", "$1") : "#";
                            out.add(hashes + " " + trimmed + " " + nextBody);
                            result.splitNums.add(trimmed);
                            i = j + 1;
                            continue;
                        }
                    }
                }
            }
            out.add(line);
            i++;
        }
        return result;
    }

    /** 找 i 之后第一个非空行下标，找不到返回 -1（最多看 3 行） */
    private int nextNonEmpty(List<String> lines, int i) {
        for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
            if (!lines.get(j).trim().isEmpty()) {
                return j;
            }
        }
        return -1;
    }

    /**
     * 序列状态机（规范化核心）：逐行判定候选编号，只有命中"序列上合法的下一编号"才接受。
     * <ul>
     *   <li>合法集合 = 下一章（当前最大章号+1）∪ 当前各层的下一兄弟 ∪ 当前条款的第一个子级</li>
     *   <li>裸数字串（OCR 丢失小数点）枚举所有插点解码，只接受落在合法集合内的解码</li>
     *   <li>不接受的标题行在正文区降级为普通文本（消灭误捕），正文前的行原样保留（标准名等信息用）</li>
     * </ul>
     */
    private NormalizeResult sequenceRewrite(List<String> lines, Set<String> splitNums) {
        NormalizeResult result = new NormalizeResult();
        result.lines = new ArrayList<>(lines.size());
        result.rules = new HashMap<>();

        // 游标：当前条款路径栈（如 ["5"] / ["5","1"] / ["A"] / ["A","1"]）
        Deque<List<String>> stack = new ArrayDeque<>();
        int lastChapter = 0;
        boolean inAppendix = false;
        String currentLetter = null;
        boolean bodyStarted = false;
        boolean finished = false; // 参考文献之后不再收集

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            String rawLine = lines.get(lineIdx);
            String trimmedLine = rawLine.trim();
            if (trimmedLine.isEmpty()) {
                result.lines.add(rawLine);
                continue;
            }
            Matcher headingM = HEADING_PATTERN.matcher(trimmedLine);
            boolean isHeading = headingM.matches() && !headingM.group(2).isEmpty();
            String text = isHeading ? sanitize(headingM.group(2).trim()) : sanitize(trimmedLine);
            int headingLevel = isHeading ? headingM.group(1).length() : 0;

            // 跳过性标题（仅真正的标题行才算）：前言/引言（正文前）、参考文献（正文结束）。
            // 目录区被降级为普通文本的"参考文献 …… 38"不会误入此分支。
            if (isHeading && isSkippableHeading(text)) {
                if (text.contains(REFERENCE_LIST_KEYWORD)) {
                    finished = true;
                }
                // 降级为普通文本，绝不作为条款
                result.lines.add(text);
                continue;
            }
            if (finished) {
                result.lines.add(text);
                continue;
            }

            // 附录标题（仅标题行可触发；正文里"附录 A 给出了…"这类引用不算）
            Matcher appendixM = APPENDIX_HEAD_PATTERN.matcher(text);
            if (isHeading && appendixM.matches()) {
                String letter = appendixM.group(1);
                boolean forward = !inAppendix || currentLetter == null || letter.charAt(0) > currentLetter.charAt(0);
                if (forward) {
                    inAppendix = true;
                    currentLetter = letter;
                    stack.clear();
                    List<String> seg = new ArrayList<>();
                    seg.add(letter);
                    stack.push(seg);
                    bodyStarted = true;
                    String nature = appendixM.group(2) == null ? "" : appendixM.group(2).trim();
                    String title = appendixM.group(3) == null ? "" : appendixM.group(3).trim();
                    String display = "附录 " + letter + (nature.isEmpty() ? "" : "（" + nature + "）")
                            + (title.isEmpty() ? "" : " " + title);
                    result.lines.add("# " + display);
                    result.rules.put(letter, RULE_APPENDIX);
                    continue;
                }
                // 重复/倒退的附录标题（目录残留）→ 降级
                result.lines.add(text);
                continue;
            }

            // 附录内条款：A.1 / A.1.1
            if (inAppendix && currentLetter != null) {
                Matcher acM = APPENDIX_CLAUSE_PATTERN.matcher(text);
                if (acM.matches() && acM.group(1).equals(currentLetter)) {
                    String path = currentLetter + "." + acM.group(2);
                    Set<String> accepted = buildAcceptedSet(stack, lastChapter, inAppendix);
                    if (accepted.contains(path)) {
                        int depth = acM.group(2).split("\\.").length + 1;
                        emitClause(result, path, acM.group(3).trim(), depth,
                                pickFormRule(headingLevel, path, splitNums), stack);
                        continue;
                    }
                    // 序列不合法 → 当普通文本
                    result.lines.add(text);
                    continue;
                }
            }

            // 纯编号行（无标题，如 "31 " / "3.2 "）：借下一非空行作候选标题，序列校验通过才消费两行。
            // 页码等纯数字行因编号不合法自然被拒绝，原样落出。
            if (NUMBER_ONLY_PATTERN.matcher(text).matches()) {
                int j = nextNonEmpty(lines, lineIdx);
                String nextTitle = j >= 0 ? sanitize(stripHashes(lines.get(j)).trim()) : "";
                boolean accepted = false;
                if (!inAppendix && !nextTitle.isEmpty()
                        && !NUMBER_ONLY_PATTERN.matcher(nextTitle).matches() && nextTitle.length() <= 80) {
                    Set<String> acceptedSet = buildAcceptedSet(stack, lastChapter, false);
                    Set<String> gapSet = buildGapSet(stack, lastChapter);
                    String hit;
                    if (text.contains(".")) {
                        // 带点编号：直接查合法集合（不走插点解码）
                        boolean gapOnly = !acceptedSet.contains(text) && gapSet.contains(text);
                        hit = (acceptedSet.contains(text) || gapOnly) && !(headingLevel == 0 && isBadPlainTitle(nextTitle))
                                ? text : null;
                        if (hit != null) {
                            int depth = hit.split("\\.").length;
                            String rule = gapOnly ? RULE_OCR_REPAIRED
                                    : (headingLevel == 0 ? RULE_PROMOTED : pickFormRule(headingLevel, text, splitNums));
                            emitClause(result, hit, nextTitle, depth, rule, stack);
                            lastChapter = updateLastChapter(stack, lastChapter);
                            bodyStarted = true;
                            lineIdx = j;
                            accepted = true;
                        }
                    } else {
                        hit = tryAcceptBareNumber(text, nextTitle, headingLevel, acceptedSet, gapSet);
                        if (hit != null) {
                            int depth = hit.split("\\.").length;
                            boolean decoded = !hit.equals(text);
                            String rule = decoded || !acceptedSet.contains(hit) ? RULE_OCR_REPAIRED
                                    : (headingLevel == 0 ? RULE_PROMOTED : pickFormRule(headingLevel, text, splitNums));
                            emitClause(result, hit, nextTitle, depth, rule, stack);
                            lastChapter = updateLastChapter(stack, lastChapter);
                            bodyStarted = true;
                            lineIdx = j; // 消费标题行
                            accepted = true;
                        }
                    }
                }
                if (!accepted) {
                    if (bodyStarted) {
                        result.lines.add(text);
                    } else {
                        result.lines.add(rawLine);
                    }
                }
                continue;
            }

            // 数字候选
            String dottedNum = null;
            String title = null;
            Matcher dottedM = DOTTED_WITH_TITLE_PATTERN.matcher(text);
            Matcher bareM = BARE_WITH_TITLE_PATTERN.matcher(text);
            boolean bareCandidate = false;
            if (dottedM.matches()) {
                dottedNum = dottedM.group(1);
                title = dottedM.group(2).trim();
            } else if (bareM.matches()) {
                dottedNum = bareM.group(1);
                title = bareM.group(2).trim();
                bareCandidate = true;
            }

            if (dottedNum != null && !inAppendix) {
                Set<String> accepted = buildAcceptedSet(stack, lastChapter, false);
                Set<String> gapAccepted = buildGapSet(stack, lastChapter);

                if (!bareCandidate) {
                    // 带点编号：直接查合法集合
                    if (accepted.contains(dottedNum)) {
                        int depth = dottedNum.split("\\.").length;
                        // 正文行提升需要标题 sanity 检查
                        if (headingLevel == 0 && isBadPlainTitle(title)) {
                            result.lines.add(text);
                            continue;
                        }
                        emitClause(result, dottedNum, title, depth,
                                pickFormRule(headingLevel, dottedNum, splitNums), stack);
                        lastChapter = updateLastChapter(stack, lastChapter);
                        bodyStarted = true;
                        continue;
                    }
                    if (gapAccepted.contains(dottedNum)) {
                        int depth = dottedNum.split("\\.").length;
                        if (headingLevel == 0 && isBadPlainTitle(title)) {
                            result.lines.add(text);
                            continue;
                        }
                        emitClause(result, dottedNum, title, depth, RULE_PROMOTED, stack);
                        lastChapter = updateLastChapter(stack, lastChapter);
                        bodyStarted = true;
                        continue;
                    }
                } else {
                    // 裸数字串：枚举插点解码，命中合法集合才接受
                    String hit = tryAcceptBareNumber(dottedNum, title, headingLevel, accepted, gapAccepted);
                    if (hit != null) {
                        int depth = hit.split("\\.").length;
                        boolean decoded = !hit.equals(dottedNum);
                        // 跳档命中或解码修复：低置信度，提醒人工重点核对
                        String rule = decoded || !accepted.contains(hit)
                                ? RULE_OCR_REPAIRED : pickFormRule(headingLevel, dottedNum, splitNums);
                        emitClause(result, hit, title, depth, rule, stack);
                        lastChapter = updateLastChapter(stack, lastChapter);
                        bodyStarted = true;
                        continue;
                    }
                    // 未命中
                    if (bodyStarted) {
                        result.lines.add(text);
                    } else {
                        result.lines.add(rawLine);
                    }
                    continue;
                }
            }

            // 未命中：正文区标题降级为普通文本；正文前原样保留（标准名/日期等信息用）
            if (bodyStarted) {
                result.lines.add(text);
            } else {
                result.lines.add(rawLine);
            }
        }
        return result;
    }

    /** 计算"序列上合法的下一编号"集合 */
    private Set<String> buildAcceptedSet(Deque<List<String>> stack, int lastChapter, boolean inAppendix) {
        Set<String> accepted = new HashSet<>();
        if (inAppendix) {
            if (!stack.isEmpty()) {
                List<String> top = stack.peek();
                accepted.add(String.join(".", childOf(top)));
            }
            for (List<String> seg : stack) {
                accepted.add(String.join(".", incrementLast(seg)));
            }
            return accepted;
        }
        accepted.add(String.valueOf(lastChapter + 1));
        if (!stack.isEmpty()) {
            accepted.add(String.join(".", childOf(stack.peek())));
        }
        for (List<String> seg : stack) {
            accepted.add(String.join(".", incrementLast(seg)));
        }
        return accepted;
    }

    /** 跳档容忍集合（兄弟位 +2，仅用于低置信度兜底；不含章级跳档） */
    private Set<String> buildGapSet(Deque<List<String>> stack, int lastChapter) {
        Set<String> gap = new HashSet<>();
        for (List<String> seg : stack) {
            if (seg.size() >= 2) {
                List<String> copy = new ArrayList<>(seg);
                int last = parseIntSafe(copy.get(copy.size() - 1)) + 2;
                copy.set(copy.size() - 1, String.valueOf(last));
                gap.add(String.join(".", copy));
            }
        }
        return gap;
    }

    /** 枚举裸数字串的所有插点解码（段长 1-2 位）：316 → {316, 3.16, 31.6, 3.1.6} */
    private List<String> enumerateDecodings(String digits) {
        List<String> out = new ArrayList<>();
        enumerate(digits, 0, new ArrayList<>(), out);
        return out;
    }

    private void enumerate(String digits, int pos, List<String> segments, List<String> out) {
        if (pos >= digits.length()) {
            if (!segments.isEmpty()) {
                out.add(String.join(".", segments));
            }
            return;
        }
        // 段长 1 或 2，最多 4 段
        for (int len = 1; len <= 2 && pos + len <= digits.length() && segments.size() < 4; len++) {
            segments.add(digits.substring(pos, pos + len));
            enumerate(digits, pos + len, segments, out);
            segments.remove(segments.size() - 1);
        }
    }

    /** 输出一个规范化标题行并推进游标 */
    private void emitClause(NormalizeResult result, String path, String title, int depth,
                            String rule, Deque<List<String>> stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("#".repeat(Math.max(1, Math.min(depth, 6))));
        sb.append(' ').append(path);
        if (title != null && !title.isEmpty()) {
            sb.append(' ').append(title);
        }
        result.lines.add(sb.toString());
        result.rules.put(path, rule);

        // 推进游标：弹出所有 depth >= 当前 depth 的层，再压入当前路径
        List<String> seg = new ArrayList<>(Arrays.asList(path.split("\\.")));
        while (!stack.isEmpty() && stack.peek().size() >= depth) {
            stack.pop();
        }
        stack.push(seg);
    }

    /**
     * 根据原文形态选择规则：分行合并 > 正文提升 > 标题直接命中。
     * 标题行 `#` 数量与编号层级不符不算不确定（仅标记风格差异），仍记 HEADING_OK。
     */
    private String pickFormRule(int headingLevel, String rawNum, Set<String> splitNums) {
        if (headingLevel > 0 && splitNums.contains(rawNum)) {
            return RULE_SPLIT_JOINED;
        }
        return headingLevel == 0 ? RULE_PROMOTED : RULE_HEADING_OK;
    }

    /**
     * 裸数字串尝试接受：枚举插点解码，命中"序列合法集合"（或跳档容忍集合）才返回规范编号。
     * 章级裸数字只接受标题行；正文提升需过标题 sanity 检查。未命中返回 null。
     */
    private String tryAcceptBareNumber(String digits, String title, int headingLevel,
                                       Set<String> accepted, Set<String> gapAccepted) {
        List<String> decodings = enumerateDecodings(digits);
        String hit = null;
        for (String d : decodings) {
            if (accepted.contains(d)) { hit = d; break; }
        }
        if (hit == null) {
            for (String d : decodings) {
                if (gapAccepted.contains(d)) { hit = d; break; }
            }
        }
        if (hit == null) {
            return null;
        }
        int depth = hit.split("\\.").length;
        // 章级裸数字只接受标题行；正文里的 "6 个月内" 永远不提升
        if (depth == 1 && headingLevel == 0) {
            return null;
        }
        if (headingLevel == 0 && isBadPlainTitle(title)) {
            return null;
        }
        return hit;
    }

    /** 正文行提升时的标题 sanity 检查 */
    private boolean isBadPlainTitle(String title) {
        return title == null || title.length() < 2 || BAD_PLAIN_TITLE_START.matcher(title).find();
    }

    /** 更新最大章号 */
    private int updateLastChapter(Deque<List<String>> stack, int lastChapter) {
        if (!stack.isEmpty()) {
            List<String> top = stack.peek();
            if (top.size() == 1) {
                int ch = parseIntSafe(top.get(0));
                return Math.max(lastChapter, ch);
            }
        }
        return lastChapter;
    }

    private List<String> incrementLast(List<String> segments) {
        List<String> copy = new ArrayList<>(segments);
        copy.set(copy.size() - 1, String.valueOf(parseIntSafe(copy.get(copy.size() - 1)) + 1));
        return copy;
    }

    private List<String> childOf(List<String> segments) {
        List<String> copy = new ArrayList<>(segments);
        copy.add("1");
        return copy;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 候选匹配前的轻量清洗：全角点/全角空格 → 半角 */
    private String sanitize(String s) {
        return s.replace('．', '.').replace('　', ' ');
    }

    // ==================== 阶段二：严格读树 ====================

    /**
     * 按契约严格读规范 md 成树：标题行即条款（深度=编号段数），其余一切行归入当前条款文本。
     */
    private List<GbClauseNode> buildTree(List<String> lines, Map<String, String> rules,
                                         List<GbClauseNode> allClauses) {
        List<GbClauseNode> roots = new ArrayList<>();
        Map<String, GbClauseNode> byPath = new LinkedHashMap<>();
        GbClauseNode current = null;
        StringBuilder currentText = new StringBuilder();
        boolean bodyStarted = false;
        boolean finished = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (current != null) {
                    currentText.append("\n");
                }
                continue;
            }

            Matcher headingM = HEADING_PATTERN.matcher(line);
            boolean isHeading = headingM.matches() && !headingM.group(2).isEmpty();
            String text = isHeading ? headingM.group(2).trim() : line;

            if (isHeading && text.contains(REFERENCE_LIST_KEYWORD)) {
                finished = true;
            }
            if (finished) {
                continue;
            }

            if (isHeading) {
                // 附录
                Matcher appendixM = APPENDIX_HEAD_PATTERN.matcher(text);
                if (appendixM.matches()) {
                    if (!bodyStarted) {
                        // 正文前的残留目录附录标题 → 忽略
                        continue;
                    }
                    flushClause(current, currentText, allClauses);
                    String letter = appendixM.group(1);
                    String nature = appendixM.group(2) == null ? "" : appendixM.group(2).trim();
                    String title = appendixM.group(3) == null ? "" : appendixM.group(3).trim();
                    current = GbClauseNode.builder()
                            .clausePath(letter)
                            .parentPath(null)
                            .depth(1)
                            .title("附录 " + letter + (nature.isEmpty() ? "" : "（" + nature + "）")
                                    + (title.isEmpty() ? "" : " " + title))
                            .clauseType(nature.contains("规范性") ? "normative_appendix" : "informative_appendix")
                            .confidence(confidenceOf(rules.get(letter)))
                            .parseRule(rules.getOrDefault(letter, RULE_APPENDIX))
                            .isAppendix(true)
                            .appendixLabel(letter)
                            .build();
                    attach(roots, byPath, current);
                    bodyStarted = true;
                    continue;
                }

                // 数字条款
                Matcher dottedM = DOTTED_WITH_TITLE_PATTERN.matcher(text);
                Matcher bareM = BARE_WITH_TITLE_PATTERN.matcher(text);
                Matcher numOnlyM = NUMBER_ONLY_PATTERN.matcher(text);
                String num = null;
                String title = "";
                if (dottedM.matches()) {
                    num = dottedM.group(1);
                    title = dottedM.group(2).trim();
                } else if (bareM.matches()) {
                    num = bareM.group(1);
                    title = bareM.group(2).trim();
                } else if (numOnlyM.matches()) {
                    num = text;
                }
                if (num != null) {
                    if (!bodyStarted && !"1".equals(num)) {
                        // 正文开始前的残留目录数字标题（如 "# 11 xxx 22"）→ 忽略
                        continue;
                    }
                    flushClause(current, currentText, allClauses);
                    int depth = num.split("\\.").length;
                    String parentPath = depth > 1 ? num.substring(0, num.lastIndexOf('.')) : null;
                    String rule = rules.get(num);
                    current = GbClauseNode.builder()
                            .clausePath(num)
                            .parentPath(parentPath)
                            .depth(depth)
                            .title(title)
                            .clauseType(determineClauseType(num, title))
                            .confidence(confidenceOf(rule))
                            .parseRule(rule != null ? rule : RULE_HEADING_OK)
                            .isScope("1".equals(num) || title.contains(SCOPE_KEYWORD))
                            .isAppendix(false)
                            .build();
                    attach(roots, byPath, current);
                    bodyStarted = true;
                    continue;
                }

                // 附录内条款（用户在规范 md 里手写的 ## A.1）
                Matcher acM = APPENDIX_CLAUSE_PATTERN.matcher(text);
                if (acM.matches()) {
                    flushClause(current, currentText, allClauses);
                    String path = acM.group(1) + "." + acM.group(2);
                    int depth = acM.group(2).split("\\.").length + 1;
                    String parentPath = depth > 2 ? path.substring(0, path.lastIndexOf('.')) : acM.group(1);
                    String rule = rules.get(path);
                    current = GbClauseNode.builder()
                            .clausePath(path)
                            .parentPath(parentPath)
                            .depth(depth)
                            .title(acM.group(3).trim())
                            .clauseType("normative")
                            .confidence(confidenceOf(rule))
                            .parseRule(rule != null ? rule : RULE_HEADING_OK)
                            .isAppendix(true)
                            .appendixLabel(acM.group(1))
                            .build();
                    attach(roots, byPath, current);
                    bodyStarted = true;
                    continue;
                }

                // 正文区无法识别的标题 → 当普通文本归入当前条款
                if (bodyStarted && current != null) {
                    currentText.append(text).append("\n");
                }
                continue;
            }

            // 普通行：归入当前条款
            if (current != null) {
                currentText.append(line).append("\n");
            }
        }
        flushClause(current, currentText, allClauses);
        return roots;
    }

    /** 把节点挂到父节点（父路径缺失时挂到根，保底不丢条款） */
    private void attach(List<GbClauseNode> roots, Map<String, GbClauseNode> byPath, GbClauseNode node) {
        byPath.put(node.getClausePath(), node);
        String parentPath = node.getParentPath();
        if (parentPath != null && byPath.containsKey(parentPath)) {
            byPath.get(parentPath).getChildren().add(node);
        } else {
            roots.add(node);
        }
    }

    /** 规则 → 置信度 */
    private String confidenceOf(String rule) {
        if (rule == null) {
            return "high";
        }
        return switch (rule) {
            case RULE_HEADING_OK, RULE_APPENDIX -> "high";
            case RULE_SPLIT_JOINED, RULE_PROMOTED -> "medium";
            default -> "low"; // OCR_REPAIRED 及其他
        };
    }

    // ==================== 标准基本信息提取 ====================

    /**
     * 从文档头部（前30行）提取标准号、版本、日期、替代关系、全称；从第2章提取规范性引用文件。
     */
    private void extractStandardInfo(List<String> lines, GbDocStructure.GbDocStructureBuilder builder) {
        int scanLimit = Math.min(lines.size(), 30);
        StringBuilder headerBuilder = new StringBuilder();
        for (int i = 0; i < scanLimit; i++) {
            headerBuilder.append(lines.get(i)).append("\n");
        }
        String header = headerBuilder.toString();

        extractStandardNo(header, builder);

        Matcher dateMatcher = DATE_PATTERN.matcher(header);
        while (dateMatcher.find()) {
            if ("发布".equals(dateMatcher.group(2))) {
                builder.publishDate(dateMatcher.group(1));
            } else {
                builder.implementationDate(dateMatcher.group(1));
            }
        }

        List<String> supersedes = new ArrayList<>();
        Matcher supMatcher = SUPERSEDES_PATTERN.matcher(header);
        while (supMatcher.find()) {
            supersedes.add(supMatcher.group(1).replaceAll("\\s+", " ").trim());
        }
        builder.supersedes(supersedes);

        // 标准全称：第一个非特殊、非英文、非条款、非附录的标题行
        for (int i = 0; i < scanLimit; i++) {
            String body = headingBody(lines.get(i));
            if (body == null) {
                continue;
            }
            String title = body.trim();
            if (title.isEmpty() || isSkippableHeading(title)) {
                continue;
            }
            if (title.matches("^[A-Za-z\\s,;:—–·()]+$") && title.length() < 60) {
                continue;
            }
            if (title.matches("^\\d+(\\.\\d+)*\\s+.*")) {
                continue;
            }
            if (title.startsWith("附录")) {
                continue;
            }
            builder.fullName(title);
            break;
        }

        builder.normativeRefs(extractNormativeRefs(lines));
    }

    private void extractStandardNo(String header, GbDocStructure.GbDocStructureBuilder builder) {
        // 跳过 GB/T 1.1（每份国标前言都会引用的《标准化工作导则》，不是本标准号）
        Matcher stdMatcher = STANDARD_NO_PATTERN.matcher(header);
        String foundNo = null;
        String foundYear = null;
        while (stdMatcher.find()) {
            String no = stdMatcher.group(1).replaceAll("\\s+", " ").trim();
            if ("GB/T 1.1".equals(no)) {
                continue;
            }
            foundNo = no;
            foundYear = stdMatcher.group(2);
            break;
        }
        if (foundNo == null) {
            return;
        }
        builder.standardNo(foundNo);
        // 版本优先取发布日期年份：标准号可能来自前言的"代替 GB xxxx—旧年"，后缀年是旧版的
        Matcher dateMatcher = DATE_PATTERN.matcher(header);
        String publishYear = null;
        String anyYear = null;
        while (dateMatcher.find()) {
            if (anyYear == null) {
                anyYear = dateMatcher.group(1).substring(0, 4);
            }
            if ("发布".equals(dateMatcher.group(2))) {
                publishYear = dateMatcher.group(1).substring(0, 4);
                break;
            }
        }
        if (publishYear != null) {
            builder.version(publishYear);
        } else if (foundYear != null) {
            builder.version(foundYear);
        } else if (anyYear != null) {
            builder.version(anyYear);
        }
    }

    /**
     * 从第2章（规范性引用文件）提取引用的标准号列表；区间限定在该章内。
     */
    private List<String> extractNormativeRefs(List<String> lines) {
        List<String> refs = new ArrayList<>();
        boolean inSection = false;
        for (String rawLine : lines) {
            String body = headingBody(rawLine);
            String trimmed = (body != null ? body : rawLine.trim());

            if (trimmed.contains(NORMATIVE_REF_KEYWORD)) {
                inSection = true;
                continue;
            }
            if (inSection) {
                // 离开区间：遇到下一个章级标题
                if (body != null && trimmed.matches("^\\d{1,2}[\\s　]+.*") && !trimmed.contains("GB")) {
                    break;
                }
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

    // ==================== 辅助方法 ====================

    /** 是标题行则返回去掉 # 的正文，否则返回 null */
    private String headingBody(String line) {
        Matcher m = HEADING_PATTERN.matcher(line.trim());
        if (m.matches() && !m.group(2).isEmpty()) {
            return m.group(2).trim();
        }
        return null;
    }

    /** 去掉行首的 # 号 */
    private String stripHashes(String line) {
        return line.trim().replaceFirst("^#{1,6}\\s*", "");
    }

    private void flushClause(GbClauseNode clause, StringBuilder text, List<GbClauseNode> allClauses) {
        if (clause != null) {
            String clauseText = text.toString().trim().replaceAll("\n{3,}", "\n\n");
            clause.setText(clauseText);
            allClauses.add(clause);
        }
        text.setLength(0);
    }

    private String determineClauseType(String clauseNum, String title) {
        if ("1".equals(clauseNum) || title.contains(SCOPE_KEYWORD)) {
            return "scope";
        }
        if ("2".equals(clauseNum) || title.contains(NORMATIVE_REF_KEYWORD)) {
            return "reference";
        }
        if ("3".equals(clauseNum) || title.contains(TERMS_KEYWORD)) {
            return "definition";
        }
        if (title.contains("试验方法") || title.contains("试验") || title.contains("测试")) {
            return "test_method";
        }
        return "normative";
    }

    private boolean isSkippableHeading(String title) {
        if (title == null) {
            return false;
        }
        for (String skip : SKIPPABLE_HEADINGS) {
            if (title.contains(skip)) {
                return true;
            }
        }
        return false;
    }
}
//update-end---author:song ---date:2026-07-14  for：【GB知识引擎】Phase 1 文档层级树解析器-----------
