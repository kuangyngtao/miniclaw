# miniclaw-tools — 工具与执行模块

## 模块定位

工具注册、发现、执行。模型改变物理世界的唯一出口，对应四层架构中的**第四层（工具与执行层）**。同时承载 schema 数据类型，是项目唯一的基础模块。

## 已完成

### 核心接口

- [Tool.java](src/main/java/com/miniclaw/tools/Tool.java) — 工具接口契约，4 个方法：`name()` / `description()` / `inputSchema()` / `execute()`
- [Registry.java](src/main/java/com/miniclaw/tools/Registry.java) — 工具注册与分发接口：`getAvailableTools()` / `execute(ToolCall)` / `register(Tool)` / `lookup(name)`
- [Result.java](src/main/java/com/miniclaw/tools/Result.java) — 统一返回 `sealed interface`，`Ok<T>` / `Err<T>` 两种变体

### 实现

- [ToolRegistry.java](src/main/java/com/miniclaw/tools/impl/ToolRegistry.java) — Registry 的默认实现，线程安全（`ConcurrentHashMap`）：
  - `getAvailableTools()` — 将 Tool 转为 ToolDefinition 列表给 LLM
  - `execute(ToolCall)` — 完成 ToolCall→Tool 查找 → JsonNode→String 参数转换 → `Result→ToolResult` 全链路

### Schema 数据类型（tools.schema）

| 类型 | 说明 |
|------|------|
| `Role` | enum：SYSTEM / USER / ASSISTANT |
| `Message` | record：role + content + toolCalls + toolCallId，5 个静态工厂方法 |
| `ToolCall` | record：id + name + arguments（JsonNode 延迟解析） |
| `ToolResult` | record：toolCallId + output + isError |
| `ToolDefinition` | record：name + description + inputSchema（给 LLM 的元信息） |

数据流：`ToolCall → Registry.execute() → Tool.execute(JSON) → Result → ToolResult → Message.toolResult()`

## 待完成

- [ ] 6 个工具实现：ReadTool / WriteTool / EditTool / BashTool / GlobTool / GrepTool
- [ ] Middleware 安全拦截链（bash 危险命令黑名单 + 审批）

## 依赖

```
miniclaw-tools
  ├── Jackson (JsonNode, @JsonInclude)
  └── SLF4J
```

被依赖：`miniclaw-provider`、`miniclaw-engine`、`miniclaw-cli`（传递）
