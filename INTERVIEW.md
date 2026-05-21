# miniclaw 项目技术全景

> 面向面试场景的深度技术文档，覆盖 ReAct Agent 引擎、工具体系、上下文管线、四层记忆架构、LLM 适配层、多通道交互的完整设计。

---

## 1. ReAct Agent 核心引擎

### 1.1 ReAct 循环原理

```
User Input → Think (LLM 推理) → Act (工具调用) → Observe (工具结果) → 循环
                ↑                                                       │
                └──────────────── 回注 contextHistory ←─────────────────┘
```

每轮迭代：
1. 将 `contextHistory`（system prompt + 历史消息 + 工具结果）发给 LLM
2. LLM 返回 text content 或 tool_calls
3. 如果是 tool_calls → 执行工具 → 将结果追加到 contextHistory → 回到步骤 1
4. 如果是纯文本 → 循环结束，返回最终回复

核心代码路径：`AgentEngine.run()` → while(true) → `provider.generate()` / `provider.generateStream()` → 解析 tool_calls → `registry.execute()` → 回注 contextHistory。

每轮 run() 的完整生命周期：

```
buildSystemPrompt → 注入工作内存 → 注入 TODO 暂停警告
  → 嗅探工作区状态（TODO.md / PLAN.md）
  → L3 自动记忆提取（条件触发）
  → MessageMasker.mask()（>12轮启用）
  → LadderedCompactor.compact()（>60% token 启用）
  → provider.generate() → 解析 tool_calls → 执行并回注
```

### 1.2 为什么自己写 Agent Loop 而不是用 LangChain？

| LangChain | miniclaw |
|-----------|----------|
| 黑盒抽象，调试困难 | 200 行 while 循环，完全可控 |
| Python 生态，冷启动慢 | Java 21 虚拟线程，JVM 瞬启 |
| 依赖链复杂 | 零框架依赖（仅 Jackson + SLF4J） |

面试话术："Agent 循环本质上就是一个 while + LLM 调用，不需要引入 LangChain 级别的抽象。自己写的好处是每一轮的状态变化都在掌控之中——什么时候注入系统消息、什么时候压缩上下文、什么时候检测死循环——这些在框架里反而是黑盒。"

### 1.3 Two-Stage 慢思考

当用户启用 `--thinking` 时，首轮分两阶段执行：

**Phase 1（规划）**：剥离所有工具定义，注入系统消息"你现在处于规划阶段，不能调用任何工具"，强制 LLM 输出 2-4 句推理规划。思考内容作为临时上下文注入 Phase 2，但不持久化到 sessionHistory。

**Phase 2（执行）**：正常携带工具定义，基于 Phase 1 的规划产出执行动作。

设计关键：Phase 1 内容 ephemeral——本轮结束后丢弃，不污染上下文。后续轮次不再执行 Two-Stage（仅首轮）。

面试追问："为什么 Phase 1 内容不持久化？"
→ 因为那是 LLM 的内部推理 Trace，包含"我应该先读文件 A 再搜索函数 B"之类的规划，持久化会浪费 Token 且后续轮次不需要。LLM 每次看到当前工具结果就足够判断下一步。

**Phase 1 的特殊处理——清洗伪 XML tool_call**：DeepSeek 等模型在剥离工具定义后，偶尔会在 Phase 1 推理中幻想出 `<tool_calls>` XML 文本（模型预训练数据中有这类格式）。`stripFakeToolCalls()` 方法检测并移除包含 `<tool_calls>` 的行，防止 Phase 2 解析工具调用时误读。

**Phase 1 的流式输出路由**：Phase 1 的 token 优先发给 `onThinkingTokenListeners`（CLI 灰色显示），如果该 listener 未注册则 fallback 到 `onTokenListeners`。这样 CLI 可以将"规划思考"和"正文输出"做颜色区分。

### 1.4 四层迭代控制

| 层级 | 触发条件 | 行为 |
|------|---------|------|
| 死循环检测 | 连续 3 轮相同工具 + 相同参数 | 注入 system 警告"你似乎在重复相同操作"，clear recentCallSignatures |
| 进度提醒 | 每 10 轮 | 注入 system 提示"请评估进展，决定是否继续或拆分任务" |
| 硬上限 | 50 轮 | 返回 A-001 错误，强制退出 |
| 子目标暂停检测 | todo_write 连续 3 轮无进展 | 注入 system 警告"任务清单连续 3 轮无进展，考虑换策略" |

设计哲学：引擎不替 LLM 决定"该不该停"，而是注入上下文让 LLM 自行判断。退出永远是 LLM 自然产出文本（无 tool_calls），而非被强制中断。50 轮硬上限只是安全兜底。

面试追问："死循环检测只看调用签名够不够？"
→ 当前设计是保守的——连续 3 轮相同 name:args 才报警。如果 LLM 在 A/B 之间来回跳（比如读文件 A → 读文件 B → 读文件 A），不会被误判。更激进的检测（如编辑距离相似度）可能误杀正常操作。

**实现细节**：`recentCallSignatures` 是一个 `List<String>`，每个签名格式为 `toolName:arguments`。每次工具执行后追加。检测逻辑只比较最近 `DEAD_LOOP_THRESHOLD`（3）个签名是否全部与最后一个相同——不是滑动窗口，而是严格的最近 N 个全等。触发后立即 `recentCallSignatures.clear()` 重置计数器，防止连续警告。

**为什么不用编辑距离？** 工具参数中文件路径可能只差一个字符（`UserService.java` vs `UserService2.java`），编辑距离会误判为重复。签名精确匹配是最保守、最可解释的方案。

### 1.5 并行工具调用与执行路由

`executeSequential()` 是整个工具调用的分发中枢，根据调用类型和读写属性选择不同的执行策略：

```
executeSequential(calls, contextHistory)
  │
  ├── 全部是 task → executeSubAgentsParallel()     // 虚拟线程并发派发子Agent
  ├── 全部 isReadOnly → executeParallel()          // 虚拟线程并发执行
  └── 其他（混合或有写工具）→ 逐个串行执行
      ├── task 调用 → spawnSubAgent()
      ├── session_context → sessionService.search()
      ├── memory_save  → diskMemoryService.save()
      ├── remember     → workingMemory.put()
      ├── skill_load / skill_unload → skillLoader
      └── 普通工具 → registry.execute() → 权限检查 → 安全拦截 → Tool.execute()
```

**并行读工具的实现**：

```java
// executeParallel()
var latch = new CountDownLatch(calls.size());
for (var call : calls) {
    Thread.ofVirtual().start(() -> {
        result = registry.execute(call);
        latch.countDown();
    });
}
latch.await();
```

为什么只并行读工具？写工具（write/edit/bash）有副作用，并发执行可能导致竞态条件。读工具（read/glob/grep/web_fetch）无副作用，可以安全并发。

**虚拟线程 vs 线程池**：Java 21 虚拟线程由 JVM 在少量 OS 线程上调度，创建成本极低（~1KB 栈）。不需要线程池管理，用完即弃。每个虚拟线程内部调用 `registry.execute()`，结果写入预先分配的数组（按索引对齐），无需同步收集。

### 1.6 SubAgent 子代理系统（V3）

#### 设计动机

SubAgent 解决的三个具体问题：

1. **上下文隔离**：主 Agent 搜索代码时可能产生大量结果（5 个文件 × 1000 tokens = 5000 tokens），如果全部留在主窗口会快速触发压缩。子 Agent 有独立的上下文窗口，主 Agent 只收到一行结果摘要。

2. **并行加速**：多个独立搜索任务可以并发派发给多个子 Agent，利用虚拟线程真正并行执行。

3. **专注度提升**：探索任务（只读）和修改任务（全工具）有不同的错误成本和迭代深度，通过分类型限制防止子 Agent 在探索任务中越权修改。

#### 架构

```
主 Agent run() → executeSequential() → 检测到 call.name()=="task"
  → spawnSubAgent()
    → 构造 ToolRegistry（explore: 仅读 / general: 全工具-无task）
    → new AgentEngine(provider, subRegistry, workDir, ThinkingMode.OFF)
    → subEngine.enableSubAgents = false  // 防递归
    → subEngine.run(instruction)
  → 结果回注主 Agent contextHistory 为 tool_result
```

#### 两种类型

| 类型 | 工具集 | 上限 | 进度提醒 | 用途 |
|------|--------|------|---------|------|
| explore | 仅读（glob/grep/web_fetch/read） | 15 轮 | 7 轮 | 搜索代码、定位文件 |
| general | 完整工具集，不含 task | 25 轮 | 7 轮 | 改代码、写文件 |

防递归靠 `enableSubAgents` volatile 标志——子 Agent 构造时设为 false，tool 列表中自然不出现 task。

#### 并行派发

同一轮多个 task + 普通工具混合时的执行策略：

- 全部是 task → `executeSubAgentsParallel()`：虚拟线程 + CountDownLatch 全部并发
- 全部是只读工具 → `executeParallel()`：虚拟线程并发
- 混合或有写工具 → `executeSequential()`：逐个串行

全部完成后，每条结果作为独立的 tool_result 回注主 Agent 的 contextHistory。

#### 关键设计决策

**不继承 TWO_STAGE**：子任务已经收到明确指令，不需要再规划。直接用 ThinkingMode.OFF 执行。

**继承 permissionMode**：主 Agent 切 ASK，子 Agent 写操作也需要用户确认。权限策略一致。

**独立三层迭代控制**：子 Agent 有自己的死循环检测（3 轮）、进度提醒（7 轮）、硬上限（15/25 轮）。不会因为主 Agent 已经跑了 30 轮而提前触发子 Agent 的硬上限。

**包级私有 lastRunTurns**：`spawnSubAgent()` 通过读这个字段获取子 Agent 实际执行轮次，用于日志和 CLI 显示。

#### CLI 可见性

通过 CopyOnWriteArrayList 回调模式：

- `onSubAgentSpawn(instruction, type, maxTurns)` → CLI 灰色打印派发信息
- `onSubAgentComplete(summary, turns, tokens, duration)` → CLI 打印完成摘要

用户不看到子 Agent 内部每一步，但知道派了什么任务、多久完成、结果摘要。这是刻意设计的透明度边界——内部搜索细节会干扰用户，但进度和结果必须可见。

### 1.7 AgentState 状态机（V3.1）

```java
public enum AgentState {
    IDLE,         // 空闲，等待用户输入
    PLANNING,     // Two-Stage Phase 1 规划中
    REASONING,    // LLM 推理中（等待 API 响应）
    EXECUTING,    // 工具执行中
    REPLYING,     // 向用户输出最终回复
    INTERRUPTED,  // Ctrl+C 中断
    ERROR         // 异常终止
}
```

通过 `Consumer<AgentStateEvent>` 回调通知 CLI 层更新状态显示。状态转换在 `run()` 方法的关键路径上触发：

```
IDLE → PLANNING (如果 TWO_STAGE) → REASONING → EXECUTING (如果有 tool_calls) → REPLYING
  ↑                                                                                    │
  └──────────────────── 下一轮 run() ←─────────────────────────────────────────────────┘
```

任意状态 → INTERRUPTED（用户 Ctrl+C）或 ERROR（LLM 异常/硬上限）。

### 1.8 工作内存 Working Memory（V3.6，记忆体系 L2）

上下文压缩（MessageMasker + LadderedCompactor）会掩盖旧轮次的关键发现。`remember(key, value)` 引擎级工具让 LLM 主动标记"这条信息很重要"——引擎负责在压缩后仍以 `[Working Memory]` system 消息注入这些 key-value 对。详见 4.2 节。

### 1.9 子目标跟踪（V3.7）

监视 `todo_write` 工具的调用，跟踪任务清单的进展。

**进展检测**：每轮 run() 后对比当前 `todo_write` 的 todos 内容与上一轮的快照。如果内容完全相同（content + status），`turnsWithoutTodoProgress++`。

**暂停警告**：连续 `TODO_STALL_THRESHOLD`（3）轮无进展 → 注入 system 警告：
```
[Runtime] 任务清单连续 3 轮无进展。请检查当前方法是否有效，考虑换策略或向用户确认。
```

**自动完成判定**：`allTodosCompleted()` 检查最近一次 todo_write 的所有条目是否都为 `completed` 状态。如果是，且 LLM 返回纯文本（无 tool_calls），引擎判定任务完成并正常退出。

设计哲学：引擎不替 LLM 决定"任务是否完成"——它只是检测信号并注入提示。退出判定仍然是 LLM 自己做的。

---

## 2. 工具体系

### 2.1 插件化设计

```java
public interface Tool {
    String name();              // kebab-case
    String description();       // LLM 可读的功能描述
    String inputSchema();       // JSON Schema 参数定义
    Result<String> execute(String arguments);
    default boolean isReadOnly() { return false; }
}
```

新增工具 = 实现 Tool 接口 → 注册到 ToolRegistry。8 个工具零耦合，每个独立可测。

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

**EditTool 四级容错降级算法**：

这是 miniclaw 最精巧的单个算法。LLM 生成的 `old_text` 常有缩进不一致、换行符差异等问题，直接 `String.replace()` 几乎必定失败。

```
原始文件内容
  ↓
L1: 精确匹配 → countOccurrences() == 1 → 直接替换
  ↓ >1 → E-001（多处匹配）
  ↓ 0  → 继续
L2: 换行符归一化 → \r\n → \n → 再试
  ↓ >1 → E-001
  ↓ 0  → 继续
L3: Trim 首尾空行/空格 → strip() → 再试
  ↓ >1 → E-001
  ↓ 0  → 继续
L4: 逐行去缩进滑动窗口
  - 将文件和 old_text 按 \n 切分
  - 每行 strip() 去掉缩进差异
  - 滑动窗口逐行匹配 → 唯一匹配则替换匹配块
  ↓ 0  → E-002（未找到）
  ↓ >1 → E-001（模糊匹配多处）
```

四级降级的设计哲学：不是让 LLM 生成更"准确"的 old_text，而是在工具侧容忍 LLM 的格式幻觉。每一级只解决一类差异（换行符、缩进、首尾空白），分级诊断错误码让 LLM 知道"到底哪出了问题"。

**为什么不用 AST 替换？** AST 要求代码语法正确，但 miniclaw 经常在写代码的过程中调用 edit（代码可能本身有错误）。字符串替换对任意文本通用，不限于编程语言。

### 2.2 统一返回模型

```java
public sealed interface Result<T> {
    record Ok<T>(T data) implements Result<T> {}
    record Err<T>(ErrorInfo error) implements Result<T> {}
}
public record ErrorInfo(String errorCode, String message) {}
```

禁止业务代码抛异常传递已知错误。异常仅用于 JVM 不可恢复错误（如 OOM）。

面试追问："为什么用 sealed interface 而不是普通 Result 类？"
→ sealed 限制为 Ok/Err 两种子类型，编译器保证 switch 穷尽性——任何处理 Result 的地方都不会漏掉错误分支。

### 2.3 安全拦截链

在 `ToolRegistry.execute()` 入口，工具执行之前遍历所有拦截器：

```java
public Result<String> execute(ToolCall call) {
    for (var interceptor : interceptors) {
        var blocked = interceptor.check(call);
        if (blocked != null) return Result.Err(blocked);
    }
    return tool.execute(call.arguments());
}
```

**CommandSafetyInterceptor：8 条正则规则**：

```java
Pattern.compile("rm\\s+(-[rRf]+\\s+)*[/~]")   // rm -rf / 或 ~
Pattern.compile("rm\\s+(-[rRf]+\\s+)*\\*")      // rm -rf *
Pattern.compile("sudo\\s")                       // sudo 提权
Pattern.compile("chmod\\s+777")                  // chmod 777
Pattern.compile(">\\s*/dev/sd")                  // 覆盖磁盘设备
Pattern.compile("mkfs\\.\\w+")                   // mkfs.ext4 等格式化
Pattern.compile("dd\\s+if=")                     // dd 磁盘操作
Pattern.compile(Pattern.quote(":(){ :|:& };:")) // fork bomb
```

只拦截 `bash` 工具调用，从 JSON 参数中提取 `command` 字段匹配。拦截成功返回 `Result.Err(T-001)`，LLM 能看到拦截原因。

关键点：拦截在 ALL 权限模式生效（包括 AUTO），不是 ASK 模式专属。因为高危命令在任何情况下都不应该执行。

**BashTool 跨平台 Shell 检测**：Windows 上不直接调 `bash`（可能是 WSL shim，性能差），而是按优先级探测：

```
1. 环境变量 GIT_BASH → 直接使用
2. PATH 中搜索 Git 安装目录下的 bash.exe（跳过 System32）
3. 硬编码常见路径: D:\Git\usr\bin\bash.exe, C:\Program Files\Git\...
4. 全部失败 → cmd /c
```

BashTool 还做了 30s 超时强杀（`process.destroyForcibly()`）和 8000 字节输出截断（防 OOM），超时时在输出末尾追加警告信息。

### 2.4 引擎级工具模式

miniclaw 有 4 个特殊工具不走 `ToolRegistry`，而是在 `AgentEngine.executeSequential()` 中硬编码拦截：

| 工具 | 层级 | 操作对象 | 为什么不是普通 Tool |
|------|------|---------|-------------------|
| `task` | 引擎 | 子 Agent 引擎 | 执行体是 `AgentEngine.run()`，不是文件系统 |
| `session_context` | 引擎 | SessionService | 操作引擎内部状态，需要访问会话存储 |
| `memory_save` | 引擎 | DiskMemoryService | 写入 `~/.miniclaw/memory/`，需要访问记忆服务 |
| `remember` | 引擎 | ConcurrentHashMap | 写入工作内存，纯内存操作 |

统一处理模式：

```java
// executeSequential() 中
if (TASK_TOOL_NAME.equals(call.name()))       → spawnSubAgent(call)
if (SESSION_CONTEXT.equals(call.name()))      → sessionService.search(query)
if (MEMORY_SAVE_TOOL_NAME.equals(call.name())) → diskMemoryService.save(entry)
if (REMEMBER_TOOL_NAME.equals(call.name()))   → workingMemory.put(key, value)
```

这些工具在 `buildSystemPrompt()` 阶段以 ToolDefinition 形式动态追加到可用工具列表末尾，LLM 无感知它们是"特殊"的。权限控制也一致：task/session_context 始终可用，memory_save 在 PLAN 模式下禁用。

---

## 3. 上下文工程管线

miniclaw 的上下文管理是一条五阶段管线：

```
原始 contextHistory
  → [嗅探工作区状态] 注入 TODO.md / PLAN.md 内容
  → [L3 自动提取] 条件触发，静默 LLM 提取记忆
  → [MessageMasker] 角色+时效双维掩码（>12轮）
  → [LadderedCompactor] Always-On + Pressure + L3 摘要（>60% token）
  → provider.generate()
```

### 3.1 为什么需要上下文压缩？

每轮 ReAct 循环追加 2-3K tokens（工具调用 + 结果）。多轮对话后 contextHistory 线性膨胀，超出 LLM 上下文窗口则报错。需要在信息保全和 token 预算之间做权衡。

### 3.2 两级阈值压缩（LadderedCompactor）

当前设计是两阈值 + Always-On 常驻规则，不再有 L1/L2/L3 标签：

```
Token 使用率 < 60%  → Always-On 常驻规则:
                      · TOOL 老消息 → [tool:name output — N bytes] 掩码
                      · TOOL >1000B → 掐头去尾各保留500字节
                      · 保护区（最近3轮 + SYSTEM）→ 完整保留
                      · 低信号行移除（分隔线、截断标记、"executing..." 等前缀）
                      · ASSISTANT(tool_calls) → 保留逻辑链

          60%-90%  → 在 Always-On 之上叠加 Pressure 压缩:
                      · 逐行压缩：删空行、删低信号行、截断 >300 字符的行
                      · 压缩到空内容 → [empty after compression] 占位

          > 90%    → 在 Always-On + Pressure 之上叠加 L3 LLM 摘要:
                      · 旧消息（保护区之前）→ LLM 生成 200-400 字中文摘要
                      · 替换为 [Conversation Summary] system 消息
                      · 降级：summarizer 不可用或调用失败 → 返回 Pressure 结果
```

与旧版（L1删空行→L2 SHA去重→L3摘要）的关键区别：
- **去掉了 L2 SHA-256 去重**：MessageMasker 已经对旧 TOOL 做了更智能的角色感知掩码，再 SHA 去重是多余开销
- **Always-On 每次运行**：TOOL 掩码和保护区逻辑在每次 compact() 都执行，不依赖 token 阈值
- **L3 从 80% 移到 90%**：因为 Always-On + Pressure 已经足够激进，L3 只在极端情况下触发

面试追问："为什么去掉了 SHA-256 去重？"
→ 在实践中效果不佳。LLM 两轮都读同一个文件时，两次输出完全相同，去重确实省了 token。但更常见的是 LLM 三轮读三个稍有差异的同目录文件，SHA-256 完全匹配不上，反而是 MessageMasker 的 `[tool:read output — 2850 bytes]` 掩码更有效。

### 3.3 压缩容器的独立性

**为什么不直接把 LLMProvider 注入 compactor？**

在 context 模块定义 `Summarizer` 接口（`@FunctionalInterface String summarize(List<Message>)`），engine 层用 lambda 桥接。这样 context 模块不依赖 provider 模块，保持分层清晰。

降级安全：LLM 摘要调用失败 → 返回 Pressure 压缩结果，log warning，不阻断 ReAct。

### 3.4 MessageMasker：角色+时效双维掩码（V3.4a）

**为什么需要 Masker？** LadderedCompactor 对所有消息一视同仁，但不同类型的消息信息密度差异巨大：
- USER 消息（"重构 auth 模块"）→ 高密度，应尽可能保留
- TOOL 消息（600 行编译输出）→ 低密度，可大幅掩码
- ASSISTANT 消息（推理链）→ 中密度，可截断

MessageMasker 在 Compactor 之前运行，按轮次距离 + 角色差异化处理。

#### 四层分级

```
轮次距离 ←────────────── 最近 ────── 最旧 ──────────────→
           Tier0 (0-2)  Tier1 (3-9)  Tier2 (10-19)  Tier3 (20+)
──────────────────────────────────────────────────────────────
USER        全保留         全保留        全保留         丢弃
ASSISTANT   全保留         截断500/300   截断200/150    丢弃
  +tool_calls             [tool_calls:   [tool_calls:  
                           name] + 正文   name] + 正文
TOOL        掐头去尾       掩码(>500B)   省略(>500B)    丢弃
  >500B     (保留首尾500)  [N bytes]     [elided]
  <500B     全保留         全保留        全保留          
  empty     占位符         占位符        占位符          丢弃
SYSTEM      保留           保留          保留           保留
```

#### 关键边界处理：空 TOOL 输出

原始设计对空 TOOL 返回 null（丢弃），导致 API 400 错误：`tool_calls must be followed by tool messages`。修复方案：空 TOOL → `[tool output — empty]` 占位符，保留 toolCallId，维持 ASSISTANT(tool_calls) → TOOL 消息配对。

面试追问："为什么 Tier3 直接丢弃而不是摘要？"
→ Tier3 是 20 轮以前的消息。在这个距离上，信息要么已经被后续轮次覆盖（读了更新的文件），要么已经在 L2 去重时被标记为陈旧，要么已经在 L3 摘要中被 LLM 提炼过。直接丢弃是安全的——而且零 token 成本。

### 3.5 PromptAssembly：五层系统提示词组装（V3.3）

```java
public static String assemble(
    String l1Kernel,           // 核心行为指令（不可变）
    String l2WorkspaceRules,   // CLAUDE.md 项目规则
    SkillCatalog skillCatalog, // 可用技能目录
    Map<String, String> activeSkills, // 已加载的技能提示词
    String l4ModePrompt,       // 权限模式提示词
    String l5MemoryIndex       // MEMORY.md 内容
)
```

五层分离的设计目的：每层独立更新，不互相污染。例如权限模式切换（`/auto` → `/ask`）只需重建 l4ModePrompt，不需要重新解析 CLAUDE.md。

### 3.6 SkillLoader 技能外挂系统（V3.4）

从 `~/.agents/skills/`（用户级，兼容 Claude Code/Cursor 生态）和 `{workDir}/.miniclaw/skills/`（项目级）扫描 `SKILL.md` 文件：

```
.agents/skills/
  using-superpowers/
    SKILL.md              ← 技能定义（name + description + prompt body）
    references/           ← 可选参考文件
      codex-tools.md
```

`SkillCatalog` 维护技能注册表，`activeSkills` 跟踪当前已加载的技能。LLM 通过 `skill_load` / `skill_unload` 引擎级工具按需加载/卸载技能提示词。

### 3.7 状态外部化：TODO.md + PLAN.md 双向同步（V3.4d）

**问题**：`todo_write` 工具创建的任务清单只存在于 contextHistory 中。会话清空后丢失。

**方案**：每次 `todo_write` 调用时，双写到 `.miniclaw/todo.md`。每次 `run()` 开头，`sniffWorkspaceState()` 读取 `.miniclaw/todo.md` 和 `.miniclaw/PLAN.md`，将内容以 system 消息注入上下文。

**自举检测**：如果 LLM 调用 `read(".miniclaw/todo.md")` 或 `read(".miniclaw/PLAN.md")` 来获取自己的状态，引擎会检测到这一模式并拦截——直接注入内容到上下文，避免浪费一次 API 调用。

---

## 4. 五层记忆架构

miniclaw 的记忆系统分五层（L1-L5），从挥发性到持久性逐层递进：

```
L1 会话上下文 (contextHistory)       ← 单次会话，消息列表，JVM 退出即销毁
L2 工作内存 (remember)               ← 会话级，ConcurrentHashMap，防压缩丢失关键信息
L3 状态外部化 (TODO.md/PLAN.md)      ← 项目级，文件系统，跨会话可读
L4 磁盘记忆 (memory/)                ← 跨会话，自动提取 + 手动保存 + MEMORY.md 索引
L5 会话检索 (BM25)                   ← 跨会话，搜索历史摘要 + 启动注入
```

**L4/L5 的边界**：L4 是"写"——把值得保留的信息持久化到 `~/.miniclaw/memory/`。L5 是"读"——从 `~/.miniclaw/sessions/` 检索引擎曾经处理过的相关上下文。两者对象不同：L4 存的是**元知识**（偏好、决策、约束），L5 存的是**历史会话摘要**（上次讨论过什么话题）。

### 4.1 L1 会话上下文

最基础的一层：`contextHistory` 是一个 `List<Message>`，包含了 system prompt + 当前会话的所有用户消息/助手回复/工具调用/工具结果。这是 ReAct 循环的完整工作区。

```
contextHistory = [
  System: "你是 miniclaw AI 编程助手..."
  User: "帮我重构 auth 模块"
  Assistant: [tool_calls: read auth/UserService.java]
  Tool: "public class UserService { ... }"
  Assistant: [tool_calls: read glob: **/auth/**]
  Tool: "auth/UserService.java\nauth/AuthFilter.java"
  ...
]
```

特点：
- 整个 ReAct 循环都建构在它上面——每次 provider.generate() 都传入 contextHistory 的全部或部分
- 会话关闭后销毁（除非 `/session save` 将完整 messages 保存到 `sessions/<id>.json`）
- 上下文压缩（MessageMasker + LadderedCompactor）操作的就是这个列表

与 L2 工作内存的关系：contextHistory 中旧轮次的工具输出会被 MessageMasker 掩码、被 LadderedCompactor 压缩。L2 就是为了在这些"信息破坏"操作中抢救关键发现而存在的。

### 4.2 L2 工作内存（remember）

**动机**：上下文压缩（MessageMasker + LadderedCompactor）会掩盖旧轮次中 LLM 发现的关键信息——"auth 入口在 AuthFilter.java:42"、"数据库连接池在 application.yaml:15"。这些细节对后续轮次至关重要，但压缩算法无法判断其重要性。

**方案**：`remember(key, value)` 引擎级工具，一个会话级 `ConcurrentHashMap<String, String>`。

```
LLM 调用 remember("auth-entry", "AuthFilter.java:42")
  → handleRemember() → workingMemory.put("auth-entry", "AuthFilter.java:42")
  → 后续每轮 buildSystemPrompt() → 注入 [Working Memory] system message:
      [Working Memory]
      auth-entry: AuthFilter.java:42
      db-config: application.yaml:15
```

与 L1 的区别：L1 是完整的对话历史，可能已被压缩。L2 是 LLM 主动标记的"这条信息很重要，别丢"。LLM 负责决定什么值得记住，引擎负责在压缩后仍然保留这些信息。

用 ConcurrentHashMap 而非普通 HashMap，为未来的 `/btw` 后台并行任务预留线程安全。

### 4.3 L3 状态外部化

见 3.7 节。`todo_write` 双写 `.miniclaw/todo.md`，`sniffWorkspaceState()` 每轮回读。任务跟踪跨会话延续。

### 4.4 L4 磁盘记忆

L4 是跨会话持久化的记忆存储，包含两个写入路径和一个读取路径：

**写入路径 A：自动提取（L3 extraction）**

引擎在每轮 run() 后条件触发静默 LLM 调用，从对话中自动提取记忆。

#### 触发条件（三者同时满足）

```
turnsSinceLastExtraction >= 5        // 冷却 5 轮
AND currentMsgCount - lastExtracted >= 10  // 新增 >= 10 条消息
AND tokenUsage > 60%                  // 上下文开始拥挤
AND permissionMode != PLAN            // PLAN 模式不提取
```

#### 提取流程

1. 裁剪消息：从上次提取点后取 ≤30 条非 SYSTEM 消息，TOOL 输出截断至 200 字符
2. 构造提取请求：`[system: EXTRACTION_PROMPT] + [user: 格式化的对话片段]`
3. 静默调用 `provider.generate(msgs, EMPTY_TOOLS)` —— 无流式、无工具
4. JSON 解析（容错：允许 markdown 代码块包裹）
5. 逐个 `diskMemoryService.save(entry)`，最多 3 条
6. `setMemoryIndex()` 热加载，下次 run() 立即生效

#### 容错设计

- LLM 调用失败 → log.warn，不重置计数器，下次满足条件重试
- JSON 解析失败 → log.debug，静默跳过
- 空结果 / 无可提取 → 重置计数器，正常继续
- 任何异常都不影响主 ReAct 循环

**关键定位**：提取发生在 buildSystemPrompt → 注入工作内存之后的 contextHistory 上，但在 MessageMasker 和 LadderedCompactor 之前。这保证了提取器看到的是完整内容（未掩码未压缩），提取的 token 成本是可控的（~30 条 × 300 字符 ≈ 2-3K tokens）。

面试追问："为什么不直接用主 LLM 调用的 contextHistory 做提取？"
→ 两个原因：(1) 提取是一个独立的小 LLM 调用，token 预算小，需要裁剪消息；(2) 提取在 mask/compact 之前执行，看到的还是完整消息，信息保真度更高。

**写入路径 B：手动保存（memory_save）**

LLM 可以通过 `memory_save` 引擎级工具主动保存记忆。与自动提取的差异：手动保存是 LLM 自觉行为（"用户让我记住这个偏好"），自动提取是引擎驱动的补漏（"LLM 没调用 memory_save 但对话中有值得保留的信息"）。两条路径写入同一存储，MEMORY.md 索引统一管理。

**读取路径：MEMORY.md 索引注入**

磁盘记忆存储在 `~/.miniclaw/memory/`，每条记忆一个 Markdown 文件：

```markdown
---
name: coding-style
description: prefer terse code, no unnecessary abstractions
type: feedback
---

Karpathy 准则：简单优先，精准修改...
```

文件命名：`{type}_{name}.md`（如 `feedback_coding-style.md`）。`MEMORY.md` 是索引文件，每行一条：`- [Title](file.md) — one-line hook`。在 `buildSystemPrompt()` 中注入为 `[Available Memory]`。自动提取或手动保存后，`setMemoryIndex()` 热加载索引，下次 run() 立即生效。

### 4.5 L5 会话检索

L5 管理历史会话的搜索和复用。数据来源是 `~/.miniclaw/sessions/`（会话 JSON 文件），不是 L4 的 `memory/` 目录。两者互补：L4 存提炼后的元知识，L5 存原始会话记录。

**BM25 关键词搜索**：替换原始的 `String.contains()` 子串匹配。

```java
// KeywordScorer（~55 行）
public class KeywordScorer {
    private static final double K1 = 1.5;  // 词频饱和参数
    private static final double B = 0.75;  // 文档长度归一化

    public double score(String query, String document) {
        // BM25: Σ IDF(qi) * TF(qi, doc) * (K1+1) / (TF + K1*(1-B + B*docLen/avgLen))
    }

    static List<String> tokenize(String text) {
        // 标点/空格切分 + CJK 2-gram（中文兜底）
    }
}
```

**分词策略**：
- 英文/数字：按标点和空格切分
- 中文：2-gram（"数据库迁移" → ["数据", "据库", "库迁", "迁移"]）

**为什么 2-gram 而非分词器？** 引入 jieba/HanLP 等分词库会增加 10MB+ 依赖，而 BM25 的 2-gram 近似在 100 条会话规模下精度足够。

**IDF 计算**：基于 corpus（所有会话的检索文本）计算 IDF，而非硬编码。新增会话后 corpus 更新，IDF 自动调整。

**为什么不用嵌入？** ~100 条会话摘要，中文关键词匹配完全够用。500+ 条时再加 embedding。

**会话自动保存**：`clearSession()` 开头自动保存当前会话，命名 `[auto]` + 首条用户消息截断 50 字符。跳过条件：只有 system prompt、已自动保存过、未配置 SessionService。失败只 `log.warn`，不阻断 clearSession。

**启动注入**：新会话首条用户消息 → `sessionService.search(firstUserMessage)` → BM25 score 前 3 条 → 注入 system prompt。

```
[Related Past Sessions]
- (05-21) [auto] 重构 auth 模块的合规要求: 需要替换 session token 存储方式...
- (05-18) [auto] 数据库迁移遇到了时区问题: 使用 Flyway 将 user 表从 MySQL...
```

实现位置：`run()` 首次调用时，`injectRelatedSessions()` 在首条 user message 之后立即执行。

---

## 5. LLM 适配层

### 5.1 统一 Provider 接口

```java
public interface LLMProvider {
    Message generate(List<Message> messages, List<ToolDefinition> tools);
    Message generateStream(List<Message> messages, List<ToolDefinition> tools,
        Consumer<String> onToken);
}
```

当前实现：`OpenAIProvider`，兼容所有 OpenAI 格式 API（DeepSeek、智谱、Moonshot 等）。扩展 Claude 原生 API 只需新增实现类。

### 5.2 韧性策略（三级）

| 机制 | 触发 | 行为 |
|------|------|------|
| 超时控制 | 连接 10s / 请求 60s | 直接超时 |
| 重试 | HTTP 429 / 5xx / IO 超时 | 3 次指数退避（2s → 4s → 8s） |
| 熔断 | 连续 5 次失败 | OPEN → 30s 冷却 → HALF_OPEN 探测 → 成功恢复 / 失败重新熔断 |

4xx 不重试：401/403/404 等客户端错误重试无意义，直接返回错误。

**熔断器实现细节**：

```java
// 三个状态，零对象分配
private static final int CIRCUIT_THRESHOLD = 5;        // 连续失败阈值
private static final long CIRCUIT_COOLDOWN_MS = 30_000; // 冷却期

private int consecutiveFailures;  // 连续失败计数
private long circuitOpenUntil;    // 0=CLOSED, >now=OPEN, <now=HALF_OPEN

private void checkCircuit() {
    if (circuitOpenUntil == 0) return;           // CLOSED
    long now = System.currentTimeMillis();
    if (now < circuitOpenUntil) {                // OPEN
        throw new LLMException("熔断器开启，请等待 " + (circuitOpenUntil - now) / 1000 + "s");
    }
    // HALF_OPEN: 允许一次探测请求通过
}

private synchronized void recordSuccess() {
    consecutiveFailures = 0;
    circuitOpenUntil = 0;  // CLOSED
}

private synchronized void recordFailure() {
    consecutiveFailures++;
    if (consecutiveFailures >= CIRCUIT_THRESHOLD && circuitOpenUntil == 0) {
        circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;  // OPEN
    } else if (consecutiveFailures >= CIRCUIT_THRESHOLD) {
        circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;  // HALF_OPEN 失败 → 重新 OPEN
    }
}
```

状态机只有三个状态但用一个 `long` 字段编码，零对象分配。`synchronized` 方法保证 `consecutiveFailures` 和 `circuitOpenUntil` 的原子更新（generate/generateStream 在多线程下可能同时调用）。

HALF_OPEN 时只允许一个探测请求。如果该请求成功 → `recordSuccess()` 恢复。如果失败 → `recordFailure()` 重新开启 30s 冷却。探测请求也能受到 `checkCircuit()` 的保护——熔断器在 HALF_OPEN + 冷却期内再次触发时会重新计算。

**重试策略实现**：

```java
private byte[] sendWithRetry(HttpRequest httpRequest) {
    for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
        if (attempt > 0) {
            long delayMs = (long) Math.pow(2, attempt) * 1000; // 2s, 4s, 8s
            Thread.sleep(delayMs);
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) return response.body();
            if (!shouldRetry(response.statusCode())) throw new LLMException(...);
        } catch (IOException e) {
            // IO 超时可重试
        }
    }
    throw new LLMException("已重试 " + config.maxRetries() + " 次...");
}

private boolean shouldRetry(int statusCode) {
    return statusCode == 429 || statusCode == 500 || statusCode == 502
        || statusCode == 503 || statusCode == 504;
}
```

重试尝试 `maxRetries() + 1` 次（初始 + 3 次重试 = 共 4 次）。`generateStream()` 不重试——SSE 流中断后无法从断点恢复。

### 5.3 流式输出与 SSE 解析

`generateStream()` 解析 OpenAI 格式的 SSE（Server-Sent Events），逐 chunk 累积增量内容：

```java
while ((line = reader.readLine()) != null) {
    if (line.isEmpty()) continue;        // SSE 分隔空行
    if (line.startsWith(": ")) continue;  // SSE 注释（keepalive）
    if (!line.startsWith("data: ")) continue;
    String data = line.substring(6);
    if ("[DONE]".equals(data)) break;

    JsonNode chunk = objectMapper.readTree(data);
    JsonNode delta = chunk.get("choices").get(0).get("delta");
    // 文本增量 → 累积到 contentBuilder + 回调 onToken
    // tool_call 增量 → 按 index 累积到 Map<Integer, ToolCallAccum>
    // finish_reason → 记录结束原因
}
```

**工具调用的增量累积**是关键难点。OpenAI 的流式 API 将同一个 tool_call 跨多个 chunk 发送：
- Chunk 1: `delta.tool_calls[0] = {index: 0, id: "call_xxx", function: {name: "bash"}}`
- Chunk 2: `delta.tool_calls[0] = {index: 0, function: {arguments: "{\"comm"}}`
- Chunk 3: `delta.tool_calls[0] = {index: 0, function: {arguments: "and\":\"ls\"}"}}`

`ToolCallAccum` 是一个 mutable 累积器，按 `index` 分组，逐个拼接 `id`、`name`、`arguments`。所有 chunk 处理完后，按 index 排序组装为 `List<ToolCall>`。

CLI 模式：`onToken` → `System.out.print` 逐字打印（JLine3 兼容）
飞书模式：`onToken` → `TokenBatcher` 节流编辑

#### L3 自动提取中的 Provider 复用

L3 提取使用的是同一个 `LLMProvider` 实例，但调用 `generate()` 而非 `generateStream()`（静默，无流式输出），且 `EMPTY_TOOLS`（无工具定义）。提取失败只记 log 不中断主流程——provider 熔断状态可能影响提取，但提取失败不触发 `recordFailure()`。

---

## 6. 飞书消息通道

### 6.1 架构

嵌入式 HTTP Server（`com.sun.net.httpserver`，JDK 内置）监听 `/feishu/callback`，虚拟线程处理请求。通过 ngrok 或手动配置暴露公网 URL。

### 6.2 双向镜像的关键设计

**问题**：CLI 和飞书共享一个 AgentEngine。两个通道同时有请求时怎么办？

**方案**：`AtomicBoolean busy` + `tryAcquire()/release()` 共享锁。

```
CLI 输入 → engine.tryAcquire() → 拿到锁 → engine.run() → release()
                                         ↓
飞书输入 → engine.tryAcquire() → 没拿到 → 回复"正在处理中"
```

谁发起谁负责：CLI 发起的请求，飞书镜像静默跳过（不触发飞书→CLI 的输入通知）。

### 6.3 多路流式监听

`onTokenListeners` 用 `CopyOnWriteArrayList` 实现线程安全的 snapshot 迭代：

- CLI 发起 → 注册 `System.out::print`（终端打印） + `batcher::onToken`（飞书镜像）
- 飞书发起 → 注册 `batcher::onToken`（飞书回复） + `System.out::print`（终端镜像）

关键 bug 修复：Java lambda 引用不 override `equals()`，`batcher::onToken` 每次创建新 lambda 对象，`CopyOnWriteArrayList.remove()` 永远匹配不到原始 lambda。解决方案：存为局部变量 `Consumer<String> listener = batcher::onToken`，add 和 remove 使用同一个引用。

### 6.4 TokenBatcher 节流算法

飞书消息编辑 API 有频率限制，不能每个 token 都发一次编辑请求。TokenBatcher 用一个自适应节流器控制编辑频率：

```
首帧: 至少累积 50 字符 OR 超过 800ms → 发第一次编辑
稳态: 至少累积 100 字符 OR 超过 1500ms → 发后续编辑
上限: 最多 8 次编辑 → 最后一次直接 flush 完整内容
```

```java
void onToken(String token) {
    buffer.append(token);
    int newChars = buffer.length() - lastFlushedLen;
    int minInterval = firstEdit ? 800 : 1500;
    int minChars = firstEdit ? 50 : 100;

    if (editCount < 8
        && (now - lastEditTime) > minInterval
        && newChars >= minChars) {
        flush();      // 发送飞书编辑请求
        editCount++;
        firstEdit = false;
    }
}
```

设计权衡：
- **首帧更激进的更新频率**：用户等待时希望尽快看到内容出现，"正在输入"的空窗期不能超过 0.8s
- **稳态更保守的频率**：降低 API 开销，1.5s 间隔 × 8 次 = 12s 覆盖，大多数回复在 8 次编辑内完成
- **8 次上限后停发**：防止长回复过度调用 API。最终调用 `finalEdit()` 发送完整内容，不走节流逻辑。

### 6.5 飞书 CLI 集成（lark-cli）

miniclaw 通过 `@larksuite/cli`（飞书官方 CLI）接入飞书开放平台的全部能力。

#### 架构

```
用户: "帮我在飞书写个周报"
  → LLM 在 skill catalog 看到 lark-doc
  → skill_load lark-doc
  → SkillLoader.loadPrompt("lark-doc") → 注入 SKILL.md 正文
  → LLM 通过 bash 工具执行: lark-cli docs +create --title "周报" --content "..."
  → 飞书文档创建完成
```

与 FeishuBot（嵌入式 HTTP Server 接收飞书消息）的区别：CLI 集成走的是**出站**方向——miniclaw 作为客户端操作飞书资源（文档、表格、日历、审批等），而非接收用户消息。

#### Skill 生态

飞书官方通过 `npx skills add larksuite/cli -y -g` 提供了 22 个预置 Skill，安装到 `~/.agents/skills/`：

| 类别 | Skill |
|------|-------|
| 文档 | lark-doc, lark-sheets, lark-slides, lark-markdown |
| 协作 | lark-wiki, lark-minutes, lark-task, lark-okr |
| 通信 | lark-im, lark-mail, lark-calendar, lark-vc |
| 管理 | lark-approval, lark-attendance, lark-contact, lark-event |
| 开发 | lark-base, lark-openapi-explorer, lark-skill-maker |

每个 Skill 的 SKILL.md 包含 YAML frontmatter（name, description）+ Markdown body（lark-cli 命令示例）。格式与 miniclaw SkillLoader 完全兼容。

#### 接入改动

实际改动量很小：

| 改动 | 说明 |
|------|------|
| SkillLoader 路径适配 | `userSkillsDir` 从 `~/.miniclaw/skills/` 改为 `~/.agents/skills/` |
| ReadTool 白名单 | 新增 `extraRoots` 参数允许读取 `~/.agents/skills/` 下的 reference 文件 |
| `skill_load` 增强 | 返回 skill 目录路径，让 LLM 知道相对引用的解析基点 |
| BashTool `.cmd` 适配 | 检测 `.cmd`/`.bat` 命令自动切换 `cmd.exe /c`，避免 bash 嵌套引号卡死 |

前置条件：`lark-cli config init --new` 创建应用 → `lark-cli auth login --recommend` 完成 OAuth 授权。

设计哲学：飞书 CLI 不是 miniclaw 的"模块"，而是通过 BashTool + SkillLoader 两个现有机制间接接入。LLM 看到的是一个装了 22 个飞书技能的 bash 环境，无需感知 CLI 的内部实现。

---

## 7. 终端交互 + 会话管理

### 7.1 JLine3 能力

- Tab 补全：`MiniclawCompleter` 实现 11 条斜杠命令补全
- 历史持久化：`~/.miniclaw/history`，跨重启保留，Ctrl+R 搜索
- 多行编辑：`\` 续行
- 跨平台信号处理：`Terminal.handle(Signal.INT, ...)` 替代 `sun.misc.Signal`（Windows 不兼容）

### 7.2 --root 工作目录限制

`resolveWorkDir()` 校验路径存在性 + 目录类型。所有工具（read/write/bash 等）基于该目录操作，配合各工具自身的路径穿越防护（`..` 拦截），防止越权访问。

### 7.3 会话持久化系统

#### 数据模型

```java
public record SessionMeta(
    String id,               // UUID 前 8 位
    String name,
    Instant createdAt,
    Instant updatedAt,
    int messageCount,
    String firstUserMessage, // 首条用户消息，截断 120 字符
    String summary           // LLM 生成的 50-150 字摘要
) {}
```

元数据与消息体分离存储：`index.json` 只存 `SessionMeta[]`，列出会话时无需加载完整消息体。每个会话的完整数据（meta + messages）存储在 `<id>.json`。

#### 存储结构

```
~/.miniclaw/sessions/
  ├── index.json       ← [{SessionMeta}]  按 updatedAt 降序
  ├── a1b2c3d4.json    ← { meta, messages }
  └── e5f6g7h8.json
```

#### SessionService 门面层

| 方法 | 逻辑 |
|------|------|
| `save()` | 生成 id → LLM 生成摘要 → 委托 store.save() |
| `load()` | 直接委托 store.load() |
| `list()` | 读 index.json 返回 List<SessionMeta> |
| `search()` | BM25 对 name+summary+firstUserMessage 计分排序 → top 5 |
| `stats()` | 按 updatedAt 分 4 个年龄桶（<7d / 7-30d / 30-90d / >90d），统计 count + bytes |
| `prune()` | 删除 updatedAt 早于 cutoff 的会话 |
| `delete()` | 删除指定 id 的会话 |

#### LLM 摘要生成与降级

```java
private String generateSummary(List<Message> messages) {
    if (provider == null) return fallbackSummary(messages);
    try {
        Message result = provider.generate(
            List.of(Message.system(SUMMARY_PROMPT), Message.user(formattedMessages)),
            EMPTY_TOOLS);
        return result.content().trim();
    } catch (LLMException e) { log.warn(...); }
    return fallbackSummary(messages); // 回退：首条用户消息前 80 字符
}
```

降级链：LLM 摘要 → 首条用户消息截断 → "(empty session)"。LLM 挂了不影响功能。

#### CLI 交互面

```
/session save [name]     保存当前会话
/session load <id>       加载历史会话
/session list            列出所有会话
/session search <query>  BM25 关键词搜索
/session stats           年龄分桶统计
/session prune <days>    删除 N 天前的会话
/session delete <id>     删除指定会话
/session new             清空当前会话（触发 auto-save）
```

#### 关键设计决策

**为什么文件系统而不是 SQLite？**
- V1 阶段会话量 < 100，JSON 文件完全够用
- "磁盘即真相"哲学：单文件可读、可手动编辑、可备份
- 零运维依赖，`java.nio.file` 即可

**为什么 session_context 不走 ToolRegistry？**
- 语义不同：它是"元工具"，操作的是引擎内部状态而非外部文件系统
- 需要特殊权限：所有 PermissionMode 下都可用（只读、无副作用）
- 和 task/memory_save/remember 放在同一处理层，概念统一

**索引文件损坏怎么办？**
- catch 后 warn 日志 + 返回空列表，下次 save 会重建
- 会话文件本身不受影响（每个 `<id>.json` 独立存储）
- 遵循"部分可用优于完全不可用"原则

---

## 8. 工程韧性总览

### 8.1 错误码体系

| 范围 | 含义 |
|------|------|
| T-001~T-005 | 工具系统错误 |
| E-001~E-002 | Edit 工具错误 |
| A-001~A-003 | Agent 引擎错误（硬上限 / LLM 异常 / SubAgent 异常） |
| S-001~S-003 | Skills 错误 |
| C-001~C-003 | 配置错误 |
| X-001~X-002 | 通用错误 |

### 8.2 级联降级路径

| 组件 | 失败降级 |
|------|---------|
| LadderedCompactor L3 | LLM 摘要失败 → fallback 到 Pressure 压缩结果 |
| SessionService.save() | LLM 摘要失败 → firstUserMessage 截断 → "(empty session)" |
| L4 记忆提取（L3 extraction） | 任何异常 → log.warn + 静默跳过，主循环不受影响 |
| 会话自动保存 | 失败 → log.warn，不阻断 clearSession |
| SessionService | index.json 损坏 → 返回空列表，下次 save 重建 |
| LLMProvider | 熔断器 OPEN → 快速失败，30s 后 HALF_OPEN 探测 |

### 8.3 模块依赖图

```
miniclaw-cli ──→ miniclaw-engine ──→ miniclaw-provider
                     │                      │
                     ├──→ miniclaw-context ─┤
                     │                      │
                     ├──→ miniclaw-memory ──┘
                     │
                     └──→ miniclaw-tools
```

约束：`tools` 是唯一基础层（零内部依赖），`engine` 是组装点，`cli` 不直接依赖 `tools`/`provider`。禁止循环依赖。

---

## 面试常见追问速查

| 问题 | 指向 | 关键话术 |
|------|------|---------|
| "为什么不用 LangChain？" | 1.2 | 200 行 while 循环完全可控，不需要框架级抽象 |
| "Agent 循环怎么防止死循环？" | 1.4 | 四层递进防御：死循环检测 + 进度提醒 + 硬上限 + 子目标暂停 |
| "上下文太长怎么办？" | 3.2-3.4 | MessageMasker（角色+时效）→ LadderedCompactor（Always-On + Pressure + L3 摘要） |
| "怎么保证工具执行安全？" | 2.3 | 拦截链在 ToolRegistry 入口对所有模式生效 |
| "SubAgent 怎么防止无限递归？" | 1.6 | enableSubAgents=false + 子引擎 task 不在工具列表 |
| "多通道（CLI+飞书）并发怎么处理？" | 6.2 | AtomicBoolean 共享锁 + CopyOnWriteArrayList 多路监听 |
| "记忆系统怎么做到不丢信息？" | 4.1-4.5 | 五层递进：L1 会话上下文 → L2 工作内存 → L3 状态外部化 → L4 磁盘记忆 → L5 会话检索 |
| "L4 自动提取会不会影响主流程性能？" | 4.4 | 条件触发（冷却5轮+10条新消息+token>60%），独立 LLM 调用，失败静默跳过 |
| "BM25 为什么不用嵌入？" | 4.5 | 100 条会话规模下 2-gram BM25 精度足够，零外部依赖，500+ 条时再加 embedding |
| "为什么不直接用 Spring Boot？" | CLAUDE.md | CLI 工具不需要 DI 容器，JVM 冷启动 1-2s 可接受 |
| "Result 为什么用 sealed？" | 2.2 | 编译器保证 switch 穷尽性，强制处理错误分支 |
| "磁盘记忆和 sessionHistory 的区别？" | 4.4 | 一个是跨会话持久化（system prompt 注入），一个是当前会话 ephemeral |
| "熔断器设计细节？" | 5.2 | CLOSED→OPEN→HALF_OPEN 状态机，连续 5 次失败触发，30s 冷却 |
| "Two-Stage 思考为什么只做首轮？" | 1.3 | 后续轮次已有工具结果反馈，不需要重新规划；也节省 Token |
| "MessageMasker 为什么在 Compactor 之前？" | 3.4 | mask 按角色差异化处理（TOOL 掩码、ASSISTANT 截断），compact 再做通用压缩——顺序错了会浪费 token |
| "状态外部化的自举检测是什么？" | 3.7 | LLM 调用 read(todo.md) → 引擎拦截直接注入内容，省一次 API 调用 |
| "虚拟线程相比线程池的优势？" | 9.1 | 创建成本 ~1KB 栈，不需池化管理，用完即弃；在 IO 密集场景下 JVM 自动调度 |
| "熔断器为什么用 long 而不是 enum+状态字段？" | 5.2 | 零对象分配，一个字段编码三个状态（0=CLOSED, >now=OPEN, <now=HALF_OPEN），synchronized 保证原子性 |
| "SSE 解析 tool_call 为什么用 Map<Integer,Accum>？" | 5.3 | tool_call 跨多个 chunk 发送，按 index 分组累积，最后排序组装 |

---

## 9. 并发与线程安全

### 9.1 虚拟线程策略

miniclaw 的并发模型基于 Java 21 虚拟线程：

| 场景 | 线程模型 | 同步机制 |
|------|---------|---------|
| `generateStream()` 流式输出 | 调用者线程（CLI/飞书的虚拟线程） | 无 |
| `executeParallel()` 读工具并发 | `Thread.ofVirtual().start()` × N | CountDownLatch |
| `executeSubAgentsParallel()` | `Thread.ofVirtual().start()` × N | CountDownLatch + 预分配数组 |
| 飞书 HTTP handler | `HttpServer` 默认虚拟线程 | AtomicBoolean busy |

**为什么不用线程池？** 虚拟线程由 JVM 在少数 OS 线程上调度，创建成本 ~1KB 栈内存。不需要池化、不需要队列、不需要 max/min 配置。用完即弃，GC 回收。每个虚拟线程的执行路径是同步的（`registry.execute()` 阻塞等待），但在 IO 密集场景下 JVM 会自动在 OS 线程间切换。

**CountDownLatch 而非 CompletableFuture**：`executeParallel()` 的并行度是 LLM 决定的（同一轮返回 3 个只读工具调用 = 3 个虚拟线程）。CountDownLatch 的语义最直接——等待 N 个任务全部完成，不关心返回值（结果写入预分配数组）。CompletableFuture 的链式组合在这里是过度设计。

### 9.2 CopyOnWriteArrayList 监听器模式

所有事件回调列表都是 `CopyOnWriteArrayList`：

```java
private final List<Consumer<String>> onTokenListeners = new CopyOnWriteArrayList<>();
private final List<Consumer<SubAgentSpawnEvent>> onSubAgentSpawnListeners = new CopyOnWriteArrayList<>();
private final List<Consumer<AgentStateEvent>> onStateChangeListeners = new CopyOnWriteArrayList<>();
// ...
```

**为什么 CopyOnWriteArrayList？**
- 遍历远多于修改：注册/注销只在 run() 开始/结束时发生，但每收到一个 token 就遍历
- 零锁遍历：`for (var listener : list)` 的迭代器是 snapshot，不阻塞写操作
- 避免 ConcurrentModificationException：当飞书 token 回调触发 UI 更新时，CLI 可能正在注销 listener

**Lambda 引用相等性陷阱**：`batcher::onToken` 每次求值创建一个新 lambda 对象。`CopyOnWriteArrayList.remove()` 用 `equals()` 查找，lambda 的 `equals()` 继承自 Object（引用相等），永远不匹配。必须将 lambda 存为局部变量，add 和 remove 使用同一个引用。

### 9.3 AtomicBoolean 共享锁

CLI 和飞书共享一个 `AgentEngine` 实例，用 `AtomicBoolean busy` 保证互斥：

```java
boolean tryAcquire() {
    return busy.compareAndSet(false, true);
}

void release() {
    busy.set(false);
}
```

`compareAndSet` 保证只有一个通道能拿到锁。飞书请求失败时直接回复"正在处理中，请稍后"，不排队。这是刻意设计——排队会导致多个请求堆积，用户以为消息丢失。

---

## 10. 面试深度追问集

以下问题超出标准面试范围，用于展示对系统设计的深层理解。

### Q1: 为什么不把 MessageMasker 和 LadderedCompactor 合并成一个组件？

**答**：因为它们的操作维度不同。Masker 是**角色+时效**双维处理——它需要知道每条消息是 USER 还是 TOOL，距离当前轮次多远，然后做差异化决策（TOOL 掩码、ASSISTANT 截断、USER 全保留）。Compactor 是**内容**维度的通用压缩——去空行、删低信号行、LLM 摘要。分开的好处：
1. 各自可独立测试（Masker test 只关心"第 5 轮 TOOL >500B 是否被 mask"，不关心 token 数）
2. Masker 的触发阈值（12 轮）和 Compactor 的触发阈值（60% token）独立调节
3. 顺序保证：Masker 先减少低频信号（大 TOOL 输出），Compactor 再做通用压缩，管道效应最大化

### Q2: 为什么 L3 自动提取选在 mask/compact 之前而不是之后？

**答**：提取需要完整信息。如果先 mask，600 行的编译输出变成 `[tool output — 5000 bytes]`，提取器看不到里面的错误信息。如果先 compact，LLM 摘要可能已经丢失了关键细节。在 mask/compact 之前执行，提取器看到的是"完整但已裁剪"的对话（TOOL 截断到 200 字符），信息保真度最高。

代价是多一次 LLM 调用（~2-3K tokens），但条件触发（>60% token + 冷却 5 轮 + 新增 10 条消息）保证频率可控。

### Q3: EditTool L4 滑动窗口为什么不用 diff 算法（Myers/LCS）？

**答**：diff 算法用于计算两个文本之间的差异，但 EditTool 的输入是 `old_text` + `new_text`（而不是旧文件 + 新文件）。问题不是"计算差异"，而是"在文件中找到 old_text 的位置"。Myers diff 的时间复杂度 O(N*M) 对于大文件太昂贵，而滑动窗口逐行匹配是 O(N) 的，且匹配的是"去缩进后的语义行"而非字符序列。

### Q4: KeywordScorer 为什么用 2-gram 而非分词库？

**答**：四个原因：
1. **零依赖**：jieba/HanLP 需要 10-50MB 词典 + native 扩展，对于 ~100 条会话的搜索是杀鸡用牛刀
2. **容错性**：2-gram 天然容忍分词错误——"数据库迁移"的 2-gram ["数据","据库","库迁","迁移"] 能匹配 "MySQL 数据迁移" 中的 "数据" + "迁移"
3. **BM25 本身就是近似**：BM25 不依赖精确分词，term frequency 在 2-gram 下仍然有效
4. **可演进**：500+ 条会话时直接上 embedding，不纠结分词优化

### Q5: 为什么 session_context 不直接让 LLM 调 FileSessionStore？而要走引擎拦截？

**答**：FileSessionStore 是 I/O 层，不暴露为 Tool。如果做成普通 Tool：
- 需要 ToolRegistry 注册，每次 `buildSystemPrompt()` 都出现在可用工具列表中（即使不需要搜索历史）
- 权限模型不一致——session_context 在 PLAN 模式也应该可用（只读），但 PLAN 模式禁用了所有写工具

引擎级工具的关键设计原则：操作引擎内部状态（会话、记忆、子代理）的工具走引擎拦截；操作外部文件系统的工具走 ToolRegistry。这条边界让两个系统的权限和生命周期管理解耦。

### Q6: 如果飞书用户同时发两条消息会怎样？

**答**：`AtomicBoolean.tryAcquire()` 保证只有第一个拿到锁。第二个请求直接返回"正在处理中，请稍后"，不排队。这是刻意设计：
1. 排队意味着用户看到"已读"但无回复，体验比"正在处理中"更差
2. Agent 执行的副作用（文件修改）不可回滚，排队可能导致两个请求的修改互相覆盖
3. 用户通常在看到回复后才发下一条，并发冲突在实践中极少发生

### Q7: 如果 LLM 在 L3 提取时返回的不是 JSON 怎么办？

**答**：`parseExtractionResult()` 做三层容错：
1. Markdown 代码块剥离：检测到 ```` ``` ` 时提取中间内容
2. `ObjectMapper.readTree()` 尝试 JSON 解析，`catch` 后返回空列表
3. 字段级容错：`type` 字段无效时 fallback 到 `REFERENCE`，`name` 或 `content` 为空时跳过该条

整个提取流程包在 try-catch 里，任何异常 → log.warn + 不影响主循环。L3 是"best-effort"增强功能，不是关键路径。

---

*本文档基于 miniclaw V3.8d，测试 229/229 通过。各模块细节见 CLAUDE.md 和源码注释。*
