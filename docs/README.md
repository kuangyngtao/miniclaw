# Clawkit 文档导航

这里是 `docs/` 的统一入口。文档按“它回答什么问题”分类，而不是按创建时间排列。

如果不同文档描述不一致，以仓库代码和测试为最终事实，并按下面的权威顺序判断：

1. 项目边界和协作约束：[`CLAUDE.md`](../CLAUDE.md)
2. 稳定工程规则：[`DESIGN.md`](../DESIGN.md)
3. 当前完成状态和下一步：[`TODO.md`](../TODO.md)
4. 用户可实际使用的能力：[`README.md`](../README.md)
5. 长期产品方向：[`product-direction.md`](product-direction.md)

阶段方案和历史报告不能覆盖上述文档中的当前事实。

## 第一次阅读

根据目的选择一条路径即可，不需要从头读完所有文档。

### 想使用 Clawkit

1. [`README.md`](../README.md)：安装、配置、启动和 CLI 使用
2. [`configuration.md`](configuration.md)：配置项和优先级
3. [`mcp.md`](mcp.md)：MCP 配置与凭据引用
4. [`runtime.md`](runtime.md)：运行时目录和持久化文件

### 想理解产品

1. [`product-direction.md`](product-direction.md)：目标用户、核心旅程和产品原则
2. [`ops-loop.md`](ops-loop.md)：查看、调查、审批处置和独立验证如何形成闭环
3. [`TODO.md`](../TODO.md)：当前实现到了哪里、下一步做什么

### 想参与开发

1. [`CLAUDE.md`](../CLAUDE.md)：模块边界和协作强约束
2. [`DESIGN.md`](../DESIGN.md)：架构、契约、安全和测试规则
3. [`development.md`](development.md)：构建、测试和发布
4. [`TODO.md`](../TODO.md)：当前任务与验收标准

### 想为秋招深度学习

1. [`project-deep-dive-guide.md`](project-deep-dive-guide.md)：从 Runtime 到 Ops Loop 的完整项目讲解
2. [`p1-g-design.md`](p1-g-design.md)：副作用可靠性和状态机设计
3. [`ops-loop.md`](ops-loop.md)：远程调查与审批修复闭环
4. [`ai-coding-self-improvement.md`](ai-coding-self-improvement.md)：如何用 AI 协作但仍真正掌握项目

## 当前维护文档

这些文档描述当前产品或稳定工程约束，代码变化后应同步维护。

| 文档 | 用途 |
| --- | --- |
| [`product-direction.md`](product-direction.md) | 产品定位、用户旅程、体验原则和长期路线 |
| [`ops-loop.md`](ops-loop.md) | Ops Loop 架构、安全边界和演进方向 |
| [`configuration.md`](configuration.md) | 非敏感配置和配置优先级 |
| [`runtime.md`](runtime.md) | 运行时目录、状态和持久化约定 |
| [`mcp.md`](mcp.md) | MCP 配置和凭据引用规则 |
| [`development.md`](development.md) | 构建、测试、打包和发布流程 |
| [`project-deep-dive-guide.md`](project-deep-dive-guide.md) | 随代码演进维护的学习材料和项目全览 |

## 定版设计参考

这些文档解释关键设计为什么这样做。它们不是当前任务清单；实施状态仍以 [`TODO.md`](../TODO.md) 为准。

| 文档 | 主题 | 状态 |
| --- | --- | --- |
| [`p1-g-design.md`](p1-g-design.md) | 写操作前强制门禁、Attempt 状态机和恢复 | 已实现的定版设计 |
| [`p1-a-design.md`](p1-a-design.md) | 失败分类、语义保留和 task-aware compact | 定版方案，按 TODO 判断实施范围 |
| [`p2-design.md`](p2-design.md) | 成本与效率记录、路由、压缩和缓存 | 后续设计参考，非当前承诺 |

## 阶段实施记录

这些文档保留需求背景、技术取舍、反方评审和验收合同，适合追溯历史。不要从标题中的“待实现”或旧日期推断当前状态。

| 文档 | 阶段 | 现在如何使用 |
| --- | --- | --- |
| [`remote-0-implementation-plan.md`](remote-0-implementation-plan.md) | 最小远程连接 | 已封板，作为连接合同和历史决策参考 |
| [`product-1-implementation-plan.md`](product-1-implementation-plan.md) | 服务器接入体验 | 已实现的方案记录；当前产品状态看 TODO |
| [`ops-mvp1-secure-remote-discovery-design.md`](ops-mvp1-secure-remote-discovery-design.md) | 远端只读 Discovery | 实施前定版设计，现作为安全设计参考 |
| [`ops-mvp1-completion-execution-plan.md`](ops-mvp1-completion-execution-plan.md) | MVP-1 修复与验收 | 已完成的执行合同 |
| [`ops-mvp2-business-fixture-report-feishu-plan.md`](ops-mvp2-business-fixture-report-feishu-plan.md) | 业务 Fixture、报告与通知 | 阶段记录；未完成项以 TODO 为准 |
| [`project-highlights-and-ops-loop-roadmap.md`](project-highlights-and-ops-loop-roadmap.md) | Runtime 到 Ops 的演进 | 2026-07-17 历史快照 |

## 文档状态词

- **当前维护**：持续反映现有代码或产品承诺。
- **定版设计**：记录稳定的设计理由和契约，通常不随每个任务更新。
- **阶段记录**：保留某一阶段的方案与验收上下文，不是当前状态源。
- **历史快照**：只用于理解演进过程，不用于判断现在能否使用。

## 维护规则

- 新功能先更新 [`TODO.md`](../TODO.md) 的状态；只有产品方向或稳定契约变化时，才更新长期文档。
- 用户可见命令、配置或限制变化时，同步更新 [`README.md`](../README.md) 和相关使用指南。
- 不再为单次修复报告、Agent 执行提示词或测试轮次单独新增长期文档。
- 阶段方案完成后保留其设计价值，并在文首标明状态；不要让旧方案继续冒充当前任务。
- 新增长文档时，把它登记在本页，并明确属于“当前维护、定版设计、阶段记录、历史快照”中的哪一类。
