# clawkit 项目纲领

本文档是项目定位、架构边界和 AI 协作规则的权威入口。它回答“项目是什么、边界在哪里、协作时必须遵守什么”。

具体工程设计看 [DESIGN.md](DESIGN.md)，当前实施顺序和完成状态看 [TODO.md](TODO.md)，用户使用方式看 [README.md](README.md)。

## 文档职责

| 文档 | 负责 | 不负责 |
| --- | --- | --- |
| `README.md` | 安装、配置、运行、示例、用户入口 | 内部重构计划 |
| `CLAUDE.md` | 项目定位、产品边界、模块原则、协作强约束 | 详细接口规范和任务状态 |
| `DESIGN.md` | 稳定的架构、契约、安全、测试和审查规范 | 临时方案、完成记录和优先级 |
| `TODO.md` | 当前事实、未完成任务、依赖顺序、验收标准 | 长篇设计原则 |

发生冲突时按职责归属判断：项目边界以本文为准，工程规则以 `DESIGN.md` 为准，当前状态和顺序以 `TODO.md` 为准。

## 协作入口

开始工作前按任务类型读取文档：

| 任务 | 必读 |
| --- | --- |
| 修 bug、加功能、修改工具/Provider/CLI | `DESIGN.md` + 相关代码 |
| 底层重构、调整优先级、更新路线 | `TODO.md` + `DESIGN.md` + 相关代码 |
| 改模块依赖、公共契约或项目边界 | 本文 + `DESIGN.md` + `TODO.md` |
| 改 README、示例、CI、Docker、发布 | `README.md` + `TODO.md` |
| 改权限、文件写入、命令执行、MCP、审计 | `SECURITY.md` + `DESIGN.md` |

强规则：

- 先核对代码事实，再写方案或修改完成状态；文档记录不能替代代码验证。
- 核心重构必须先确认 `TODO.md` 中对应主链和前置条件。
- 公共契约、模块边界、权限模型或路线变化，必须同步更新对应文档。
- 重构默认不改变外部行为；需要改变时，先写清验收标准和回归测试。
- 不因“创建了新类、接口或模块”宣称解耦完成；只有旧运行路径已迁移并删除才算完成。
- 发现密钥、Token、Webhook 或私有配置时停止传播，按 `SECURITY.md` 处理。

## 项目定位

clawkit 是 Java 21 实现的本地 AI 编程 Agent 底座，主要运行形态是 CLI，也支持 IM 通道镜像。

核心价值不是某个垂类效果，而是提供通用、可控、可测、可靠、可观测、可扩展的 Agent runtime：

- 本地工作区内的代码分析、文件操作、命令执行和任务编排。
- ReAct、Plan-and-Execute、SubAgent 和慢思考等执行模式。
- 内置工具与 MCP 工具的统一契约、权限和审计。
- 上下文预算、压缩、会话、记忆和 Skill 注入。
- OpenAI-compatible Provider 的适配、重试、熔断和流式解析。

## 产品边界

项目做：

- 本地 CLI/IM 输入输出适配。
- Agent loop、计划执行、工具编排和人工审批。
- Provider、工具、上下文、记忆、观测等底座能力。
- 通过 MCP、Skill、工具包和 workflow 接入扩展。

项目不做：

- 中心化 Gateway 或多租户调度平台。
- Web 前端和通用运维控制台。
- 将浏览器自动化或某个业务领域写死到核心引擎。
- 在权限、审计和回滚不完整时提供自动高风险远程写操作。

垂类能力应放在插件、MCP server、Skill、业务工具包或上层 workflow 中。确定性任务优先使用结构化 workflow；ReAct 用于探索、追问和异常兜底。

## AI Coding 实践原则

复杂任务按三阶段推进（低风险可合并）：

1. **需求分析与技术调研** — 明确范围、验收标准、边界场景和风险分级；产出结构化任务单（背景/目标/非目标/模块/验收/风险）。
2. **方案设计与评审** — 确定模块边界、接口契约、异常策略和测试计划；架构、权限、数据和安全决策由人确认。
3. **实现、测试与代码评审** — 小步执行，每步可编译可测试；完成后按验收标准逐项提供真实验证证据。

日常原则：低风险细节 AI 自主处理；涉及架构、权限、数据、安全或兼容性时由人确认。不因完成任务删除或跳过失败测试。规范先于编码，架构先于填充。

## 质量目标

| 目标 | 含义 |
| --- | --- |
| 可控 | 工作目录、工具风险、审批和副作用边界明确 |
| 可测 | Provider、工具、上下文和执行模式能独立验证 |
| 可靠 | 超时、失败、上下文膨胀和死循环有明确处理 |
| 可观测 | run、turn、provider、tool、compact、approval 可追踪 |
| 可扩展 | 新 Provider、工具来源和入口通道不污染核心流程 |

## 架构方向

目标分层：

```text
CLI / IM adapters
        |
Application composition
        |
Agent runtime / Plan runtime
        |
Context pipeline ---- Memory hooks
        |
Provider contract --- Tool execution contract
        |
Built-in tools / MCP / local stores / observability
```

模块职责：

| 模块 | 应负责 | 不应负责 |
| --- | --- | --- |
| `clawkit-tools` | 工具契约、内置工具、MCP adapter、安全拦截、ExecutionControl/Action 契约 | Agent loop、终端 UI |
| `clawkit-reliability` | 取消树、预算账本、Attempt journal/状态机、目标互斥、Side Effect Gate、恢复扫描 | Agent loop、终端 UI、具体 Ops 领域 |
| `clawkit-provider` | 模型通信、协议解析、重试、熔断 | 工具执行、上下文决策 |
| `clawkit-context` | Prompt、预算、压缩、消息裁剪、Skill 上下文 | 会话持久化、工具副作用 |
| `clawkit-memory` | 记忆存储、检索和演进 | Agent 执行流程 |
| `clawkit-observability` | 运行事件、指标模型、记录和读取 | 业务编排 |
| `clawkit-engine` | Agent runtime、执行模式、工具调用编排 | 终端 UI、具体 Provider JSON |
| `clawkit-im` | 飞书、微信等通道适配 | Agent 状态机 |
| `clawkit-cli` | 启动装配、REPL、命令路由、展示和审批 UI | 核心业务规则 |

依赖原则：

- 模块依赖必须单向、显式、无循环；源码 import 不能依赖 Maven 传递依赖碰巧可见。
- `tools`、`memory` 和 `reliability` 是基础模块，不依赖 engine/cli/im；`reliability` 只依赖 `tools` 契约。
- `engine` 依赖稳定的 Provider、Tool、Context、Memory、Observability、Reliability 契约。
- `cli` 和 `im` 是入口适配层，不拥有 Agent 核心状态机。
- 新能力放入能表达职责的最窄模块；组合根可以装配具体实现，但不能承载业务逻辑。

## 当前实现事实

项目已经具备可运行的多模块原型、P0 四条主链和 P1-G 写操作强制门禁：

- `AgentEngine` 仍是千行以上的大类，保留 ReAct 主循环与公共门面；工作区、事件、会话、上下文、内部工具、SubAgent、Plan runtime、独立验证已拆分到独立组件。
- `ApplicationBootstrap` 是唯一组合根，并承担进程启动的可靠性恢复扫描；REPL、命令、审批业务已下沉。
- 普通 ReAct、Plan-and-Execute、SubAgent 和 internal tools 已进入同一工具执行链；`ToolCallExecutor` 同时是唯一 Side Effect Gate——副作用工具必须生成 `ActionDescriptor`，否则 fail closed。
- `clawkit-reliability` 承载取消/预算/Attempt journal/目标互斥/恢复扫描；结果未知 sticky、durable DISPATCH_INTENT、MANUAL_REQUIRED 不自动成功等硬门禁由测试机械断言。
- 本地观测覆盖 run、turn、provider、tool、compact、approval 与 attempt 迁移事件；并发 run 隔离与 reader 容错已建立。

当前真实状态和下一步以 `TODO.md` 为准，不在本文维护逐项完成记录。

## 开发与重构流程

```text
代码事实
  -> 小范围方案和验收标准
  -> 行为/回归测试
  -> 迁移一条运行路径
  -> 删除旧路径
  -> 相关测试 + diff 检查
  -> 更新 TODO 和必要文档
```

实施要求：

- 一个 PR 只处理一个职责边界或一条运行路径。
- 方案必须列出修改范围、禁止事项、行为变化、测试命令和回滚方式。
- 评审先查 bug、权限绕过、失败路径、模块边界和漏测，再看风格。
- 不通过删除断言、吞异常或降低安全检查换取测试通过。
- 工作区可能已有用户修改；不得覆盖或清理无关变更。

## 完成定义

一项底层工作只有同时满足以下条件才算完成：

- 目标运行路径已使用新边界，旧实现已删除或有明确删除条件。
- 成功、失败和权限边界有自动化测试。
- 结构化错误、审计和 metrics 没有退化。
- 模块依赖和源码 import 与文档一致。
- 相关文档与 TODO 状态已同步。
- 相关测试和 `git diff --check` 已执行；无法执行时记录原因和残余风险。

本文只维护稳定纲领，不记录临时方案和每日进度。
