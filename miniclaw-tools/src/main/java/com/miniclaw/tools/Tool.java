package com.miniclaw.tools;

/**
 * 工具接口契约 — miniclaw 中所有工具的抽象父类型。
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
}
