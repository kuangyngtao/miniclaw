# Clawkit Ops Loop 架构与演进路线

> 修订日期：2026-07-17
>
> 状态：目标架构，尚未实现
>
> 当前工程事实：[project-highlights-and-ops-loop-roadmap.md](project-highlights-and-ops-loop-roadmap.md)

## 1. 目标与非目标

Ops Loop 用真实但可丢弃的运维环境验证 Agent Runtime 是否能够：

1. 自动发现异常。
2. 形成带时间和来源的证据。
3. 在证据不足时返回 `INCONCLUSIVE`。
4. 命中受限 Playbook 后选择最小动作。
5. 由独立上下文重新验证业务、性能和数据状态。
6. 失败时执行预定义回滚或补偿，并可靠升级人工。
7. 由隐藏 Ground Truth Evaluator 确定性评分。

非目标：

- 不接入真实生产数据或生产写权限。
- 不向模型暴露任意 root、sudo、SSH 命令或自由 SQL。
- 不把 SSH、Incident、SOP 等垂类逻辑写入 `AgentEngine`。
- 不自建通用监控、混沌或 Runbook 平台。
- 不让诊断 Agent 自行宣布修复成功。

## 2. 架构与职责

```text
Clawkit Agent Runtime
  ReAct / Plan / SubAgent
  Context / Memory / Session
  ProviderGateway / ToolCallExecutor
  RunEvent / Metrics / Benchmark
              |
              v
Safe Ops Capability Layer
  clawkit-ops-loop
  clawkit-ops-mcp
  opsro / Policy Gate / opsfix
  Typed Ops Runner / Verification
              |
              v
Ops Arena
  Docker Compose / PostgreSQL / k6
  Fault Injector / Hidden Ground Truth
  Deterministic Evaluator
```

推荐目录：

```text
extensions/
  clawkit-ops-mcp/
  clawkit-ops-loop/
ops-fixtures/
  compose.yaml
  cases/
  assertions/
  reports/
```

| 组件 | 唯一职责 | 明确不负责 |
| --- | --- | --- |
| Fixture Runner | 建立初始状态、注入和销毁故障，持有 Ground Truth | 不向 Clawkit 暴露根因 |
| k6 / Probe | 生成业务流量，输出确定性业务、性能和一致性断言 | 不做根因诊断 |
| `clawkit-ops-mcp` | 暴露结构化只读能力和类型化写动作 | 不暴露通用 shell/SSH |
| `clawkit-ops-loop` | Incident、采证策略、诊断、Policy Gate 和报告 | 不直接执行底层命令 |
| Typed Ops Runner | 校验枚举参数并执行固定动作 | 不接受模型生成命令 |
| Verification Run | 使用新上下文重新采集证据 | 不信任修复 Run 的结论 |
| Ops Evaluator | 持有隐藏答案并确定性评分 | 不参与诊断和修复 |

如果引入 Chaos Toolkit，其职责仅为 Fixture 实验生命周期；Clawkit Incident 不成为第二个故障注入编排器。ChaosBlade 只作为标准故障执行器。

## 3. Incident 与数据契约

### 3.1 状态机

只读阶段：

```text
DISCOVERED
  -> COLLECTING
  -> EVIDENCE_READY
  -> DIAGNOSED
  -> READ_ONLY_COMPLETE

COLLECTING / EVIDENCE_READY
  -> INCONCLUSIVE
  -> ESCALATED
```

写操作启用后：

```text
DIAGNOSED
  -> PLAN_READY
  -> WAITING_APPROVAL
  -> PRECHECKING
  -> EXECUTING
  -> VERIFYING
  -> RESOLVED

EXECUTING / VERIFYING
  -> COMPENSATING
  -> ROLLED_BACK
  -> ESCALATED
```

约束：

- 每次迁移记录原因、时间、runId 和证据引用。
- 证据不可覆盖，只能追加新版本。
- Incident 报告引用 Runtime RunEvent，不复制工具审计事实。
- 进程恢复后必须识别未完成、结果未知和待验证 Attempt。

### 3.2 Evidence

```text
evidenceId
incidentId
source
observedAt
collectedAt
scope
fact
rawReference
freshness
redaction
```

必须区分：

- 事实与推测。
- 当前证据与历史残留。
- 未采集、采集失败和证据明确正常。

### 3.3 Diagnosis

```text
rootCauseCode
confidence
supportingEvidence[]
contradictingEvidence[]
alternatives[]
missingEvidence[]
recommendedActionCode
```

`confidence` 不能单独触发修复；Policy Gate 同时检查必要证据、状态新鲜度和 Playbook 匹配。

### 3.4 Action Contract

每个写动作必须声明：

```text
actionCode
targetType
allowedTargets
parameterEnums
riskLevel
reversibility
idempotencyKey
preconditions
expectedEffects
verificationPolicy
compensationAction
blastRadius
cooldown
maxAttempts
```

恢复语义：

| 分类 | 示例 | 失败处理 |
| --- | --- | --- |
| 可回滚 | 恢复已知配置、切换已知 Release | 回到前一版本 |
| 可补偿 | 重启指定服务、重新加载配置 | 执行预定义恢复动作 |
| 不可逆但可约束 | 终止带 Fixture 标签的 DB 会话 | 严格 Precheck、业务一致性验证、失败升级 |
| 永久禁止 | 删除业务数据、未知 SQL、任意 sudo/bash | 工具层和主机层双重拒绝 |

## 4. 能力与安全模型

### 4.1 能力分级

| 等级 | 能力 | 初始策略 |
| --- | --- | --- |
| L0 | 状态、日志、端口、磁盘、HTTP、DB 只读视图 | 自动 |
| L1 | 重启 allowlisted 服务、恢复已知配置、清理专用 Fixture | 先审批，达标后逐动作自动 |
| L2 | 切换/回滚已知 Release、受限 DB 维护 | 长期审批，逐动作评估 |
| L3 | 任意命令、系统升级、防火墙、业务数据删除、未知 SQL | 永久禁止或人工接管 |

`opsro` 是默认身份，无 sudo、无写权限。`opsfix` 不拥有通用 shell，只调用 root 持有的 Typed Ops Runner。

### 4.2 Policy Gate

写操作至少同时满足：

1. `rootCauseCode` 命中版本化 Playbook。
2. 必要证据齐全且未过期。
3. Precheck 与诊断时状态一致。
4. 目标、参数、次数、预算和冷却均在 Allowlist。
5. 动作具有确定性 Verification Policy。
6. 可回滚动作存在有效恢复点；不可逆动作通过更严格门禁。
7. 同一 Incident 不存在并发修复 Attempt。

任一条件不满足时返回 `INCONCLUSIVE`、等待审批或升级人工，模型无权放宽门槛。

### 4.3 独立验证

Verification 必须：

- 使用新上下文和新的 runId。
- 重新采集 HTTP、进程、端口、日志、数据库和业务事务证据。
- 先运行确定性断言，再允许模型解释。
- 能识别表面健康、旧错误日志、业务仍失败和数据不一致。

一票否决项：

- 越权动作。
- 数据破坏。
- 未恢复却宣称成功。
- Ground Truth 泄漏。
- Verification 未重新采证。

## 5. 最小工具栈

OPS-0 首个切片只引入：

- Docker Compose：Fixture。
- k6：业务流量与阈值。
- 确定性脚本：注入、清理和 Ground Truth。
- `clawkit-ops-mcp`：结构化只读采证。
- Clawkit Evaluation：确定性评分和回归。

按门禁增加：

- ChaosBlade：需要标准 CPU、网络、磁盘、进程或容器故障时。
- Chaos Toolkit：需要跨步骤实验编排和 Journal 时。
- Gatus：进入远程持续探测时。
- Ansible Runner：固定动作复杂度证明自建 Runner 不经济时。

不在 1～2 GB 靶机常驻 Prometheus、Grafana、ELK、Kubernetes 或完整 Clawkit Runtime。

## 6. 总体路线

路线按可验证门禁推进：

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

共同原则：

1. 一次只建立一条端到端闭环。
2. 先只读、后审批写、最后有限自动化。
3. 先确定性断言、后模型解释。
4. 先本地可重复、后远程真实环境。
5. 先证明不会误修，再优化命中率和 MTTR。

## 7. D0：P0-D 外部证据收口

### 任务

1. 将 P0-R/P0-D 变更整理为可审查提交。
2. 在真实 push/PR 跑通 Windows Java 21 CI。
3. 完成 Docker build 和 `docker run --rm -it` smoke。
4. 决定 `v0.1.0` 范围，移除 SNAPSHOT 并执行 tag Release。
5. 从干净 clone 按 README 复验用户路径。

### 门禁

- 工作树可解释，不混入无关文件。
- GitHub Actions 真实成功。
- Docker 镜像有构建和交互式 smoke 证据。
- Release 包含可运行 JAR 和 SHA-256。
- README 命令与实际产物一致。

本阶段不开始 SSH，不新增 Runtime 抽象，也不把真实模型调用放入普通 CI。

## 8. OPS-0A：App Down 工程冒烟

目标是打通管道，不追求复杂诊断：

```text
Compose Fixture
  -> k6 / Probe
  -> Fault Injector
  -> Read-only Ops MCP
  -> Incident / Evidence
  -> Diagnosis
  -> Hidden Ground Truth Evaluator
  -> Cleanup
```

实施顺序：

1. 定义 Case、Evidence、Diagnosis 和 Evaluation Contract。
2. 实现 nginx → demo-api 正常态、容器退出注入和幂等清理。
3. 实现 `service_status`、`container_status`、`ports`、`http_probe` 和有界 `logs`。
4. 实现只读 Incident 状态机。
5. 输出 Evidence Bundle、Diagnosis JSON 和 Markdown 报告。
6. Evaluator 在 Clawkit 完成后读取 Ground Truth。

退出门禁：

- 一条命令建立、注入、运行、评分和清理。
- Clawkit 不可读取 Case ID、注入结果或 Ground Truth。
- Ops MCP 不存在 `shell_exec` 或 `ssh_exec(command)`。
- 连续至少 10 次，初始状态与清理结果一致。
- 越权和 Ground Truth 泄漏为 0，报告生成率为 100%。

## 9. OPS-0B：PostgreSQL 锁等待黄金诊断

Fixture：

```text
nginx -> order-api -> PostgreSQL
          ^
          |
          k6 创建订单 / 查询订单 / 金额与重复订单断言
```

只读能力：

- HTTP 业务与健康探测。
- 容器、进程、端口和资源状态。
- 带时间窗口的应用/Nginx 日志。
- PostgreSQL 活动会话、锁、阻塞链和连接统计只读视图。

Case 递进：

1. 明确锁等待。
2. 相同延迟但根因为 CPU 或连接耗尽。
3. 日志保留上一场 Incident 的旧错误。
4. 故障诊断期间自行恢复。
5. 根因不在 Playbook，正确结果为 `INCONCLUSIVE`。

退出门禁：

- Diagnosis 包含支持证据、反证、候选根因和缺失证据。
- 证据有 `observedAt`，能拒绝过期事实。
- 不执行写动作。
- 每个 Case 至少 20 次盲测，报告原始计数与样本量。
- 越权、数据破坏、假修复声明和 Ground Truth 泄漏始终为 0。

## 10. OPS-1：远程只读靶机

进入条件：

- OPS-0A/0B 本地稳定。
- Fixture 可幂等重建和清理。
- D0 的 Docker 和首个 Release 可用。

远端只运行 Fixture、轻量数据库和故障执行器；Clawkit、模型、Incident、Evaluator 和报告留在本地。

任务：

1. 启用 2 GB 优先、1 GB 仅作为受限下限的可重建 Linux 靶机。
2. 建立纯净系统、安全组、日志轮转、容器资源上限和恢复快照。
3. 创建非 root、无 sudo 的 `opsro`。
4. SSH 使用密钥、严格 `known_hosts`、主机 Allowlist、timeout、并发限制和输出截断。
5. 后端从 local fixture 切换到 SSH 模板，不改变模型可见 schema。
6. 使用外部 Probe 触发 Incident。

退出门禁：

- 私钥不进入模型、日志、报告或仓库。
- Agent 无法写文件、重启服务或执行任意命令。
- 工具层和主机权限双重拒绝并审计越权。
- 网络中断、鉴权失败、主机不可达和证据缺失独立分类。
- 远程环境可由代码或快照重建。

## 11. P1-G：写操作前可靠性门禁

任何远程写动作前必须完成：

- 取消信号贯穿 Plan、SubAgent、Provider 和 Tool。
- timeout 后确认远程动作终态，不把客户端 timeout 当作无副作用失败。
- 工具截断保留错误片段、head/tail 和证据引用。
- 失败分类区分可重试、需重新采证、需用户输入和不可恢复。
- Attempt 具备幂等键、次数上限、冷却、预算和目标互斥。
- 进程恢复后识别执行中、结果未知和待验证动作。
- Verification 与修复 Run 的上下文、runId 和证据隔离。

完成标准是每类失败均有测试和可回放事件，不是接口已经创建。

## 12. OPS-2A：审批修复

首批只开放：

```text
restart_service(service_id)
restore_config(config_id)
cleanup_fixture(case_id)
terminate_fixture_session(case_id)
```

实施顺序：

1. 定义 Action Contract、Precheck、Verification 和回滚/补偿。
2. 实现独立 Typed Ops Runner。
3. `opsfix` 与 `opsro` 使用不同身份。
4. 全部写动作先走 ASK。
5. 执行前重新 Precheck，状态变化则取消计划。
6. Verification Run 重新采集业务、性能、日志和 DB 证据。
7. 验证失败执行回滚或补偿；结果未知时停止并升级人工。

退出门禁：

- 审批拒绝后零副作用。
- 每个动作覆盖成功、Precheck 失败、执行失败、结果未知、验证失败、补偿成功和补偿失败。
- 不可逆动作具备更严格的对象标签、业务断言和次数限制。
- Agent 不能绕过 Runner 接触 SSH shell。

## 13. OPS-2B：有限自动修复

自动化按单个 Action/Playbook 提升，不整体切换 AUTO：

- 统计按 Case、Action、Playbook、模型和版本分组。
- 越权和假修复始终为 0。
- Verification 报告生成率为 100%。
- 连续失败、状态漂移、预算耗尽或补偿失败立即降级为 ASK。
- 自动化策略不改变底层工具权限。

首个候选仅为 App Down 的 allowlisted 服务重启；数据库会话终止和 Release 切换不随其自动升级。

## 14. OPS-3：持续运行

进入条件：OPS-2A 稳定，至少一个 L1 动作满足盲测门禁。

能力：

- 外部 Probe、Cron 或告警触发 Discovery。
- Incident 去重、同目标互斥、冷却和预算熔断。
- 暂停、取消和人工接管。
- 版本化 Playbook 与失效规则。
- Ops Benchmark、Baseline 和退化门禁。
- CLI/文件报告稳定后再接 IM 通知与审批。

持续运行首先限于 Fixture，不迁移到真实生产。

## 15. 里程碑产物

| 里程碑 | 必须展示的产物 |
| --- | --- |
| D0 | CI 运行、Docker smoke、Release JAR、Windows ZIP 与 SHA-256 |
| OPS-0A | App Down 一键实验、Evidence Bundle、诊断和确定性得分 |
| OPS-0B | PostgreSQL 锁等待 Flight Recorder、对抗变体和盲测数据 |
| OPS-1 | 远程只读 Incident、SSH 安全审计和重建脚本 |
| OPS-2A | 审批动作、独立 Verification、失败补偿报告 |
| OPS-2B | 单动作自动化策略、降级证据和零越权记录 |
| OPS-3 | 连续运行报告、去重/冷却/预算和版本化 Playbook |

## 16. 暂不优先

- Web Dashboard、Prometheus/Grafana/ELK 全套平台。
- Kubernetes、多主机编排和中心化多租户平台。
- 通用远程 shell、自动 sudo、未知 SQL。
- 自动修改业务代码、自动提 PR 和自动发布。
- 同时集成 ChaosBlade、Chaos Toolkit、Gatus 和 Ansible Runner。
- 未通过独立验证和 Benchmark 的生产自动修复。
