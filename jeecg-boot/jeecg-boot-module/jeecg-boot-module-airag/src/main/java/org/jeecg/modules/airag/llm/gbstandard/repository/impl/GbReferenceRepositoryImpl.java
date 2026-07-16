//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbReferenceRepository实现-----------
package org.jeecg.modules.airag.llm.gbstandard.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbReferenceMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbReference;
import org.jeecg.modules.airag.llm.gbstandard.repository.GbReferenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 国标引用关系仓库实现
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Repository
public class GbReferenceRepositoryImpl implements GbReferenceRepository {

    @Autowired
    private GbReferenceMapper referenceMapper;

    @Override
    public List<GbReference> findBySourceStandardId(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId)
               .orderByAsc(GbReference::getSourceClausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findBySourceClause(String standardId, String clausePath) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(clausePath)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId)
               .eq(GbReference::getSourceClausePath, clausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findReferencedBy(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getTargetStandardId, standardId)
               .orderByAsc(GbReference::getSourceStandardId);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findBetweenStandards(String sourceStandardId, String targetStandardId) {
        if (!StringUtils.hasText(sourceStandardId) || !StringUtils.hasText(targetStandardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, sourceStandardId)
               .eq(GbReference::getTargetStandardId, targetStandardId)
               .orderByAsc(GbReference::getSourceClausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findByRefType(String standardId, String refType) {
        if (!StringUtils.hasText(standardId) || !StringUtils.hasText(refType)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId)
               .eq(GbReference::getRefType, refType)
               .orderByAsc(GbReference::getSourceClausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findIntraReferences(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId)
               .eq(GbReference::getTargetType, "intra")
               .orderByAsc(GbReference::getSourceClausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findInterReferences(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId)
               .eq(GbReference::getTargetType, "inter")
               .orderByAsc(GbReference::getSourceClausePath);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findByTargetStandardNo(String targetStandardNo) {
        if (!StringUtils.hasText(targetStandardNo)) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getTargetStandardNo, targetStandardNo)
               .orderByAsc(GbReference::getSourceStandardId);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public List<GbReference> findReferenceChain(String standardId, int maxDepth) {
        if (!StringUtils.hasText(standardId) || maxDepth <= 0) {
            return Collections.emptyList();
        }

        List<GbReference> result = new ArrayList<>();
        Set<String> visitedStandards = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        queue.offer(standardId);
        visitedStandards.add(standardId);

        int currentDepth = 0;

        while (!queue.isEmpty() && currentDepth < maxDepth) {
            int levelSize = queue.size();
            currentDepth++;

            for (int i = 0; i < levelSize; i++) {
                String currentStandardId = queue.poll();

                // 查询当前标准的所有引用
                List<GbReference> references = findBySourceStandardId(currentStandardId);
                result.addAll(references);

                // 将引用的标准加入队列（用于下一层递归）
                for (GbReference ref : references) {
                    if ("inter".equals(ref.getTargetType()) 
                        && StringUtils.hasText(ref.getTargetStandardId())
                        && !visitedStandards.contains(ref.getTargetStandardId())) {
                        
                        visitedStandards.add(ref.getTargetStandardId());
                        queue.offer(ref.getTargetStandardId());
                    }
                }
            }
        }

        return result;
    }

    @Override
    public List<GbReference> findByIds(List<String> referenceIds) {
        if (referenceIds == null || referenceIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(GbReference::getId, referenceIds);

        return referenceMapper.selectList(wrapper);
    }

    @Override
    public int countBySourceStandardId(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return 0;
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getSourceStandardId, standardId);

        return Math.toIntExact(referenceMapper.selectCount(wrapper));
    }

    @Override
    public int countReferencedBy(String standardId) {
        if (!StringUtils.hasText(standardId)) {
            return 0;
        }

        LambdaQueryWrapper<GbReference> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GbReference::getTargetStandardId, standardId);

        return Math.toIntExact(referenceMapper.selectCount(wrapper));
    }

    @Override
    public List<String> findMostReferenced(int limit) {
        if (limit <= 0) {
            limit = 10;
        }

        // 查询所有引用关系
        List<GbReference> allReferences = referenceMapper.selectList(null);

        // 按目标标准ID分组统计
        Map<String, Long> referenceCount = allReferences.stream()
            .filter(ref -> "inter".equals(ref.getTargetType()) 
                && StringUtils.hasText(ref.getTargetStandardId()))
            .collect(Collectors.groupingBy(
                GbReference::getTargetStandardId,
                Collectors.counting()
            ));

        // 按引用次数降序排序，返回前 N 个
        return referenceCount.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L5层数据访问 - GbReferenceRepository实现-----------