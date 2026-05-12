# miniclaw TODO

> 从 Demo 到高可用智能助手的完整路线图。
> 状态标记：`[ ]` 待做 `[~]` 进行中 `[x]` 已完成

---

## 一、引擎层 (miniclaw-engine)

### 1.1 多轮对话上下文 `[x]` **P0** ✅ 2026-05-11

`AgentEngine` 新增 `List<Message> sessionHistory` 字段。每次 `run()` 复用而非新建 `contextHistory`，第二问能引用第一问的结果。

**已完成：**
- `sessionHistory` 字段 + `clearSession()` 方法
- `AgentLoop` 接口新增 `clearSession()`
- System prompt 仅首次注入，后续 run() 不再重复
- TWO_STAGE Phase 1 思考内容保持 ephemeral（不污染 sessionHistory）
- 权限模式切换复用已有工具过滤，V1 不重复注入 system prompt
- `isReadOnly()` 从引擎层 `READ_ONLY_TOOLS` 硬编码下沉到 Tool 接口各工具自行声明
- 安全拦截器链：`ToolRegistry.execute()` 新增拦截层，`CommandSafetyInterceptor` 阻断 8 类高危命令

**影响文件：** `AgentEngine.java`, `AgentLoop.java`, `Tool.java`, `Registry.java`, `ToolRegistry.java`

---

### 1.2 迭代控制 `[x]` **P0** ✅ 2026-05-11

从简单计数上限演进为**三层递进防御**：

```
第1层: 死循环检测 — 连续3轮相同工具+相同参数 → 注入 [Runtime] 警告
第2层: 进度提醒 — 每10轮注入 [Runtime] 进度评估提示
第3层: 安全硬上限 — 50轮兜底
```

核心哲学：引擎不替 LLM 决定"该不该停"，而是注入上下文让 LLM 自行判断。退出永远是 LLM 自然产出文本（无 tool_calls），而非被强制中断。

**已完成：**
- `recentCallSignatures` 调用签名追踪（保留最近 10 条）
- 死循环检测：连续 3 轮相同 `name:args` → system 消息警告
- 进度提醒：每 10 轮注入评估提示
- 硬上限 50 轮为最后兜底

**影响文件：** `AgentEngine.java`

---

### 1.3 上下文阶梯压缩 `[x]` **P0** ✅ 2026-05-11

实现 `LadderedCompactor`（miniclaw-context 模块），三级递进压缩：

| 级别 | Token 使用率 | 策略 |
|------|-------------|------|
| 不压缩 | < 60% | 原样返回 |
| L1 | 60-80% | 删空行 + 截断超长行（>300 字符） |
| L2 | 80-95% | 跨轮去重（SHA-256 hash）+ 删低信号行 |
| L3 | > 95% | ✅ LLM 摘要压缩 — System Prompt + 旧轮次消息 → LLM 生成摘要 → 替换原文 |

**实测效果**（模拟 8 轮编程对话，含两次读取 200 行源码）：
- Token: 6588 → 3546（**减少 46%**）
- 跨轮去重: 重复读取同一文件自动替换为 `[content unchanged from earlier turn]`
- 错误信息保留、system prompt 保护、最近 3 轮完整

**已完成：**
- 重新设计 `ContextManager` 接口（`estimateTokens` + `compact`）
- `LadderedCompactor` 实现（155 行）
- `miniclaw-context` 新增 `miniclaw-tools` 依赖
- `miniclaw-engine` 新增 `miniclaw-context` 依赖
- AgentEngine 每轮开始前自动检查并压缩

**影响文件：** `ContextManager.java`, `LadderedCompactor.java`, 两个 `pom.xml`, `AgentEngine.java`

---

### 1.4 SubAgent 引擎 `[x]` **P1** ✅ 2026-05-12

派生子 Agent 独立执行子任务，结果回传主 Agent。

**核心设计：**
```
主 Agent 调用 Task 工具
  → AgentEngine 拦截 task 调用（引擎级特殊工具，不注册到 ToolRegistry）
  → spawnSubAgent() 构造裁剪后的 ToolRegistry + 新 AgentEngine
  → 子 Agent 独立 ReAct 循环直到完成
  → 返回最终文本结果给主 Agent → 主 Agent 综合判断
```

**已完成：**
- `task` 工具作为引擎内置特殊工具（`buildTaskToolDefinition()` 生成 ToolDefinition），不注册到 ToolRegistry
- `spawnSubAgent(ToolCall)` 方法：解析参数 → 构建子 Registry → new AgentEngine → subEngine.run()
- `enableSubAgents` volatile 标志防止子 Agent 递归派发（子引擎设为 false）
- `executeSubAgentsParallel()` — 同一轮多个 task 调用虚拟线程并发派发（CountDownLatch）
- `executeSequential` 中拦截 task 调用走 spawnSubAgent 路径
- 两种类型：`explore`（仅读工具，15轮上限）和 `general`（全工具不含 task，25轮上限）
- 子 Agent 继承主 Agent permissionMode，不继承 TWO_STAGE
- `SubAgentSpawnEvent` / `SubAgentCompleteEvent` 回调（CopyOnWriteArrayList 模式）
- CLI 灰色 `[SubAgent]` 前缀打印派发/完成信息
- `lastRunTurns` package-private 字段跟踪子 Agent 轮次

**测试：** `SubAgentTest.java` — 6 个用例（explore 读工具/ general 写工具/ 并行派发/ 防递归/ PLAN 模式隐藏/ 权限继承）

**影响文件：** `AgentEngine.java`, `MiniclawApp.java`, `SubAgentTest.java`

---

### 1.5 熔断器 `[x]` **P2** ✅ 2026-05-11

连续 API 失败后快速返回错误，不无限重试。归属 `miniclaw-provider`。

**已完成：**
- `checkCircuit()` — OPEN 直接抛异常，HALF_OPEN 允许探测，CLOSED 通过
- `recordSuccess()` / `recordFailure()` — 同步状态更新
- `generate()` 和 `generateStream()` 入口统一熔断检查
- 阈值：连续 5 次失败 → 30s 冷却 → 半开探测 → 成功恢复 / 失败重开

**测试：** 5 个用例（熔断开启/计数重置/半开恢复/半开失败重开/generateStream 熔断）

**影响文件：** `OpenAIProvider.java`, `OpenAIProviderTest.java`

---

## 二、工具层 (miniclaw-tools)

### 2.1 WebFetch 工具 `[x]` **P1** ✅ 2026-05-11

让 Agent 能查阅在线文档、API 参考、GitHub issue。

**已完成：**
- `WebFetchTool` 实现 Tool 接口（~160 行）
- JDK `HttpClient` HTTP GET，连接 10s + 读取 30s 超时
- Jsoup HTML → 纯文本，自动去除 script/style
- `ConcurrentHashMap` 15 分钟 TTL 缓存（max 50 条）
- 响应体 1MB 上限 + 输出 8000 字符截断
- Content-Type charset 自动检测
- `isReadOnly() = true`（可并行执行）
- 仅允许 http/https 协议

**测试：** `WebFetchToolTest.java` — 12 个用例（5 参数校验 + 3 HTTP 请求 + 截断 + 缓存 + 只读/命名）

**影响文件：** `WebFetchTool.java`, `WebFetchToolTest.java`, `MiniclawApp.java`, `pom.xml`（新增 Jsoup 依赖）

---

### 2.2 TodoWrite 工具 `[x]` **P1** ✅ 2026-05-11

Agent 自管理任务列表，提升多步骤任务完成率。

**设计：**
```
输入: todos (JSON 数组，每项含 content/status/activeForm)
      status ∈ {pending, in_progress, completed}
流程: 解析 JSON → 校验 status 枚举 → AtomicReference 内存存储 → 格式化返回
```

**已完成：**
- `TodoWriteTool` 实现 Tool 接口（~120 行）
- 严格校验 JSON 格式 + status 枚举
- `AtomicReference<List<TodoEntry>>` 会话级 ephemeral
- 格式化输出：`[N/M] [status] activeForm/content`

**测试：** `TodoWriteToolTest.java` — 6 个用例（创建/更新/非法status/空content/completed显示/空列表）

**影响文件：** `TodoWriteTool.java`, `TodoWriteToolTest.java`, `MiniclawApp.java`

---

### 2.3 Git 专用工具 `[ ]` **P2**

封装 git status / diff / log，解决 bash 输出截断问题。

**影响文件：** 新建 `GitDiffTool.java`, `GitLogTool.java`, `GitStatusTool.java`

---

### 2.4 安全中间件 `[x]` **P2** ✅ 2026-05-11

高危命令执行前拦截（rm -rf、sudo、chmod 777 等）。AUTO 模式下也生效。

**已完成：**
- `SafetyInterceptor` 接口（`@FunctionalInterface`，返回 null=通过，非 null=拦截原因）
- `CommandSafetyInterceptor` 实现 — 8 条高危规则（rm -rf /~/*、sudo、chmod 777、> /dev/sd、mkfs、dd、fork bomb）
- `ToolRegistry` 新增 `addInterceptor()` + `execute()` 内拦截链循环
- `MiniclawApp` 启动时注册拦截器
- 拦截在工具执行**之前**，所有权限模式生效

**测试：** `CommandSafetyInterceptorTest.java` — 8 个用例（3 拦截 + 3 放行 + 非bash工具放行 + dd拦截）

**影响文件：** `SafetyInterceptor.java`, `CommandSafetyInterceptor.java`, `ToolRegistry.java`, `MiniclawApp.java`

---

### 2.5 BashTool 增强 `[ ]` **P2**

a) 后台守护：`--background` 标志，超时不杀，返回 PID
b) 超时可配置：通过 inputSchema 参数覆盖默认 30s
c) 跨平台 shell 检测：动态检测 git bash / wsl / cmd

**影响文件：** `BashTool.java`

---

### 2.6 WriteTool 覆盖确认 `[ ]` **P3**

目标文件已存在时交互确认。

**影响文件：** `WriteTool.java`

---

## 三、上下文与记忆层

### 3.1 MemoryStore 实现 `[x]` **P1** ✅ 2026-05-11

实现磁盘记忆系统，跨会话保留用户偏好、项目背景、反馈记录。

**已完成：**
- `MemoryType` 枚举（USER/FEEDBACK/PROJECT/REFERENCE）
- `MemoryEntry` record + `filename()` 命名规则
- `YamlFrontmatterParser` — Jackson YAML 解析/生成 frontmatter
- `FileMemoryStore` — NIO Files 后端，路径穿越防护
- `DiskMemoryService` — 高层 API：save/load/delete/listIndex/regenerateIndex
- MEMORY.md 索引自动管理（增删自动更新）
- `AgentEngine` — `memoryIndex` 字段注入 system prompt
- `/remember` LLM 自动提取元数据（name/description/type），无需手动分步输入
- `/remember` CLI 交互式命令 + `/memory list|regen`
- `~/.miniclaw/memory/` 全局记忆目录
- `setMemoryIndex()` 热加载 — `/remember` 和 `/memory regen` 后即时生效，无需重启
- `CLAUDE.md` / `AGENTS.md` 项目上下文 — 启动时自动读取并注入 system prompt

**测试：** 24 个（8 YAML + 8 FileMemoryStore + 8 DiskMemoryService）

**影响文件：** `MemoryType.java`, `MemoryEntry.java`, `YamlFrontmatterParser.java`, `FileMemoryStore.java`, `DiskMemoryService.java`, `AgentEngine.java`, `MiniclawApp.java`，3 个测试类，2 个 pom.xml

---

### 3.2 ContextManager 实现 `[x]` **P0** ✅ 2026-05-11

已由 `LadderedCompactor` 实现（见 1.3）。ContextManager 接口重新设计为 token 估算 + 阶梯压缩。

---

## 四、CLI 交互层 (miniclaw-cli)

### 4.1 JLine3 REPL 升级 `[x]` **P2** ✅ 2026-05-11

替换 `Scanner(System.in)` 裸读，支持历史记录、Tab 补全、Ctrl+C 中断、多行编辑。

**已完成：**
- JLine3 `LineReader` 替换 `Scanner(System.in)`
- `MiniclawCompleter` — Tab 补全 `/` 命令（11 条）
- 历史持久化到 `~/.miniclaw/history`（重启保留，Ctrl+R 搜索）
- 多行编辑：`\` 续行
- Ctrl+C 双重模式：空闲清空当前行 / 引擎执行时中断 ReAct 循环

**影响文件：** `MiniclawApp.java`, `MiniclawCompleter.java`, `pom.xml` (parent + cli)

---

### 4.2 `--root` 工作目录限制 `[x]` **P2** ✅ 2026-05-11

实现 CLAUDE.md 已规划但未实现的 `--root` CLI 选项。

**已完成：**
- `@Option(names = {"--root"})` Picocli 选项，`Path` 类型自动转换
- `resolveWorkDir()` 校验路径存在性 + 目录类型
- 默认回退 `user.dir`（`--root` 未指定时）
- `--feishu` 模式同步支持 `--root`

**影响文件：** `MiniclawApp.java`

---

### 4.3 application.yaml 热加载 `[ ]` **P3**

读取配置文件的合并：默认值 → application.yaml → 环境变量 → CLI 参数（优先级递增）。

**影响文件：** `MiniclawApp.java`

---

### 4.4 `/clear` 命令 `[x]` **P2** ✅ 2026-05-11

CLI 斜杠命令调用 `engine.clearSession()` 清空会话历史。

**已完成：**
- `/c` 和 `/clear` 斜杠命令
- 调用 `engine.clearSession()` 清空会话历史
- CLI 反馈 "session cleared."

**影响文件：** `MiniclawApp.java`

---

## 五、Provider 层 (miniclaw-provider)

### 5.1 熔断器 `[ ]` **P2**（见 1.5）

---

### 5.2 多模型路由 `[ ]` **P3**

支持 Anthropic 原生 API。V1 非必须（OpenAI 兼容接口已覆盖 DeepSeek/智谱/Moonshot）。

---

## 六、测试与质量

### 6.1 SubAgent 测试 `[x]` **P2** ✅ 2026-05-12

SubAgent 引擎 6 个 Mock 集成测试（见 1.4）。

---
	
### 6.2 CLI 层测试 `[ ]` **P2**

斜杠命令解析、权限模式切换、confirmTool 交互测试。

---

### 6.2 工具集成测试补充 `[ ]` **P3**

WebFetch / Task/SubAgent 集成测试。

---

### 6.3 评测基准 `[ ]` **P3**（远期）

20 个编程任务基准，测量完成率、平均轮次、Token 消耗。

---

## 当前进度

```
已完成 (P0 + 部分 P1/P2):
  ├─ 1.1 多轮对话上下文        ✅ sessionHistory + clearSession
  ├─ 1.2 迭代控制（三层防御）   ✅ 死循环检测 + 进度提醒 + 50轮硬上限
  ├─ 1.3 上下文阶梯压缩        ✅ L1 删空行 + L2 跨轮去重 + L3 LLM 摘要
  ├─ 1.4 SubAgent 引擎         ✅ task 工具 + explore/general + 并行派发 + 权限继承
  ├─ 1.5 熔断器                ✅ CLOSED→OPEN→HALF_OPEN 状态机
  ├─ 2.1 WebFetch 工具         ✅ HTTP GET + Jsoup + 15min 缓存
  ├─ 2.2 TodoWrite 工具        ✅ Agent 自管理任务列表
  ├─ 2.4 安全中间件            ✅ CommandSafetyInterceptor 高危命令拦截
  ├─ 4.2 --root 标志          ✅ Picocli Path 选项 + 路径校验
  ├─ 4.4 /clear 命令           ✅ CLI 斜杠命令清空会话
  ├─ Token 监控 + /compact     ✅ 提示符实时显示token用量 + 手动压缩
  ├─ Tool.isReadOnly() 下沉    ✅ 引擎不再硬编码读/写工具名
  ├─ TWO_STAGE 优化            ✅ 仅首轮思考 + 流式盒子展示
  ├─ Ctrl+C 中断               ✅ JLine3 Terminal.handle(Signal.INT) 跨平台信号处理
  ├─ 3.1 MemoryStore           ✅ YAML frontmatter + /remember LLM提取 + 热加载 + CLAUDE.md
  ├─ 飞书 IM 通道               ✅ HTTP Server + 流式编辑 + TokenBatcher + 多用户会话隔离
  └─ CLI ↔ 飞书双向共享         ✅ onToken 多路监听 + 共享锁 + 双向镜像 + ngrok管理 + 配置统一

测试: 115/115 通过
  miniclaw-tools:    46 (20 原有 + 6 TodoWrite + 8 安全拦截器 + 12 WebFetch)
  miniclaw-context:   6 (LadderedCompactor)
  miniclaw-engine:   25 (AgentEngine 10 + PermissionMode 9 + SubAgent 6)
  miniclaw-provider:  14 (9 原有 + 5 熔断器)
  miniclaw-memory:    24 (8 YAML + 8 FileMemoryStore + 8 DiskMemoryService)
  miniclaw-cli:       0 ← 待补充
```

## 功能演进总览（更新）

```
P0（基础能力）✅ 已完成
  ├─ 1.1 多轮对话上下文     ✅
  ├─ 1.2 迭代控制（三层）    ✅
  └─ 1.3 上下文阶梯压缩      ✅

P1（质变特性）
  ├─ 1.4 SubAgent 引擎          ✅
  ├─ 2.1 WebFetch 工具          ✅
  ├─ 2.2 TodoWrite 工具         ✅
  └─ 3.1 MemoryStore 磁盘记忆   ✅

P2（工程完善）
  ├─ 1.5 熔断器 (provider)        ✅
  ├─ 2.3 Git 工具
  ├─ 2.4 安全中间件              ✅
  ├─ 2.5 BashTool 增强
  ├─ 4.1 JLine3 REPL             ✅
  ├─ 4.2 --root 标志            ✅
  ├─ 4.4 /clear 命令             ✅
  ├─ Token 监控 + /compact       ✅
  ├─ Banner 重设计 (FIGlet)     ✅
  ├─ 飞书 IM 通道               ✅
  └─ CLI ↔ 飞书双向共享         ✅

P3（远期打磨）
  ├─ 2.6 WriteTool 覆盖确认
  ├─ 4.3 application.yaml 加载
  ├─ 5.2 多模型路由
  ├─ 6.1 CLI 测试
  ├─ 6.2 工具集成测试补充
  └─ 6.3 评测基准
```

---

## 技术债记录

| 项 | 位置 | 说明 | 状态 |
|----|------|------|------|
| Git Bash 路径硬编码 | `BashTool.java` | `D:\Git\...` 写死 | 待修 |
| application.yaml 闲置 | `miniclaw-cli/src/main/resources/` | 文件存在但代码未读取 | 待修 |
| context/memory 模块空壳 | `miniclaw-context`, `miniclaw-memory` | context ✅, memory ✅ | 已完成 |
| 无 CLI 测试 | `miniclaw-cli` | 零测试覆盖 | 待补 |
| ~~contextHistory 无截断~~ | ~~`AgentEngine.java:run()`~~ | 已由 LadderedCompactor 解决 | ✅ |
| ~~READ_ONLY_TOOLS 硬编码~~ | ~~`AgentEngine.java`~~ | 已下沉到 Tool.isReadOnly() | ✅ |
| ~~高危命令无拦截~~ | ~~`BashTool.java`~~ | 已由 CommandSafetyInterceptor 解决 | ✅ |
