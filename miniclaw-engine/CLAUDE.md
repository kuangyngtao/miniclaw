# miniclaw-engine — 核心引擎模块

## 模块定位

组装 LLMProvider + Registry，驱动 ReAct 循环。是 miniclaw 的控制中枢，对应四层架构中的**第二层（核心引擎层）**。

## 已完成

### 接口定义

- [AgentLoop.java](src/main/java/com/miniclaw/engine/AgentLoop.java) — 对外暴露的唯一入口：`run(String userPrompt) → String`

### 核心实现

- [AgentEngine.java](src/main/java/com/miniclaw/engine/impl/AgentEngine.java) — ReAct 主循环完整实现

### 状态流转

```
INIT    → contextHistory = [system, user]
THINK   → provider.generate(contextHistory, availableTools)
  ├─ toolCalls 为空 → END（返回 content）
  └─ toolCalls 非空 → ACT → OBSERVE → 循环回 THINK
ACT     → registry.execute(toolCall) × N（串行）
OBSERVE → contextHistory.add(Message.toolResult(id, output))
END     → return responseMsg.content()
```

### 组件依赖

| 注入组件 | 接口 | 来源模块 |
|---------|------|---------|
| LLM 提供者 | `LLMProvider.generate()` | miniclaw-provider |
| 工具注册表 | `Registry.getAvailableTools()` / `execute()` | miniclaw-tools |

### Mock 验证

`AgentEngineTest` 验证了两轮 ReAct 循环的完整链路：
- Turn 1：Mock LLM 返回 tool_call(bash ls) → 引擎执行 → 观察结果注入上下文 → 循环
- Turn 2：Mock LLM 返回纯文本 → 引擎识别 toolCalls 为空 → 退出返回最终内容
- 断言：`turn == 2` / `result.contains("task complete")`

## 待完成

- [ ] 多工具并行执行（V2，虚拟线程 `parallelStream()`）
- [ ] 最大迭代次数保护（当前 `while(true)`）
- [ ] 用户中断机制
- [ ] 从 AGENTS.md 动态组装 system prompt（对接 miniclaw-context）

## 依赖

```
miniclaw-engine
  ├── miniclaw-tools    (Registry, schema types)
  ├── miniclaw-provider (LLMProvider)
  └── SLF4J
```
