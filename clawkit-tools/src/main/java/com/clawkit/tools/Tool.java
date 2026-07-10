package com.clawkit.tools;

/**
 * 工具接口契约 — clawkit 中所有工具的抽象父类型。
 * 新增工具只需: 实现本接口 → 注册到 ToolRegistry → 编写测试。
 */
public interface Tool {

    /** 工具名，kebab-case，如 "read", "write", "edit", "bash", "glob", "grep" */
    String name();

    /** 给 LLM 看的描述，说明何时使用、接收什么参数 */
    String description();

    /** 输入参数的 JSON Schema，供 LLM 理解参数结构 */
    String inputSchema();

    /** 执行工具，参数为 JSON 字符串，返回统一 Result */
    Result<String> execute(String arguments);

    /** 是否为只读工具。读工具可并行执行，写工具需串行。默认 false（写工具）。 */
    default boolean isReadOnly() { return false; }

    /** 工具元数据（风险等级、副作用等）。默认从 isReadOnly() 推导。 */
    default ToolMetadata metadata() {
        return ToolMetadata.from(this);
    }

    /**
     * 结构化执行入口（新接口）。默认适配旧 execute(String) 接口。
     * 工具实现可 override 以获得结构化结果。
     */
    default ToolExecutionResult execute(ToolExecutionRequest req) {
        long start = System.currentTimeMillis();
        try {
            String args = req.arguments() != null ? req.arguments().toString() : "{}";
            Result<String> result = execute(args);
            long duration = System.currentTimeMillis() - start;
            return switch (result) {
                case Result.Ok<String> ok -> ToolExecutionResult.success(
                    req.toolCallId(), req.toolName(), ok.data(), duration, metadata());
                case Result.Err<String> err -> ToolExecutionResult.error(
                    req.toolCallId(), req.toolName(),
                    err.error().errorCode(), err.error().message(), duration, metadata());
            };
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ToolExecutionResult.error(
                req.toolCallId(), req.toolName(), "INTERNAL_ERROR",
                e.getMessage(), duration, metadata());
        }
    }
}
