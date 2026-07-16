//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbClauseRepository实现-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbClauseMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbClause;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbClauseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 国标条款仓库实现
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Repository
public class GbClauseRepositoryImpl implements GbClauseRepository {

    @Autowired
    private GbClauseMapper clauseMapper;

    @Override
    public List<GbClause> findClauseTree(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public List<GbClause> findByDepth(String standardId, Integer depth) {
        if (!StringUtils.hasText(standardId) || depth == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getDepth, depth)
               .orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public GbClause findByClausePath(String standardId, String clausePath) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(clausePath)) {
            return null;
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getClausePath, clausePath);

        return clauseMapper.selectOne(wrapper);
    }

    @Override
    public List<GbClause> findByParentPath(String standardId, String parentPath) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId);

        if (StringUtils.hasText(parentPath)) {
            // 查询子条款：parentPath = parentPath 或 clausePath LIKE parentPath.%
            wrapper.and(w -> w
                .eq(GbClause::getParentPath, parentPath)
                .or()
                .likeRight(GbClause::getClausePath, parentPath + ".")
            );
        } else {
            // 查询顶级条款（第1章）
            wrapper.isNull(GbClause::getParentPath)
                   .or()
                   .eq(GbClause::getParentPath, "");
        }

        wrapper.orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public List<GbClause> searchByKeyword(String standardId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        // 使用 MyBatis-Plus 的 selectPage 进行分页查询
        Page<GbClause> page = new Page<>(1, limit > 0 ? limit : 10);
        
        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        
        if (StringUtils.hasText(standardId)) {
            wrapper.eq(GbClause::getStandardId, standardId);
        }

        // 全文检索：标题或文本中包含关键词
        wrapper.and(w -> w
            .like(GbClause::getTitle, keyword)
            .or()
            .like(GbClause::getText, keyword)
        );

        wrapper.orderByDesc(GbClause::getCreatedAt);

        IPage<GbClause> result = clauseMapper.selectPage(page, wrapper);
        return result.getRecords();
    }

    @Override
    public List<GbClause> findByClauseType(String standardId, String clauseType) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(clauseType)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getClauseType, clauseType)
               .orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public List<GbClause> findByRequirementStrength(String standardId, String requirementStrength) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(requirementStrength)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getRequirementStrength, requirementStrength)
               .orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public GbClause findScopeClause(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return null;
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getIsScope, true);

        return clauseMapper.selectOne(wrapper);
    }

    @Override
    public List<GbClause> findAppendixClauses(String standardId, String appendixLabel) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getIsAppendix, true);

        if (StringUtils.hasText(appendixLabel)) {
            wrapper.eq(GbClause::getAppendixLabel, appendixLabel);
        }

        wrapper.orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public List<GbClause> findExceptionClauses(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId)
               .eq(GbClause::getPolarity, "exception")
               .orderByAsc(GbClause::getClausePath);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> searchByVector(String standardId, float[] queryVector, int topK) {
        // TODO: 集成向量数据库（PGVector 或 Milvus）
        // 当前返回空列表，待 L4 层实现时完善
        log.warn("向量检索功能尚未实现，请等待 L4 层集成");
        return Collections.emptyList();
    }

    @Override
    public List<GbClause> findByIds(List<String> clauseIds) {
        if (clauseIds == null || clauseIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(GbClause::getId, clauseIds);

        return clauseMapper.selectList(wrapper);
    }

    @Override
    public int countByStandardId(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return 0;
        }

        LambdaQueryWrapper<GbClause> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbClause::getStandardId, standardId);

        return Math.toIntExact(clauseMapper.selectCount(wrapper));
    }

    @Override
    public Map<String, Integer> countByClauseType(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyMap();
        }

        // 查询所有条款
        List<GbClause> clauses = findClauseTree(standardId);

        // 按类型分组统计
        return clauses.stream()
            .filter(clause -> StringUtils.hasText(clause.getClauseType()))
            .collect(Collectors.groupingBy(
                GbClause::getClauseType,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
    }

    //update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】saveBatch 实现（先删后插，全量替换）-----------
    @Override
    public int saveBatch(String standardId, List<GbClause> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return 0;
        }
        // 先清理该标准下的旧条款（全量替换语义，保证幂等可重入）
        clauseMapper.deleteByStandardId(standardId);
        int count = 0;
        for (GbClause c : clauses) {
            if (c.getStandardId() == null) {
                c.setStandardId(standardId);
            }
            clauseMapper.insert(c);
            count++;
        }
        log.info("[GbClauseRepository] saveBatch 完成, standardId={}, 条数={}", standardId, count);
        return count;
    }
    //update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】saveBatch 实现（先删后插，全量替换）-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbClauseRepository实现-----------
