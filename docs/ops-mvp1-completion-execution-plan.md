# OPS MVP-1 修复与完成实施合同

> 日期：2026-07-26  
> 执行主体：Claude Code Agent（内部模型为 DeepSeek）  
> 目标：完成单靶机、手动触发、远程只读 Discovery MVP，并关闭当前虚假完成项  
> 范围外：多主机调度、自动修复、飞书通知、报告美化、通用远程 Shell、生产数据复制

## 0. 给实现 Agent 的总指令

严格按本文执行。一次只完成一个 PR，不得把 PR 合并，不得跳过测试，不得用
`@Disabled`、TODO 注释、空实现、仅新增类型或仅打印 warning 作为“完成”。

每个 PR 开始前：

1. 完整阅读 `CLAUDE.md`、`SECURITY.md`、`DESIGN.md`、`TODO.md`、
   `docs/ops-loop.md`、`docs/ops-mvp1-secure-remote-discovery-design.md` 和本文。
2. 执行 `git status --short`，记录基线；不得覆盖不属于本 PR 的变更。
3. 只修改该 PR 的允许范围。发现必须越界时停止并报告，不自行扩大范围。
4. 先写或启用失败测试，再修改实现。

每个 PR 结束时必须交付：

- 修改文件列表及每个文件的用途。
- 实际执行的测试命令、通过数、失败数、跳过数。
- `git diff --check` 结果。
- 未解决风险。
- 回滚方法。
- 新开一个只读 Claude Code Agent + DeepSeek 会话进行反向评审，固定输出：
  `Blocking / Missing Tests / Scope Cuts / Conclusion`。

存在以下任一情况时不得宣称完成：

- 本文指定的合同测试仍有 `@Disabled`。
- 测试显示 `BUILD SUCCESS`，但目标测试被跳过。
- 新类型未被生产入口引用。
- attestation 不匹配只记录 warning。
- 单项采证失败导致成功 Evidence 丢失。
- 仍可通过生产配置启用 `SshCommandExecutor`。
- 远端 smoke 只证明 JSON-RPC 可调用，没有跑本地 Discovery 入口。
- 服务器当前没有 Fixture，却报告服务 running/healthy。

## 1. 已核实基线

以下是 2026-07-26 的代码和远端事实，实现 Agent 必须先复现：

### 已成立

- 提交 `4c2679d` 包含 41 个文件、`+4481/-203`。
- `ops-mcp` 66 项测试无失败，但跳过 8 项。
- `ops-loop` 70 项测试无失败，但跳过 15 项。
- 远端 gateway、launcher、JAR 与本地文件哈希一致。
- `opsro` 密码锁定、不在 docker 组、不能读取 Docker socket。
- `opsro` 只有固定 launcher 的受限 sudo grant，没有通用 sudo。
- forced-command 能忽略客户端提供的任意命令并启动 MCP。
- MCP initialize/list/call 可用，服务端返回完整 attestation。
- 远端 `PermitUserEnvironment no` 已确认生效。

### 尚未成立

- `GatewaySecurityTest` 8 项被禁用。
- `TransportAndProfileSecurityTest` 8 项被禁用。
- `PartialFailureEvidenceTest` 7 项被禁用。
- `McpClient.initialize()` 不返回 serverInfo，协议不匹配只 warning。
- `RemoteOpsSession` 没有校验 probeVersion/capabilityProfile/protocolVersion。
- `RemoteOpsSession` 未被生产入口创建。
- `SshConnectionConfig.requestTimeout/maxOutputBytes` 未实际生效。
- `EvidenceSpec` 没有固定工具参数，不能独立驱动调用。
- `REMOTE_APP_DOWN_V1` 包含 `container_resources`，但远端
  `APP_DOWN_V1` 不暴露该工具。
- `McpEvidenceCollector` 仍硬编码且 fail-fast。
- 旧 `CLAWKIT_OPS_SSH_HOST -> SshCommandExecutor` 生产路径仍有效。
- `SshCommandExecutor` 只有 Javadoc `@deprecated`，没有真正退场。
- `verify-opsro.sh` 因把 `!` 当作命令参数而提前退出。
- SFTP 请求不会获得文件权限，但会进入 MCP 并挂起，未快速拒绝。
- 当前远端 Fixture 容器未运行。

## 2. 最终 Definition of Done

只有以下条件全部满足，才允许把 OPS MVP-1 标记为完成：

1. 用户可从本地执行一次显式的单靶机 Discovery 命令。
2. 一次 Discovery 只创建一个 `ssh -T` 子进程，并在全部采证项间复用。
3. SSH 只进入 forced-command MCP；不存在远端 Shell、自由命令、自由 SQL、
   Docker socket 或 Docker TCP API。
4. 客户端严格校验 protocolVersion、probeVersion、capabilityProfile、
   toolSetHash 和工具 annotations，任何不匹配都在采证前失败。
5. Discovery 由版本化 Profile 驱动，参数固定且不可由模型生成。
6. 每个 EvidenceSpec 预分配 Evidence ID，单项失败也产生结构化失败 Evidence；
   后续项目继续采集。
7. required Evidence 不满足时标记 `INCOMPLETE`，不得调用运行时 DeepSeek Diagnosis。
8. Bundle 冻结后不再追加，所有成功 Evidence 包含 validUntil。
9. timeout、取消、EOF 和异常路径最终都关闭 SSH/MCP 进程。
10. 旧 `SshCommandExecutor` 生产入口和 PASSWORD/sshpass 配置被删除或硬拒绝。
11. 三个合同测试文件不存在 `@Disabled`，目标模块测试 0 failure、0 error、
    0 skipped。
12. 远端正常态和 App Down 各跑一次完整 E2E，输出可审计的 Discovery 结果。
13. `verify-opsro.sh` 完整执行结束并返回 0。
14. SFTP、SCP、PTY、forwarding 和任意远端命令在限定时间内明确失败，不挂起。
15. `TODO.md` 只根据上述证据更新，不能根据文件存在更新为 `[x]`。

## 3. PR-M1：修复安全脚本与真实护栏测试

### 目标

让 P0 安全边界可重复安装、可机械验证，并把 PR-1 的 8 个禁用测试变成真实测试。

### 允许修改

- `ops-fixtures/remote/**`
- `extensions/clawkit-ops-mcp/src/test/**`
- 安全设计文档中与 SSH_ORIGINAL_COMMAND 拒绝策略直接相关的段落

### 必须实现

1. 重写 `verify-opsro.sh`：
   - 不得把 shell 关键字 `!` 作为 `check` 的命令参数。
   - 增加 `check_not` 或显式检查函数。
   - “无 sudo”改成“无通用 sudo；只允许固定 launcher”。
   - 校验 sudoers 仅包含：
     `/usr/local/sbin/clawkit-ops-mcp-stdio`。
   - 使用 `sshd -T -C user=opsro,host=localhost,addr=127.0.0.1`
     校验最终生效配置，而不是只 grep 文本。
   - 对每项检查累计失败，完整运行到 Summary，不得首错退出。

2. 修复 `setup-opsro.sh`：
   - `PermitUserEnvironment no` 必须位于全局上下文，不能写入不支持它的
     `Match User` 块。
   - 安装前备份；生成候选配置后先 `sshd -t`，成功才 reload。
   - 重复执行不得重复 authorized_keys、sudoers 或 Match 条目。
   - 失败必须恢复原配置并再次执行 `sshd -t`。

3. 修改 gateway：
   - 不得 `eval`、解析或执行 `SSH_ORIGINAL_COMMAND`。
   - 允许只检查其是否为空；非空时无 stdout、快速返回非零。
   - 正常 `ssh -T` 没有 remote command 时才启动 MCP。
   - 这样 shell、SFTP、SCP 都明确失败，而不是被导入 MCP 后挂起。

4. 将 `GatewaySecurityTest` 的 8 个 `@Disabled` 全部实现并启用。
   - Windows 可执行静态合同测试。
   - Linux 专属进程行为使用 CI Linux job 或临时 Fixture 验证。
   - 不允许仅删除 `@Disabled` 而保留空断言。

5. `revoke-opsro.sh` 只在临时 Fixture 测试，不在当前真实服务器执行。

### 本地验收

```text
mvn -B -ntp -pl extensions/clawkit-ops-mcp -am test
rg -n "@Disabled" extensions/clawkit-ops-mcp/src/test/java/com/clawkit/ops/mcp/GatewaySecurityTest.java
git diff --check
```

第二条必须无输出。脚本必须通过 Linux `bash -n`。

### 远端验收

真实服务器操作前保留 root/云控制台第二通道，并展示配置 diff 和回滚命令。

```text
sshd -t
bash verify-opsro.sh
```

使用客户端超时分别验证：

- `ssh -T opsro@target` 加 MCP initialize：成功。
- `ssh -T opsro@target bash`：快速失败，无 MCP JSON。
- SFTP/SCP：快速失败，不挂起。
- PTY 和 forwarding：失败。

### 停止条件

- `sshd -t` 失败。
- 第二管理通道不可用。
- 需要把 opsro 加回 docker 组。
- 需要开放任意 sudo 或 Shell。

## 4. PR-M2：严格 MCP Handshake 与 SSH 生命周期

### 目标

让 `RemoteOpsSession` 成为真实、可测试、fail-closed 的单连接生命周期组件。

### 允许修改

- `clawkit-tools/src/main/java/com/clawkit/tools/mcp/**`
- `clawkit-tools/src/test/**`
- `extensions/clawkit-ops-loop/src/main/java/**Remote**`
- `extensions/clawkit-ops-loop/src/test/**`

### 必须实现

1. 新增通用 `McpInitializeResult`：
   - protocolVersion
   - serverName/serverVersion
   - 原始且有界的 serverInfo 扩展字段

2. `McpClient.initialize()` 返回 `McpInitializeResult`。
   - 保持普通调用方可以忽略返回值。
   - 通用 MCP 层不硬编码 Clawkit profile。
   - 提供接受 `ExecutionControl` 的 overload。

3. `RemoteOpsSession.start()` 必须依次严格校验：
   - `protocolVersion == 2024-11-05`
   - `serverName == clawkit-ops-mcp`
   - probeVersion 等于 `RemoteTargetDescriptor.expectedProbeVersion`
   - capabilityProfile 等于 descriptor 的 capabilityProfile
   - initialize 返回的 toolSetHash 等于本地 pinned hash
   - `tools/list` 重新计算的 hash也等于 pinned hash
   - 每个工具必须 readOnly=true、destructive=false、openWorld=false

4. 任一不匹配：
   - 状态进入 FAILED。
   - 保存 firstError。
   - 立即关闭 transport。
   - 不允许调用任何工具。

5. 为 `RemoteOpsSession` 注入 `McpTransportFactory`，测试不得启动真实 SSH。

6. 把 `requestTimeout` 用于 initialize/list/call 的 `ExecutionControl` deadline。

7. `maxOutputBytes` 必须在 transport 读取层生效：
   - 单行和 stderr ring 都有界。
   - 超限分类为协议错误并关闭 session。

8. 状态机语义：
   - 重复 `start()` 不创建第二进程。
   - `close()` 幂等。
   - FAILED 后 close 最终为 CLOSED，但 firstError 不丢失。
   - EOF、timeout、取消后 pending request 全部结束。

9. 启用 `TransportAndProfileSecurityTest` 的 8 个禁用测试，并增加：
   - initialize toolSetHash 与 list hash不一致。
   - unsafe annotation。
   - FAILED 时 transport 已关闭。

### 本地验收

```text
mvn -B -ntp -pl clawkit-tools,extensions/clawkit-ops-loop -am test
rg -n "@Disabled" extensions/clawkit-ops-loop/src/test/java/com/clawkit/ops/loop/TransportAndProfileSecurityTest.java
git diff --check
```

第二条必须无输出。

## 5. PR-M3：Profile 驱动的 Remote Discovery

### 目标

实现单靶机、单 Session、手动触发、部分失败可继续的确定性 Discovery。

### 允许修改

- `extensions/clawkit-ops-loop/src/main/**`
- `extensions/clawkit-ops-loop/src/test/**`
- 必要的启动/使用文档

### 必须实现

1. 扩展 `EvidenceSpec`，增加不可变固定参数：

```text
Map<String, Object> arguments
```

只允许字符串、整数、布尔和枚举化值；构造时深复制。参数来自版本化 Profile，
不得来自 DeepSeek、Case manifest 或 Ground Truth。

2. 修正 `REMOTE_APP_DOWN_V1`：
   - 只能调用远端 `APP_DOWN_V1` 暴露的5类工具：
     service_status、container_status、ports、http_probe、logs。
   - 删除当前不可用的 `container_resources`。
   - 建议8项：两个 service、两个 container、一个 port、一个 HTTP、两份 logs。
   - 前6项 required，两份 logs optional。
   - 固定参数使用：
     `service_status(gateway)`、`service_status(demo-api)`、
     `container_status(gateway)`、`container_status(demo-api)`、
     `ports(gateway,80)`、`http_probe(gateway-health)`、
     `logs(gateway,300,100)`、`logs(demo-api,300,100)`。

3. 保留 `REMOTE_POSTGRES_DIAGNOSIS_V1`，但增加静态一致性测试：
   Profile 中的每个工具都必须属于 `POSTGRES_DIAGNOSIS_V1` tool set。

4. 新增以下核心类型：

```text
RemoteDiscoveryCoordinator
DiscoveryRequest
DiscoveryResult
CollectionOutcome
DiscoveryStatus = COMPLETE | INCOMPLETE | TRANSPORT_FAILED
```

5. Coordinator 执行规则：
   - 启动前为全部 spec 预分配稳定 Evidence ID。
   - 按 sequenceNumber 串行执行。
   - 每项使用自己的 timeout。
   - 每项结束立即写 recorder。
   - 工具错误生成 `COLLECTION_FAILED` Evidence，继续下一项。
   - transport EOF 后剩余项全部生成 transport failure Evidence，不再发请求。
   - 成功 Evidence 的 `validUntil = observedAt + freshnessTtl`。
   - 所有项完成后一次性构造不可变 EvidenceBundle。
   - required 成功数不足时状态为 INCOMPLETE。

6. 外部 HTTP：
   - MVP 优先使用远端 allowlisted `http_probe`。
   - 不额外建设任意 URL fetcher。

7. 不调用运行时 DeepSeek；PR-M3 只完成确定性 Discovery。

8. 启用 `PartialFailureEvidenceTest` 的7个禁用测试，覆盖：
   - 单项失败。
   - 多项失败。
   - transport中断。
   - required缺失。
   - optional缺失。
   - TTL/过期。
   - Bundle冻结。

### 本地验收

```text
mvn -B -ntp -pl extensions/clawkit-ops-loop -am test
rg -n "@Disabled" extensions/clawkit-ops-loop/src/test/java/com/clawkit/ops/loop/PartialFailureEvidenceTest.java
git diff --check
```

第二条必须无输出。

## 6. PR-M4：手动触发入口与旧路径退场

### 目标

把 M2/M3 接到用户可以运行的入口，并保证远程生产路径只有 forced-command MCP。

### 允许修改

- `extensions/clawkit-ops-loop/**`
- `extensions/clawkit-ops-mcp/**`
- `README.md`
- `docs/ops-loop.md`
- 示例配置，但不得包含真实 host、用户名和 key 路径

### 必须实现

1. 新增 `RemoteDiscoveryMain`，接受：

```text
discover --target <logical-target-id> --profile REMOTE_APP_DOWN_V1 --output <dir>
```

连接参数只从本地环境或用户配置读取：

```text
CLAWKIT_REMOTE_OPS_HOST
CLAWKIT_REMOTE_OPS_PORT
CLAWKIT_REMOTE_OPS_USER
CLAWKIT_REMOTE_OPS_IDENTITY_FILE
CLAWKIT_REMOTE_OPS_KNOWN_HOSTS
CLAWKIT_REMOTE_OPS_EXPECTED_PROFILE
CLAWKIT_REMOTE_OPS_EXPECTED_PROBE_VERSION
CLAWKIT_REMOTE_OPS_EXPECTED_TOOLSET_HASH
```

不得把连接配置写入 Incident、Evidence、报告或模型上下文。

2. 手动入口流程固定：

```text
validate config
  -> create Incident/runId
  -> create one RemoteOpsSession
  -> strict start/attestation
  -> RemoteDiscoveryCoordinator.collect
  -> atomically persist discovery.json
  -> finally close session
  -> print status/runId/output path
```

3. 退出码：
   - 0：COMPLETE
   - 2：INCOMPLETE
   - 3：SSH/MCP/attestation失败
   - 4：本地配置错误
   - 5：结果持久化失败

4. 删除生产入口中的：
   - `CLAWKIT_OPS_SSH_HOST -> SshCommandExecutor`
   - PASSWORD/sshpass 远程配置

旧变量出现时必须打印迁移错误并退出，不能继续执行。

5. 删除 `SshCommandExecutor`、`SshTargetConfig` 及其只服务旧路径的测试；
若存在确认过的本地兼容消费者，先列出代码引用证据并停止，不得自行保留生产后门。

6. 添加 composition test，证明：
   - 手动入口确实创建 RemoteOpsSession。
   - 一次 Discovery 只创建一个 transport。
   - 不存在远端 shell command。
   - finally 后 transport关闭。

### 本地验收

```text
mvn -B -ntp -pl extensions/clawkit-ops-mcp,extensions/clawkit-ops-loop -am test
rg -n "new SshCommandExecutor|CLAWKIT_OPS_SSH_HOST|sshpass" extensions/clawkit-ops-mcp/src/main extensions/clawkit-ops-loop/src/main
rg -n "new RemoteOpsSession|RemoteDiscoveryCoordinator" extensions/clawkit-ops-loop/src/main
git diff --check
```

第一个 `rg` 必须无生产代码输出；第二个必须显示真实入口接线。

## 7. PR-M5：运行时 DeepSeek Diagnosis Gate

### 目标

在完整 Evidence Bundle 之后调用产品运行时 DeepSeek；模型不参与采证。

### 必须实现

1. 只有 `DiscoveryStatus.COMPLETE` 且 required Evidence 全部当前有效时才调用 Provider。
2. 模型只获得冻结、脱敏的 Evidence Bundle和逻辑 targetId。
3. 唯一工具为 `submit_diagnosis`。
4. 校验所有 evidence ID、状态、TTL、rootCauseCode 和 claimedResolved=false。
5. 空 content、截断、非法 JSON、虚构 ID：
   - 最多重试一次。
   - 第二次仍失败返回 INCONCLUSIVE。
   - 不改变原始 Evidence。
6. 测试必须使用 fake Provider；真实 API smoke 单独运行且不进入默认单测。

### 验收

- COMPLETE 会调用一次 Provider。
- INCOMPLETE/TRANSPORT_FAILED 调用次数为0。
- 虚构或过期 Evidence不能形成成功 Diagnosis。
- Provider失败不把 Discovery改成失败，也不能声称已修复。

## 8. PR-M6：远端单靶机 E2E 与最终收口

### 前置条件

- PR-M1 至 PR-M5 全部通过。
- root/云控制台第二通道可用。
- 远端只部署合成 Fixture。
- 不执行 `revoke-opsro.sh`。

### E2E 场景

#### 场景 A：正常态

1. 由 root/fixture-admin 启动 App Down Fixture。
2. 确认容器和 HTTP 健康。
3. 从本地运行 `RemoteDiscoveryMain`。
4. 验证：
   - status=COMPLETE
   - 一个 SSH 进程
   - required Evidence齐全且未过期
   - 无连接信息和秘密进入输出

#### 场景 B：App Down

1. 由 root/fixture-admin 停止 `demo-api`，诊断 Agent不参与注入。
2. 手动触发同一 Discovery。
3. 验证 Evidence 包含：
   - service/container异常
   - gateway端口或 HTTP退化
   - 有界日志
   - 其他成功 Evidence未因单项失败丢失
4. 若启用 PR-M5，Diagnosis必须引用真实 Evidence ID。
5. 由 root/fixture-admin 恢复 Fixture并执行确定性健康检查。

#### 场景 C：连接故障

至少分别验证：

- 错误 host key。
- 错误 key/auth。
- 不匹配 profile/probe/hash。
- MCP中途退出。

所有场景必须分类、关闭子进程并返回非零，不得误报业务根因。

### 最终门禁

```text
mvn -B -ntp clean verify
git diff --check
git status --short
```

额外机械检查：

- 本文指定的三个测试文件 `@Disabled` 数量为0。
- 目标测试 failure=0、error=0、skipped=0。
- 旧 SSH executor 生产引用为0。
- 远端 `verify-opsro.sh` 返回0。
- 正常态和 App Down 的 E2E 产物均存在。
- E2E结束后无本地 SSH/MCP残留进程。
- E2E结束后 Fixture恢复正常，无故障残留。

只有门禁全部满足后，才更新：

- `TODO.md`
- `docs/ops-loop.md`
- `README.md`

状态文字必须区分：

- 已实现
- 已自动测试
- 已远端 smoke
- 已完整 E2E

## 9. 反向评审清单

每个 PR 的只读评审会话必须逐项攻击以下假设：

### Blocking

- 是否仍能执行任意远端命令或访问 Docker socket？
- 是否能通过环境变量恢复旧 SSH executor？
- 是否存在 attestation 只 warning 不拒绝？
- 是否存在新类没有生产调用方？
- 是否用 `@Disabled` 隐藏失败？
- 是否部分失败后丢失已采证据？
- 是否模型可以选择工具或参数？
- 是否任何秘密进入输出、日志、测试 fixture 或 diff？

### Missing Tests

- success、empty、malformed、oversized。
- timeout、cancel、EOF、重复 start/close。
- profile/probe/hash/annotations mismatch。
- required/optional失败。
- 全部失败。
- SFTP/SCP/remote command 快速拒绝。
- setup幂等、配置回滚、verify完整运行。

### Scope Cuts

如实现 Agent提出以下内容，直接删除：

- ControlMaster。
- 并行采证。
- 多主机池。
- HTTPS/mTLS Agent。
- 模型驱动 Discovery。
- 飞书通知。
- 自动修复。
- 任意 URL、命令或 SQL。

## 10. 最终交付报告模板

```text
OPS MVP-1 最终状态：

1. Git
   commit:
   changed files:
   pushed:

2. Local tests
   command:
   tests:
   failures:
   errors:
   skipped:

3. Security
   opsro groups:
   docker socket:
   sudo allowlist:
   sshd -t:
   verify script:
   shell/sftp/scp/forwarding:

4. Discovery
   manual command:
   targetId:
   profile:
   session count:
   discovery status:
   evidence complete/failed:
   output path:

5. E2E
   normal:
   app down:
   connection failure:
   cleanup:

6. Diagnosis
   provider called:
   evidence gate:
   invalid output behavior:

7. Remaining risks
   only risks outside MVP-1 may remain.
```
