# miniclaw 进度记录

> 初始提交: `fd29acc` (2026-05-09)，含 35 个 Java 源文件。本次会话在 2026-05-10 做了一轮功能补全。

---

## 已完成模块

### miniclaw-tools — 基础层（9 文件）

| 文件 | 说明 |
|------|------|
| `Tool.java` | 接口: name/description/inputSchema/execute |
| `Result.java` | sealed interface: Ok<T> / Err<T> |
| `Registry.java` | 接口: register/lookup/execute/getAvailableTools |
| `ToolRegistry.java` | ConcurrentHashMap 线程安全实现 |
| `schema/Role.java` | enum: SYSTEM/USER/ASSISTANT/TOOL |
| `schema/Message.java` | record + 5 工厂方法 |
| `schema/ToolCall.java` | record(id, name, JsonNode arguments) |
| `schema/ToolResult.java` | record(toolCallId, output, isError) |
| `schema/ToolDefinition.java` | record(name, description, inputSchema) |

**缺**: 6 个具体工具实现（Read/Write/Edit/Bash/Glob/Grep）、Middleware 安全链。

### miniclaw-provider — LLM 适配层（13 main + 3 test）

- `LLMProvider` / `LLMConfig` / `LLMException` — 接口 + 配置 + 异常
- `OpenAIProvider` (263 行) — JDK HttpClient + Jackson，DeepSeek API 调通
- 重试策略: 3 次指数退避（2s→4s→8s），仅对 429/5xx/IO，4xx 直接抛
- 9 个 DTO record (package-private)
- 测试: 9 用例（Mock HttpServer）+ 2 个连通性测试
- 已修复: `parseSchema` null inputSchema 导致 NPE

### miniclaw-engine — 核心引擎（3 main + 3 test）

- `AgentLoop` — 唯一入口 `run(String) → String`
- `AgentEngine` (144 行) — ReAct 主循环
  - OFF 模式: 单阶段 ReAct
  - TWO_STAGE 模式: Phase 1 空工具推理 → Phase 2 带工具执行
  - `onReasoning` 回调: 引擎计算、CLI 展示，关注点分离
- `ThinkingMode` — OFF / TWO_STAGE
- 测试: 6 个 Mock 用例全部通过
- Demo: `DeepSeekReActDemo` (真实 API + ListFilesTool + ReadFileTool)、`SlowThinkingDemo`

### miniclaw-cli — 命令行入口（1 main，可运行）

- `MiniclawApp` — Picocli 驱动的交互式 REPL，已可用
  - `-m` / `--model` — 切换模型
  - `--base-url` — 切换 API 端点
  - `--thinking` — 启用 TWO_STAGE 慢思考，推理首句可视化
  - `/exit` 退出，`/help` 帮助
- Nyan Cat ASCII 启动画面 + 信息卡片
- Logback: 日志写 `~/.miniclaw/logs/`，按天滚动 7 天

---

## 仅有骨架

| 模块 | 状态 |
|------|------|
| miniclaw-context | `ContextManager` 接口已定义，无实现 |
| miniclaw-memory | `MemoryStore` 接口已定义，无实现 |
| miniclaw-feishu | V2 占位，仅 package-info |

---

## 本次会话完成的工作

1. **CLI 层从骨架到可运行** — 加引擎依赖、接 REPL、加 Picocli 参数
2. **品牌启动画面** — Nyan Cat ASCII logo + 信息卡片
3. **慢思考推理可视化** — `onReasoning` 回调，首句摘要式 `[思考]` 输出
4. **引擎瘦身** — 移除 `System.out.println`，展示归 CLI，引擎只记日志
5. **Bug 修复** — `parseSchema` NPE 加 null 保护
6. **清理** — 删除 3 个冗余的 per-module CLAUDE.md

---

## 当前可跑的命令

```bash
# 构建
mvn package -pl miniclaw-cli -am -q -DskipTests

# 普通模式
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT.jar

# 慢思考模式
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT.jar --thinking

# 集成 Demo（需 API Key）
mvn exec:java -pl miniclaw-engine -Dexec.classpathScope=test \
  -Dexec.mainClass=com.miniclaw.engine.impl.DeepSeekReActDemo
```

## 待完成（按优先级）

1. 6 个工具实现 — 至少先接一个让 ReAct 循环有意义
2. 最大迭代保护 — while(true) 加 turn 上限
3. ContextManager 实现 — token 截断
4. Middleware 安全链 — bash 危险命令拦截
5. MemoryStore 实现 — 磁盘文件读写
