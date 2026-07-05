# miniclaw

[![CodeQL](https://github.com/kuangyngtao/miniclaw/actions/workflows/codeql.yml/badge.svg)](https://github.com/kuangyngtao/miniclaw/actions/workflows/codeql.yml)

> Java 本地 AI 编程 Agent，面向代码仓库的 CLI 编程助手

miniclaw 是一个基于 Java 的本地编程 Agent。它将 LLM Provider、工具调用、权限模式、上下文管理、记忆、会话和 MCP 扩展组合成一个 CLI 运行时，用于探索 AI Agent 在本地代码仓库中的执行流程。

当前项目处于原型开发和底层重构阶段，优先完善任务执行稳定性、工具安全边界、上下文管理和可测试性。

## 项目概览

| 维度 | 说明 |
| --- | --- |
| 项目类型 | Java 多模块 AI Agent / CLI 编程助手 |
| 运行方式 | 本地 CLI |
| 核心目标 | 在本地代码仓库中完成代码分析、文件搜索、工具调用、代码修改和任务执行 |
| 关键机制 | ReAct、Plan-and-Execute、权限模式、MCP、上下文压缩、会话记忆 |
| 当前阶段 | 原型开发中，重点建设底层 Agent 运行时 |

## 项目目标

miniclaw 关注的是编程 Agent 的底层运行能力，而不是单次对话效果。项目目标包括：

- **本地优先**：围绕本地仓库运行，支持指定项目目录作为 Agent 工作空间。
- **工具可控**：通过统一工具层执行读文件、搜索、编辑、命令运行等操作。
- **权限分级**：通过 `plan`、`ask`、`auto` 模式区分不同风险级别的操作。
- **上下文可管理**：跟踪上下文使用情况，支持压缩、会话和磁盘记忆。
- **扩展可插拔**：通过 MCP 接入外部工具，通过 IM 模块接入消息通道。
- **基座通用**：核心运行时保持通用，垂类能力放在上层扩展。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| Agent 任务循环 | ReAct Loop、慢思考模式、Plan-and-Execute、SubAgent |
| 工具系统 | 内置工具、MCP 工具、Tool Registry、工具安全拦截 |
| 权限模式 | `plan` / `ask` / `auto`，控制工具执行边界 |
| 上下文管理 | Token 使用跟踪、阶梯式压缩、消息脱敏、会话持久化 |
| 记忆系统 | 基于磁盘文件的长期记忆，用于跨任务复用上下文 |
| Provider 适配 | LLM Provider 抽象、超时、重试、熔断 |
| IM 通道 | 抽象消息通道，预留飞书、微信等入口 |

## 工程状态

| 类型 | 状态 |
| --- | --- |
| 本地 CLI 运行 | 已支持 |
| Maven 多模块构建 | 已支持 |
| Shaded JAR 打包 | 已支持 |
| 单元测试 | 已覆盖主要模块 |
| CodeQL 安全扫描 | 已支持 |
| 安全策略与 Dependabot | 已支持 |
| Docker 容器化 | 计划补齐 |
| CI 测试流水线 | 计划补齐 |
| GitHub Release / 镜像发布 | 后续规划 |

## 快速开始

```bash
git clone https://github.com/kuangyngtao/miniclaw.git
cd miniclaw
mvn package -pl miniclaw-cli -am -DskipTests
```

配置 API Key：

```bash
export MINICLAW_API_KEY=<your-api-key>
```

Windows PowerShell：

```powershell
$env:MINICLAW_API_KEY = "<your-api-key>"
```

启动 CLI：

```bash
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar
```

常用启动方式：

```bash
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar --root /path/to/project
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar -m deepseek-chat
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar --thinking
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar --feishu
```

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `/help` | 查看命令帮助 |
| `/thinking` | 切换慢思考模式 |
| `/context` | 查看上下文和 Token 使用情况 |
| `/plan`、`/ask`、`/auto` | 切换权限模式 |
| `/plan-exec` | 执行 Plan-and-Execute 工作流 |
| `/clear` | 清空当前对话 |
| `/compact` | 压缩长上下文 |
| `/session` | 管理会话 |
| `/remember` | 写入一条记忆 |
| `/memory` | 查看或管理记忆 |
| `/skill` | 加载和查看技能 |
| `/mcp` | 管理 MCP Server |
| `/feishu-on`、`/feishu-off` | 开关飞书通道镜像 |
| `/exit` | 退出 |

## MCP 扩展

在 `~/.miniclaw/mcp.json` 中配置 MCP Server：

```json
{
  "mcpServers": {
    "chrome": {
      "command": "npx",
      "args": [
        "-y",
        "chrome-devtools-mcp@latest",
        "--browser-url=http://127.0.0.1:9222"
      ]
    }
  }
}
```

MCP 管理命令：

| 命令 | 说明 |
| --- | --- |
| `/mcp` | 查看 MCP Server 状态 |
| `/mcp restart <name>` | 重启指定 Server |
| `/mcp logs <name>` | 查看 Server 日志 |
| `/mcp disable <name>` | 停止并禁用 Server |
| `/mcp enable <name>` | 重新启用 Server |

## 架构概览

```text
CLI / IM Channel
    |
Agent Engine
    |
Provider Adapter  ----  Context / Memory / Session
    |
Tool Registry
    |
Built-in Tools / MCP Tools / Safety Interceptors
```

核心运行时尽量保持通用：Agent 循环、工具模型、上下文、记忆、权限和 Provider 适配不绑定具体业务；业务场景、垂类知识、指标体系和交付模板放在上层扩展。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `miniclaw-cli` | 命令行入口、交互界面、Slash Commands、启动参数 |
| `miniclaw-engine` | Agent 核心循环、任务执行、权限流转 |
| `miniclaw-tools` | 内置工具、MCP Client、工具安全拦截 |
| `miniclaw-provider` | LLM Provider 抽象、超时、重试、熔断 |
| `miniclaw-context` | 上下文统计、消息脱敏、上下文压缩 |
| `miniclaw-memory` | 磁盘记忆、YAML frontmatter 存储 |
| `miniclaw-im` | IM 通道抽象、消息桥接 |

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 语言 | Java 21 |
| 构建 | Maven 多模块 |
| CLI | Picocli、JLine3 |
| 数据处理 | Jackson、YAML |
| 日志 | SLF4J、Logback |
| 测试 | JUnit 5、AssertJ |
| 扩展协议 | MCP |

## 项目文档

- [CLAUDE.md](./CLAUDE.md)：协作规则、项目约定、Agent 使用说明
- [DESIGN.md](./DESIGN.md)：架构原则、类设计、接口设计、解耦、测试和代码审查规范
- [TODO.md](./TODO.md)：当前路线图和重构待办
- [SECURITY.md](./SECURITY.md)：安全策略和漏洞报告方式

## 演进方向

当前阶段优先完善底层 Agent 基本功：

- 重构核心任务循环，让任务状态、错误恢复和执行边界更清晰。
- 统一工具调用结果模型，减少工具层和 Engine 层的耦合。
- 强化权限、安全拦截和审计日志，避免自动执行带来不可控风险。
- 完善上下文压缩、记忆和会话机制，让长任务更稳定。
- 增加回归测试和评估用例，让重构不会破坏核心行为。
- 补齐 Docker、CI 测试流水线、Release 和镜像发布。
- 在底层稳定后，再扩展垂类上层能力。

更详细的待办见 [TODO.md](./TODO.md)。

## 开发与测试

运行全量测试：

```bash
mvn test
```

按模块测试：

```bash
mvn test -pl miniclaw-engine -am
mvn test -pl miniclaw-tools -am
```

## 安全说明

不要提交 API Key、Token、Webhook URL、私有配置文件或本地凭据。如果发现密钥泄露，应先吊销密钥，再处理仓库历史和安全告警。

安全问题处理方式见 [SECURITY.md](./SECURITY.md)。
