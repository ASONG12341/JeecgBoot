//update-begin---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】standardNo→standardId 解析器-----------
package org.jeecg.modules.airag.llm.gbstandard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.airag.llm.gbstandard.mapper.GbStandardMapper;
import org.jeecg.modules.airag.llm.gbstandard.model.GbStandard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * GB standard-no to standardId resolver.
 * <p>Tool input uses standardNo (visible to LLM); internally resolves to standardId
 * to query gb_parameter. Prefers current status when multiple versions exist.</p>
 *
 * @author song
 * @date 2026-07-17
 */
@Component
public class GbStandardResolver {

    @Autowired
    private GbStandardMapper gbStandardMapper;

    public Optional<String> resolveStandardId(String standardNo) {
        if (standardNo == null || standardNo.isBlank()) return Optional.empty();
        List<GbStandard> list = gbStandardMapper.selectList(
                new LambdaQueryWrapper<GbStandard>().eq(GbStandard::getStandardNo, standardNo.trim()));
        if (list == null || list.isEmpty()) return Optional.empty();
        // prefer current status
        return list.stream()
                .filter(s -> "current".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .or(() -> list.stream().findFirst())
                .map(GbStandard::getId);
    }
}
//update-end---author:song ---date:2026-07-17  for：【GB-RAG v4 P4】standardNo→standardId 解析器-----------
