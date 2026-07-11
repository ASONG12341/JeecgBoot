// update-begin---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】IntentContext 线程级 intent 暂存（不修改 AIChatParams，临时方案，P1.1 实施时升级为 RequestScope Bean / InheritableThreadLocal）-----------
package org.jeecg.modules.airag.common.handler;

/**
 * 线程级 intent 暂存工具类（v3.1 P1.2 临时方案）
 *
 * 为什么不修改 AIChatParams：
 * 1. AIChatParams 在源码里找不到定义（可能在 Maven 依赖 jar 或其他 git 分支）
 * 2. 修改 jar 内的类需要重新打包，破坏现有依赖
 * 3. 用 ThreadLocal 暂存 + 后续 P1.1 升级到 RequestScope Bean 是安全路径
 *
 * 生命周期：
 * - set：在 AIChatHandler.mergeParams 同步抽取后
 * - get：在下游 EmbeddingHandler.searchEmbedding（P1.1 实施时）
 * - clear：在 AIChatHandler.completions / chat 出口 try-finally
 *
 * @author song-claude
 * @date 2026-07-11
 */
public final class IntentContext {

    private static final ThreadLocal<GbQueryIntent> CURRENT = new ThreadLocal<>();

    private IntentContext() {
        // utility class
    }

    public static void set(GbQueryIntent intent) {
        CURRENT.set(intent);
    }

    public static GbQueryIntent get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
// update-end---author:song-claude ---date:2026-07-11  for：【v3.1 P1.2】IntentContext 线程级 intent 暂存（不修改 AIChatParams，临时方案，P1.1 实施时升级为 RequestScope Bean / InheritableThreadLocal）-----------
