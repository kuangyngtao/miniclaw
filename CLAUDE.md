# miniclaw 项目开发纲领

## 项目定义

miniclaw 是 [OpenClaw](https://openclaw.ai/) 的 Java CLI 实现——本地 AI 编程助手。

### 三问裁剪

- **做什么**: CLI Agent + 飞书 IM 通道，8 工具（read/write/edit/bash/glob/grep/todo_write/web_fetch），可扩展工具体系
- **不做什么**: Gateway、多 Agent 调度、浏览器自动化、MCP、Web 前端
- **做到什么程度**: JVM 本地运行、CLI + 飞书双通道、工具独立可测

---

## 四层架构

| 层 | 职责 | V1 完成度 | 已实现 | 待实现 |
|----|------|-----------|--------|--------|
| 入口交互层 | CLI REPL + HITL 审批 | ▓▓▓▓ 85% | CLI REPL（Picocli）、JLine3 REPL（历史持久化 + Tab 补全 + Ctrl+C 跨平台）、`--root` 工作目录限制、HITL 审批（ASK 模式）、权限模式切换（AUTO/ASK/PLAN）、`/clear` `/compact` `/context` 命令、FIGlet Banner + 工作目录 + 入门提示、Token 用量实时显示 | — |
| 核心引擎层 | Main Loop (ReAct) + LLM 适配器 | ▓▓▓▓ 90% | ReAct Loop、多轮会话上下文、三层迭代控制（死循环检测+进度提醒+硬上限）、OpenAI/DeepSeek Provider（含熔断器）、TWO_STAGE 慢思考（仅首轮规划）、流式输出、并行工具调用（读工具虚拟线程并发）、PermissionMode 引擎层权限控制、Ctrl+C 优雅中断 | SubAgent 引擎、多模型路由 |
| 上下文工程层 | Prompt 组装 + Token 截断 + 记忆 | ▓▓▓▓ 90% | LadderedCompactor 阶梯压缩（L1 删空行/L2 跨轮去重/L3 LLM 摘要）、DiskMemoryService 磁盘记忆（YAML frontmatter + MEMORY.md 索引）、System Prompt 记忆注入 | 自动记忆提取 |
| 工具与执行层 | ToolRegistry + Middleware | ▓▓▓▓ 90% | 8 工具（read/write/edit/bash/glob/grep/todo_write/web_fetch）、ToolRegistry、EditTool 四级模糊匹配、`Tool.isReadOnly()` 读写声明、`SafetyInterceptor` 高危命令拦截链、`CommandSafetyInterceptor` 8 条规则 | Git 工具 |

核心哲学：上下文即缓存，磁盘即真相。状态不跨层持有。

层间通信：`CLI → AgentLoop → LLM → tool_call → allReadOnly? → executeParallel/Sequential → ToolRegistry.execute (interceptor chain) → Tool.execute → Result → 回注 prompt → 循环`

---

## 模块依赖关系

| 模块 | 依赖（内部） | 外部依赖 |
|------|------------|---------|
| `miniclaw-tools` | 无 | Jackson, SLF4J |
| `miniclaw-memory` | 无 | Jackson, Jackson YAML, SLF4J |
| `miniclaw-context` | tools | SLF4J |
| `miniclaw-provider` | tools | Jackson, JDK HTTP, SLF4J |
| `miniclaw-engine` | tools, provider, context | SLF4J |
| `miniclaw-cli` | engine, memory | Picocli, Logback |
| `miniclaw-feishu` | engine, tools | FeishuApi + FeishuBot + FeishuConfig + TokenBatcher；双向共享通道：CLI ↔ 飞书输入输出镜像 |

约束：
- `tools` 是唯一基础层，`Result<T>` 定义在此，所有模块通过依赖 tools 获得统一返回模型
- `engine` 是组装点，不实现具体逻辑
- `cli` 不直接依赖 tools / provider，必须通过 engine
- 禁止循环依赖

---

## 技术选型

| 项 | 选型 |
|----|------|
| Java | 21 LTS（虚拟线程、sealed class、record）|
| 构建 | Maven 3.9+ |
| CLI | Picocli（注解驱动、零依赖）|
| JSON/YAML | Jackson |
| HTTP | `java.net.http`（JDK 内置）|
| 模板 | StringTemplate4 |
| 测试 | JUnit 5 + AssertJ |
| 日志 | SLF4J + Logback |

明确排除：Spring Boot/Shell（启动慢）、Gradle（不必要复杂度）、OkHttp（JDK 内置已够）。

---

## 运维

- **日志**: `~/.miniclaw/logs/miniclaw.log`，按天滚动保留 7 天，`MINICLAW_LOG_LEVEL=DEBUG` 切换
- **API Key**: 优先级 环境变量 > `~/.miniclaw/config.yaml` > 交互输入。硬编码=零容忍
- **工作目录**: 默认启动时所在目录，`--root` 限制范围。元数据（logs/config/memory）统一在 `~/.miniclaw/`
- **V1 不做**: 健康检查、指标、自动更新——CLI 模式用户可观察

---

## 部署架构

miniclaw 是本地 CLI 工具，不是服务端应用。部署 = `java -jar miniclaw.jar`。

### 存储选型

| 组件 | 需要？ | 理由 |
|------|-------|------|
| SQL / PostgreSQL | **否** | 文件系统即数据库，"磁盘即真相" |
| Redis | **否** | 单用户、无共享状态、无缓存需求 |
| pgvector | **否** | 无向量检索，代码搜索靠 glob/grep |

唯一存储：`~/.miniclaw/` 下的 logs + config + Markdown 记忆文件。

### 性能瓶颈与演进

| 瓶颈 | 量级 | 影响 |
|------|------|------|
| LLM API 延迟 | 30-60s/次 | 占 Agent Loop 95% 时间，绝对主导 |
| 上下文膨胀 | +2-3K tokens/轮 | 长对话越来越慢，截断策略已预留 |
| JVM 冷启动 | 1-2s | 可接受，不是高频重启服务 |
| 文件 I/O | < 0.1s | 对本地项目无感 |

演进路径：

| 阶段 | 优化 | 触发条件 |
|------|------|---------|
| V1 | 超时+重试+虚拟线程 | ✅ 已完成 |
| V1.5 | 并行工具调用 + 流式输出 + 权限模式系统 | ✅ 已完成 |
| V2 | 多轮上下文 + 三层迭代控制 + 阶梯压缩 | ✅ 已完成 |
| V2.5 | TodoWrite + isReadOnly 下沉 + 安全拦截器链 | ✅ 已完成 |
| V2.7 | TWO_STAGE 优化 + Ctrl+C 优雅中断 + 熔断器 | ✅ 已完成 |
| V2.8 | MemoryStore 磁盘记忆 + 熔断器 | ✅ 已完成 |
| V2.9 | 飞书 IM 通道（嵌入式 HTTP Server + 流式回复 + 多用户会话隔离） | ✅ 已完成 |
| V2.10 | CLI ↔ 飞书双向共享（多路监听 + 共享锁 + 双向镜像 + 飞书自适应节流） | ✅ 已完成 |
| V2.11 | JLine3 REPL（历史持久化 + Tab 补全 + 跨平台 Ctrl+C） | ✅ 已完成 |
| V3 | SubAgent 引擎 | 下一阶段 |
| V3 | GraalVM native-image 编译（启动 < 0.1s） | 需要瞬启 |
| 远期 | 本地嵌入模型语义搜索 | grep 无法覆盖的语义场景 |

### 功能演进

| 层 | 方向 | 优先级 | 说明 |
|----|------|--------|------|
| 引擎 | ~~并行工具调用~~ | ~~高~~ | ✅ 已实现：读工具虚拟线程并发，写工具保持串行 |
| 引擎 | ~~权限模式系统~~ | ~~高~~ | ✅ 已实现：AUTO/ASK/PLAN + CLI 斜杠命令运行时切换 |
| 引擎 | ~~多轮对话上下文~~ | ~~高~~ | ✅ 已实现：sessionHistory 跨 run() 复用 + clearSession() |
| 引擎 | ~~迭代控制~~ | ~~高~~ | ✅ 已实现：三层防御（死循环检测+进度提醒+50轮硬上限） |
| 引擎 | ~~上下文阶梯压缩~~ | ~~高~~ | ✅ 已实现：LadderedCompactor（L1 删空行/L2 跨轮去重/L3 LLM 摘要压缩），Summarizer 接口解耦 context/provider |
| 引擎 | SubAgent 引擎 | 高 | 派生子 Agent 独立执行，并行派发虚拟线程 |
| 工具 | ~~高危操作拦截~~ | ~~高~~ | ✅ 已实现：`SafetyInterceptor` 链 + `CommandSafetyInterceptor` 8 条规则，AUTO 模式也生效 |
| 工具 | ~~WebFetch 工具~~ | ~~中~~ | ✅ 已实现：HTTP GET + Jsoup HTML→text + 15min TTL 缓存 + 1MB/8000 双截断 |
| 工具 | ~~TodoWrite 工具~~ | ~~中~~ | ✅ 已实现：`TodoWriteTool` 会话级 ephemeral，AtomicReference 存储，status 枚举校验 |
| 工具 | ~~isReadOnly 下沉~~ | ~~中~~ | ✅ 已实现：`Tool.isReadOnly()` default method，引擎不再硬编码 READ_ONLY_TOOLS |
| 引擎 | ~~熔断器~~ | ~~中~~ | ✅ 已实现：`OpenAIProvider` CLOSED→OPEN→HALF_OPEN 状态机，连续 5 次失败熔断 30s |
| 记忆 | ~~MemoryStore 磁盘记忆~~ | ~~中~~ | ✅ 已实现：`DiskMemoryService` + YAML frontmatter + MEMORY.md 索引 + `/remember` CLI |
| 工具 | Git 专用工具 | 低 | git status/diff/log 封装，解决 bash 输出截断 |
| 工具 | BashTool 后台守护 | 低 | --background 标志，超时不杀返回 PID |
| 工具 | WriteTool 覆盖确认 | 低 | 目标文件已存在时交互确认 |
| CLI | ~~JLine3 REPL~~ | 高 | ✅ 已完成：JLine3 `LineReader` 替换 Scanner、`MiniclawCompleter` Tab 补全、`~/.miniclaw/history` 持久化、Ctrl+C 跨平台信号处理（`Terminal.handle(Signal.INT)` + `UserInterruptException`）、`\` 多行续行 |
| CLI | ~~--root 工作目录限制~~ | 中 | ✅ 已完成：`--root` CLI 选项限制 Agent 可访问范围，自动 resolve + 路径校验（存在性+目录类型） |
| 飞书 | ~~Feishu IM 通道~~ | 高 | ✅ 已完成：HTTP Server + 流式编辑 + TokenBatcher 自适应节流 |
| 飞书 | ~~CLI ↔ 飞书双向共享~~ | 高 | ✅ 已完成：onToken 多路监听（CopyOnWriteArrayList）+ 引擎共享锁（tryAcquire/release）+ ngrok 自动管理 + 配置统一（env > config.yaml）+ 双向输入输出镜像 |
| 工具 | 跨平台 Shell 统一 | 低 | 动态检测 git bash / wsl / cmd |

### 数据模型

V1 不需要建数据模型。核心结构已在代码中：

| 结构 | 所在模块 | 说明 |
|------|---------|------|
| `Message(role, content)` | provider | 对话记录，会话级 ephemeral |
| `Result<T>` | tools | 工具返回，模块间唯一通行证 |
| `LLMConfig` | provider | 超时/重试配置 |
| Markdown 文件 | memory | TODO.md / AGENTS.md，磁盘即真相 |

若将来需会话历史索引：不建表，给 `~/.miniclaw/sessions/` 加 JSON 索引文件。

---

## LLM 外部调用韧性

| 能力 | MVP | 设计 |
|------|-----|------|
| 超时控制 | **必须** | 连接 10s + 请求 60s，通过 `LLMConfig` 配置 |
| 重试策略 | **必须** | 3 次指数退避（2s→4s→8s），仅对 429/5xx/IO 超时重试；4xx 直接返回错误 |
| 线程池隔离 | V2 | 飞书引入并发回调后，Agent Loop 和 IM 回调分池 |
| 熔断器 | V2 | 连续 5 次失败快速返回错误，V1 由超时+重试兜底 |

---

## 规范体系

### 命名风格

| 元素 | 风格 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.miniclaw.tools` |
| 类/接口 | PascalCase | `AgentLoop`, `Tool` |
| 方法/变量 | camelCase | `executeTool` |
| 常量/枚举值 | UPPER_SNAKE_CASE | `MAX_RETRIES` |
| 测试类 | 被测类名+Test | `ReadToolTest` |
| 资源文件 | kebab-case | `error-messages.properties` |

### 代码组织

- **接口与实现分离**: 公开接口放包根，实现类放 `impl/` 子包（如 `tools/impl/ReadTool.java`）
- **测试目录镜像**: `src/test/java/` 完整镜像 `src/main/java/` 的包结构
- **无循环依赖**: 模块依赖严格单向

### 统一返回格式

```java
public sealed interface Result<T> {
    record Ok<T>(T data) implements Result<T> {}
    record Err<T>(ErrorInfo error) implements Result<T> {}
}
public record ErrorInfo(String errorCode, String message) {}
```

禁止业务代码抛异常传递已知错误；异常仅用于 JVM 不可恢复错误。

### 错误码（`<前缀>-<三位数字>`）

```
T-001~T-005  工具系统（未注册/参数非法/权限不足/超时/IO）
E-001~E-002  Edit 工具（非唯一匹配/未找到匹配）
A-001~A-003  Agent（超最大迭代/LLM 调用失败/返回不可解析）
S-001~S-003  Skills（文件不存在/格式非法/匹配失败）
C-001~C-003  配置（文件不存在/格式非法/校验失败）
X-001~X-002  通用（内部错误/不可达分支）
```

### 设计原则

1. **约定优于配置** — 目录/注解/接口决定行为，不做无谓配置项
2. **渐进式复杂度** — 核心 Loop < 200 行，Tool 接口 < 5 方法，新增工具=实现一个接口
3. **工具原子化** — 一个 Tool 做一件事，统一 `Tool` 接口 + `Result<T>` 返回
4. **失败透明** — 错误必须含：错误码 + 上下文 + 可操作信息
5. **前后一致** — 全项目一套规范，有异议先改本文件再改代码

---

## Tool 接口契约

```java
public interface Tool {
    String name();              // kebab-case
    String description();       // 给 LLM 看
    String inputSchema();       // JSON Schema
    Result<String> execute(String arguments);
    default boolean isReadOnly() { return false; }  // 读工具 override 返回 true
}
```

新增工具：实现 Tool → 注册到 ToolRegistry → 测试 → 合入。读工具需 override `isReadOnly()`。

### 现有工具清单

| 工具 | 文件 | 安全机制 | 读/写 |
|------|------|---------|:---:|
| read | ReadTool.java | 路径穿越防护、8000 字节截断 | 读 |
| write | WriteTool.java | 路径穿越防护、自动建父目录 | 写 |
| edit | EditTool.java | **四级模糊匹配**（L1 精确→L2 换行符归一化→L3 去首尾空行→L4 逐行去缩进滑动窗口）、唯一性校验（E-001/E-002） | 写 |
| bash | BashTool.java | 30s 超时强杀、workDir 绑定、8000 字节截断、Windows Git Bash 检测 | 写 |
| glob | GlobTool.java | 200 条 + 8000 字节双截断、路径归一化 | 读 |
| grep | GrepTool.java | 50 条 + 200 字符/行 + 8000 字节三层截断、自动跳过 `.git`/`target`/`node_modules`/二进制文件 | 读 |
| todo_write | TodoWriteTool.java | JSON schema 校验、status 枚举限制 | 写 |
| web_fetch | WebFetchTool.java | HTTP GET + Jsoup HTML→text、15min TTL 缓存、1MB/8000 双截断 | 读 |

**全局安全拦截器**（`ToolRegistry.execute()` 入口，所有模式生效）：
- `CommandSafetyInterceptor` — 8 条高危命令正则：`rm -rf /~/*`、`sudo`、`chmod 777`、`> /dev/sd`、`mkfs`、`dd`、fork bomb

工具工作流：`glob（定位文件）→ grep（定位代码）→ read（确认）→ edit（修改）`

---

## 开发流程

```
SPEC.md（三问裁剪）→ 实现 → 三部检查 → 合入
```

SPEC 必须包含：做什么 / 不做什么 / 做到什么程度 / 接口定义 / 验收用例。

---

## 三部检查法

**意图**: diff 是否实现 SPEC 描述？是否有范围外改动？删除代码是否确实废弃？

**质量**: 命名/返回格式/错误码是否合规？风格是否一致？是否有 debug 残留？

**边界**: null/空输入→明确错误 / timeout 有默认值 / IO 失败不抛裸异常 / 共享状态线程安全性

---

## 测试约定

### 测试清单

| 模块 | 测试文件 | 类型 | 用例数 |
|------|---------|------|--------|
| miniclaw-tools | EditToolTest | 单元 | 9 |
| miniclaw-tools | GlobToolTest | 单元 | 5 |
| miniclaw-tools | GrepToolTest | 单元 | 6 |
| miniclaw-tools | TodoWriteToolTest | 单元 | 6 |
| miniclaw-tools | WebFetchToolTest | 单元 | 12 |
| miniclaw-tools | CommandSafetyInterceptorTest | 单元 | 8 |
| miniclaw-context | LadderedCompactorTest | 单元 | 6 |
| miniclaw-engine | AgentEngineTest | Mock 集成 | 10 |
| miniclaw-engine | PermissionModeTest | Mock 集成 | 9 |
| miniclaw-engine | ReadToolIntegrationTest | E2E 集成 | — |
| miniclaw-engine | WriteBashIntegrationTest | E2E 集成 | — |
| miniclaw-engine | EditIntegrationTest | E2E 集成 | — |
| miniclaw-provider | OpenAIProviderTest | 单元 | 14 |
| miniclaw-memory | YamlFrontmatterParserTest | 单元 | 8 |
| miniclaw-memory | FileMemoryStoreTest | 单元 | 8 |
| miniclaw-memory | DiskMemoryServiceTest | 单元 | 8 |
| miniclaw-provider | DeepSeekConnectivityTest | 连通性 | — |

### 测试原则

- **Mock 验证必须输出完整日志** — 每个模块的 `test` scope 必须包含 `logback-classic`，确保 Agent Loop 的执行轨迹完整可见
- Mock 测试验证三条：引擎是否达到预期轮次、工具是否被正确调用、最终返回值是否正确
- 集成测试通过真实 API Key 调用 LLM，验证端到端 ReAct 行为

---

*本文件是 miniclaw 的唯一权威开发纲领。规范变更必须先改本文件，再改代码。*
