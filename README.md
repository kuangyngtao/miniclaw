# clawkit

[![CodeQL](https://github.com/kuangyngtao/miniclaw/actions/workflows/codeql.yml/badge.svg)](https://github.com/kuangyngtao/miniclaw/actions/workflows/codeql.yml)

> 本地优先的个人 AI 运维助手：连接你的服务器，解释问题，审批后安全处置，并独立验证结果

clawkit 运行在用户自己的电脑上，通过受限远程能力连接用户已经登记的 Linux 服务器。用户可以用自然语言查看服务状态、分析有界日志、调查故障；命中经过审核的修复方案后，Clawkit 会说明原因和影响，等待人工批准，执行前重新检查现场，执行后使用独立只读会话确认业务是否真的恢复。

Java 21 Agent Runtime、MCP、权限门禁和状态机是实现这些体验的底座，不是用户必须先理解的产品。项目仍处于工程化原型阶段；当前重点是把服务器接入、快速查看、Ops 调查和审批修复打磨成一条顺手的个人使用路径，而不是继续堆工具数量或建设通用 SSH/SRE 平台。

## 项目概览

| 维度 | 说明 |
| --- | --- |
| 项目类型 | 本地个人 AI 运维助手，内部使用 Java 多模块 Agent Runtime |
| 运行方式 | 本地 CLI |
| 核心目标 | 让个人开发者更容易理解并安全处理自己服务器上的服务故障 |
| 关键机制 | 复用本地 SSH、预定义只读能力、证据化调查、人工审批、受限执行和独立复验 |
| 当前阶段 | REMOTE-0 与 OPS MVP-3 工程闭环已完成；下一步打磨服务器接入和统一调查体验 |

## 项目目标

clawkit 关注 Agent 的底层运行能力和可验证闭环，而不是单次对话效果。项目目标包括：

- **本地优先**：围绕本地仓库运行，支持指定项目目录作为 Agent 工作空间。
- **工具可控**：通过统一工具层执行读文件、搜索、编辑、命令运行等操作。
- **权限分级**：通过 `plan`、`ask`、`auto` 模式区分不同风险级别的操作。
- **上下文可管理**：跟踪上下文使用情况，支持压缩、会话和磁盘记忆。
- **扩展可插拔**：通过 MCP 接入外部工具，通过 IM 模块接入消息通道。
- **基座通用**：核心运行时保持通用，垂类能力放在上层扩展。
- **证据优先**：事实、推测、时效和采集失败可区分；证据不足时允许 `INCONCLUSIVE`。
- **独立验收**：执行者不能自证成功，确定性断言和独立上下文重新采证优先。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 任务执行 | 支持边观察边执行、先规划后执行、慢思考和并行子任务 |
| 工具系统 | 内置工具、外部工具接入、统一登记和安全拦截 |
| 权限模式 | `plan` / `ask` / `auto`，控制工具执行边界 |
| 上下文管理 | Token 使用跟踪、阶梯式压缩、消息脱敏、会话持久化 |
| 记忆系统 | 基于磁盘文件的长期记忆，用于跨任务复用上下文 |
| 模型通信 | 统一适配、超时、重试和熔断 |
| 消息通道 | 飞书、微信等消息入口的统一适配层 |
| 可靠性门禁 | 取消、预算、执行记录、结果未知、幂等、目标互斥和恢复扫描 |
| 运维扩展 | 白名单只读采证、事故记录、诊断、时间线和隐藏答案评测 |

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
| Docker 容器化 | Dockerfile 已提供，面向 Windows Docker Desktop |
| CI 测试流水线 | Windows Java 21 全量验证工作流已提供 |
| GitHub Release | tag 构建可运行 JAR 的工作流已提供 |

## 快速开始

```bash
git clone https://github.com/kuangyngtao/miniclaw.git
cd miniclaw
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

API Key 只能通过环境变量提供，禁止写入 `config.yaml`。非敏感配置和优先级见 [docs/configuration.md](docs/configuration.md)。

启动 CLI：

```powershell
.\clawkit.cmd
```

常用启动方式：

```powershell
.\clawkit.cmd --root C:\path\to\project
.\clawkit.cmd -m deepseek-v4-flash
.\clawkit.cmd --thinking
.\clawkit.cmd --im=feishu
```

从源码运行时，`clawkit.cmd` 会定位当前构建出的 CLI JAR；发布包中的同名脚本会直接运行包内 `clawkit.jar`，不依赖 Maven。下载 `clawkit-0.1.0-windows.zip` 后，先按 `SHA256SUMS.txt` 校验，再解压并执行 `.\clawkit.cmd`。

## 常用命令

| 命令 | 说明 |
| --- | --- |
| `/help` | 查看命令帮助 |
| `/thinking` | 切换慢思考模式 |
| `/context` | 查看上下文和 Token 使用情况 |
| `/config` | 查看脱敏后的有效配置及来源 |
| `/plan`、`/ask`、`/auto` | 切换权限模式 |
| `/plan-exec` | 执行 Plan-and-Execute 工作流 |
| `/clear` | 清空当前对话 |
| `/compact` | 压缩长上下文 |
| `/session` | 管理会话 |
| `/remember` | 写入一条记忆 |
| `/memory` | 查看或管理记忆 |
| `/skill` | 加载和查看技能 |
| `/mcp` | 管理 MCP Server |
| `/remote` | 查看当前远程连接状态 |
| `/remote list` | 查看已登记服务器 |
| `/remote add --from-ssh <alias>` | 从现有 OpenSSH 配置登记服务器 |
| `/remote doctor <targetId>` | 检查 SSH、认证、主机身份和远端能力 |
| `/remote connect <targetId>` | 连接已登记服务器 |
| `/remote inspect <targetId>` | 查看目标和能力合同详情 |
| `/remote remove <targetId>` | 删除已登记服务器 |
| `/remote disconnect` | 断开当前服务器 |
| `/remote tools` | 查看当前挂载的预定义远程能力 |
| `/feishu-on`、`/feishu-off` | 开关飞书通道镜像 |
| `/exit` | 退出 |

普通接入路径会复用 OpenSSH config、SSH Agent 和系统 `known_hosts`；Clawkit 保存逻辑目标和受支持的能力合同，不复制私钥。高级 YAML 导入继续作为兼容入口。首次使用建议依次运行 `/remote add --from-ssh <alias>`、`/remote doctor <targetId>` 和 `/remote connect <targetId>`。完整命令与文档状态见 [docs/README.md](docs/README.md)。

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

MCP 凭据应使用 `${env:VAR_NAME}` 引用，不要在 JSON 中写入真实值。完整说明见 [docs/mcp.md](docs/mcp.md)。

## Docker Desktop（Windows）

```powershell
docker build -t clawkit:dev .
docker run --rm -it `
  -e CLAWKIT_API_KEY=$env:CLAWKIT_API_KEY `
  -v "${PWD}:/workspace" `
  -v "clawkit-home:/home/clawkit/.clawkit" `
  clawkit:dev
```

Docker 当前只承诺交互式 CLI，必须使用 `-it`；后台 IM bot 不在本轮容器支持范围内。

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
| `clawkit-reliability` | 取消、预算、Attempt、Side Effect Gate、恢复与独立验证 |
| `clawkit-observability` | RunEvent 事实源、指标投影、Trace 与报告读取 |
| `clawkit-evaluation` | 固定 Case、Baseline、Scorer 与回归比较 |
| `clawkit-im` | IM 通道抽象、消息桥接，属于扩展入口 |
| `extensions/clawkit-ops-mcp` | 结构化、白名单、可审计的运维能力层 |
| `extensions/clawkit-ops-loop` | Incident、采证、诊断、评测和后续修复编排 |

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

- [docs/README.md](docs/README.md)：完整文档地图、推荐阅读顺序和文档状态说明
- [CLAUDE.md](./CLAUDE.md)：AI 协作入口、项目边界和强约束
- [docs/product-direction.md](docs/product-direction.md)：目标用户、产品承诺、核心旅程、技术调研和近期体验路线
- [DESIGN.md](./DESIGN.md)：架构原则、类设计、接口设计、解耦、测试和代码审查规范
- [TODO.md](./TODO.md)：当前路线图和重构待办
- [docs/ops-loop.md](docs/ops-loop.md)：远程运维闭环的架构、安全边界和演进路线
- [docs/project-deep-dive-guide.md](docs/project-deep-dive-guide.md)：面向个人学习和秋招准备的项目全览
- [SECURITY.md](./SECURITY.md)：安全策略和漏洞报告方式

## 演进方向

运行底座、远程只读连接和第一个审批修复闭环已经建立。后续顺序以 [TODO.md](./TODO.md) 为准：

1. 补齐无需 Incident 的 Quick Check，让“服务是否正常、最近有什么错误”成为一句话任务。
2. 通过真实远端 dogfood 检验现有调查、审批修复和独立验证产品链，优先修复重复出现的摩擦。
3. 记录首次连接、有效结果、调查耗时、成本和人工决策等最小产品数据。
4. 数据足够后再评审 Observe-only 持续运行与 Shadow；不整体切换 Agent 权限。

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
