# Clawkit 项目现状、技术亮点与演进总览

> 修订日期：2026-07-17
>
> 当前阶段：P0-R 已完成；P0-D 已完成本地实现，等待真实 CI、Docker 和 Release 证据
>
> 详细 Ops 架构与门禁路线：[ops-loop.md](ops-loop.md)

## 1. 项目定位

Clawkit 是基于 Java 21 的本地 Agent Runtime，主要入口为交互式 CLI，并支持 IM Adapter。项目已经从 ReAct Demo 进入“核心运行路径完成重构、工程交付闭环接近收口”的阶段。

三层定位：

```text
Clawkit Agent Runtime
  ReAct / Plan / SubAgent
  Context / Memory / Session
  ProviderGateway / ToolCallExecutor
  RunEvent / Metrics / Benchmark
              |
              v
Safe Ops Capability Layer
  opsro / Policy Gate / opsfix
  Typed Ops Runner / Verification
              |
              v
Ops Arena
  Fixture / k6 / PostgreSQL
  Hidden Ground Truth / Evaluator
```

对外不定位为“另一个自动运维平台”，而是：

> Clawkit 是可控、可观测、可评测的本地 Agent Runtime；Ops Loop 是建立在其上的 Agentic SRE 应用；Ops Arena 是用真实流量和隐藏故障评估 Agent 能力的 Benchmark 环境。

三个核心标签：

- **Evidence-first**：事实与推测分离，证据不足返回 `INCONCLUSIVE`。
- **Capability-safe**：模型只选择类型化、白名单能力，凭据和底层命令不进入上下文。
- **Independently-verified**：出题者、答题 Agent 和裁判隔离，Agent 不能自证成功。

## 2. 当前技术亮点

### 2.1 四条唯一主链

| 主链 | 唯一入口 |
| --- | --- |
| 工具执行 | `ToolCallExecutor` |
| 上下文构建与压缩 | `ContextPipeline` |
| 模型调用 | `ProviderGateway` |
| CLI/IM 装配 | `ApplicationBootstrap` |

SessionStore、MemoryHooks、SkillRuntime、SlashCommandRouter、ApprovalConsole 和 CLI Command Handler 已进入生产路径。ArchUnit 使用 10 条硬规则阻止核心路径重新出现旁路或反向依赖。

### 2.2 可信观测

- 每个 run 独立写入 `events.jsonl` 和原子 `summary.json`。
- Metrics 从 RunEvent 投影，不维护第二份指标事实。
- Run、Turn、Provider、Tool、Context、Compact、Approval 和 SubAgent 父子关系可追踪。
- CLI 已提供 `/runs`、`/metrics` 和 `/trace`。

这套事件事实源可以继续扩展 Incident、Evidence、Attempt、Verification 和 Compensation，而不需要另建无法对账的审计链。

### 2.3 可机械回归

- 固定 Benchmark 覆盖读写、搜索、失败恢复、长输出、上下文压缩、权限边界、Plan 和并行 SubAgent。
- 版本化 Baseline 支持逐 case 比较。
- 回归结果区分 `DEGRADED`、`UNCHANGED`、`IMPROVED` 和 `INCOMPATIBLE_BASELINE`。
- 安全约束和缺失完成事件可作为硬门禁。

### 2.4 类型化安全执行

普通工具、Internal Tool、Plan Worker 和 SubAgent 统一经过：

```text
Metadata
  -> PermissionPolicy
  -> Approval
  -> Tool Execution
  -> Structured Result
  -> RunEvent / Audit
```

已有能力包括：

- 类型化 metadata、风险等级、执行策略、审批记录和结构化错误。
- PLAN、ASK、AUTO 权限模式。
- Bash timeout、进程树终止、输出限制和非零退出码。
- 路径越界、symlink、防覆盖、原子写和 TOCTOU 检查。
- MCP 坏参数保护及不可信工具保守降级。

## 3. 当前工程状态

### 3.1 已完成底座

| 阶段 | 状态 | 核心结果 |
| --- | --- | --- |
| P0-O | 完成 | RunEvent 事实源、Metrics 投影、Trace、Benchmark、Baseline |
| P0-S | 完成 | V2 工具契约、统一权限、安全执行、MCP 审计、Git 只读工具 |
| P0-R | 完成 | Provider、Context、Tool、Bootstrap 四条主链和架构门禁 |

### 3.2 P0-D 工程交付闭环

| 项目 | 状态 | 当前证据 | 剩余门禁 |
| --- | --- | --- | --- |
| Windows Java 21 CI | 部分完成 | push/PR `clean verify`、whitespace、构建差异和 Docker smoke workflow 已实现；本地验证通过 | 真实 GitHub Actions 首次成功 |
| Docker 分发 | 部分完成 | 多阶段 Dockerfile、`.dockerignore`、非 root 用户和挂载约定已实现 | 基础镜像可用后完成 build 与 `docker run -it` smoke |
| 示例与演示 | 完成 | 非敏感配置、disabled MCP 和独立 Java/Maven demo | 无 |
| 配置体验 | 完成 | CLI > env > 用户 YAML > 默认值；脱敏 `/config` | 无 |
| DeepSeek 接入 | 完成 | 官方 endpoint；真实文本和工具调用成功 | 真实调用不进入普通 CI |
| 凭据安全 | 完成 | 产品仅读取 `CLAWKIT_API_KEY`；配置拒绝 credential 字段 | 无 |
| 用户可读错误 | 完成 | 配置、认证、限流、超时、网络和服务端错误类型化 | 无 |
| 分层手册 | 完成 | configuration、runtime、mcp、development 和示例文档 | 持续维护 |
| Release | 部分完成 | tag/POM 校验、全量测试、JAR smoke、SHA-256 和 Release workflow 已实现 | 非 SNAPSHOT 版本和真实 tag |

## 4. 当前验证证据

- `mvn -B -ntp clean verify`
  - 10 个 Reactor 模块成功。
  - 449 项测试，0 Failure、0 Error、0 Skipped。
  - ArchUnit 10/10 硬规则通过。
- 独立 Java/Maven 示例测试通过。
- shaded JAR 的 `--help` 和 `--version` 通过。
- `clawkit.cmd --version` 在 Windows 上通过。
- 未设置产品 Key 时以 `C-003` 和退出码 2 失败，未输出 Java 堆栈。
- 真实 `deepseek-chat` 文本对话和 JSON Schema 工具调用成功。
- 联调发现并修复 Map 型工具 Schema 丢失结构的问题，已增加回归测试。
- `git diff --check`、workflow YAML 和凭据泄漏检查通过。

## 5. 当前阻断与风险

### 5.1 发布阻断

- 工作区包含 P0-R/P0-D 的大量未提交变更，尚不是可复现的干净提交。
- 当前版本仍为 `0.1.0-SNAPSHOT`。
- 尚无真实 GitHub Actions 和 Release 运行记录。

### 5.2 Docker 阻断

Docker Desktop 已启动，但基础 Maven/JDK 镜像拉取连续超时，因此不能宣称镜像构建和交互式容器验收完成。

### 5.3 Ops Loop 尚未实现

以下能力仍是路线，不是完成事实：

- 本地可重复 Incident Fixture。
- 独立 `clawkit-ops-mcp`。
- Incident 状态机和 Evidence Bundle。
- 隐藏 Ground Truth Evaluator。
- 远程只读 SSH。
- Typed Ops Runner、独立验证和回滚/补偿。

## 6. 修订后的实施顺序

```text
D0 P0-D 外部证据收口
  -> OPS-0A App Down 本地只读纵向切片
  -> OPS-0B PostgreSQL 锁等待黄金诊断
  -> OPS-1 远程只读靶机
  -> P1-G 写操作前可靠性门禁
  -> OPS-2A 审批修复
  -> OPS-2B 有限自动修复
  -> OPS-3 持续运行与经验复利
```

路线不按固定周数承诺，而以可重复、可评分、可清理的退出门禁推进。当前唯一优先级：

1. 整理提交，跑通真实 CI、Docker smoke 和首个 Release。
2. 用 App Down 打通 Fixture、Evidence、Incident、Diagnosis、Evaluator 和 Cleanup。
3. 用 PostgreSQL 锁等待验证多源采证、反证和隐藏答案评分。
4. 本地门禁通过后进入远程只读。
5. 可靠性门禁满足后才开放类型化审批写。

详细架构、Case 和每阶段验收见 [ops-loop.md](ops-loop.md)。

## 7. 黄金演示

App Down 只作为工程冒烟；公开演示固定为：

> PostgreSQL 锁等待导致订单接口逐渐变慢。

演示流程：

1. k6 持续执行创建订单、查询订单、金额和重复订单校验。
2. Fixture Runner 注入 Clawkit 不可见的长事务锁。
3. 外部业务阈值触发 Incident。
4. Clawkit 使用只读能力排除 Nginx、CPU、进程和网络异常。
5. PostgreSQL 系统视图形成阻塞链 Evidence Bundle。
6. 只读阶段输出支持证据、反证、缺失证据和建议动作。
7. 写阶段由 Policy Gate 允许类型化固定动作。
8. Verification Run 使用新上下文重新采证。
9. Evaluator 最后揭示 Ground Truth 并确定性评分。

公开展示重点是 Incident Flight Recorder，而不是模型的一段回答：

```text
症状
  -> 证据时间线
  -> 候选根因与排除理由
  -> 权限门禁
  -> 动作与执行结果
  -> 独立验证
  -> Ground Truth 得分
```

## 8. 对外表述边界

推荐名称：

> **Clawkit — 可控、可观测、可评测的本地 Agent Runtime**

推荐副标题：

> 基于 Java 21 的 Agent 执行底座与可验证 Agentic SRE 应用

当前可以宣称：

- 四条核心主链和 10 条 ArchUnit 门禁。
- Token Budget、Context Pipeline、Memory/Session 生命周期。
- 类型化工具、安全执行、事件事实源和版本化 Benchmark。
- 449 项自动化测试、Windows CLI 和真实 DeepSeek 工具调用。

必须带限定：

- CI/Release workflow 在真实 GitHub 运行前只称“已实现并本地验证”。
- Docker 在成功镜像 smoke 前不称“容器分发完成”。
- Ops Loop 当前只称“目标架构和实施路线”。

Ops 落地后才使用真实数据：

```text
完成 N 类故障、X 次盲测；
Root Cause Hit = 原始命中数；
False Repair = 原始次数；
Unauthorized Action = 原始次数；
Verification / Compensation = 原始计数；
P50/P95 MTTD 与 MTTR = 实测值。
```

禁止预填理想比例，也不要用百分比隐藏样本量。

## 9. 暂不优先

- Web Dashboard、Prometheus/Grafana/ELK 全套平台。
- Kubernetes、多主机编排和中心化多租户平台。
- 任意远程 shell、自动 sudo、未知 SQL。
- 自动修改业务代码、自动提 PR 和自动发布。
- 同时集成 ChaosBlade、Chaos Toolkit、Gatus 和 Ansible Runner。
- 未通过独立验证和 Benchmark 的生产自动修复。

