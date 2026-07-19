# P1-A 实施状态与阶段冻结说明

> 日期：2026-07-19
> 状态：阶段冻结，可转入 OPS-0A；P1-A 保持未关闭
> 依据：[需求分析](p1-a-requirements-and-technical-research.md)、
> [定版设计与反方评审](p1-a-design.md)、
> [历史执行指令](p1-a-deepseek-execution-prompts.md)

## 1. 结论

本轮已经把 PA-1 的只读工具有界重试、Provider retry metadata 和对应观测主链
接入 Runtime；PA-2、PA-3 则完成了通用契约、部分组件和接入点。

当前适合停止继续扩展 Runtime 抽象，转入 OPS-0A/0B，用真实日志、Evidence、
Diagnosis state 和 PostgreSQL 阻塞关系驱动剩余 reducer、compact pipeline 与
组合 benchmark。当前状态不得描述为三个产品目标全部完成：

- **PA-1：主链完成，保留兼容性和 Provider hardening 待办。**
- **PA-2：契约与 Bash 统计接入完成，语义 reducer 主体未完成。**
- **PA-3：契约、注入点和 AnchorSnapshot 组件完成，生产 compact pipeline 未接入。**
- **A8：scorer 骨架完成，尚未形成产品级完成率门禁。**

## 2. 验证证据

- 执行方报告：`mvn clean test` → `BUILD SUCCESS`。
- 独立复核：

```text
mvn test
BUILD SUCCESS
11 reactor modules succeeded
Total time: 37.342 s
```

- 代码实施快照：31 个 tracked 文件变更，新增 24 个 Java 文件，覆盖
  tools、reliability、provider、engine、context、observability、evaluation。
- 模块边界保持：
  - tools 不依赖 engine/ops；
  - reliability 只决策，不执行工具；
  - context 不依赖 OPS 领域类型；
  - observability 不参与恢复或 compact 决策；
  - 副作用工具仍只经 `ToolCallExecutor -> SideEffectGate`。

测试绿色证明当前回归集通过，不等于下述尚未实现的产品场景已经通过。

## 3. 已落地能力

### 3.1 PA-1：失败分类与恢复

- 新增 `REPAIR_INPUT`、`DEADLINE_EXCEEDED_BEFORE_DISPATCH` 等恢复契约。
- `DefaultToolRetryPolicy` 对只读、无副作用、可信 provenance、明确 retryable
  的失败执行有界同输入重试；每个新增 attempt 重新消费 control budget。
- `ToolCallExecutor` 在串行和并行路径保留真实 `attemptCount`、逻辑耗时、
  `recoveryDirective` 和 `finalStopReason`。
- `ToolRetryScheduledPayload` 已注册稳定 event type，并覆盖 codec round-trip
  与 `FileRunRecorder` 持久化。
- `OpenAIProvider` 通过单次调用返回值传递 `ProviderResponseMetadata`，
  不再使用共享的最近一次 retry 状态。

### 3.2 PA-2：输出事实契约

- 新增 `ReducedToolOutput` 和扩展后的 `ToolOutputStats`、`OutputEnvelope` 字段。
- Bash 的 stats 改为从 envelope 派生，减少统计与信封的双写漂移。
- `BoundedOutputCollector` 增加行数和 WARN 采集能力。
- `ToolCompletedPayload` 增加 source/retained/returned bytes、行数、
  truncation reason、retention policy 和 input completeness 字段。

### 3.3 PA-3：compact 通用契约

- 新增 `CompactionProfile`、`CompactionHint`、`CompactionAnchor`、
  `CompactionAudit`、`CompactionOptions` 和 `CompactionHintProvider`。
- `CompactionHintProvider` 已接入 `AgentEngine -> EngineContextCoordinator`
  调用链，默认保持 GENERAL 行为。
- `AnchorSnapshot` 已实现 canonical render、escaping、hash verify、required ID
  检查和有界渲染，并有组件级测试。
- compact 观测 payload 已预留 profile、retained/lost anchor 和 failure 字段。

### 3.4 Evaluation

- 新增 `ContentInvariantScorer` 并接入现有 `BenchmarkScorer` 接口。
- 已有 pass/fail/edge 单元测试，证明 scorer 可被框架调用。

## 4. 未完成项与真实边界

### 4.1 PA-2

1. `ReducedToolOutput` 尚未成为 Bash、Read、WebFetch、Grep 的生产唯一事实源。
2. Grep 尚未迁移为逐行 streaming reader，也没有实现
   `beforeContext/afterContext`、重叠区间合并和 match group 预算。
3. WARN 已采集但尚未进入最终 envelope/模型可见语义输出。
4. LogReducer、RelationReducer 及其 fixture 未实现；实际 adapter 依赖 OPS-0A/0B。

### 4.2 PA-3

1. `DefaultContextPipeline` 尚未在 destructive mask/compact 前创建 AnchorSnapshot。
2. 尚未实现 legacy constraint 转 required anchor、compact 后 verify/reinsert、
   再预算以及 required anchor 丢失时 fail-closed。
3. `CompactionAudit` 当前生产结果仍为空，扩展观测字段尚未写入真实审计值。
4. `OPS_DIAGNOSIS` 在上述链路完成前不能视为可用产品能力。

### 4.3 Benchmark

1. 尚无 fake diagnosis producer 和 20+ turn OPS 组合场景。
2. `NoDuplicateToolResult` 当前实现为自比较，不能发现重复结果。
3. 未识别的 invariant 当前可能在“事件非空”时通过，不能作为 CI 强制门禁。
4. ContainsMatch、ContainsBlockingEdge、真实 MaxAttempts 和 compact failure 后
   Provider 调用顺序仍需基于事件内容实现。

### 4.4 兼容性与 Provider hardening

1. 旧 JSON 缺失新字段时目前得到 Jackson primitive 默认值：
   `attemptCount=0`、`inputComplete=false`，而设计目标是旧调用按单次完整输入解释。
2. 旧 compact event 的 `profile` 当前可能为 `null`，需要 reader normalization。
3. Provider jitter 仍直接使用 `Math.random()`，随机源不可注入。
4. `Retry-After` 当前只支持整数秒，不支持 HTTP-date；解析响应失败时也需确认
   transport retryCount 是否完整进入 `LLMException`。

## 5. 冻结与恢复条件

现在可以暂停 P1-A 的继续实现，但必须遵守：

- TODO 中保持 PA-2、PA-3 和 A8 未关闭；
- 不把 `ContentInvariantScorer` 作为强制质量门禁；
- 不以 `OPS_DIAGNOSIS` 宣称跨 compact 保留任务状态；
- 不把契约或 fixture 描述为真实 Log/PostgreSQL adapter；
- 新增 Runtime 能力优先由 OPS-0A/0B 的真实失败数据驱动。

恢复 P1-A 时按以下顺序收口：

1. 修复旧 event 默认值和 scorer 的恒真/宽松判断；
2. 用 OPS-0A 日志 fixture 完成 streaming Grep/Log reducer；
3. 将 AnchorSnapshot 接入 `DefaultContextPipeline`，完成 verify/reinsert/re-budget；
4. 用 OPS-0B 关系数据完成 RelationReducer；
5. 建立 20+ turn 的 PA-1 → PA-2 → PA-3 组合 benchmark；
6. 组合 benchmark 通过后再关闭 P1-A。
