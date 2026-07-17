//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationTool 构建（ToolSpecification 模式，不用 @Tool）-----------
package org.jeecg.modules.airag.llm.gbstandard.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.service.GbStandardResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GbCalculationTool builder.
 * <p>Uses project's existing ToolSpecification + ToolExecutor pattern (NOT @Tool).
 * Tool query_gb_parameter: queries gb_parameter, prefers static param_value;
 * if variable substitution needed, uses SafeArithmeticEvaluator. Domain-agnostic.
 * </p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class GbCalculationToolBuilder {

    @Autowired private GbStandardResolver standardResolver;
    @Autowired private GbParameterRepository parameterRepository;
    @Autowired private SafeArithmeticEvaluator arithmeticEvaluator;
    @Autowired private ObjectMapper objectMapper;

    public Map<ToolSpecification, ToolExecutor> buildTools() {
        Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();

        ToolSpecification spec = ToolSpecification.builder()
                .name("query_gb_parameter")
                .description("查询国标中的技术参数值并按公式计算。优先返回静态参数值；需变量代入时用安全算术计算。禁止自行估算数值。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("standardNo", "标准号，如 GB 31241")
                        .addStringProperty("paramName", "参数名，如 overcharge_threshold")
                        .addStringProperty("variables", "变量值 JSON，如 {\"n\":3}；无变量传空")
                        .required("standardNo", "paramName")
                        .build())
                .build();

        ToolExecutor executor = (toolExecutionRequest, memoryId) -> {
            try {
                JsonNode args = objectMapper.readTree(toolExecutionRequest.arguments());
                String standardNo = args.path("standardNo").asText(null);
                String paramName = args.path("paramName").asText(null);
                String variablesJson = args.path("variables").asText("");

                Optional<String> stdIdOpt = standardResolver.resolveStandardId(standardNo);
                if (stdIdOpt.isEmpty()) return "未找到标准: " + standardNo;
                GbParameter param = parameterRepository.findByName(stdIdOpt.get(), paramName);
                if (param == null) return "未找到参数: " + paramName;

                // 1. prefer static value
                if (param.getParamValue() != null) {
                    return formatResult(param, param.getParamValue().toPlainString());
                }
                // 2. variable substitution via safe arithmetic
                if (param.getFormula() != null && !param.getFormula().isBlank()) {
                    Map<String, BigDecimal> vars = parseVariables(variablesJson);
                    Optional<BigDecimal> calc = arithmeticEvaluator.evaluate(param.getFormula(), vars);
                    if (calc.isPresent()) return formatResult(param, calc.get().toPlainString());
                    return "公式计算失败（变量不足或公式非法）: " + param.getFormula();
                }
                return "参数无值且无公式: " + paramName;
            } catch (Exception e) {
                return "工具执行异常: " + e.getMessage();
            }
        };

        tools.put(spec, executor);
        return tools;
    }

    private Map<String, BigDecimal> parseVariables(String json) {
        Map<String, BigDecimal> vars = new HashMap<>();
        if (json == null || json.isBlank()) return vars;
        try {
            JsonNode node = objectMapper.readTree(json);
            node.fields().forEachRemaining(e -> {
                try { vars.put(e.getKey(), new BigDecimal(e.getValue().asText())); }
                catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        return vars;
    }

    private String formatResult(GbParameter param, String value) {
        String unit = param.getUnit() != null ? param.getUnit() : "";
        String formulaNote = param.getFormula() != null && !param.getFormula().isBlank()
                ? "（公式: " + param.getFormula() + "）" : "（静态值）";
        return value + " " + unit + formulaNote + "（来源：gb_parameter）";
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】GbCalculationTool 构建-----------
