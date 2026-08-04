# PRODUCT-3：连续 7 天 Dogfood 执行与验收指南

> 状态：执行方案已确定；开始前必须先通过日志采集自检。
> 范围：验证个人日常使用体验，不扩大 `test-server` 的写权限。

## 1. 目的与边界

这七天验证的是：用户能否顺利连接、得到有效结果、理解调查与审批信息，并在需要时找到下一步。

它**不是**自动修复试验。`test-server` 始终保持只读；不得为了制造故障而停止其服务、修改配置或开放写权限。需要体验审批时，只能使用可丢弃的本地 Fixture，并在日志中标记为 `FIXTURE`。

## 2. 开始前自检

先执行：

```powershell
mvn -q test
```

然后完成一次调查，确认 `~/.clawkit/dogfood/usage.jsonl` 新增一条记录，并满足：

- `decision` 是实际决定，或无审批时为 `NONE`；
- 有审批时 `readingTimeSeconds` 是非负秒数；
- `userActions` 是实际输入次数，不是 `-1`；
- 尚未反馈时，`understood` 与 `fellBackToSsh` 为未知值，不能默认写成 `false`；
- 记录不含 IP、密钥、SSH 参数、完整提问或原始日志。

任一项不满足，先修复日志，不开始 Day 1。

## 3. 每日最小流程

每天至少完成一次真实的只读使用任务。优先使用当天确实需要的信息，不强行重复同一命令。

可选任务包括：

```text
/remote status
检查 order-api 状态
调查 order-api 为什么返回 500
/ops recent
/ops inspect <incidentId>
```

一次任务结束后，用追加式反馈保存理解度、是否退回 SSH 和摩擦说明，不能修改历史 JSON 行：

```text
/ops feedback <incidentId> <yes|no> <yes|no> <HIGH|MEDIUM|LOW> [说明]
```

例如：

```text
/ops feedback inc-test-server-123 yes no MEDIUM 不清楚网关和上游连接的关系
```

四个固定参数依次为 Incident、是否看懂、是否退回 SSH、摩擦优先级；说明可留空。反馈记录通过 `incidentId` 关联原任务，并会对常见密钥格式脱敏。

## 4. 七天建议安排

| 日期 | 必做观察 | 成功标准 |
| --- | --- | --- |
| Day 1 | 启动、连接、首次检查 | 从启动到首次有效结果不超过 5 分钟 |
| Day 2 | 普通状态查看 | 不需要猜命令或阅读无关本地信息 |
| Day 3 | 自然语言调查 | 能看懂状态、证据、结论和下一步 |
| Day 4 | `recent`、`inspect`、`continue` | 能找到上一次调查且状态解释明确 |
| Day 5 | 可丢弃 Fixture 的拒绝/取消审批 | 30 秒内理解“未执行写操作” |
| Day 6 | 可丢弃 Fixture 的批准或失败终态 | 能区分成功、验证失败与结果未知 |
| Day 7 | 汇总回看 | 输出原始日志、指标和摩擦优先级清单 |

Day 5、Day 6 的 Fixture 体验只用于验证审批可理解性，不能计为 `test-server` 的真实写操作。当前 Fixture 是自动化测试夹具；在提供可交互的 Fixture 入口前，这两天只能记录真实只读任务，不能伪造审批阅读时间。若某天没有自然发生的审批，不得人为破坏真实服务器。

## 5. 日志字段与填写规则

任务记录至少包括：

```json
{
  "date": "2026-08-04",
  "environment": "REMOTE_READONLY",
  "target": "test-server",
  "taskType": "INVESTIGATE",
  "userActions": 2,
  "incidentId": "inc-...",
  "readingTimeSeconds": 23,
  "decision": "REJECT",
  "finalStatus": "REJECTED",
  "understood": null,
  "fellBackToSsh": null
}
```

- `userActions`：用户提交的命令、审批决定和反馈输入次数；
- `readingTimeSeconds`：审批框出现到用户决定的秒数；无审批时为 `null`；
- `understood`：仅在用户明确反馈后填写 `true` 或 `false`；
- `fellBackToSsh`：仅在用户明确反馈后填写 `true` 或 `false`；
- `environment`：真实只读任务使用 `REMOTE_READONLY`，可丢弃夹具使用 `FIXTURE`。

## 6. Day 7 评审

汇总并保留以下原始证据：

- 连续 7 个自然日的使用记录；
- 每天至少一个真实任务；
- 审批任务的阅读时间与决定；
- 是否理解、是否退回 SSH；
- 所有摩擦描述，按出现次数和影响分为高、中、低优先级。

只修复重复出现或严重阻塞的摩擦。禁止因为 dogfood 而扩大写权限、增加自动修复或进入 Shadow。

## 7. PRODUCT-3 最终验收门槛

只有同时满足下列条件，才可结束 PRODUCT-3：

- 7 天原始日志完整，且没有伪造或补写的“真实使用”；
- 审批可理解性有真实阅读时间数据，目标为 30 秒内；
- 首次有效结果、用户动作数和退回 SSH 情况可汇总；
- 已形成摩擦优先级清单，并只处理有证据的高频问题；
- 不存在未确认的写操作、结果未知误报成功或绕过独立验证；
- 评审后才决定是否进入 OPS-3A（Observe-only 持续检查）。
