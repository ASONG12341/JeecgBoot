//update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数检索通道（查 gb_parameter，PARAM_QUERY 路由或 quantityValue 槽位触发）-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.query.QueryIntent;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 参数检索通道
 * <p>
 * 核心职责：针对参数/公式/量值类查询，直接命中 gb_parameter 结构化数据。
 * 与 StructureChannel 的差异：StructureChannel 是通用结构化通道（条款树 + 参数），
 * 本通道专注参数表，路由意图=PARAM_QUERY 或 QueryIntent.quantityValue 非空时激活，
 * 在加权融合中以独立通道参与（权重由 QueryPlanner 配置）。
 * </p>
 *
 * @author song
 * @date 2026-07-16
 */
@Slf4j
@Component("paramChannel")
public class ParamChannel implements RetrievalChannel {

    @Autowired
    private GbParameterRepository parameterRepository;

    @Override
    public String getChannelName() {
        return "PARAM";
    }

    @Override
    public List<RetrievalResult> search(RetrievalRequest request) {
        log.info("[ParamChannel] 开始参数检索, query={}, standardId={}",
                request.getQuery(), request.getStandardId());

        try {
            List<GbParameter> parameters = new ArrayList<>();

            // 优先：客户端显式传入 parameterName
            String paramName = request.getParameterName();

            // 次选：从 QueryIntent 槽位推导参数名（secondaryType/objectType 描述"对谁"，常映射到参数名）
            QueryIntent qi = request.getQueryIntent();
            if (!StringUtils.hasText(paramName) && qi != null) {
                paramName = firstNonBlank(qi.getSecondaryType(), qi.getObjectType());
            }

            String standardId = request.getStandardId();

            if (StringUtils.hasText(standardId) && StringUtils.hasText(paramName)) {
                // 按标准 + 参数名模糊查
                List<GbParameter> byName = parameterRepository.searchByName(standardId, paramName);
                if (byName != null) {
                    parameters.addAll(byName);
                }
            } else if (StringUtils.hasText(standardId) && qi != null && qi.getQuantityValue() != null) {
                // 有量值槽位但无参数名：退化为查询该标准下所有参数，交给上层按量值裁剪
                // （gb_parameter 表无按量值匹配的现成 repo 方法，这里召回候选，避免空结果）
                List<GbParameter> all = parameterRepository.findByStandardId(standardId);
                if (all != null) {
                    parameters.addAll(all);
                }
            } else if (StringUtils.hasText(paramName)) {
                // 无 standardId：跨标准按参数名查（searchByName 允许 standardId 为 null）
                List<GbParameter> byName = parameterRepository.searchByName(null, paramName);
                if (byName != null) {
                    parameters.addAll(byName);
                }
            }

            List<RetrievalResult> results = parameters.stream()
                    .map(this::convertParameterToResult)
                    .collect(Collectors.toList());

            log.info("[ParamChannel] 参数检索完成, 结果数={}", results.size());
            return results;

        } catch (Exception e) {
            log.error("[ParamChannel] 参数检索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isAvailable() {
        return parameterRepository != null;
    }

    /**
     * 适用条件：路由意图=PARAM_QUERY 或 QueryIntent.quantityValue 槽位非空。
     * 两层意图任一命中即激活，覆盖"用户口语提到数值"但规则路由未判为 PARAM_QUERY 的情况。
     */
    @Override
    public boolean isApplicable(RetrievalRequest request) {
        if (!isAvailable()) {
            return false;
        }
        String intent = request.getIntent();
        if ("PARAM_QUERY".equalsIgnoreCase(intent)) {
            return true;
        }
        QueryIntent qi = request.getQueryIntent();
        return qi != null && qi.getQuantityValue() != null;
    }

    @Override
    public double getWeight(RetrievalRequest request) {
        // 真实权重由 QueryPlanner 按意图配置（weightedFuse 读取 plan.getChannelWeights()）。
        // 此处返回请求级启用标志（默认开启），仅用于非加权路径的兜底。
        return 0.5;
    }

    /**
     * 将 GbParameter 转换为 RetrievalResult（sourceChannel=PARAM）。
     */
    private RetrievalResult convertParameterToResult(GbParameter parameter) {
        RetrievalResult result = new RetrievalResult();

        result.setResultId(parameter.getId());
        result.setStandardId(parameter.getStandardId());
        result.setClauseId(parameter.getClauseId());
        result.setSourceChannel("PARAM");

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append("参数名称：").append(parameter.getParamName());

        if (parameter.getParamValue() != null) {
            textBuilder.append("\n参数值：").append(parameter.getParamValue());
            if (StringUtils.hasText(parameter.getUnit())) {
                textBuilder.append(" ").append(parameter.getUnit());
            }
        }

        if (StringUtils.hasText(parameter.getFormula())) {
            textBuilder.append("\n计算公式：").append(parameter.getFormula());
        }

        if (StringUtils.hasText(parameter.getConditionExpr())) {
            textBuilder.append("\n条件：").append(parameter.getConditionExpr());
        }

        result.setText(textBuilder.toString());
        result.setTitle(parameter.getParamName());

        // 参数通道默认给较高分数（结构化精确命中）
        result.setScore(0.88);

        // 结构化参数明细（与 StructureChannel 保持一致的 parameters 结构，便于前端复用）
        List<Map<String, Object>> parameters = new ArrayList<>();
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("paramName", parameter.getParamName());
        paramMap.put("paramValue", parameter.getParamValue());
        paramMap.put("unit", parameter.getUnit());
        paramMap.put("formula", parameter.getFormula());
        paramMap.put("conditionExpr", parameter.getConditionExpr());
        parameters.add(paramMap);
        result.setParameters(parameters);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("paramId", parameter.getId());
        metadata.put("clauseId", parameter.getClauseId());
        result.setMetadata(metadata);

        return result;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }
}
//update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】参数检索通道-----------
