# P1-A 前三项需求分析与技术调研

> 日期：2026-07-19
> 范围：PA-1 失败分类与恢复策略、PA-2 工具结果智能截断、PA-3 任务感知 compact
> 阶段：需求分析与技术调研，不包含实现
> 当前实施状态：[P1-A 实施状态与阶段冻结说明](p1-a-implementation-status.md)

> 后续定版：具体接口、实施拆分及反方评审后的范围修订以
> [P1-A 前三项技术方案与反方评审](p1-a-design.md) 为准。

## 1. 结论摘要

P1-A 的前三项不应作为三个互不相关的局部优化实施。它们共同解决一条完成率链路：

```text
工具或 Provider 失败
  -> 结构化判断是否可恢复
  -> 重试或重新采证
  -> 从大输出中保留高信号证据
  -> 长任务 compact 后继续保留诊断状态和证据引用
  -> 完成任务或明确停止
```

建议按以下边界实施：

1. **PA-1 只让“相同输入再次执行确实有意义且安全”的失败进入自动重试。**
   `INVALID_ARGUMENTS` 需要修改参数，不得原样重放；Provider 已有自己的重试循环，Engine 不得再叠加一层 Provider 重试。
2. **PA-2 把“有界采集”和“语义保留”分开。**
   `BoundedOutputCollector` 继续负责 OOM 防护、head/tail、hash 和脱敏；工具类型感知的 reducer 负责保留 grep 上下文、日志严重级别和 PostgreSQL 阻塞链。
3. **PA-3 不依赖 LLM 从自由文本中重新猜测任务状态。**
   OPS workflow 应提供结构化的未解决假设、证据、反证和引用；compact 对这些 anchor 做确定性保留与事后校验。
4. **PA-2 和 PA-3 通过 evidence reference 连接。**
   工具层输出高信号片段与 `evidenceRefs`，compact 保留诊断状态及引用。完整大输出不默认进入或持久化在模型上下文中。
5. **现有 Benchmark 只能证明“run 结束”，不能证明关键信息没有丢失。**
   三项都需要新增内容不变量 scorer 和对抗 case，不能只看 `RunStatus`、轮次或工具失败数。

## 2. 当前代码事实

| 项目 | 已有能力 | 关键缺口 |
| --- | --- | --- |
| PA-1 | `FailureClass` 17 类、固定 `EffectCertainty`、`RecoveryDirective`、`FailureDecisionTable`、P1-G Side Effect Gate | Engine 没有消费 `RecoveryDirective` 的主动恢复循环；Provider 仍使用独立 `ProviderError`；同输入重试条件不够精确；重试观测恒为 0 |
| PA-2 | `OutputEnvelope`、`BoundedOutputCollector`、Bash stdout/stderr head/tail/error/hash/脱敏 | grep/read/web_fetch 等仍通过旧字符串接口；统计与信封可能不一致；无 grep 上下文；无日志/关系型结果 reducer；观测事件没有截断原因和保留策略 |
| PA-3 | `CompactionRequest/Result`、预算阈值、MessageMasker、L1-L3 compact、文件路径/错误码/TODO 约束校验 | 请求无 task hint；MessageMasker 会在 compact 判断前按轮次掩码；约束提取晚于掩码；无证据/反证/假设类型；消息无可靠 topic/时间元数据；结果无结构化丢弃摘要 |

### 2.1 PA-1 现状细节

- [`FailureClass`](../clawkit-tools/src/main/java/com/clawkit/tools/action/FailureClass.java) 已表达副作用确定性，P1-G 的安全基础可复用。
- [`FailureDecisionTable`](../clawkit-reliability/src/main/java/com/clawkit/reliability/FailureDecisionTable.java) 已给出恢复指令，但当前 Engine 只对 `EFFECT_UNKNOWN` 和 `PARTIAL_EFFECT` 注入安全提示，没有执行 `RETRY_ALLOWED`。
- `INVALID_ARGUMENTS -> RETRY_ALLOWED` 不能直接解释为“相同参数自动重试”。参数错误通常是确定性错误，必须先修正输入。
- [`OpenAIProvider`](../clawkit-provider/src/main/java/com/clawkit/provider/impl/openai/OpenAIProvider.java) 已对 429、部分 5xx 和 I/O 异常重试；当前是固定指数序列，没有 jitter。
- [`ObservingProviderGateway`](../clawkit-engine/src/main/java/com/clawkit/engine/impl/ObservingProviderGateway.java) 的 `retryCount` 当前固定记录为 0，无法验证 Provider 实际重试次数。
- Provider 的 [`ProviderError`](../clawkit-provider/src/main/java/com/clawkit/provider/ProviderError.java) 尚未与 `FailureClass/RecoveryDirective` 形成同一份端到端恢复事实。因此“Provider/工具/Engine 共用同一套契约”目前只完成了一部分。

### 2.2 PA-2 现状细节

- [`BoundedOutputCollector`](../clawkit-tools/src/main/java/com/clawkit/tools/impl/BoundedOutputCollector.java) 已解决流式大输出的内存上界、UTF-8 边界、head/tail、错误片段、hash 和脱敏。
- [`BashTool`](../clawkit-tools/src/main/java/com/clawkit/tools/impl/BashTool.java) 已附加 `OutputEnvelope`，但 `ToolOutputStats.returnedBytes` 仍按总字节数填写；`withOutputEnvelope()` 也不会自动校准平铺统计字段。
- [`GrepTool`](../clawkit-tools/src/main/java/com/clawkit/tools/impl/GrepTool.java) 只保留前 50 个匹配，未提供 before/after context；第二次字节截断仍可能切掉完整匹配组。
- [`Tool.execute(ToolExecutionRequest)`](../clawkit-tools/src/main/java/com/clawkit/tools/Tool.java) 对旧 `Result<String>` 的默认适配无法知道工具内部已截断，因此 read、grep、web fetch 等可能把已截断输出记录为未截断。
- `ToolCompletedPayload` 只有 `outputBytes/truncated`，无法回答“为什么截断、原始多少行、保留了什么策略、扫描是否完整”。
- 当前没有日志读取或 PostgreSQL 活动专用工具。PA-2 必须区分核心框架能力与 OPS-0A/0B 接入工作，不能为了满足文案在 Bash 输出上做脆弱的命令字符串猜测。

### 2.3 PA-3 现状细节

- [`CompactionRequest`](../clawkit-context/src/main/java/com/clawkit/context/CompactionRequest.java) 只有消息、工具定义 token 和 turn 数。
- [`ConstraintExtractor`](../clawkit-context/src/main/java/com/clawkit/context/ConstraintExtractor.java) 只识别文件路径、错误码和 Markdown 未完成 TODO。
- [`MessageMasker`](../clawkit-context/src/main/java/com/clawkit/context/impl/MessageMasker.java) 从第一个 turn 后就启用，按距离掩码或驱逐旧 TOOL/ASSISTANT 消息，而不是仅在 token 压力下运行。
- [`DefaultContextPipeline`](../clawkit-context/src/main/java/com/clawkit/context/impl/DefaultContextPipeline.java) 先 mask，再做预算判断；`LadderedCompactor` 的约束提取发生在 mask 之后。因此证据可能在进入约束提取和 L3 摘要前已被移除。
- [`CompactionResult`](../clawkit-context/src/main/java/com/clawkit/context/CompactionResult.java) 没有 dropped topic、时间范围、原因和 required anchor 丢失状态。
- [`CompactCompletedPayload`](../clawkit-observability/src/main/java/com/clawkit/observability/CompactCompletedPayload.java) 虽有前后 token 和规则，但 Engine 当前把 `evictedGroups`、`durationMs` 固定记录为 0，也不记录 profile、保留/丢失 anchor。

## 3. 统一需求原则

### 3.1 安全和完成率的优先关系

完成率提升不得绕过 P1-G：

- 结果未知、部分执行、取消、预算耗尽不得通过“多试一次”伪装成恢复。
- 自动重试必须同时满足：
  1. 错误是瞬态或本地可恢复错误；
  2. 相同输入重试有意义；
  3. 副作用确定性允许；
  4. deadline、取消、次数和 token/tool-call 预算允许。
- 自动恢复失败后必须留下最终分类、尝试次数和停止原因。

### 3.2 原始事实、模型上下文和观测数据分层

```text
原始工具流
  -> 有界采集与完整性摘要（bytes/hash/redaction）
  -> 高信号片段与 evidenceRefs
  -> 模型上下文中的诊断状态
  -> compact 后的状态摘要与引用
```

- 原始大输出不是模型上下文。
- 模型摘要不是原始事实。
- 观测事件不保存敏感完整输出，但必须保存选择策略、计数、hash/ref 和恢复决策。
- compact 丢弃自由文本后，原始证据仍可通过引用重新采集或读取。

### 3.3 模块边界

- `clawkit-tools`：输出采集、reducer 契约、统计、工具级 retryability 事实。
- `clawkit-reliability`：确定性恢复决策、重试次数/退避策略；不执行 Engine loop。
- `clawkit-context`：通用 compact profile、anchor、保留校验和丢弃摘要；不依赖 Incident/Diagnosis 业务类型。
- `clawkit-engine`：消费恢复决策、执行重试、传递 task hint、发观测事件。
- OPS workflow：把 Incident/Diagnosis 转成通用 anchor；日志和 PostgreSQL 工具提供领域结构，不把领域规则写进 Engine。
- `clawkit-observability/evaluation`：记录并机械验证恢复、截断和 compact 不变量。

## 4. PA-1：失败分类与恢复策略

### 4.1 目标

- 对安全且有意义的瞬态失败自动恢复，减少模型额外一轮“猜测式重试”。
- 对永久错误、需改参错误、需用户输入和结果未知保持确定性停止或重新采证。
- 统一记录 Provider 内部重试和工具恢复，不出现乘法重试。

### 4.2 非目标

- 不实现 Provider fallback。
- 不对结果未知的非幂等写操作自动重放。
- 不让 LLM 修改 `FailureClass -> EffectCertainty`。
- 不把所有 `ToolError` 或非零退出码都标记为可重试。
- 不在 Engine 外再包一层 Provider HTTP 重试。

### 4.3 恢复决策必须区分的动作

现有 `RecoveryDirective` 可保留，但执行层至少要区分：

| 动作 | 含义 | 示例 |
| --- | --- | --- |
| `RETRY_SAME_INPUT` | 相同调用可在退避后重试 | 只读远程查询遇到 503；临时文件锁冲突 |
| `REPAIR_INPUT` | 交给模型或确定性修复器修改参数，不能原样重放 | JSON 参数缺字段、正则语法错误 |
| `RECOLLECT` | 只读查询状态或重新采证，不重复原动作 | timeout outcome unknown |
| `VERIFY` | 独立验证可能已发生的效果 | accepted without final result |
| `USER_INPUT` | 请求用户决定或补充信息 | 审批拒绝、权限不足 |
| `ABORT` | 停止 | 取消、deadline、预算耗尽 |

因此，`INVALID_ARGUMENTS` 应从“自动重试候选”中排除。它可以保留 `RETRY_ALLOWED` 兼容映射，但执行策略必须返回 `REPAIR_INPUT`，或后续把决策枚举拆得更精确。

### 4.4 推荐接口

在 `clawkit-reliability` 增加纯决策组件，输入只依赖 tools 契约：

```java
record RecoveryDecision(
    RecoveryAction action,
    String reasonCode,
    int maxAttempts,
    Duration nextDelay
) {}

interface RecoveryPolicy {
    RecoveryDecision decide(
        ToolExecutionResult result,
        int completedAttempts,
        ExecutionControl control
    );
}
```

约束：

- 建议使用 `maxAttempts=3`（包含首次执行），避免“3 retries”究竟是 3 次还是 4 次总请求的歧义。
- 退避使用 capped exponential backoff + jitter；随机源和 sleeper 必须可注入以便确定性测试。
- 每次尝试和 sleep 前调用 `ExecutionControl.checkpoint()`。
- PA-1 第一阶段只对只读工具和 `NO_EFFECT_CONFIRMED/NOT_DISPATCHED` 的明确瞬态失败启用自动相同输入重试。
- 副作用工具即使声明幂等，也继续经过 `SideEffectGate/ActionAttemptCoordinator`，不能在 `ToolCallExecutor` 外旁路。
- `ToolError.retryable` 应成为决策输入；仅靠 `FailureClass` 无法区分永久执行错误和瞬态执行错误。

### 4.5 Provider 重试边界

- Provider HTTP/transport 重试继续由 Provider 层拥有。
- Engine 只消费最终 `ProviderError`，不重新调用同一个 Provider 请求。
- Provider 应把实际 `retryCount` 放入公开响应/异常元数据，Gateway 如实写入事件。
- 流式响应开始后不自动重放；半截流属于不可透明重试场景。
- 429 的 `Retry-After` 优先于本地退避；没有时才使用 jitter backoff。

### 4.6 观测要求

工具完成事件或新增 retry attempt 事件至少包含：

- `failureClass`
- `recoveryAction`
- `attemptNumber/maxAttempts`
- `delayMs`
- `retryReason`
- `finalStopReason`
- `effectCertainty`

Provider 完成事件必须记录真实 `retryCount` 和最终 `ProviderError` 分类。

### 4.7 验收

机械不变量：

1. 瞬态只读失败两次后成功：总 attempts 为 3，最终只回注一个 tool result。
2. 超过 `maxAttempts`：停止，最终失败包含最后 cause 和累计尝试数。
3. `INVALID_ARGUMENTS`：相同参数自动执行次数为 0。
4. `ABORT/USER_INPUT/RECOLLECT/VERIFY`：相同动作自动重试次数为 0。
5. `EFFECT_UNKNOWN/PARTIAL_EFFECT` 的非幂等动作：自动重复副作用次数为 0。
6. 取消、deadline 或预算在退避期触发：不再开始下一次 attempt。
7. Provider 已内部重试 N 次时，Engine 的 Provider 重放次数为 0，事件记录 N。
8. 重试不改变 tool call 顺序，模型协议仍只收到每个原始 tool call 的一个最终 result。

## 5. PA-2：工具结果智能截断

### 5.1 目标

- 在固定输出预算内最大化诊断信号，而不是简单保留最前 N 行。
- 所有截断结果都可解释、可计数、可追踪。
- 大输出始终有内存上界，语义增强不得先读取完整输出再裁剪。

### 5.2 非目标

- 不在通用 Bash 层通过命令字符串猜测“这是 journalctl/psql”。
- 不保证截断后上下文包含全部原始输出。
- 不默认持久化完整 stdout/stderr。
- 不在 PA-2 提前实现 OPS-0A/0B 的完整日志和 PostgreSQL 工具。

### 5.3 两阶段输出管线

```text
Stage A: Bounded capture
  bytes/hash/redaction/head/tail
            |
Stage B: Semantic reduction
  generic / grep / log / relation
            |
OutputEnvelope + ToolOutputStats + evidenceRefs
```

Stage A 必须一直有界。Stage B 优先消费流式行、结构化 match 或结构化 row，不允许为了智能截断恢复无界字符串。

### 5.4 统一统计契约

建议扩展 `ToolOutputStats`：

```java
record ToolOutputStats(
    long totalBytes,
    long returnedBytes,
    long totalLines,
    long returnedLines,
    boolean truncated,
    String truncationReason,
    String retentionPolicy,
    boolean inputComplete
) {}
```

语义：

- `inputComplete=false` 表示工具为控制成本提前停止扫描，`totalLines/totalBytes` 只是已观察值，不能伪装为全量总数。
- `truncationReason` 使用稳定代码，例如 `MAX_OUTPUT_BYTES`、`MAX_MATCH_GROUPS`、`TIME_WINDOW`。
- `retentionPolicy` 使用稳定代码，例如 `GENERIC_HEAD_TAIL_V1`、`GREP_CONTEXT_V1`、`LOG_SEVERITY_V1`、`PG_BLOCKING_CLOSURE_V1`。
- `OutputEnvelope` 是 byte/hash 的唯一事实来源；从 envelope 构造或附加 envelope 时必须同步平铺 `outputBytes/truncated/outputStats`，避免当前双份事实漂移。
- 为旧构造器保留兼容重载，避免一次性破坏所有工具。

### 5.5 通用 Bash 输出

默认策略保持通用，不识别命令业务：

1. head
2. tail
3. FATAL/ERROR/EXCEPTION 等高置信错误片段
4. WARN 片段（新增）
5. 省略字节数、hash、截断原因

同一行不得因 head/tail/error 多次返回。片段应记录原始行号或流偏移；无法获得时明确为 unknown。

### 5.6 grep 输出

输入新增兼容字段：

- `beforeContext`
- `afterContext`
- 或 `context`（同时设置前后）

输出以 match group 为单位：

- 匹配行永远优先于普通上下文。
- 相邻或重叠的上下文区间合并，同一行只输出一次。
- 组间保留明确分隔符。
- 每行包含路径、行号、match/context 标记。
- 达到预算时不把一个 match group 从中间切断；宁可少保留一组。
- 选择顺序至少避免单个大文件吞掉全部预算。建议先保留每个文件的首个 group，再按稳定顺序补充。

第一阶段可以继续限制扫描量，但必须返回 `inputComplete=false`；如果继续扫描仅计数而不保存，则可以给出准确 total matches。

### 5.7 日志输出

日志 reducer 只在调用方显式声明 `LOG` 类型，或由未来专用 LogReadTool 使用：

保留优先级：

1. incident 时间窗内的 FATAL/ERROR
2. 与上述行相邻的 stack trace / cause / request id 上下文
3. WARN
4. 时间窗 head/tail
5. 低级别普通行

要求：

- 优先使用结构化 severity/timestamp 字段；自由文本正则只是降级路径。
- 输出保留首尾时间戳和实际覆盖时间范围。
- 同一异常的重复行可折叠，但必须给出重复次数。
- 无法解析时间戳时不得伪造时间范围。

### 5.8 PostgreSQL 阻塞链

PostgreSQL 结果应在结构化 row 阶段选择，不应格式化成文本后做前 N 行截断：

1. 找出 waiting sessions。
2. 使用 `pg_blocking_pids(pid)` 获取 blocker。
3. 递归保留 blocker 的闭包，直到根 blocker 或深度/循环保护。
4. 优先保留链上行，再使用剩余预算保留其他 activity。
5. 稳定排序：root blocker -> downstream waiter；同层按 query/xact start。

链上至少保留：

- `pid`
- `leader_pid`
- `state`
- `wait_event_type/wait_event`
- `xact_start/query_start/state_change`
- `application_name/client_addr`
- 截断后的 query 与 query hash/ref
- blocking pid 列表

这部分应随 OPS-0B 的 PostgreSQL 只读工具接入。PA-2 核心阶段只提供通用 relation reducer 契约和测试 fixture。

### 5.9 evidence reference

智能截断保留的关键片段应可生成：

```text
evidence://<runId>/<toolCallId>/<sliceId>
```

引用至少关联：

- tool call
- 原始输出 hash
- slice 类型和行/row 范围
- observedAt
- redaction 状态

PA-3 只需保留引用与短摘要；是否允许读取对应 artifact 由安全和持久化策略决定。

### 5.10 验收

1. Bash 中段 ERROR/WARN 在超长输出下仍保留，head/tail 仍存在。
2. grep 的匹配行及配置的上下文行成组保留，重叠区间不重复。
3. 字节预算不会切断 UTF-8 字符或 match group。
4. 日志 fixture 中关键 ERROR、关联 stack、时间窗首尾均保留。
5. PostgreSQL fixture 中所有 blocker/waiter 关系行保留；无关 activity 可先丢弃。
6. `returnedBytes + omittedBytes == totalBytes`。
7. `ToolOutputStats` 与 `OutputEnvelope` 的 truncated、returned、reason 一致。
8. ToolCompleted 事件可查询 `truncationReason/retentionPolicy/inputComplete`。
9. 旧 read/grep/web_fetch 的已截断输出不再记录为 `truncated=false`。
10. 固定大输出下内存占用与输入大小无关，只与 policy cap 有关。

## 6. PA-3：任务感知 compact

### 6.1 目标

- `GENERAL` 保持现有通用行为。
- `OPS_DIAGNOSIS` 在多轮、长输出和多次 compact 后仍保留：
  - incident 身份和时间窗
  - 已确认事实
  - 关键证据与反证摘要/引用
  - 未解决假设及状态
  - 待采证项和下一步
  - 权限与审批边界
- compact 结果明确说明丢弃了什么、为什么丢弃、哪些 required anchor 被保留或丢失。

### 6.2 非目标

- 不把完整日志和数据库快照永久塞进 context。
- 不让 `clawkit-context` 依赖 OPS Incident/Diagnosis 领域模型。
- 不只靠 prompt 要求 LLM“记得所有重要内容”。
- 不用 embedding 相似度替代 required anchor 的确定性保留。
- 不在本项实现自适应分层 compact 或 Prompt caching。

### 6.3 CompactionHint 不能只有 enum

`GENERAL/OPS_DIAGNOSIS` 只说明策略，不能说明“本次哪些信息绝不能丢”。建议：

```java
record CompactionHint(
    CompactionProfile profile,
    List<CompactionAnchor> anchors
) {}

record CompactionAnchor(
    String id,
    AnchorKind kind,
    String summary,
    String sourceRef,
    Instant observedAt,
    boolean required
) {}
```

`AnchorKind` 至少包含：

- `INCIDENT`
- `CONSTRAINT`
- `EVIDENCE`
- `COUNTER_EVIDENCE`
- `OPEN_HYPOTHESIS`
- `DECISION`
- `PENDING_ACTION`
- `APPROVAL_BOUNDARY`

OPS workflow 负责从领域对象生成这些通用 anchor；context 只认识通用类型和 opaque ref。

### 6.4 结构化诊断状态优先于自由文本回忆

推荐每轮构建一个非持久化、不可 compact 的小型状态快照：

```text
[Diagnosis State]
incident: INC-...
time-window: ...
confirmed:
  - E-12 ...
counter-evidence:
  - E-19 ...
open-hypotheses:
  - H-3 ...
pending:
  - ...
```

原始证据通过 `evidenceRefs` 按需重新读取。这样 compact 的目标是保持“工作状态”，不是把全部历史对话总结成一段无法审计的散文。

### 6.5 Pipeline 顺序调整

现有顺序会在识别 required anchor 前 mask。推荐顺序：

```text
collect messages + hint
  -> identify/protect anchored turn groups
  -> always-on safe normalization
  -> hint-aware masking
  -> budget analysis
  -> pressure compact / summary
  -> deterministic anchor verification
  -> reinsert missing required anchors
  -> re-budget
  -> success or structured COMPACT_FAILED
```

要求：

- `MessageMasker.mask()` 必须接收 hint/protected group 信息。
- required anchor 所在 turn 不得进入 T3 无摘要驱逐。
- 未达到 compact 阈值时，也不能静默丢弃 required anchor。
- L3 summarizer prompt 可因 profile 改变，但 summarizer 输出不能推翻 anchor verifier。
- required anchor 补回后仍超 hard limit 时，必须结构化失败，不继续 Provider 调用。

### 6.6 丢弃摘要

建议在 `CompactionResult` 增加：

```java
record DiscardSummary(
    List<String> topics,
    Instant from,
    Instant to,
    String reason,
    int messageCount,
    List<String> retainedRefs
) {}
```

以及：

- `profile`
- `retainedAnchorIds`
- `lostAnchorIds`
- `evictedGroups`
- `durationMs`
- `failureReason`

当前 `Message` 没有可靠 timestamp/topic 元数据。第一阶段只可从 anchor、evidence 和显式 metadata 生成时间范围；不能从自由文本猜测后宣称准确。若需要覆盖普通消息，需引入 context item sidecar metadata，而不是污染 Provider 的 `Message` schema。

### 6.7 手动 compact 与 session

- `/compact` 默认使用当前 session 最近一次 profile；没有 profile 时使用 `GENERAL`。
- 不持久化完整 ephemeral diagnosis snapshot，但 session metadata 可持久化 profile 和必要的 anchor refs。
- compact 清空并重写 session 时需要原子或版本化保存，失败不得留下半份 session。
- compact 可能调用 Provider；其失败应保留原 session，不得先删后写。

### 6.8 观测要求

`CompactCompletedPayload` 增加或关联：

- `profile`
- `hintAnchorCount`
- `retainedAnchorCount`
- `lostRequiredAnchorCount`
- `discardedTopics`
- `discardedTimeRange`
- 真实 `evictedGroups`
- 真实 `durationMs`
- summary Provider 的调用和错误

敏感 anchor 只记录 ID、类型和 hash/ref，不记录原始内容。

### 6.9 验收

1. `GENERAL` 现有 context 测试行为不回归。
2. 超过 20 轮的 OPS fixture 中，旧的未解决假设仍在 compact 后状态快照中。
3. 关键证据和反证都保留摘要与 ref，不能只保留支持当前结论的证据。
4. 已解决假设可降级为 decision summary；未解决假设不得被普通 recency 规则驱逐。
5. summarizer 故意遗漏 required anchor 时，verifier 能补回并报告。
6. required anchor 补回导致 hard limit 时返回 `COMPACT_FAILED`，不调用主任务 Provider。
7. 丢弃摘要包含可验证的 topic、时间范围和 reason；无元数据时明确 unknown。
8. compact 事件中的 profile、anchor 数、evicted groups 和 duration 不是固定占位值。
9. compact 前后任务最终诊断在确定性 fixture 上保持一致。
10. 对抗 case 中相同症状不同根因、旧证据、自恢复和 `INCONCLUSIVE` 状态不因 compact 被错误收敛。

## 7. Benchmark 与完成率验证

现有 `long-output-truncation` 和 `compact-trigger` case 只验证 run/事件/预算，ScriptedProvider 的最终文本也不证明模型实际看到了关键内容。

需要新增 scorer：

| Scorer | 机械断言 |
| --- | --- |
| `RecoveryPolicyScorer` | attempts、delay、final directive、禁止重试类 |
| `OutputRetentionScorer` | 必须出现的 match/error/blocking row；统计与 envelope 一致 |
| `CompactionAnchorScorer` | required anchor 全保留，反证不丢，lostRequired=0 |
| `DiagnosisOutcomeScorer` | fixture 的根因/INCONCLUSIVE 与 compact 前后一致 |

建议 case：

1. 只读瞬态失败两次后成功。
2. 参数错误不原样重试。
3. Provider 内部重试不被 Engine 放大。
4. ERROR 位于超长 Bash 输出中段。
5. grep 多文件、多组重叠上下文。
6. 日志中早期 root cause + 晚期重复噪声。
7. PostgreSQL 多级阻塞链 + 大量无关 activity。
8. 25+ turn OPS 诊断，旧假设未解决。
9. 关键反证与当前主假设冲突。
10. compact 后仍应输出 `INCONCLUSIVE`，不能为了完成率强行给结论。

完成门禁：

- 硬安全不变量保持 100%。
- 确定性内容保留 case 100% 通过。
- 固定 benchmark 的 task pass rate 不低于基线。
- Provider/tool 调用数不得因分层重试出现乘法增长。
- compact 后 token 低于 target；否则明确结构化失败。

## 8. 推荐实施顺序

### PR 1：PA-1 契约校准

- 明确 `RETRY_SAME_INPUT` 与 `REPAIR_INPUT`。
- 把 `ToolError.retryable` 纳入决策。
- 增加 RecoveryPolicy 纯逻辑和单元测试。
- 修复 Provider retryCount 观测。

### PR 2：PA-1 只读工具恢复执行

- `ToolCallExecutor` 内执行有界重试并只回注一个最终结果。
- 注入 sleeper/jitter source。
- 贯穿取消、deadline、预算。
- 增加 retry attempt 观测与 benchmark。

### PR 3：PA-2 统一输出事实

- `ToolOutputStats` 扩展与兼容构造器。
- `OutputEnvelope -> stats` 唯一派生。
- 迁移 read/grep/web_fetch 等旧字符串截断结果。
- ToolCompleted 记录 reason/policy/inputComplete。

### PR 4：PA-2 reducer

- 通用 Bash error/warn/head/tail 去重。
- grep match group + before/after context。
- 日志/relation reducer 契约和 fixture；实际 Log/PostgreSQL adapter 随 OPS-0A/0B 接入。

### PR 5：PA-3 compact contract

- `CompactionHint/Profile/Anchor/DiscardSummary`。
- `CompactionRequest/Result` 和事件 schema 兼容扩展。
- Engine 默认传 `GENERAL`。

### PR 6：PA-3 hint-aware pipeline

- anchor-aware MessageMasker。
- profile-aware summarizer prompt。
- deterministic verify/reinsert/re-budget。
- 修复真实 evicted groups/duration 观测。

### PR 7：OPS 诊断闭环 benchmark

- OPS workflow 生成 diagnosis anchors。
- PA-2 evidence refs 接入 PA-3。
- 10 个对抗 case 和新 scorer。
- 生成新基线并做逐 case 回归。

## 9. 风险与决策点

| 风险/决策 | 建议 |
| --- | --- |
| `maxRetries=3` 还是 `maxAttempts=3` | 采用 `maxAttempts=3`，契约更不易误解 |
| `INVALID_ARGUMENTS` 现映射为 `RETRY_ALLOWED` | 执行层禁止同输入重试；后续再做兼容性枚举调整 |
| Engine 与 Provider 双层重试 | Provider transport retry 单点拥有；Engine 不重放 Provider 请求 |
| 通用 Bash 猜测日志/SQL | 禁止；由显式 output kind 或专用工具选择 reducer |
| OPS 类型污染 context | OPS workflow 转为通用 anchor，context 不依赖领域类 |
| LLM summary 遗漏或偏向主假设 | required anchor 机械校验；反证独立类型 |
| 保留过多导致 hard limit | 保留短摘要+ref，不保留原始大输出；补回后重新预算 |
| record 字段扩展破坏兼容 | 保留旧构造器、JSON reader 容错、分 PR 迁移 |
| 统计“总行数”不真实 | 增加 `inputComplete`，提前停止时不宣称全量 |
| evidence artifact 含敏感数据 | 继续脱敏、最小持久化、ref 受 workspace/权限控制 |

## 10. 技术调研结论

### 10.1 重试

- Google Cloud 的官方重试指南把“响应是否瞬态”和“操作是否幂等”作为两个同时满足的条件，并明确建议指数退避加 jitter、限制总时长，避免多层重试相乘。这个模型与 P1-G 的 `FailureClass × EffectCertainty` 一致，但也说明“可安全重试”不等于“相同输入重试有意义”。<br>
  来源：[Google Cloud Retry strategy](https://docs.cloud.google.com/storage/docs/retry-strategy)
- AWS Builders' Library 强调 timeout 不代表没有副作用，重试会放大过载，应该使用 backoff、jitter 和幂等 API。<br>
  来源：[Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)

### 10.2 grep、日志和 PostgreSQL

- GNU grep 的 `-A/-B/-C` 以相邻 group 表达上下文，重叠区间不会重复输出行。这适合作为 GrepTool match group 的兼容语义。<br>
  来源：[GNU grep Context Line Control](https://www.gnu.org/s/grep/manual/html_node/Context-Line-Control.html)
- journalctl 原生支持 `--since/--until` 时间过滤和 `--priority` 严重级别过滤，说明日志选择应优先使用结构化时间和 severity，而不是只做字符串 head 截断。<br>
  来源：[journalctl(1)](https://man7.org/linux/man-pages/man1/journalctl.1%40%40systemd.html)
- PostgreSQL 官方文档明确建议用 `pg_blocking_pids()` 识别 blocker，而不是自行把 `pg_locks` 多次 join 后猜冲突关系。因此智能保留应围绕阻塞关系闭包，而不是保留 `pg_stat_activity` 的前 N 行。<br>
  来源：[PostgreSQL pg_locks](https://www.postgresql.org/docs/current/view-pg-locks.html)、[pg_stat_activity](https://www.postgresql.org/docs/current/monitoring-stats.html)
- OpenTelemetry 日志数据模型把 timestamp 和 severity 作为一等字段，也支持本设计把日志 reducer 的结构化字段优先级高于自由文本正则。<br>
  来源：[OpenTelemetry Logs Data Model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)

### 10.3 context 与 compact

- Anthropic 的 context editing 会按时间顺序清理旧 tool results，并保留 placeholder；其文档同时强调完整客户端历史与模型看到的编辑后 context 可以分离。这支持“原始事实/本地状态”和“模型工作上下文”分层。<br>
  来源：[Claude Context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing)
- Anthropic 的 context engineering 总结是选择能最大化任务成功概率的最小高信号 token 集，并推荐保留轻量标识符后按需读取。这支持 evidence ref，而不是把全部证据常驻 context。<br>
  来源：[Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- OpenAI Agents SDK 将 compaction 包装在 session 外，并允许自定义 trigger；官方说明 compaction 会清空并重写底层 session，重操作可能阻塞 streaming。这提示本项目必须保证 session 重写安全，并准确观测 compact 耗时。<br>
  来源：[OpenAI Agents SDK Sessions](https://openai.github.io/openai-agents-python/sessions/)
- OpenAI 对 Codex loop 的说明显示，native compact 用更小的 item 列表替换旧 input，并在达到阈值后自动触发。关键思想是保留可继续工作的状态，而非保留逐字历史。<br>
  来源：[Unrolling the Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/)

## 11. 需求评审通过条件

进入方案设计前需要确认：

1. 接受 `maxAttempts=3` 的定义。
2. 接受 PA-1 第一阶段只自动重试只读/确认无效果的瞬态失败。
3. 接受日志与 PostgreSQL 实际 adapter 随 OPS-0A/0B 接入，PA-2 先交付 reducer 契约和 fixture。
4. 接受 OPS workflow 提供结构化 anchors，compact 不从自由文本独自推断诊断状态。
5. 接受 evidence ref 是 compact 后保留关键证据的主要形式，原始大输出不常驻 context。
6. 接受新增观测字段和 benchmark scorer 是三项完成定义的一部分。
