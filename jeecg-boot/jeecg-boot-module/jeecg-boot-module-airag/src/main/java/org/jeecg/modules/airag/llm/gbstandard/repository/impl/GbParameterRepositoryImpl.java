//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbParameterRepository实现-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbParameterMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbParameter;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbParameterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 国标参数仓库实现
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Repository
public class GbParameterRepositoryImpl implements GbParameterRepository {

    @Autowired
    private GbParameterMapper parameterMapper;

    @Override
    public List<GbParameter> findByStandardId(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> findByClauseId(String clauseId) {
        if (!StringUtils.hasText(clauseId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getClauseId, clauseId)
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> searchByName(String standardId, String paramName) {
        if (!StringUtils.hasText(paramName)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        
        if (StringUtils.hasText(standardId)) {
            wrapper.eq(GbParameter::getStandardId, standardId);
        }

        wrapper.like(GbParameter::getParamName, paramName)
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public GbParameter findByName(String standardId, String paramName) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(paramName)) {
            return null;
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .eq(GbParameter::getParamName, paramName);

        return parameterMapper.selectOne(wrapper);
    }

    @Override
    public List<GbParameter> searchByValueRange(String standardId, BigDecimal minValue, BigDecimal maxValue) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId);

        if (minValue != null) {
            wrapper.ge(GbParameter::getParamValue, minValue);
        }

        if (maxValue != null) {
            wrapper.le(GbParameter::getParamValue, maxValue);
        }

        wrapper.orderByAsc(GbParameter::getParamValue);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> findByUnit(String standardId, String unit) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(unit)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .eq(GbParameter::getUnit, unit)
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> findWithFormula(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .isNotNull(GbParameter::getFormula)
               .ne(GbParameter::getFormula, "")
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> findWithCondition(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .isNotNull(GbParameter::getConditionExpr)
               .ne(GbParameter::getConditionExpr, "")
               .orderByAsc(GbParameter::getParamName);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public List<GbParameter> findByIds(List<String> paramIds) {
        if (paramIds == null || paramIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(GbParameter::getId, paramIds);

        return parameterMapper.selectList(wrapper);
    }

    @Override
    public int countByStandardId(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return 0;
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId);

        return Math.toIntExact(parameterMapper.selectCount(wrapper));
    }

    @Override
    public Map<String, Integer> countByUnit(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyMap();
        }

        // 查询所有参数
        List<GbParameter> parameters = findByStandardId(standardId);

        // 按单位分组统计
        return parameters.stream()
            .filter(param -> StringUtils.hasText(param.getUnit()))
            .collect(Collectors.groupingBy(
                GbParameter::getUnit,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
    }

    @Override
    public BigDecimal[] getValueRange(String standardId, String paramName) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(paramName)) {
            return null;
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .eq(GbParameter::getParamName, paramName)
               .isNotNull(GbParameter::getParamValue)
               .orderByAsc(GbParameter::getParamValue);

        List<GbParameter> parameters = parameterMapper.selectList(wrapper);

        if (parameters.isEmpty()) {
            return null;
        }

        BigDecimal minValue = parameters.get(0).getParamValue();
        BigDecimal maxValue = parameters.get(parameters.size() - 1).getParamValue();

        return new BigDecimal[]{minValue, maxValue};
    }

    @Override
    public List<BigDecimal> getDistinctValues(String standardId, String paramName) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(paramName)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbParameter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbParameter::getStandardId, standardId)
               .eq(GbParameter::getParamName, paramName)
               .isNotNull(GbParameter::getParamValue)
               .orderByAsc(GbParameter::getParamValue);

        List<GbParameter> parameters = parameterMapper.selectList(wrapper);

        // 去重并返回值列表
        return parameters.stream()
            .map(GbParameter::getParamValue)
            .distinct()
            .collect(Collectors.toList());
    }

    //update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】saveBatch 实现（先删后插，全量替换）-----------
    @Override
    public int saveBatch(String standardId, List<GbParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return 0;
        }
        // 先清理该标准下的旧参数（全量替换语义，保证幂等可重入）
        parameterMapper.deleteByStandardId(standardId);
        int count = 0;
        for (GbParameter p : parameters) {
            if (p.getStandardId() == null) {
                p.setStandardId(standardId);
            }
            parameterMapper.insert(p);
            count++;
        }
        log.info("[GbParameterRepository] saveBatch 完成, standardId={}, 条数={}", standardId, count);
        return count;
    }
    //update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P5】-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbParameterRepository实现-----------