package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbTermDictMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbTermDict;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 术语检索通道
 * <p>
 * 核心职责：
 * 1. 术语字典匹配
 * 2. 术语定义查询
 * 3. 同义词扩展
 * 4. 术语关联条款检索
 * </p>
 *
 * @author ThinkPad
 * @date 2026-07-14
 */
@Slf4j
@Component("termChannel")
public class TermChannel implements RetrievalChannel {

    @Autowired
    private GbTermDictMapper termDictMapper;

    @Override
    public String getChannelName() {
        return "TERM";
    }

    @Override
    public List<RetrievalResult> search(RetrievalRequest request) {
        log.info("[TermChannel] 开始术语检索, query={}", request.getQuery());

        try {
            List<RetrievalResult> results = new ArrayList<>();

            if (!StringUtils.hasText(request.getQuery())) {
                return results;
            }

            // 1. 精确匹配术语
            List<GbTermDict> exactMatches = findExactMatches(request);
            
            // 2. 模糊匹配术语
            List<GbTermDict> fuzzyMatches = findFuzzyMatches(request);

            // 合并结果（去重）
            Map<String, GbTermDict> termMap = new HashMap<>();
            exactMatches.forEach(term -> termMap.put(term.getId(), term));
            fuzzyMatches.forEach(term -> termMap.putIfAbsent(term.getId(), term));

            // 转换为 RetrievalResult
            results.addAll(termMap.values().stream()
                    .map(this::convertTermToResult)
                    .collect(Collectors.toList()));

            log.info("[TermChannel] 术语检索完成, 结果数={}", results.size());
            return results;

        } catch (Exception e) {
            log.error("[TermChannel] 术语检索失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isAvailable() {
        // 术语通道需要 Mapper 可用
        return termDictMapper != null;
    }

    @Override
    public double getWeight(RetrievalRequest request) {
        // 返回请求中配置的术语检索权重
        return request.isEnableTerm() ? request.getTermWeight() : 0.0;
    }

    /**
     * 精确匹配术语
     */
    private List<GbTermDict> findExactMatches(RetrievalRequest request) {
        LambdaQueryWrapper<GbTermDict> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(request.getStandardId())) {
            wrapper.eq(GbTermDict::getStandardId, request.getStandardId());
        }

        //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm-----------
        // 精确匹配术语名称
        wrapper.eq(GbTermDict::getCanonicalTerm, request.getQuery());
        //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm-----------

        return termDictMapper.selectList(wrapper);
    }

    /**
     * 模糊匹配术语
     */
    private List<GbTermDict> findFuzzyMatches(RetrievalRequest request) {
        LambdaQueryWrapper<GbTermDict> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(request.getStandardId())) {
            wrapper.eq(GbTermDict::getStandardId, request.getStandardId());
        }

        //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm，移除不存在的 englishName-----------
        // 模糊匹配术语名称或定义
        String query = request.getQuery();
        wrapper.and(w -> w
            .like(GbTermDict::getCanonicalTerm, query)
            .or()
            .like(GbTermDict::getDefinition, query)
        );
        //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm，移除不存在的 englishName-----------

        wrapper.last("LIMIT " + request.getTopK());

        return termDictMapper.selectList(wrapper);
    }

    /**
     * 将 GbTermDict 转换为 RetrievalResult
     */
    private RetrievalResult convertTermToResult(GbTermDict term) {
        RetrievalResult result = new RetrievalResult();

        result.setResultId(term.getId());
        result.setStandardId(term.getStandardId());
        result.setSourceChannel("TERM");

        //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm，移除不存在的 englishName/symbol-----------
        // 设置标题
        result.setTitle(term.getCanonicalTerm());

        // 设置文本内容
        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append("术语：").append(term.getCanonicalTerm());

        if (StringUtils.hasText(term.getDefinition())) {
            textBuilder.append("\n定义：").append(term.getDefinition());
        }

        result.setText(textBuilder.toString());

        // 设置分数
        result.setScore(0.80);

        // 设置元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("termId", term.getId());
        metadata.put("termName", term.getCanonicalTerm());
        metadata.put("definition", term.getDefinition());
        result.setMetadata(metadata);
        //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，术语字段使用 canonicalTerm，移除不存在的 englishName/symbol-----------

        return result;
    }
}