# clawkit

[![CodeQL](https://github.com/kuangyngtao/clawkit/actions/workflows/codeql.yml/badge.svg)](https://github.com/kuangyngtao/clawkit/actions/workflows/codeql.yml)

> Java 本地 AI 编程 Agent，面向代码仓库的 CLI 编程助手

clawkit 是一个基于 Java 的本地编程 Agent。它将 LLM Provider、工具调用、权限模式、上下文管理、记忆、会话和 MCP 扩展组合成一个 CLI 运行时，用于探索 AI Agent 在本地代码仓库中的执行流程。

当前项目处于原型开发和底层工程化阶段，重点建设 Agent harness 的基本能力：上下文管理、工具系统、执行编排、状态记忆、评估观测、权限约束和失败恢复。

## 项目概览

| 维度 | 说明 |
| --- | --- |
| 项目类型 | Java 多模块 AI Agent / CLI 编程助手 |
| 运行方式 | 本地 CLI |
| 核心目标 | 在本地代码仓库中完成代码分析、文件搜索、工具调用、代码修改和任务执行 |
| 关键机制 | ReAct、Plan-and-Execute、权限模式、MCP、上下文压缩、会话记忆 |
| 当前阶段 | 原型开发中，重点建设底层 Agent harness |

## 项目目标

clawkit 关注的是编程 Agent 的底层运行能力，而不是单次对话效果。项目目标包括：

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
| 单元测试 | 已覆盖主要模块，后续继续补重构护栏测试 |
| CodeQL 安全扫描 | 已配置 |
| Dependabot | 已配置 |
| 安全策略 | 已提供 `SECURITY.md` |
| Docker 容器化 | 计划补齐 |
| CI 测试流水线 | 计划补齐 |
| GitHub Release / 镜像发布 | 后续规划 |

## 快速开始

```bash
git clone https://github.com/kuangyngtao/clawkit.git
cd clawkit
mvn package -pl clawkit-cli -am -DskipTests
```

配置 API Key：

```bash
export CLAWKIT_API_KEY=<your-api-key>
```

Windows PowerShell：

```powershell
$env:CLAWKIT_API_KEY = "<your-api-key>"
```

启动 CLI：

```bash
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT-shaded.jar
```

常用启动方式：

```bash
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT-shaded.jar --root /path/to/project
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT-shaded.jar -m deepseek-chat
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT-shaded.jar --thinking
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT-shaded.jar --feishu
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

在 `~/.clawkit/mcp.json` 中配置 MCP Server：

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
| `clawkit-cli` | 命令行入口、交互界面、Slash Commands、启动参数 |
| `clawkit-engine` | Agent 核心循环、任务执行、权限流转 |
| `clawkit-tools` | 内置工具、MCP Client、工具安全拦截 |
| `clawkit-provider` | LLM Provider 抽象、超时、重试、熔断 |
| `clawkit-context` | 上下文统计、消息脱敏、上下文压缩 |
| `clawkit-memory` | 磁盘记忆、YAML frontmatter 存储 |
| `clawkit-im` | IM 通道抽象、消息桥接，属于扩展入口 |

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

- [CLAUDE.md](./CLAUDE.md)：AI 协作入口、项目边界和强约束
- [DESIGN.md](./DESIGN.md)：架构原则、类设计、接口设计、解耦、测试和代码审查规范
- [TODO.md](./TODO.md)：当前路线图和重构待办
- [SECURITY.md](./SECURITY.md)：安全策略和漏洞报告方式

## 演进方向

当前阶段优先完善底层 Agent 基本功，顺序以 [TODO.md](./TODO.md) 为准：

1. 建立重构前最小护栏：行为测试、基础 metrics、工具执行结果模型。
2. 完善可靠运行闭环：token 预算、上下文管理、观测与评估、工具安全。
3. 推进底层结构重构：拆分 `AgentEngine`、CLI 交互层、工具执行和上下文管线。
4. 补齐工程可用性：CI、Docker、示例、配置体验和 Release 产物。
5. 在底座稳定后，再扩展后台任务、远程运维 MCP、语义搜索等高级能力。

更详细的待办见 [TODO.md](./TODO.md)。

## 开发与测试

运行全量测试：

```bash
mvn test
```

按模块测试：

```bash
mvn test -pl clawkit-engine -am
mvn test -pl clawkit-tools -am
```

## 安全说明

不要提交 API Key、Token、Webhook URL、私有配置文件或本地凭据。如果发现密钥泄露，应先吊销密钥，再处理仓库历史和安全告警。

安全问题处理方式见 [SECURITY.md](./SECURITY.md)。
