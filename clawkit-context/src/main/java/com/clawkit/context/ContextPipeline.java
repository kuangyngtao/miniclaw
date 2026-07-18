package com.clawkit.context;

/**
 * 上下文管线：模型上下文的唯一组装入口。
 *
 * <p>负责收集、规范化、排序、压缩和预算管理所有上下文片段，
 * 输出统一的 ModelContext。
 *
 * <p>实现类负责编排已有组件（LadderedCompactor、MessageMasker、
 * ContextBudgetAnalyzer 等），不替代它们。
 */
public interface ContextPipeline {

    /**
     * 从请求构建模型上下文。
     *
     * @param request 所有输入数据源
     * @return 组装完成的 ModelContext（含预算报告）
     */
    ModelContext build(ContextRequest request);

    /**
     * 压缩模型上下文以满足预算限制。
     *
     * @param request 待压缩的上下文和约束
     * @return 压缩结果（含前后预算报告）
     */
    CompactionResult compact(CompactionRequest request);
}
