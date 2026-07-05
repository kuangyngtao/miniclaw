# miniclaw TODO

> `[ ]` 待做 `[~]` 进行中 `[x]` 已完成

本 TODO 按“底层基座 Agent 基本功”排序。模块分层见 [CLAUDE.md](CLAUDE.md)。

## 优先级定义

| 优先级 | 目标 | 判断标准 |
|--------|------|---------|
| P0 | 可靠运行闭环 | 上下文可信、工具安全、过程可观测、任务可评测 |
| P1 | 任务完成率提升 | 减少失败、减少重复调用、提高复杂任务完成质量 |
| P2 | 成本与效率优化 | 降 token、降延迟、降模型成本，但不牺牲可靠性 |
| P3 | 高级扩展能力 | 后台任务、远程运维、语义搜索、native image 等 |

---

## P0：可靠运行闭环

这些是基座 Agent 的基本功，优先级高于新增花哨能力。

- **[ ] 真实 tokenizer**（context）  
  引入 JTokkit 或等价 tokenizer，替换字符数估算。  
  验收：`/context` 展示真实 token；上下文压缩触发点不再依赖字符估算。

- **[ ] 上下文预算管理**（context / engine）  
  建立模型窗口预算线，例如 70% 预警、85% 强制 compact。按 system、tools、history、memory、tool result 分区预算。  
  验收：任务运行中能解释 token 分布；压缩前后保留关键约束。

- **[ ] 结构化指标 `metrics.jsonl`**（observability）  
  记录 token、轮次、工具调用、工具耗时、工具成功率、compact 次数、Provider 重试、缓存命中。  
  验收：每个 run 至少写入一条结构化指标，便于后续 benchmark。

- **[ ] 评测基准**（evaluation）  
  建立 20 个编程任务 benchmark，测完成率、轮次、token、工具失败率。  
  验收：可以一条命令跑基准并输出汇总报告。

- **[ ] Git 专用工具**（tools）  
  封装 `git status`、`git diff`、`git log`、`git show`。  
  验收：PLAN 模式可读；输出结构化；无需通过 bash 才能获得常用 git 信息。

- **[ ] WriteTool 覆盖确认**（tools / safety）  
  目标文件已存在时触发确认或强制参数。  
  验收：`ASK` 模式可拦截覆盖；`AUTO` 模式也不能静默覆盖非空文件，除非显式传入 overwrite。

---

## P0.5：底层结构重构

这组任务用于偿还当前实现中已经影响可维护性和安全边界的结构债。目标不是改风格，而是把运行时状态、持久会话、工具执行、上下文管线和安全审计拆清楚。

- **[ ] 拆分 `AgentEngine` 上帝类**（engine）  
  当前 `AgentEngine` 同时承担 ReAct loop、工具执行、SubAgent、Plan-and-Execute、记忆提取、session 注入、skill runtime、todo 追踪和 compact 状态。  
  目标拆分：
  - `AgentRuntime`：主循环、状态机、执行模式分发。
  - `ToolCallExecutor`：串行/并行工具调用、审批、工具结果回注。
  - `ContextPipeline`：system prompt、runtime system messages、mask、compact、ephemeral context。
  - `MemoryHooks`：自动记忆提取、memory_save、session_context。
  - `SkillRuntime`：skill load/unload、active skill prompt 拼装。
  - `PlanRuntime`：Plan-and-Execute 入口和 plan 文件同步。
  验收：`AgentEngine` 只保留编排门面；每个拆分组件有独立单元测试；现有 AgentEngine 行为测试继续通过。

- **[ ] 分离运行时上下文和持久会话**（engine / context）  
  当前 working memory、进度提醒、死循环提醒、相关历史会话等 runtime system message 会进入 `sessionHistory`，容易污染长期上下文。  
  目标：
  - `sessionHistory` 只保存用户、助手、工具结果等真实对话事实。
  - runtime 注入走 ephemeral context，不持久化。
  - related sessions、working memory、progress reminder、stall reminder 都标记来源和生命周期。
  验收：连续多轮运行不会重复累积 `[Working Memory]`、`[Runtime]`、`[Related Past Sessions]` system 消息。

- **[ ] 重构 MCP 工具元数据和风险模型**（tools / mcp / engine）  
  当前 MCP 工具统一 `isReadOnly=false`，审批风险分级也只认识少数内置工具。  
  目标：
  - MCP 工具支持 `readOnly`、`riskLevel`、`destructive`、`requiresApproval` 元数据。
  - `PLAN` 模式可使用只读 MCP 工具。
  - 高风险 MCP 工具在 `ASK` 下展示明确风险说明。
  - 未声明风险的 MCP 写工具默认按中高风险处理，而不是 LOW。
  验收：新增 MCP adapter 测试覆盖只读工具、高风险工具、未知风险工具。

- **[ ] 修复 MCP 审计日志 JSONL**（tools / mcp）  
  当前 `McpAuditLogger` 手拼 JSON，输出不是稳定合法 JSON。  
  目标：
  - 使用 Jackson 写 JSONL。
  - 每条记录包含 `ts`、`tool`、`action`、`argumentsPreview`、`outcome`、`outputBytes`、`durationMs`。
  - 参数预览做长度限制和敏感字段脱敏。
  验收：审计日志能被 Jackson 逐行读取；失败和成功记录结构一致。

- **[ ] 修复 BashTool 输出读取和进程生命周期**（tools）  
  当前先 `waitFor()` 再 `readAllBytes()`，长输出命令可能阻塞 pipe。  
  目标：
  - 启动进程后并发 drain stdout/stderr。
  - 超时后销毁进程树或至少确保子进程退出。
  - 输出截断保留 head/tail 和退出码。
  - 支持可配置 timeout。
  验收：长输出命令、无输出命令、超时命令、非零退出码都有测试。

---

## P1：任务完成率提升

这些任务直接影响 Agent 是否能稳定完成真实编码任务。

- **[ ] 应用层工具结果缓存**（tools / engine）  
  同一 turn 内相同 read、grep、glob 调用返回缓存，turn 结束清空。  
  验收：metrics 记录缓存命中率；重复工具调用不再重复 IO。

- **[ ] 工具结果智能截断**（tools / context）  
  替代硬 8000 字节截断。优先保留命中行、文件路径、错误上下文、前后文窗口。  
  验收：grep/read 大输出时不丢关键命中信息。

- **[ ] 任务感知压缩策略**（context）  
  修 bug 保留代码和错误栈；解释任务保留对话推理；实现任务保留 todo 和文件 diff。  
  验收：同一长任务 compact 后仍能继续执行，不丢路径、测试失败和用户约束。

- **[ ] Provider 降级链路**（provider）  
  主模型超时、熔断或限流后自动切备用模型。  
  验收：错误分类清晰；fallback 写入 metrics；用户能看到降级说明。

- **[ ] 流式早期终止**（provider / engine）  
  检测虚构文件/API、协议破坏、明显工具调用幻觉时中止流并重试。  
  验收：不会把明显坏输出继续执行到工具层。

- **[ ] BashTool 增强**（tools）  
  支持可配置超时、跨平台 shell 检测、后台进程防护。  
  验收：Windows / Unix 行为有测试；超时和进程清理可观测。

---

## P2：成本与效率优化

这些优化应该建立在 P0/P1 的观测能力之上。

- **[ ] 自适应分层 compact**（context）  
  根据 token 压力动态选择 L1/L2/L3 策略。  
  验收：compact 策略可解释，并记录到 metrics。

- **[ ] 多模型路由**（provider）  
  简单工具/总结走小模型，复杂推理/规划走强模型。  
  验收：有路由策略、回退策略和质量对比，不只按价格路由。

- **[ ] Prompt caching**（provider / context）  
  支持 Anthropic 原生 `cache_control` 或兼容策略，优先缓存 system prompt 和 tools。  
  验收：命中率和节省 token 成本可观测。

- **[ ] Bash 会话复用**（tools）  
  维护可重置的交互式 shell 会话，保留 `cd` / `export` 状态。  
  验收：必须暴露当前 cwd/env 摘要；支持 reset；避免状态污染。

- **[ ] 记忆去重与合并**（memory）  
  对相似记忆做合并，对冲突记忆做标记。  
  验收：新增记忆不会无限膨胀；冲突不被静默覆盖。

- **[ ] 记忆衰减**（memory）  
  按访问频率和时间归档低价值记忆。  
  验收：召回结果能解释来源、时间和命中原因。

---

## P3：高级扩展能力

这些能力有价值，但应在基座可靠性和观测能力稳定后推进。

- **[ ] `/btw` 后台并行任务**（engine / cli）  
  独立 AgentEngine 虚拟线程并发。  
  前置条件：任务状态、日志、取消、资源隔离、metrics 已稳定。

- **[ ] 远程运维 MCP**（tools / mcp）  
  本地 MCP server + SSH 代理，提供 `remote_exec`、`remote_read`、`remote_status`。  
  安全要求：默认 ASK 模式、RemoteSafetyInterceptor、审计日志、主机白名单。

- **[ ] 任务级回滚**（tools / engine）  
  写入前自动保存 `.miniclaw.bak`，支持按 task/action 回滚。  
  验收：多文件修改可列出备份和恢复路径。

- **[ ] 本地嵌入模型语义搜索**（memory / context）  
  在 BM25 稳定后补语义召回。  
  验收：召回结果必须能解释，不替代精确 grep/read。

- **[ ] GraalVM native-image**（cli）  
  启动时间优化，目标 < 0.1s。  
  前置条件：核心能力稳定，反射配置和资源打包可维护。

---

## 暂不优先

- 预测性工具预热：主要节省 50-200ms，低于上下文和工具可靠性优先级。
- 大而全垂类业务包：应作为上层插件/MCP/Skill，不写入底层引擎。
- 自动高风险写操作：没有人审、审计、回滚之前不做。

---

## 最近测试记录

最近文档记录：295/295 通过（tools 66 + provider 14 + context 45 + memory 32 + engine 69 + im 23 + cli 46）。

本记录用于路线参考。每次实际合入前仍需重新跑相关测试。
