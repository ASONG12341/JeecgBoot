//update-begin---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索通道接口-----------
package org.jeecg.modules.airag.llm.gbstandard.retrieval.channel;

import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalRequest;
import org.jeecg.modules.airag.llm.gbstandard.retrieval.dto.RetrievalResult;

import java.util.List;

/**
 * 检索通道接口
 * 
 * 定义检索通道的标准行为，支持多种检索策略：
 * 1. 向量检索（VectorChannel）
 * 2. 结构化检索（StructureChannel）
 * 3. 术语检索（TermChannel）
 * 
 * @author ThinkPad
 * @date 2026-07-14
 */
public interface RetrievalChannel {

    /**
     * 获取通道名称
     * 
     * @return 通道名称（如 "vector", "structure", "term"）
     */
    String getChannelName();

    /**
     * 执行检索
     * 
     * @param request 检索请求
     * @return 检索结果列表
     */
    List<RetrievalResult> search(RetrievalRequest request);

    /**
     * 检查通道是否可用
     * 
     * @return true-可用，false-不可用
     */
    boolean isAvailable();

    /**
     * 获取通道权重
     *
     * @param request 检索请求
     * @return 权重值（0.0-1.0）
     */
    double getWeight(RetrievalRequest request);

    //update-begin---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充编排器需要的默认方法-----------
    /**
     * 检查当前请求是否适用本通道
     *
     * @param request 检索请求
     * @return true-适用，false-不适用
     */
    default boolean isApplicable(RetrievalRequest request) {
        return isAvailable();
    }

    /**
     * 执行检索（默认委托给 search）
     *
     * @param request 检索请求
     * @return 检索结果列表
     */
    default List<RetrievalResult> retrieve(RetrievalRequest request) {
        return search(request);
    }
    //update-end---author:Claude Fable 5 ---date:2026-07-14  for：修复编译错误，补充编排器需要的默认方法-----------
}
//update-end---author:ThinkPad ---date:2026-07-14  for：【GB知识引擎】Phase 2 L4层检索通道 - 检索通道接口-----------