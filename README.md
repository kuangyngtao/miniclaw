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
  <b>Java CLI AI 编程助手</b> &nbsp;·&nbsp; ReAct Agent &nbsp;·&nbsp; 工具体系 &nbsp;·&nbsp; 上下文工程 &nbsp;·&nbsp; 飞书通道
</p>

---

## 架构

```
入口交互层  JLine3 REPL · Picocli CLI · HITL 审批 · 权限模式
    │
核心引擎层  ReAct Loop · Two-Stage 慢思考 · 三层迭代控制 · 并行工具调用
    │
上下文工程  三级阶梯压缩 (L1→L2→L3) · 磁盘记忆 · 会话持久化
    │
LLM 适配层  多模型协议兼容 · 超时重试 · 熔断韧性
    │
工具体系    8 工具插件化注册 · 安全拦截链 · 统一返回模型
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

配置 `~/.miniclaw/config.yaml`：

```yaml
apiKey: sk-xxx

feishu:
  appId: cli_xxx
  appSecret: xxx
  port: 8080
```

启动：

```bash
java -jar miniclaw-cli/target/miniclaw-cli-0.1.0-SNAPSHOT-shaded.jar
java -jar ... -m deepseek-chat --root /path/to/project    # 指定模型与目录
java -jar ... --feishu                                     # 飞书 Bot 模式
```

---

## 内部命令

| 命令 | 说明 | | 命令 | 说明 |
|------|------|-|------|------|
| `/thinking` | 切换慢思考 | | `/context` | Token 用量 |
| `/plan` `/ask` `/auto` | 权限模式 | | `/remember` | 添加记忆 |
| `/clear` | 重置会话 | | `/memory list` | 列出记忆 |
| `/compact` | 压缩上下文 | | `/feishu-on` `/feishu-off` | 飞书开关 |
| `/exit` | 退出 | | `/help` | 帮助 |

---

## 模块

| 模块 | 职责 |
|------|------|
| miniclaw-tools | 工具体系 + SafetyInterceptor 拦截链 |
| miniclaw-provider | LLM 适配 + 超时重试熔断 |
| miniclaw-context | LadderedCompactor 阶梯压缩 |
| miniclaw-memory | YAML frontmatter 磁盘记忆 |
| miniclaw-engine | Agent 核心引擎 |
| miniclaw-cli | JLine3 终端交互 |
| miniclaw-feishu | 飞书消息通道 |

## 技术栈

<p align="center">
  <code>Java 21</code> · <code>Picocli</code> · <code>JLine3</code> · <code>Maven</code> · <code>Jackson</code> · <code>SLF4J</code>
</p>
