---
name: clawkit-dev
description: clawkit 项目开发规范 — 四层架构、模块依赖、命名与代码风格、统一返回格式
---

# clawkit 项目开发规范

## 四层架构

| 层 | 职责 |
|----|------|
| 入口交互层 | CLI REPL + HITL 审批 |
| 核心引擎层 | ReAct Loop + LLM 适配器 |
| 上下文工程层 | Prompt 组装 + Token 截断 + 记忆 |
| 工具与执行层 | ToolRegistry + Middleware |

层间通信：`CLI → AgentLoop → LLM → tool_call → ToolRegistry.execute → Tool.execute → Result → 回注 prompt`

## 模块依赖

| 模块 | 内部依赖 | 外部依赖 |
|------|---------|---------|
| `clawkit-tools` | 无 | Jackson, SLF4J |
| `clawkit-memory` | 无 | Jackson, SLF4J |
| `clawkit-context` | tools | SLF4J |
| `clawkit-provider` | tools | Jackson, JDK HTTP, SLF4J |
| `clawkit-engine` | tools, provider, context | SLF4J |
| `clawkit-cli` | engine, memory | Picocli, Logback |
| `clawkit-feishu` | engine, tools | FeishuApi + FeishuBot |

约束：`tools` 是唯一基础层，`engine` 是组装点。禁止循环依赖。

## 命名规范

| 元素 | 风格 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.clawkit.tools` |
| 类/接口 | PascalCase | `AgentLoop`, `Tool` |
| 方法/变量 | camelCase | `executeTool` |
| 常量/枚举值 | UPPER_SNAKE_CASE | `MAX_RETRIES` |
| 测试类 | 被测类名+Test | `ReadToolTest` |

## 统一返回格式

```java
public sealed interface Result<T> {
    record Ok<T>(T data) implements Result<T> {}
    record Err<T>(ErrorInfo error) implements Result<T> {}
}
public record ErrorInfo(String errorCode, String message) {}
```

禁止业务代码抛异常传递已知错误。

## 错误码

```
T-001~T-005  工具系统    E-001~E-002  Edit 工具
A-001~A-003  Agent       S-001~S-003  Skills
C-001~C-003  配置        X-001~X-002  通用
```

## Tool 接口

```java
public interface Tool {
    String name();              // kebab-case
    String description();       // 给 LLM 看
    String inputSchema();       // JSON Schema
    Result<String> execute(String arguments);
    default boolean isReadOnly() { return false; }
}
```

## 技术选型

| 项 | 选型 |
|----|------|
| Java | 21 LTS |
| 构建 | Maven 3.9+ |
| JSON/YAML | Jackson |
| HTTP | java.net.http（JDK 内置） |
| 测试 | JUnit 5 + AssertJ |
| 日志 | SLF4J + Logback |

明确排除：Spring Boot/Shell、Gradle、OkHttp。

## 代码组织

- 接口与实现分离：公开接口放包根，实现类放 `impl/` 子包
- 测试目录镜像 `src/test/java/` 完整镜像 `src/main/java/` 的包结构
- 无循环依赖
