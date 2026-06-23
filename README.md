# miniclaw

<p align="center">
  <pre>
╔══════════════════════════════════════════════════════════════╗
║  _       _      _                                           ║
║  _ __ ___ (_)_ __ (_) ___ ___| | __ ___      __             ║
║  | '_ ` _ \| | '_ \| |/ __/ _ \ |/ _` \ \ /\ / /           ║
║  | | | | | | | | | | | (_|  __/ | (_| |\ V  V /            ║
║  |_| |_| |_|_|_| |_|_|\___\___|_|\__,_| \_/\_/             ║
║                                                              ║
║              your local AI coding companion                  ║
╚══════════════════════════════════════════════════════════════╝
  </pre>
</p>

<p align="center">
  <b>Java CLI AI 编程助手</b> · ReAct Agent · 8 内置工具 + MCP 动态扩展 · 上下文工程 · 飞书通道
</p>

---

## 架构

```
入口交互层  JLine3 REPL · Picocli CLI · HITL 审批 · 权限模式
    │
核心引擎层  ReAct Loop · Two-Stage 慢思考 · 三层迭代控制 · 并行工具调用 · SubAgent · Plan-and-Execute
    │
上下文工程  三级阶梯压缩 (L1→L2→L3) · 磁盘记忆 · 会话持久化 · 技能外挂
    │
LLM 适配层  多模型协议兼容 · 超时重试 · 熔断韧性
    │
工具体系    8 内置工具 + MCP 动态扩展 · 安全拦截链 · 统一返回模型
    │
飞书通道    HTTP Server · CLI ↔ 飞书双向流式镜像
```

---

## 快速开始

```bash
git clone https://github.com/kuangyngtao/miniclaw.git
cd miniclaw
mvn package -pl miniclaw-cli -am -DskipTests
```

配置 API Key（环境变量，推荐）：

```bash
export MINICLAW_API_KEY=sk-xxx
```

启动：

```bash
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar
java -jar ... -m deepseek-chat --root /path/to/project    # 指定模型与目录
java -jar ... --thinking                                    # 慢思考模式
java -jar ... --feishu                                     # 飞书 Bot 模式
```

### MCP 扩展（可选）

配置 `~/.miniclaw/mcp.json` 接入第三方 MCP server：

```json
{
  "mcpServers": {
    "chrome": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]
    }
  }
}
```

启动 Chrome 调试端口后，miniclaw 自动发现并注册 MCP 工具。

---

## 内部命令

| 命令 | 说明 | | 命令 | 说明 |
|------|------|-|------|------|
| `/thinking` | 切换慢思考 | | `/context` | Token 用量 |
| `/plan` `/ask` `/auto` | 权限模式 | | `/plan-exec` | Plan+Execute |
| `/clear` | 重置会话 | | `/compact` | 压缩上下文 |
| `/session` | 会话管理 | | `/remember` | 添加记忆 |
| `/memory` | 记忆管理 | | `/skill` | 技能加载 |
| `/mcp` | MCP 服务器管理 | | `/feishu-on` `/feishu-off` | 飞书开关 |
| `/exit` | 退出 | | `/help` | 帮助 |

### /mcp 子命令

| 命令 | 说明 |
|------|------|
| `/mcp` | 列出所有 MCP server 及状态 |
| `/mcp restart <name>` | 重启指定 server |
| `/mcp logs <name>` | 查看 server stderr 日志 |
| `/mcp disable <name>` | 停止并禁用 server |
| `/mcp enable <name>` | 重新启用 server |

---

## 模块

| 模块 | 职责 |
|------|------|
| miniclaw-tools | 8 内置工具 + MCP 客户端（stdio + HTTP）+ SafetyInterceptor 拦截链 |
| miniclaw-provider | LLM 适配 + 超时重试熔断 |
| miniclaw-context | LadderedCompactor 阶梯压缩 + MessageMasker |
| miniclaw-memory | YAML frontmatter 磁盘记忆 |
| miniclaw-engine | Agent 核心引擎（ReAct Loop + SubAgent + Plan-and-Execute） |
| miniclaw-cli | JLine3 终端交互 + /slash 命令 |
| miniclaw-feishu | 飞书消息通道 |

## 技术栈

<p align="center">
  <code>Java 21</code> · <code>Picocli</code> · <code>JLine3</code> · <code>Maven</code> · <code>Jackson</code> · <code>SLF4J</code>
</p>

---

## 测试

271/271 通过（tools 66 + context 45 + engine 69 + provider 14 + memory 32 + cli 45）
