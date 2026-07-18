//update-begin---author:song ---date:2026-07-18  for：【GB-RAG v4】结构解析器两阶段重写回归测试（三份真实 MinerU 样例为夹具）-----------
package org.jeecg.modules.airag.llm.gbstandard.ingestion;

import org.jeecg.modules.airag.llm.gbstandard.model.GbClauseNode;
import org.jeecg.modules.airag.llm.gbstandard.model.GbDocStructure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GbDocumentStructureParser 回归测试。
 * <p>
 * 夹具是 docs/cankao/ 下三份真实 MinerU 输出，代表三种解析质量：
 * GB 31241（全 # 平级 + 编号/标题分行）、GB/T 34131（## 规范层级）、
 * GB 38031（OCR 严重退化，丢失小数点）。
 * 断言解析器按 GB/T 1.1 编号语法 + 序列连续性把它们统一解析为正确条款树。
 * </p>
 */
class GbDocumentStructureParserTest {

    private static final String CANKAO_DIR = "docs/cankao";

    private static GbDocStructure gb31241;
    private static GbDocStructure gbt34131;
    private static GbDocStructure gb38031;

    @BeforeAll
    static void setUp() throws IOException {
        GbDocumentStructureParser parser = new GbDocumentStructureParser();
        gb31241 = parser.parse(readSample("MinerU_markdown_GB+31241-2022_2076854834143617024.md"));
        gbt34131 = parser.parse(readSample("MinerU_markdown_GBT+34131-2023_2076854854825734144.md"));
        gb38031 = parser.parse(readSample("MinerU_markdown_GB+38031-2025_2076854803734913024.md"));
    }

    private static String readSample(String fileName) throws IOException {
        Path path = Paths.get(CANKAO_DIR, fileName);
        if (!Files.exists(path)) {
            // 兼容从仓库根目录运行的情况
            path = Paths.get("jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag", CANKAO_DIR, fileName);
        }
        return Files.readString(path);
    }

    /** 拍平条款树：clausePath → 节点（保留顺序） */
    private static Map<String, GbClauseNode> flatten(GbDocStructure structure) {
        Map<String, GbClauseNode> map = new LinkedHashMap<>();
        for (GbClauseNode root : structure.getClauses()) {
            flattenInto(root, map);
        }
        return map;
    }

    private static void flattenInto(GbClauseNode node, Map<String, GbClauseNode> map) {
        map.put(node.getClausePath(), node);
        for (GbClauseNode child : node.getChildren()) {
            flattenInto(child, map);
        }
    }

    private static void assertNoDuplicatePaths(GbDocStructure structure) {
        Set<String> seen = new HashSet<>();
        for (GbClauseNode root : structure.getClauses()) {
            assertNoDuplicate(root, seen);
        }
    }

    private static void assertNoDuplicate(GbClauseNode node, Set<String> seen) {
        assertThat(seen.add(node.getClausePath()))
                .as("条款路径重复: %s", node.getClausePath())
                .isTrue();
        for (GbClauseNode child : node.getChildren()) {
            assertNoDuplicate(child, seen);
        }
    }

    private static void assertChapters(GbDocStructure structure, int from, int to) {
        Map<String, GbClauseNode> flat = flatten(structure);
        for (int i = from; i <= to; i++) {
            assertThat(flat).as("应存在第 %d 章", i).containsKey(String.valueOf(i));
        }
        // 章必须挂为根节点（目录条目若被误判会在这里露出马脚）
        List<String> rootPaths = structure.getClauses().stream().map(GbClauseNode::getClausePath).toList();
        for (int i = from; i <= to; i++) {
            assertThat(rootPaths).as("第 %d 章应为根节点", i).contains(String.valueOf(i));
        }
    }

    // ==================== GB 31241（全 # 平级 + 编号/标题分行） ====================

    @Test
    void gb31241_chaptersOnceEach() {
        assertChapters(gb31241, 1, 12);
        assertNoDuplicatePaths(gb31241);
    }

    @Test
    void gb31241_splitLineTermsRecovered() {
        Map<String, GbClauseNode> flat = flatten(gb31241);
        // 原文 "# 3.1" 与 "# 锂离子电池 lithium ion cell" 分两行
        assertThat(flat).containsKey("3.1");
        assertThat(flat.get("3.1").getTitle()).contains("锂离子电池");
        assertThat(flat.get("3.1").getParseRule()).isEqualTo(GbDocumentStructureParser.RULE_SPLIT_JOINED);
    }

    @Test
    void gb31241_appendicesExist() {
        Map<String, GbClauseNode> flat = flatten(gb31241);
        for (String letter : List.of("A", "B", "C", "D", "E", "F", "G")) {
            assertThat(flat).as("应存在附录 %s", letter).containsKey(letter);
            assertThat(flat.get(letter).isAppendix()).isTrue();
        }
    }

    @Test
    void gb31241_bodyClausesExist() {
        Map<String, GbClauseNode> flat = flatten(gb31241);
        assertThat(flat).containsKeys("7.1", "7.2", "7.3", "9.2");
        assertThat(flat.get("7.1").getTitle()).contains("低气压");
        // 7.1 应挂在第 7 章下
        assertThat(flat.get("7.1").getParentPath()).isEqualTo("7");
    }

    // ==================== GB/T 34131（## 规范层级） ====================

    @Test
    void gbt34131_structureCorrect() {
        assertChapters(gbt34131, 1, 9);
        assertNoDuplicatePaths(gbt34131);
        Map<String, GbClauseNode> flat = flatten(gbt34131);
        for (int i = 1; i <= 16; i++) {
            assertThat(flat).as("应存在 6.%d", i).containsKey("6." + i);
        }
        assertThat(flat).containsKeys("6.4.1", "6.4.2", "6.4.3");
        assertThat(flat.get("6.4.1").getParentPath()).isEqualTo("6.4");
    }

    @Test
    void gbt34131_appendixClauses() {
        Map<String, GbClauseNode> flat = flatten(gbt34131);
        for (String letter : List.of("A", "B", "C", "D")) {
            assertThat(flat).as("应存在附录 %s", letter).containsKey(letter);
        }
        assertThat(flat).containsKeys("A.1", "A.2", "A.3");
        assertThat(flat.get("A.1").getParentPath()).isEqualTo("A");
    }

    // ==================== GB 38031（OCR 严重退化） ====================

    @Test
    void gb38031_chaptersExist() {
        assertChapters(gb38031, 1, 7);
        assertNoDuplicatePaths(gb38031);
    }

    @Test
    void gb38031_ocrNumbersRepaired() {
        Map<String, GbClauseNode> flat = flatten(gb38031);
        // "# 316" → 3.16，"# 41 符号" → 4.1，"# 51" → 5.1，"511 …" → 5.1.1
        assertThat(flat).containsKeys("3.16", "4.1", "4.2", "5.1", "5.2");
        assertThat(flat).containsKeys("5.1.1", "5.1.2", "5.1.3", "5.1.4", "5.1.5", "5.1.6", "5.1.7");
        assertThat(flat.get("4.1").getTitle()).contains("符号");
        // OCR 修复的条款必须标低置信度，提醒人工重点核对
        assertThat(flat.get("4.1").getConfidence()).isEqualTo("low");
        assertThat(flat.get("4.1").getParseRule()).isEqualTo(GbDocumentStructureParser.RULE_OCR_REPAIRED);
    }

    @Test
    void gb38031_noFakePaths() {
        Map<String, GbClauseNode> flat = flatten(gb38031);
        // 退化编号绝不能以假路径入库
        for (String fake : List.of("41", "42", "51", "52", "61", "316", "317", "318", "511", "512")) {
            assertThat(flat).as("不应存在假路径 %s", fake).doesNotContainKey(fake);
        }
    }

    // ==================== 通用契约 ====================

    @Test
    void normalizationIsIdempotent() {
        GbDocumentStructureParser parser = new GbDocumentStructureParser();
        for (GbDocStructure structure : List.of(gb31241, gbt34131, gb38031)) {
            String once = structure.getNormalizedMarkdown();
            assertThat(once).isNotBlank();
            // 对规范化产物再解析一次，条款路径集合必须一致
            GbDocStructure twice = parser.parse(once);
            assertThat(flatten(twice).keySet())
                    .isEqualTo(flatten(structure).keySet());
        }
    }

    @Test
    void confidenceStatsAreConsistent() {
        for (GbDocStructure structure : List.of(gb31241, gbt34131, gb38031)) {
            assertThat(structure.getTotalClauseCount()).isGreaterThan(0);
            assertThat(structure.getHighConfidenceCount()
                    + structure.getMediumConfidenceCount()
                    + structure.getLowConfidenceCount())
                    .isEqualTo(structure.getTotalClauseCount());
        }
        // GB 38031 退化严重，必须有低置信度条款提示人工核对
        assertThat(gb38031.getLowConfidenceCount()).isGreaterThan(0);
    }

    @Test
    void standardInfoExtracted() {
        assertThat(gb31241.getStandardNo()).contains("31241");
        assertThat(gbt34131.getStandardNo()).contains("34131");
        // GB 38031 封面标准号在 MinerU 输出中丢失（前 250 行无 "GB" 字样），
        // 标准号允许为空 —— 这正是确认页要人工补的字段
        if (gb38031.getStandardNo() != null) {
            assertThat(gb38031.getStandardNo()).contains("38031");
        }
    }
}
//update-end---author:song ---date:2026-07-18  for：【GB-RAG v4】结构解析器两阶段重写回归测试-----------
