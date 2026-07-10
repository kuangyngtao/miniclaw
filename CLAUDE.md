# clawkit 项目开发纲领

> 文档分工：本文件负责项目定位、架构边界和 AI 协作约束；详细工程设计规范见 [DESIGN.md](DESIGN.md)；路线图和重构任务见 [TODO.md](TODO.md)。

## AI 协作入口规则

`CLAUDE.md` 是 AI 协作者进入项目时的入口索引。开始任何代码修改前，先判断任务类型，并读取对应文档。

| 任务类型 | 必读文档 | 原因 |
| --- | --- | --- |
| 修 bug、加功能、改工具、改 Provider、改 CLI | [DESIGN.md](DESIGN.md) | 确认模块边界、接口契约、权限、安全、测试和代码审查规则 |
| 底层重构、任务排序、路线调整、能力取舍 | [TODO.md](TODO.md) | 确认当前优先级、阶段顺序、前置条件和验收标准 |
| 修改架构边界、模块依赖、项目原则 | [CLAUDE.md](CLAUDE.md) + [DESIGN.md](DESIGN.md) + [TODO.md](TODO.md) | 同步项目纲领、设计规范和路线图 |
| 修改 README、示例、部署、CI、Docker | [README.md](README.md) + [TODO.md](TODO.md) | 保持用户入口和工程闭环路线一致 |
| 处理安全、密钥、权限、审计问题 | [SECURITY.md](SECURITY.md) + [DESIGN.md](DESIGN.md) | 避免泄露密钥、绕过审批或破坏审计链路 |
| 方案评审（设计提案审查） | [DESIGN.md](DESIGN.md) + [TODO.md](TODO.md) + 相关核心代码 | 确认设计不违反约束、代码对照无误、未覆盖的风险点回写 TODO |

强规则：

- 不要只读 `CLAUDE.md` 就直接修改核心代码；涉及实现细节时必须参考 `DESIGN.md`。
- 不要凭临时判断调整开发顺序；涉及路线和优先级时必须参考 `TODO.md`。
- 重构类任务必须先看 `TODO.md` 的 `P0-R`，再看 `DESIGN.md` 的重构规范和代码审查规范。
- 新增工具、MCP、远程执行、文件写入、消息发送等能力，必须先定义风险等级、权限模式、审计字段和测试策略。
- 如果实现改变了公共契约、模块边界、权限模型、工具协议或路线优先级，必须同步更新相关文档。
- 文档冲突时，以职责归属判断：项目边界看 `CLAUDE.md`，工程规范看 `DESIGN.md`，执行路线看 `TODO.md`。
- 方案评审必须做代码对照：方案中的每个设计决策需注明对应代码位置（文件名 + 行号），用代码事实约束设计假设。
- 方案评审结束后，评审发现的问题必须以 `[ ]` 条目写回 [TODO.md](TODO.md)，避免评审结论丢失。

## AI 协作典型流程

```text
1. 方案设计（强模型）
   输出：完整可实现方案，含关键代码对照
   ↓
2. 方案评审（交叉模型）
   读 DESIGN.md + TODO.md + 相关核心代码 → 输出评审意见
   发现的问题回写 TODO.md
   ↓
3. 实施（便宜模型）
   按 Phase 拆分，每阶段只做一个模块
   ↓
4. 每阶段验证
   mvn -pl <module> -am test，不过不进入下一阶段
   ↓
5. 收尾
   更新 TODO.md → rebuild jar → commit
```

核心原则：方案从代码出发（不是从假设出发），评审用不同模型（不是同一视角），结论回写文档（不是仅存脑记）。

## 项目定位

clawkit 是 [OpenClaw](https://openclaw.ai/) 的 Java CLI 实现，是一个本地运行的 AI 编程 Agent 底座。

它的核心价值不是某一个垂类场景，而是把 Agent 的基本功做好：

- **可控**: 工具、权限、审批、工作目录边界清晰。
- **可测**: 工具、上下文、Provider、Agent loop 都能独立验证。
- **可靠**: 模型超时、工具失败、上下文膨胀、任务卡死都有降级路径。
- **可观测**: token、工具调用、失败原因、缓存命中、任务轮次都能落盘分析。
- **可扩展**: 内置工具和 MCP 工具遵循同一套 `Tool` 契约。

### 做什么

- CLI Agent：本地 REPL、slash 命令、权限模式、HITL 审批。
- Agent 引擎：ReAct loop、Plan-and-Execute、SubAgent、慢思考、流式输出。
- 工具体系：8 个内置工具 + MCP 动态扩展 + 安全拦截链。
- 上下文工程：Prompt 组装、MessageMasker、LadderedCompactor、会话持久化、记忆注入。
- LLM Provider：OpenAI-compatible Provider、超时、重试、熔断、流式解析。
- IM 通道：飞书 / 微信等消息入口，作为 CLI 能力的远程镜像。

### 不做什么

- 不做 Gateway。
- 不做中心化多 Agent 调度平台。
- 不做 Web 前端。
- 不把浏览器自动化内置为核心能力；需要时通过 MCP/工具接入。
- 不把某个垂类业务写死到底层引擎里。

### 产品边界

clawkit 的底层应保持通用。垂类应用应该以插件、MCP server、Skill、业务工具包或上层 workflow 的方式接入。

ReAct 是一种执行模式，不是唯一产品形态。对确定性任务，应优先使用 workflow、结构化工具和指标复盘；ReAct 主要用于探索、追问、异常归因和工具失败兜底。

---

## 架构分层

| 层 | 职责 | 关键能力 |
|----|------|---------|
| 入口交互层 | 用户输入、命令、审批、输出 | JLine3 REPL、Picocli、slash 命令、Ctrl+C、HITL 审批、IM 镜像 |
| 核心引擎层 | Agent loop 和任务编排 | ReAct、Plan-and-Execute、SubAgent、并行工具调用、三层迭代控制 |
| 上下文工程层 | Prompt、历史、记忆、压缩 | PromptAssembly、MessageMasker、LadderedCompactor、SessionService、Memory |
| Provider 层 | 模型 API 适配 | OpenAI-compatible API、流式解析、超时、重试、熔断 |
| 工具执行层 | 工具注册、调用和拦截 | ToolRegistry、SafetyInterceptor、内置工具、MCP adapter |
| 存储与观测层 | 本地状态和运行证据 | logs、sessions JSON、Markdown memory、metrics.jsonl |

核心哲学：上下文即缓存，磁盘即真相。

层间通信：

```text
CLI / IM
  -> AgentEngine
  -> LLMProvider
  -> tool_calls
  -> ToolRegistry
  -> SafetyInterceptor
  -> Tool.execute
  -> ToolResult
  -> 回注上下文
```

---

## 执行模式

| 模式 | 适用场景 | 约束 |
|------|---------|------|
| ReAct | 开放式代码任务、调试、探索、归因 | 必须受最大轮次、进度检测和工具权限限制 |
| Plan-and-Execute | 多步骤任务、可拆 DAG、需要复盘的工作 | 计划应写入 `.clawkit/plan.md`，执行结果可追踪 |
| SubAgent | 并行搜索、只读探索、独立子任务 | 子 Agent 不递归再派发 SubAgent |
| TWO_STAGE | 复杂问题先规划再执行 | 第一阶段禁止工具调用 |
| Workflow | 周期性、确定性、可指标化任务 | 优先用于垂类业务和固定流程 |

---

## 模块依赖

| 模块 | 内部依赖 | 外部依赖 |
|------|---------|---------|
| `clawkit-tools` | 无 | Jackson, SLF4J |
| `clawkit-memory` | 无 | Jackson, Jackson YAML, SLF4J |
| `clawkit-context` | tools | SLF4J |
| `clawkit-provider` | tools | Jackson, JDK HTTP, SLF4J |
| `clawkit-engine` | tools, provider, context, memory | SLF4J |
| `clawkit-cli` | engine, memory, im | Picocli, Logback |
| `clawkit-im` | engine | FeishuApi + FeishuChannel + WeixinChannel + WeixinIlinkClient, Jackson |

约束：`tools` 是唯一基础层，`engine` 是组装点，`cli` 不直接依赖 tools/provider。禁止循环依赖。

新增能力优先放在最窄的模块里：

- 工具协议、MCP、拦截器放 `clawkit-tools`。
- Prompt、压缩、Skill 加载放 `clawkit-context`。
- 模型协议、重试、熔断、fallback 放 `clawkit-provider`。
- Agent loop、权限、计划执行、会话检索放 `clawkit-engine`。
- 终端交互、命令路由、启动组装放 `clawkit-cli`。

---

## 基座设计原则

1. **上下文即缓存，磁盘即真相**：会话、计划、任务、记忆和指标都应能落盘追踪。模型上下文只是运行时缓存，不是唯一状态源。
2. **工具原子化**：一个工具只做一件事。工具输入必须有 JSON Schema，输出必须能被上层稳定解析。
3. **失败透明**：已知错误不要裸抛异常。错误信息必须包含错误码、上下文和可操作建议。
4. **权限先于能力**：新写工具必须先定义读写属性、风险等级、审批策略和审计证据。
5. **确定性优先**：能用确定性 workflow 解决的任务，不依赖自由 ReAct 循环。ReAct 用于探索和兜底。
6. **先观测再优化**：没有指标的优化不可合入为“性能提升”。至少要能记录 token、耗时、工具成功率或任务完成率。

---

## 权限模型

| 模式 | 行为 |
|------|------|
| `PLAN` | 只暴露只读工具，禁止写操作 |
| `ASK` | 写工具执行前触发审批 |
| `AUTO` | 自动执行所有已注册工具，仍受 SafetyInterceptor 限制 |

工具风险默认原则：

- 只读查询可以并行。
- 写文件、改状态、发消息、远程执行命令必须可审计。
- 高风险动作必须要求人审，不能通过提示词绕过。

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

- **日志**: `~/.clawkit/logs/clawkit.log`，按天滚动保留 7 天
- **API Key**: 环境变量 > `~/.clawkit/config.yaml` > 交互输入。硬编码零容忍
- **工作目录**: 默认启动目录，`--root` 限制范围。元数据统一在 `~/.clawkit/`
- **存储**: 文件系统即数据库。logs + config + Markdown 记忆 + sessions JSON

---

## 当前能力快照

已具备的底座能力：

- 交互：JLine3 REPL、Picocli CLI、slash 命令、Ctrl+C 中断、HITL 审批。
- 引擎：ReAct loop、TWO_STAGE、SubAgent、Plan-and-Execute、并行工具调用、三层迭代控制。
- 上下文：PromptAssembly、MessageMasker、LadderedCompactor、SessionService、DiskMemoryService、SkillLoader。
- 工具：read/write/edit/bash/glob/grep/todo_write/web_fetch，统一 `Tool` 契约。
- MCP：stdio / HTTP 双传输、schema 清洗、动态注册、审计日志、故障隔离。
- Provider：OpenAI-compatible API、流式输出、工具调用解析、超时重试、熔断。
- IM：飞书、微信通道和统一 IM 抽象。

最近记录：295/295 测试通过（tools 66 + provider 14 + context 45 + memory 32 + engine 69 + im 23 + cli 46）。该数字是文档记录，实际合入前仍以本地测试为准。

---

## Provider 韧性

| 能力 | 设计 |
|------|------|
| 超时控制 | 连接 10s + 请求 60s，`LLMConfig` 配置 |
| 重试策略 | 3 次指数退避（2s -> 4s -> 8s），仅对 429/5xx/IO 超时重试 |
| 熔断器 | 连续 5 次失败快速返回错误 |

后续增强优先级：

1. 降级链路：主模型超时或熔断后自动切换备用模型。
2. 错误分类：区分鉴权、限流、上下文过长、工具 schema 错误和模型内容错误。
3. 流式早期终止：发现明显幻觉或协议破坏时中止并重试。
4. 多模型路由：在指标可观测后再做成本和质量分流。

---

## 工程规范入口

具体命名、类设计、接口设计、测试分层和代码审查标准统一维护在 [DESIGN.md](DESIGN.md)。本文件只保留 AI 协作者需要优先记住的项目边界：

- 不把实现细节塞进入口文档；公共契约变化时更新 `DESIGN.md`。
- 不把路线图塞进入口文档；任务优先级变化时更新 `TODO.md`。
- 不把用户使用说明塞进入口文档；安装、运行和示例变化时更新 `README.md`。
- 已知错误走结构化结果和错误码，不通过裸异常或模糊字符串传递。
- 核心逻辑不直接输出到 `System.out`，输出展示应留在 CLI/IM 入口层。
- 新增副作用能力必须先补权限、审计和测试策略。

---

## 上下文工程准则

上下文工程是 clawkit 的核心基本功。任何新增记忆、压缩、工具结果缓存或 prompt 层级调整都必须遵循：

- 不丢关键约束：用户目标、文件路径、测试结果、错误信息、未完成 todo 必须优先保留。
- 不污染当前任务：历史记忆和会话召回必须标明来源，不能当作当前事实。
- 不用字符数冒充长期 token 预算：应逐步迁移到真实 tokenizer。
- 工具结果应结构化截断：优先保留命中片段、文件路径、行号、错误上下文。
- compact 后应能解释：压缩摘要应保留它由哪些消息或工具结果生成。

---

## 工具系统边界

| 工具 | 读/写 | 安全机制 |
|------|:---:|------|
| read | 读 | 路径穿越防护、8000 字节截断 |
| write | 写 | 路径穿越防护、自动建父目录 |
| edit | 写 | 四级模糊匹配、唯一性校验 |
| bash | 写 | 30s 超时强杀、workDir 绑定、高危命令拦截 |
| glob | 读 | 200 条 + 8000 字节双截断 |
| grep | 读 | 50 条 + 200 字符/行 + 8000 字节三层截断 |
| todo_write | 写 | JSON schema 校验、双写到 .clawkit/todo.md |
| web_fetch | 读 | 15min TTL 缓存、1MB/8000 双截断 |

工具设计细则见 [DESIGN.md](DESIGN.md)。当前路线要求逐步从 `execute(String) -> Result<String>` 演进到结构化的 `ToolMetadata`、`ToolExecutionRequest`、`ToolExecutionResult`。

默认工具工作流：`glob -> grep -> read -> edit -> test`。

---

## 开发流程与检查

标准流程：

```text
SPEC / TODO
  -> 实现
  -> 单元测试
  -> 相关集成验证
  -> diff 自查
  -> 更新文档
```

- **意图**: diff 是否实现 SPEC？是否有范围外改动？
- **质量**: 命名/错误码/风格合规？无 debug 残留？
- **边界**: null/空 -> 明确错误 / timeout 默认值 / IO 不抛裸异常 / 线程安全

---

## 演进原则

下一阶段重点不是继续堆“更多 Agent 能力”，而是补齐底层基座基本功：

1. 上下文预算可信。
2. 工具结果可信。
3. 失败可恢复。
4. 过程可观测。
5. 任务可评测。
6. 写操作可审计。

具体优先级以 [TODO.md](TODO.md) 为准。

---

本文件是 clawkit 的权威开发纲领。架构边界、模块依赖和核心原则变更，应先更新本文件，再修改实现。
