# P1-A DeepSeek 执行指令

状态：历史执行 runbook。A1–A8 已完成一轮阶段性实施；当前代码事实、冻结结论和
恢复顺序见 [P1-A 实施状态与阶段冻结说明](p1-a-implementation-status.md)。

用途：保留 P1-A 的 PA-1、PA-2、PA-3 可审查、可回滚的阶段指令，供后续收口时复用。

依据：

- [技术方案与反方评审](p1-a-design.md)
- [需求分析与技术调研](p1-a-requirements-and-technical-research.md)
- [P1-G 写操作门禁](p1-g-design.md)
- [稳定架构](../DESIGN.md)
- [项目协作约束](../CLAUDE.md)

## 1. 推荐交接方式

不要让一次上下文连续实现 A1–A8。按以下顺序逐阶段发送：

```text
A1
 -> A2
 -> A3
 -> A4
 -> A5
 -> A6
 -> A7
 -> A8
 -> Final Integration Review
```

每次发送：

1. “通用前置指令”；
2. 当前阶段指令；
3. 等 DeepSeek 完成实现、测试和自审后再进入下一阶段。

A1 是 A2 的前置，A4 是 A5 的前置，A6 是 A7 的前置。A2 与 A3、A5 与 A6
在代码边界上可以并行，但同一个工作区建议串行，减少公共 record 和事件 schema 冲突。

## 2. 通用前置指令

以下内容应附在每个阶段指令之前。

```text
你正在 D:\Agent\miniclaw 实施 P1-A。先完整阅读并遵守：

1. CLAUDE.md
2. DESIGN.md
3. docs/p1-a-requirements-and-technical-research.md
4. docs/p1-a-design.md
5. docs/p1-g-design.md
6. TODO.md 的 P1-A 段落

当前工作区中的 TODO.md、docs/p1-a-design.md、
docs/p1-a-requirements-and-technical-research.md 和
docs/p1-a-deepseek-execution-prompts.md 是本次任务基线，不得删除、reset、
checkout 覆盖或误判为无关改动。先运行 git status --short，保留所有既有用户改动。

工作规则：

- 先核对当前代码事实和调用链，再实施；文档描述与代码冲突时报告冲突，并以稳定架构、
  安全不变量和本阶段验收为准。
- 严格限制在当前阶段范围，不提前实现后续阶段。
- 不绕过 ToolCallExecutor、SideEffectGate、ExecutionControl、ContextPipeline 等唯一入口。
- 不对任何副作用工具增加 PA-1 自动重试。
- 不创建 Map 作为新的长期公共协议；稳定有限状态使用 enum/record/sealed type。
- 兼容公共 record、事件 codec 和旧构造器；新增字段必须有旧数据读取测试。
- 所有等待、重试、扫描和输出采集必须有界，并响应取消/deadline/预算。
- 不伪造 evidenceRef、测试结果、完成状态或观测字段。
- 使用 Java 21；优先不可变 record 和纯函数策略。
- 修改生产代码时同步增加机械化测试。先跑目标模块测试，再跑要求的回归测试。
- 不执行 git commit、push、reset、clean 或 checkout，除非我另行明确要求。
- 只有代码和验收测试都通过后，才更新 TODO 中本阶段的事实状态；不要把设计完成写成实现完成。

实施前先做一次简短反方审查，至少回答：

1. 该改动是否产生第二个事实源？
2. 是否可能绕过副作用、权限、预算或取消边界？
3. 兼容构造器、JSON/event reader 和旧调用方是否会破坏？
4. 是否可能无界重试、无界内存、无限 reinsert 或 token 膨胀？
5. 失败时是否会错误地继续调用 Provider 或重复执行动作？

若发现设计在当前代码中不可实现，不要自行扩大范围。给出代码证据、最小修订建议，
在安全可逆且不改变产品语义时才继续。

完成后按固定格式回报：

1. 代码事实与采用的方案
2. 修改文件清单
3. 验收项逐条结果
4. 实际执行的测试命令、通过/失败数量
5. 兼容性与剩余风险
6. git status --short 和 diff 摘要

不要只给方案或代码片段；本阶段要求直接修改工作区并验证。
```

## 3. A1：恢复契约校准

```text
执行 PR A1：恢复契约校准。只做分类、映射和 control-halt 结果契约，不实现 retry loop。

目标：

1. RecoveryDirective 新增 REPAIR_INPUT。
2. FailureClass 新增 DEADLINE_EXCEEDED_BEFORE_DISPATCH，
   EffectCertainty 固定为 NOT_DISPATCHED。
3. FailureDecisionTable 至少修正：
   - INVALID_ARGUMENTS -> REPAIR_INPUT
   - DEADLINE_EXCEEDED_BEFORE_DISPATCH -> ABORT
   - 其余映射保持 docs/p1-a-design.md 3.1 的语义。
4. autoRetryAllowed(INVALID_ARGUMENTS, ...) 必须为 false。
5. ToolExecutionResult 增加统一的 halted(...) 工厂，把：
   - CANCELLED -> CANCELLED_BEFORE_DISPATCH
   - DEADLINE_EXCEEDED -> DEADLINE_EXCEEDED_BEFORE_DISPATCH
   - BUDGET_EXHAUSTED -> BUDGET_EXHAUSTED
6. ToolCallExecutor 首次 acquireToolCall() 失败不能再构造 blocked/
   PERMISSION_BLOCKED，必须使用 halted(...)。
7. 更新所有 exhaustive switch、序列化/codec 和兼容测试。

非目标：

- 不实现任何自动重试。
- 不改变 SideEffectGate 或 ActionAttemptCoordinator 行为。
- 不调整 Provider retry。

验收：

- FailureClass × RecoveryDirective 全覆盖，新增 enum 没有落入默认兜底。
- INVALID_ARGUMENTS 相同输入自动重试资格为 false。
- 三种 ExecutionHaltedException.Reason 均得到正确 FailureClass 和
  NOT_DISPATCHED certainty。
- 原权限拒绝仍是 PERMISSION_BLOCKED，不与预算/deadline 混淆。

测试：

mvn test -pl clawkit-reliability,clawkit-engine -am

通过后停止，不进入 A2。
```

## 4. A2：只读工具 retry loop

```text
执行 PR A2：只读工具有界重试。A1 必须已经通过。

目标：

1. 在 clawkit-reliability 增加纯函数 ToolRetryPolicy、
   ToolRetryContext、ToolRetryDecision。
2. 默认总 maxAttempts=3，包含首次；full jitter：
   cap=min(200ms*2^(attemptsMade-1), 2000ms)，delay=random(0,cap)。
3. RetryBackoff/随机源/RetrySleeper 必须可注入，测试不能真实 sleep。
4. 同输入重试必须同时满足：
   - metadata.readOnly=true
   - sideEffects 为空
   - metadata.provenance.trusted=true
   - EffectCertainty=NO_EFFECT_CONFIRMED
   - directive=RETRY_ALLOWED
   - ToolError.retryable=true
   - attempts 未达上限
   - control/deadline/预算允许
5. ToolCallExecutor 只在非副作用分支调用 executeReadOnlyWithRetry。
   metadata 在 loop 前冻结；副作用分支保持原样进入 SideEffectGate。
6. 首次调用沿用已有 acquireToolCall；每次新增 attempt 前再次 acquireToolCall。
7. 等待前后执行 control checkpoint，取消/deadline/预算后不得启动下一次。
8. 每个原始 toolCall 对模型只回注一个最终 tool result，batch 结果顺序不变。
9. executor 内使用 ExecutedToolCall 包装 result、attemptCount、
   logicalDurationMs、finalStopReason；最终 duration 是整个逻辑调用耗时。
10. 新增 ToolRetryScheduledPayload；ToolCompletedPayload 兼容增加
    attemptCount、failureClass、recoveryDirective、finalStopReason。
11. 旧 event reader 和旧构造器仍可读取，默认 attemptCount=1。

失败处理：

- 达上限返回最后失败，并在安全 details 中记录 attemptsMade。
- 退避期 control halt 使用 A1 的 halted(...)，保留 previousFailureClass。
- 未知异常和 retryable=false 不重试。

反方重点：

- 用生成式/参数化测试证明任何非只读、带 sideEffects、不可信 metadata、
  EFFECT_UNKNOWN、PARTIAL_EFFECT 都不会进入 loop。
- 确认并行 batch 中每个调用独立重试，输出仍按输入顺序归并。

测试：

mvn test -pl clawkit-reliability,clawkit-engine,clawkit-observability -am

至少覆盖：0 次重试成功、失败两次后成功、三次失败、参数错误、取消、
deadline、预算、不可信 MCP、副作用工具、并行顺序、event codec。

通过后停止，不进入 A3。
```

## 5. A3：Provider retry 可观测性

```text
执行 PR A3：Provider transport retry 可观测性。不要在 Engine 增加 Provider 重放。

目标：

1. 将 ProviderResponseMetadata 移为 public 独立 record，保留 EMPTY 和兼容使用方式。
2. OpenAIProvider 非流式 sendWithRetry 返回 body + retryCount。
3. 覆盖 V2 generate(ModelRequest)，返回真实 model/id/usage/retryCount。
4. V1 generate(...) 只适配 V2 response 为 Message；检查并证明没有 V1/V2 递归。
5. LLMException 兼容增加 retryCount，最终失败也能观测实际已重试次数。
6. ObservingProviderGateway 成功时从 response metadata、失败时从 exception
   写入真实 retryCount 和最终 ProviderError 分类。
7. 429、500、502、503、504、网络/超时保持可重试；400/401/403/
   context length/protocol error 不重试。
8. 合法 Retry-After 优先；否则 exponential backoff + full jitter。
   随机源和 sleeper 可注入，等待前后检查 ExecutionControl。
9. 流式调用不重放已开始的 stream，第一阶段 retryCount=0。

非目标：

- 不让 ObservingProviderGateway 或 AgentEngine 再次提交请求。
- 不改变 ToolRetryPolicy。

测试：

mvn test -pl clawkit-provider,clawkit-engine,clawkit-observability -am

至少覆盖：首次成功、429 后成功、503 耗尽、400 不重试、Retry-After、
jitter 上界、等待期取消、成功/失败事件 retryCount、流式为 0、无适配递归。

通过后停止，不进入 A4。
```

## 6. A4：统一输出事实

```text
执行 PR A4：统一输出事实。先实现契约和不变量，再迁移工具。

目标：

1. 扩展 ToolOutputStats：
   - totalBytes：观察到的源 bytes
   - returnedBytes：模型实际看到的最终 UTF-8 bytes，保持旧语义
   - retainedSourceBytes：被 reducer 代表的脱敏前源 bytes
   - totalLines / returnedLines
   - truncated / truncationReason / retentionPolicy / inputComplete
2. 旧 3 参数构造器兼容：
   retainedSourceBytes=min(returnedBytes,totalBytes)，line=-1，
   retentionPolicy=LEGACY_V0。
3. OutputEnvelope 兼容增加 inputComplete；truncated() 为
   omittedBytes>0 || !inputComplete。hash/total 只覆盖已观察源数据。
4. 新增 ReducedToolOutput(text,envelope,stats)，构造时校验：
   - stats.totalBytes == envelope.totalBytes
   - stats.retainedSourceBytes == envelope.returnedBytes
   - stats.returnedBytes == UTF8(text).length
   - stats.inputComplete == envelope.inputComplete
   - envelope.returnedBytes + omittedBytes == totalBytes
5. ToolExecutionResult 增加 withReducedOutput(...)，一次同步 output、
   outputBytes、truncated、stats、envelope；旧 withOutputEnvelope deprecated。
6. 修复 Bash 当前 total/returned 和 envelope 漂移。
7. ReadTool、WebFetchTool、GrepTool 迁移到准确的 V2 结果路径。
8. ToolCompletedPayload 兼容增加 totalSourceBytes、retainedSourceBytes、
   returnedOutputBytes、line counts、reason、policy、inputComplete。
   事件只能从最终 stats 投影。

边界：

- 渲染标签不是源数据，不能令 envelope.returnedBytes 等于 outputBytes。
- 脱敏可能改变可见长度，但不改变源空间 byte 不变量。
- 没有真实 evidence store 时 evidenceRefs 必须为空。
- 不在本阶段实现日志/PostgreSQL adapter 或复杂 reducer。

实施建议：

- 先完成 record、兼容构造器和 invariant tests。
- 再逐个迁移 Bash、Read、WebFetch、Grep，每迁移一个就跑模块测试。
- 最后更新 event codec/reader。

测试：

mvn test -pl clawkit-tools,clawkit-engine,clawkit-observability -am

至少覆盖：ASCII/中文/emoji、脱敏改变长度、完整/截断/提前停止、
旧构造器、旧事件 JSON、Bash stdout+stderr 组合、四处投影一致。

通过后停止，不进入 A5。
```

## 7. A5：语义 reducer

```text
执行 PR A5：在 A4 统一契约上实现有界语义 reducer。

目标：

1. BoundedOutputCollector 增加有界 WARN 采集和 line/byte 位置：
   head、tail、最多 8 ERROR、最多 8 WARN、每条最多 300 code points。
2. 最终渲染按源位置去重，同一行不能同时出现在 head/tail/diagnostics。
3. 保留 head/tail 兜底；ERROR/WARN 只是附加信号，不能取代兜底。
4. 所有采集有界；禁止先 readAllBytes/readAllLines 再做 reducer。
5. GrepTool 支持 beforeContext/afterContext/context。
6. Grep 使用逐行 reader、rolling before deque、pending after context；
   合并重叠 group，不切半个 group。
7. 先给每个文件保留首个 group，再按稳定路径/行号填充，避免单文件吞掉预算。
8. 达到扫描或 group 上限时 inputComplete=false，只报告 observed count。
9. 提供 LogReducer 和 RelationReducer 的最小通用 contract 与 fixture：
   - Log fixture：ERROR/FATAL、stack/cause/request-id、WARN、时间窗
   - Relation fixture：pg_blocking_pids 关系闭包、root->waiter、环/深度保护
10. 不创建实际 LogReadTool 或 PostgreSQL adapter；它们属于 OPS-0A/0B。

安全与完整性：

- 无真实 artifact store 时不生成 evidenceRef。
- 无法解析 timestamp 时不伪造时间范围。
- 结构化 severity/timestamp 优先，自由文本 regex 只是 fallback。

测试：

mvn test -pl clawkit-tools -am

另运行：

mvn test -pl clawkit-engine,clawkit-observability -am

至少覆盖：ERROR/WARN 位于中段、超长无换行、UTF-8 边界、重复行、
重叠 grep context、多文件公平性、提前停止、日志和阻塞链 fixture。

通过后停止，不进入 A6。
```

## 8. A6：compact contract

```text
执行 PR A6：只落地 task-aware compact 公共契约、注入点和兼容层，
暂不改变实际压缩顺序。

目标：

1. 在 clawkit-context 增加：
   - CompactionProfile
   - AnchorKind
   - AnchorProvenance
   - CompactionAnchor
   - CompactionHint
   - CompactionHintProvider
   - CompactionOptions
   - CompactionAudit
   - DiscardedTurnRange
2. CompactionRequest 增加 hint，旧构造器默认 GENERAL。
   ContextRequest 不增加重复 hint。
3. ContextManager 增加接受 CompactionOptions 的兼容 overload；
   旧 overload 委托 GENERAL。
4. CompactionResult 兼容增加 audit，旧调用方获得 EMPTY audit。
5. AgentEngine/EngineContextCoordinator 接受默认 GENERAL 的
   CompactionHintProvider，每轮 snapshot 一次；不依赖任何 OPS 类型。
6. 定义并测试 anchor validation：
   - id 字符集和长度
   - summary code-point 上限
   - provenance 与 kind 的合法组合
   - 非 USER 的 confirmed fact/counter-evidence 要有真实 ref
   - 同 id 后值覆盖
7. 扩展 ephemeral persistence 判断，使未来
   [Runtime][Compaction Anchors] 不进入 session。
8. 扩展 CompactCompletedPayload 的 additive schema 和旧 reader 测试，
   本阶段允许新字段保持 GENERAL/空值，但不能破坏旧事件。

非目标：

- 不注入 anchor system message。
- 不调整 MessageMasker 顺序。
- 不实现 verify/reinsert/re-budget。
- 不创建 OPS diagnosis state。

测试：

mvn test -pl clawkit-context,clawkit-engine,clawkit-observability -am

通过后停止，不进入 A7。
```

## 9. A7：hint-aware compact

```text
执行 PR A7：完成 task-aware compact 行为。A6 必须已经通过。

目标：

1. 删除 AgentEngine 在 ContextPipeline.compact() 前直接调用
   applyAlwaysOnRules() 的重复 destructive 路径；always-on 由 Pipeline 唯一编排。
2. DefaultContextPipeline 顺序严格为：
   a. 从原始 modelContext 提取 legacy constraints
   b. always-on normalization
   c. 合并 explicit anchors，按 id 去重
   d. validation 和预算选择
   e. canonical anchor snapshot
   f. MessageMasker
   g. budget analyze
   h. LadderedCompactor
   i. sidecar/hash verify
   j. 删除旧 snapshot，最多重建一份 canonical snapshot
   k. re-budget
3. legacy constraints 转为 USER_CONSTRAINT anchors。
4. snapshot renderer 必须 canonical escaping，防止 summary 中的换行、
   id=、system-like 文本伪造结构。
5. 验证以内部 AnchorSnapshot sidecar/hash 为真相源，不做自由文本模糊搜索。
6. 单 anchor ≤512 Unicode code points，默认 ≤64 anchors；
   snapshot ≤targetTokens 的 10% 且绝对 ≤8192 tokens。
7. required anchors 超自身预算：
   REQUIRED_ANCHORS_OVER_BUDGET。
8. 重建后仍丢 required：
   REQUIRED_ANCHOR_LOST。
9. 重建后仍超硬限制：
   COMPACT_HARD_LIMIT。
10. 上述失败均使 Engine 结束为 COMPACT_FAILED，不能继续主任务 Provider。
11. OPS_DIAGNOSIS 摘要 prompt 区分 fact/counter-evidence/hypothesis，
    保留 chronology、pending checks、approval boundary 和 evidence ref。
12. CompactionAudit 和 CompactCompletedPayload 写真实 profile、retained/lost ids、
    discarded turn range/roles/reason、evictedGroups、duration、failureCode。
13. 不从自由文本猜 topic 或时间；时间只来自带 provenance 的 observedAt。
14. anchor snapshot 和派生 conversation summary 不进入普通 session。

反方重点：

- required anchor 即使 summarizer 故意遗漏也能恢复。
- summary 中伪造 anchor 结构不能绕过 sidecar/hash。
- reinsert 只允许一次，不存在振荡。
- GENERAL 空 hint 保持旧行为，除已明确修复的约束提取顺序外无回归。

测试：

mvn test -pl clawkit-context,clawkit-engine,clawkit-observability -am

至少覆盖：GENERAL、20+ turns OPS、summarizer 空/超时/漏 anchor、
恶意 summary、anchor 去重/上限、三种结构化失败、session 不持久化、
Provider 未在 compact failure 后调用、真实 event metrics。

通过后运行一次：

mvn test

通过后停止，不进入 A8。
```

## 10. A8：OPS 闭环 benchmark

```text
执行 PR A8：为 PA-1/PA-2/PA-3 建立组合完成率 benchmark。
先阅读 clawkit-evaluation 的现有 benchmark、scorer、fixture 和事件断言结构，
沿用现有框架，不另建平行 runner。

目标：

1. 新增内容 invariant scorer：
   - ContainsAnchor(id)
   - ContainsEvidenceRef(ref)
   - ContainsMatch(path,line)
   - ContainsBlockingEdge(blockerPid,waiterPid)
   - NoDuplicateToolResult(toolCallId)
   - MaxAttempts(toolCallId,n)
   - NoProviderCallAfterCompactFailure
2. 建立 fake diagnosis state producer，实现通用 CompactionHintProvider；
   不提前实现真实 OPS 日志/PostgreSQL adapter。
3. 至少新增组合场景：
   a. 只读工具瞬态失败两次，第三次成功
   b. 返回大输出，ERROR/WARN 或 grep match 位于中段
   c. 运行 20+ turns 并触发 OPS_DIAGNOSIS compact
   d. 保留确认事实、反证、OPEN hypothesis、pending check、evidenceRef
   e. 后续检查使用该状态完成任务
4. 新增 compact fail-closed 场景，证明 failure 后没有 Provider 调用。
5. 记录 attempts、truncation、anchors、token 和最终 RunStatus；
   不只检查“事件存在”，必须检查内容 invariant。
6. 更新 TODO 的 PA-1/2/3 状态时逐项引用真实测试，不把 fixture 当成实际
   Log/PostgreSQL adapter 已完成。

测试：

mvn test -pl clawkit-evaluation -am
mvn test

完成后给出 benchmark 场景名、scorer、通过结果和仍未覆盖的 OPS adapter 风险。
通过后停止，等待 Final Integration Review。
```

## 11. Final Integration Review

```text
执行 P1-A 最终集成审查。此阶段以审查和修复为主，不新增产品范围。

1. 重新阅读 docs/p1-a-design.md 第 11 节定版闸门。
2. 检查 git diff 和全部新增公共类型，确认模块边界：
   - tools 不依赖 engine/ops
   - reliability 不执行工具
   - context 不依赖 ops
   - observability 不参与决策
   - 副作用执行仍只有 ToolCallExecutor -> SideEffectGate
3. 搜索并消除：
   - INVALID_ARGUMENTS -> RETRY_ALLOWED
   - retryCount 固定为 0 的非流式成功/失败路径
   - OutputEnvelope/stats/outputBytes/event 漂移
   - ContextPipeline 前的重复 destructive compact
   - 文本搜索式 anchor 验证
   - compact failure 后 Provider 调用
4. 检查所有兼容构造器和旧 JSON/event fixture。
5. 运行：

mvn test
mvn package -pl clawkit-cli -am -DskipTests

6. 对 docs/p1-a-design.md 的 PA-1/2/3 验收逐项给出测试类和结果证据。
7. 只根据已实现代码更新 TODO：
   - contract/fixture 与实际 adapter 状态分开
   - 未完成项继续保留，不做乐观标记
8. 最后进行一次反方代码评审，按严重度列出仍存在的问题。
   若有高/致命问题，修复并重新执行相关测试；若无法安全修复，明确阻塞，不宣称完成。

不要 commit 或 push。最终回报完整测试结果、工作区状态和剩余风险。
```

## 12. 每阶段人工闸门

收到 DeepSeek 回报后，进入下一阶段前至少检查：

- 是否真的修改了工作区，而不是只复述方案；
- 实际测试命令是否与阶段要求一致；
- 是否给出测试数量和失败详情；
- `git status --short` 中是否出现越界文件；
- 是否提前实现下一阶段；
- 是否把副作用工具纳入 PA-1；
- 是否修改或删除现有 P1-A 文档；
- 是否把 fixture 写成真实 OPS adapter 已完成；
- 是否更新了与代码事实不符的 TODO 状态。

出现以下情况应退回当前阶段：

- 只跑单个测试类，没有模块回归；
- 使用真实 sleep 导致测试慢或不稳定；
- 依赖错误消息字符串决定是否重试；
- 为兼容问题保留两个可漂移的事实源；
- 用自由文本搜索验证 anchor；
- compact 失败仍继续 Provider；
- 没有证据存储却生成 evidenceRef；
- 使用 readAllBytes/readAllLines 处理无界大输出。
