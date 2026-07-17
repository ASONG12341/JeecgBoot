package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbClauseRepository;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结构化检索通道
 * <p>
 * 核心职责：
 * 1. 条款树遍历检索
 * 2. 条款编号精确匹配
 * 3. 条款标题关键词匹配
 * 4. 参数表检索
 * 5. 引用关系追溯
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component("structureChannel")
public class StructureChannel implements RetrievalChannel {

    @Autowired
    private GbClauseRepository clauseRepository;

    @Autowired
    private GbParameterRepository parameterRepository;

    @Override
    public String getChannelName() {
        return "STRUCTURE";
    }

    @Override
    public List<RetrievalResult> search(RetrievalRequest request) {
        log.info("[StructureChannel] 开始结构化检索, query={}, intent={}", 
                request.getQuery(), request.getIntent());

        try {
            List<RetrievalResult> results = new ArrayList<>();

            // 根据意图选择不同的检索策略
            String intent = request.getIntent();
            
            if ("CLAUSE_LOOKUP".equals(intent)) {
                // 条款查询
                results.addAll(searchByClauseNumber(request));
            } else if ("PARAM_QUERY".equals(intent)) {
                // 参数查询
                results.addAll(searchByParameter(request));
            } else {
                // 默认：关键词搜索
                results.addAll(searchByKeyword(request));
            }

            log.info("[StructureChannel] 结构化检索完成, 结果数={}", results.size());
            return results;

        } catch (Exception e) {
            log.error("[StructureChannel] 结构化检索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isAvailable() {
        // 结构化通道需要 Repository 可用
        return clauseRepository != null && parameterRepository != null;
    }

    @Override
    public double getWeight(RetrievalRequest request) {
        // 返回请求中配置的结构化检索权重
        return request.isEnableStructure() ? request.getStructureWeight() : 0.0;
    }

    /**
     * 按条款编号检索
     */
    private List<RetrievalResult> searchByClauseNumber(RetrievalRequest request) {
        List<RetrievalResult> results = new ArrayList<>();

        // 如果请求中指定了条款编号
        if (StringUtils.hasText(request.getClauseNumber())) {
            //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，findByClausePath 返回单条 GbClause-----------
            GbClause clause = clauseRepository.findByClausePath(
                    request.getStandardId(),
                    request.getClauseNumber()
            );
            if (clause != null) {
                results.add(convertClauseToResult(clause));
            }
            //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，findByClausePath 返回单条 GbClause-----------
        }

        // 从查询文本中提取条款编号
        String query = request.getQuery();
        if (StringUtils.hasText(query)) {
            // 提取条款编号（如 "4.1.2", "第4章" 等）
            List<String> clauseNumbers = extractClauseNumbers(query);

            for (String clauseNumber : clauseNumbers) {
                //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，findByClausePath 返回单条 GbClause-----------
                GbClause clause = clauseRepository.findByClausePath(
                        request.getStandardId(),
                        clauseNumber
                );
                if (clause != null) {
                    results.add(convertClauseToResult(clause));
                }
                //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，findByClausePath 返回单条 GbClause-----------
            }
        }

        return results;
    }

    /**
     * 按参数检索
     */
    private List<RetrievalResult> searchByParameter(RetrievalRequest request) {
        List<RetrievalResult> results = new ArrayList<>();

        //update-begin---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】StructureChannel 移除领域专属关键词提取（领域无关红线；参数名只走 request 显式传入，槽位驱动的参数查询由 ParamChannel 负责）-----------
        // 仅使用请求中显式指定的参数名；不再从 query 文本做领域关键词提取（违反领域无关原则）
        String paramName = request.getParameterName();
        //update-end---author:song ---date:2026-07-16  for：【GB-RAG v4 P3】-----------

        if (StringUtils.hasText(paramName)) {
            //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，使用 GbParameterRepository 已有的 searchByName-----------
            List<GbParameter> parameters = parameterRepository.searchByName(
                    request.getStandardId(),
                    paramName
            );
            //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，使用 GbParameterRepository 已有的 searchByName-----------

            results.addAll(parameters.stream()
                    .map(this::convertParameterToResult)
                    .collect(Collectors.toList()));
        }

        return results;
    }

    /**
     * 按关键词检索
     */
    private List<RetrievalResult> searchByKeyword(RetrievalRequest request) {
        List<RetrievalResult> results = new ArrayList<>();

        if (!StringUtils.hasText(request.getQuery())) {
            return results;
        }

        // 在条款标题和文本中搜索关键词
        List<GbClause> clauses = clauseRepository.searchByKeyword(
                request.getStandardId(),
                request.getQuery(),
                request.getTopK()
        );

        results.addAll(clauses.stream()
                .map(this::convertClauseToResult)
                .collect(Collectors.toList()));

        return results;
    }

    /**
     * 将 GbClause 转换为 RetrievalResult
     */
    private RetrievalResult convertClauseToResult(GbClause clause) {
        RetrievalResult result = new RetrievalResult();
        
        result.setResultId(clause.getId());
        result.setStandardId(clause.getStandardId());
        result.setClauseId(clause.getId());
        result.setClausePath(clause.getClausePath());
        result.setTitle(clause.getTitle());
        result.setText(clause.getText());
        result.setClauseType(clause.getClauseType());
        result.setRequirementStrength(clause.getRequirementStrength());
        result.setSourceChannel("STRUCTURE");
        
        // 设置分数（结构化检索默认给较高分数）
        result.setScore(0.85);
        
        // 设置元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("depth", clause.getDepth());
        metadata.put("parentPath", clause.getParentPath());
        metadata.put("isScope", clause.getIsScope());
        metadata.put("isAppendix", clause.getIsAppendix());
        result.setMetadata(metadata);
        
        return result;
    }

    /**
     * 将 GbParameter 转换为 RetrievalResult
     */
    private RetrievalResult convertParameterToResult(GbParameter parameter) {
        RetrievalResult result = new RetrievalResult();
        
        result.setResultId(parameter.getId());
        result.setStandardId(parameter.getStandardId());
        result.setClauseId(parameter.getClauseId());
        result.setSourceChannel("STRUCTURE");
        
        // 设置文本内容
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
        
        result.setText(textBuilder.toString());
        result.setTitle(parameter.getParamName());
        
        // 设置分数
        result.setScore(0.90);
        
        // 设置参数信息
        List<Map<String, Object>> parameters = new ArrayList<>();
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("paramName", parameter.getParamName());
        paramMap.put("paramValue", parameter.getParamValue());
        paramMap.put("unit", parameter.getUnit());
        paramMap.put("formula", parameter.getFormula());
        paramMap.put("conditionExpr", parameter.getConditionExpr());
        parameters.add(paramMap);
        result.setParameters(parameters);
        
        // 设置元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("paramId", parameter.getId());
        metadata.put("clauseId", parameter.getClauseId());
        result.setMetadata(metadata);
        
        return result;
    }

    /**
     * 从查询文本中提取条款编号
     */
    private List<String> extractClauseNumbers(String query) {
        List<String> clauseNumbers = new ArrayList<>();
        
        if (!StringUtils.hasText(query)) {
            return clauseNumbers;
        }

        // 匹配 "4.1.2", "第4章", "附录A" 等格式
        Pattern pattern = Pattern.compile(
                "(\\d+\\.\\d+(\\.\\d+)?)|" +
                "(第[一二三四五六七八九十百千万]+[章节条])|" +
                "(附录[A-Z])",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            clauseNumbers.add(matcher.group());
        }

        return clauseNumbers;
    }
}