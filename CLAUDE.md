# miniclaw 项目开发纲领

## 项目定义

miniclaw 是 [OpenClaw](https://openclaw.ai/) 的 Java CLI 实现——本地 AI 编程助手。

- **做什么**: CLI Agent + 飞书 IM 通道，8 内置工具（read/write/edit/bash/glob/grep/todo_write/web_fetch）+ MCP 协议支持（通用 client，stdio + HTTP 双传输，动态工具扩展）
- **不做什么**: Gateway、多 Agent 调度、浏览器自动化、Web 前端
- **做到什么程度**: JVM 本地运行、CLI + 飞书双通道、工具独立可测、MCP 生态兼容

---

## 四层架构

| 层 | 职责 | 已实现 |
|----|------|--------|
| 入口交互层 | CLI REPL + HITL 审批 | JLine3 REPL（历史+补全+Ctrl+C）、HITL 审批面板（风险分级+四选项 y/a/n/m）、权限模式切换、`/clear` `/compact` `/context`、工具调用可视化（[..]/[OK]/[ER]） |
| 核心引擎层 | ReAct Loop + LLM 适配器 | ReAct Loop、三层迭代控制（死循环+进度提醒+硬上限）、OpenAI/DeepSeek Provider（含熔断器）、TWO_STAGE 慢思考、流式输出、并行工具调用、Ctrl+C 中断、SubAgent 引擎 |
| 上下文工程层 | Prompt 组装 + Token 截断 + 记忆 | LadderedCompactor + MessageMasker（mask→compact 管线）、状态外部化（todo_write→.miniclaw/todo.md + PLAN.md 同步 + 自举检测）、PromptAssembly 5 层、SkillLoader、memory_save、DiskMemoryService、SessionService（BM25 检索 + 自动保存）、L3 自动记忆提取 |
| 工具与执行层 | ToolRegistry + Middleware | 8 内置工具 + MCP 动态扩展、EditTool 四级模糊匹配、SafetyInterceptor 链、CommandSafetyInterceptor 8 规则、McpManager 并行启动 + 故障隔离 |

核心哲学：上下文即缓存，磁盘即真相。

层间通信：`CLI → AgentLoop → LLM → tool_call → executeParallel/Sequential → ToolRegistry.execute (interceptor chain) → Tool.execute → Result → 回注 prompt`

---

## 模块依赖

| 模块 | 内部依赖 | 外部依赖 |
|------|---------|---------|
| `miniclaw-tools` | 无 | Jackson, SLF4J |
| `miniclaw-memory` | 无 | Jackson, Jackson YAML, SLF4J |
| `miniclaw-context` | tools | SLF4J |
| `miniclaw-provider` | tools | Jackson, JDK HTTP, SLF4J |
| `miniclaw-engine` | tools, provider, context | SLF4J |
| `miniclaw-cli` | engine, memory | Picocli, Logback |
| `miniclaw-feishu` | engine, tools | FeishuApi + FeishuBot + FeishuConfig + TokenBatcher |

约束：`tools` 是唯一基础层，`engine` 是组装点，`cli` 不直接依赖 tools/provider。禁止循环依赖。

---

## 技术选型

| 项 | 选型 |
|----|------|
| Java | 21 LTS（虚拟线程、sealed class、record） |
| 构建 | Maven 3.9+ |
| CLI | Picocli（注解驱动、零依赖） |
| JSON/YAML | Jackson |
| HTTP | `java.net.http`（JDK 内置） |
| 测试 | JUnit 5 + AssertJ |
| 日志 | SLF4J + Logback |

明确排除：Spring Boot/Shell、Gradle、OkHttp。

---

## 运维

- **日志**: `~/.miniclaw/logs/miniclaw.log`，按天滚动保留 7 天
- **API Key**: 环境变量 > `~/.miniclaw/config.yaml` > 交互输入。硬编码零容忍
- **工作目录**: 默认启动目录，`--root` 限制范围。元数据统一在 `~/.miniclaw/`
- **存储**: 文件系统即数据库。logs + config + Markdown 记忆 + sessions JSON

---

## 演进路径

| 阶段 | 功能 | 状态 |
|------|------|------|
| V1 | 超时+重试+虚拟线程 | ✅ |
| V1.5 | 并行工具调用 + 流式输出 + 权限模式 | ✅ |
| V2 | 多轮上下文 + 三层迭代控制 + 阶梯压缩 | ✅ |
| V2.5 | TodoWrite + isReadOnly 下沉 + 安全拦截器 | ✅ |
| V2.7 | TWO_STAGE + Ctrl+C 中断 + 熔断器 | ✅ |
| V2.8 | MemoryStore 磁盘记忆 | ✅ |
| V2.9 | 飞书 IM 通道 | ✅ |
| V2.10 | CLI ↔ 飞书双向共享 | ✅ |
| V2.11 | JLine3 REPL | ✅ |
| V2.12 | 会话持久化（JSON + LLM 摘要 + session_context） | ✅ |
| V2.13 | LadderedCompactor 角色感知增强 | ✅ |
| V3 | SubAgent 引擎（task + explore/general + 并行派发） | ✅ |
| V3.1 | AgentState 状态机 + Reporter 抽象 | ✅ |
| V3.3 | 提示词分层加载（PromptAssembly 5 层） | ✅ |
| V3.4 | 技能外挂系统（SkillLoader + /skill） | ✅ |
| V3.4a | MessageMasker 上下文掩码（4 层 Tier + mask→compact） | ✅ |
| V3.4c | memory_save 引擎内部工具 | ✅ |
| V3.4d | 状态外部化（todo.md 双写 + PLAN.md 同步 + 自举检测） | ✅ |
| V3.6 | 工作内存（remember 工具 + ConcurrentHashMap + 每轮注入） | ✅ |
| V3.7 | 子目标跟踪（todo 进展检测 + 3轮无进展警告 + allTodosCompleted 判定） | ✅ |
| V3.8a | L3 自动记忆提取（提示词驱动 + 静默 LLM + 条件触发） | ✅ |
| V3.8b | L4 会话检索升级（SessionService BM25 替换子串匹配） | ✅ |
| V3.8c | 会话自动保存（clearSession 钩子 + [auto] 命名） | ✅ |
| V3.8d | 启动注入相关历史会话摘要 | ✅ |
| V3.9 | Plan-and-Execute（任务DAG + 逐级并行 + 失败重试 + 槽位传递） | ✅ |
| V3.4e | HITL 审批面板（ApprovalRequest + RiskLevel + y/a/n/m 四选项 + autoApprovedTools） | ✅ |
| V3.10 | MCP 协议支持（通用 client + stdio/HTTP 双传输 + schema 清洗 + 审计日志 + 两级配置 + /mcp CLI） | ✅ |
| — | `/btw` 后台并行任务 | 待做 |
| — | 多模型路由（Anthropic 原生 API） | 待做 |
| — | 上下文工程优化（Masker-Compactor 去冗余 / 真实 tokenizer / 自适应分层 / L3 异步 / prompt caching） | 待做 |
| V3.5 | GraalVM native-image | 远期 |

测试: 271/271 通过（tools 66 + context 45 + engine 69 + provider 14 + memory 32 + cli 45）

---

## LLM 调用韧性

| 能力 | 设计 |
|------|------|
| 超时控制 | 连接 10s + 请求 60s，`LLMConfig` 配置 |
| 重试策略 | 3 次指数退避（2s→4s→8s），仅对 429/5xx/IO 超时重试 |
| 熔断器 | 连续 5 次失败快速返回错误 |

---

## 规范体系

### 命名

| 元素 | 风格 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.miniclaw.tools` |
| 类/接口 | PascalCase | `AgentLoop`, `Tool` |
| 方法/变量 | camelCase | `executeTool` |
| 常量/枚举值 | UPPER_SNAKE_CASE | `MAX_RETRIES` |
| 测试类 | 被测类名+Test | `ReadToolTest` |

### 代码组织

- 接口与实现分离：公开接口放包根，实现类放 `impl/` 子包
- 测试目录镜像 `src/test/java/` 完整镜像 `src/main/java/` 的包结构
- 无循环依赖

### 统一返回格式

```java
public sealed interface Result<T> {
    record Ok<T>(T data) implements Result<T> {}
    record Err<T>(ErrorInfo error) implements Result<T> {}
}
public record ErrorInfo(String errorCode, String message) {}
```

禁止业务代码抛异常传递已知错误。

### 错误码

```
T-001~T-005  工具系统    E-001~E-002  Edit 工具
A-001~A-003  Agent       S-001~S-003  Skills
C-001~C-003  配置        X-001~X-002  通用
```

### 设计原则

1. **约定优于配置** — 目录/注解/接口决定行为
2. **渐进式复杂度** — 核心 Loop < 200 行，Tool 接口 < 5 方法
3. **工具原子化** — 一个 Tool 做一件事，统一 `Result<T>` 返回
4. **失败透明** — 错误必须含：错误码 + 上下文 + 可操作信息

---

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

### 工具清单

| 工具 | 读/写 | 安全机制 |
|------|:---:|------|
| read | 读 | 路径穿越防护、8000 字节截断 |
| write | 写 | 路径穿越防护、自动建父目录 |
| edit | 写 | 四级模糊匹配、唯一性校验 |
| bash | 写 | 30s 超时强杀、workDir 绑定、高危命令拦截 |
| glob | 读 | 200 条 + 8000 字节双截断 |
| grep | 读 | 50 条 + 200 字符/行 + 8000 字节三层截断 |
| todo_write | 写 | JSON schema 校验、双写到 .miniclaw/todo.md |
| web_fetch | 读 | 15min TTL 缓存、1MB/8000 双截断 |

全局安全拦截器：`rm -rf /~/*`、`sudo`、`chmod 777`、`> /dev/sd`、`mkfs`、`dd`、fork bomb

工具工作流：`glob → grep → read → edit`

---

## 开发流程与检查

**SPEC.md（三问裁剪）→ 实现 → 三部检查 → 合入**

- **意图**: diff 是否实现 SPEC？是否有范围外改动？
- **质量**: 命名/错误码/风格合规？无 debug 残留？
- **边界**: null/空→明确错误 / timeout 默认值 / IO 不抛裸异常 / 线程安全

---

*本文件是 miniclaw 的唯一权威开发纲领。规范变更必须先改本文件，再改代码。*
