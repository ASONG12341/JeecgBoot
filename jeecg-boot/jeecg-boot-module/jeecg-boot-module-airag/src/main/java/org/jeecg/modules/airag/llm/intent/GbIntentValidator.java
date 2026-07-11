//update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentValidator 通用化：testType 不再限制固定枚举，仅做归一化清洗；objectType 仍限制为 cell/pack/system-----------
package org.jeecg.modules.airag.llm.intent;

import org.jeecg.modules.airag.common.handler.GbQueryIntent;

import java.util.Set;

/**
 * GB 国标查询意图字段校验器。
 *
 * <p>对 {@link GbQueryIntent} 中的字段就地（in-place）清洗：
 * - testType：归一化为小写 snake_case，非法值置 null，不再限定具体枚举（支持后续新增任意国标）
 * - objectType：仅允许 cell / pack / system，非法值置 null
 * 只要 7 个字段中至少有一个非 null 字段即返回 true，否则返回 false。</p>
 *
 * @author song-claude
 * @date 2026-07-11
 */
public class GbIntentValidator {

    /**
     * 合法对象类型枚举。
     */
    private static final Set<String> VALID_OBJECT_TYPES = Set.of(
            "cell",
            "pack",
            "system"
    );

    private GbIntentValidator() {
        // 工具类禁止实例化
    }

    /**
     * 校验并清洗意图对象中的字段。
     *
     * @param intent 待校验的 GB 查询意图；为 null 时直接返回 false
     * @return true 表示至少存在一个有效字段；false 表示所有字段均为 null 或 intent 为 null
     */
    public static boolean validate(GbQueryIntent intent) {
        if (intent == null) {
            return false;
        }

        boolean anyValid = false;

        String gbStandard = intent.getGbStandard();
        if (gbStandard != null) {
            gbStandard = gbStandard.trim();
            if (!gbStandard.isEmpty()) {
                intent.setGbStandard(gbStandard);
                anyValid = true;
            } else {
                intent.setGbStandard(null);
            }
        }

        String testType = intent.getTestType();
        if (testType != null) {
            testType = normalizeTestType(testType);
            if (!testType.isEmpty()) {
                intent.setTestType(testType);
                anyValid = true;
            } else {
                intent.setTestType(null);
            }
        }

        if (intent.getNCells() != null) {
            anyValid = true;
        }

        String objectType = intent.getObjectType();
        if (objectType != null && VALID_OBJECT_TYPES.contains(objectType)) {
            anyValid = true;
        } else {
            intent.setObjectType(null);
        }

        String inferredChapter = intent.getInferredChapter();
        if (inferredChapter != null) {
            inferredChapter = inferredChapter.trim();
            if (!inferredChapter.isEmpty()) {
                intent.setInferredChapter(inferredChapter);
                anyValid = true;
            } else {
                intent.setInferredChapter(null);
            }
        }

        String environmentCondition = intent.getEnvironmentCondition();
        if (environmentCondition != null) {
            environmentCondition = environmentCondition.trim();
            if (!environmentCondition.isEmpty()) {
                intent.setEnvironmentCondition(environmentCondition);
                anyValid = true;
            } else {
                intent.setEnvironmentCondition(null);
            }
        }

        if (intent.getIsBooleanQuery() != null) {
            anyValid = true;
        }

        return anyValid;
    }

    /**
     * 将 testType 归一化为小写 snake_case。
     */
    private static String normalizeTestType(String testType) {
        return testType.trim()
                .toLowerCase()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }
}
//update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】GbIntentValidator 通用化：testType 不再限制固定枚举，仅做归一化清洗；objectType 仍限制为 cell/pack/system-----------
