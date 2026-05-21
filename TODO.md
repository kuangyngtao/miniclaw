# miniclaw TODO

> `[ ]` 待做 `[~]` 进行中 `[x]` 已完成

---

## 已完成

V1 ~ V3.8d 全部完成，测试 229/229 通过。

---

## 待实现

### 引擎层
- **[ ] `/btw` 后台并行任务** (V3.2) — 独立 AgentEngine 虚拟线程并发，Plan: `.claude/plans/btw-fizzy-hammock.md`
- **[x] 工作内存 Working Memory** (V3.6) — `remember(key, value)` 工具 + 每轮注入 + clearSession 清空
- **[x] 子目标跟踪** (V3.7) — todo 进展检测 + 3轮无进展警告 + allTodosCompleted 判定
- **[x] L3 自动记忆提取** (V3.8a) — 提示词驱动 + 静默 LLM + 条件触发（token>60% + 冷却>=5轮 + 新增>=10条）
- **[x] 会话自动保存** (V3.8c) — clearSession 钩子 + [auto] 命名

### 上下文层
- **[ ] Feishu CLI Skill 适配** (V3.4b) — 24 个预置 Skill 适配，底层通过 ProcessBuilder 调用 `lark-cli`
- **[x] 会话检索 BM25 升级** (V3.8b) — SessionService.search() 从 contains() 子串匹配升级为 BM25 关键词计分
- **[x] 启动注入历史会话** (V3.8d) — 新会话首条消息 BM25 搜索 → 前 3 条摘要注入 system prompt

### 工具层
- **[ ] Git 专用工具** — git status/diff/log 封装
- **[ ] BashTool 增强** — 后台守护、可配置超时、跨平台 shell 检测
- **[ ] WriteTool 覆盖确认** — 目标文件已存在时交互确认
- **[ ] 行为约束引擎** (V3.8) — CLAUDE.md 声明规则 → 拦截器执行

### 观测层
- **[ ] 结构化指标采集** (V3.9) — token P50/P90、工具成功率 → `~/.miniclaw/metrics.jsonl`
- **[ ] 输出自动验收** (V3.10) — 检测构建系统，提示验证

### Provider 层
- **[ ] 多模型路由** — Anthropic 原生 API 支持
- **[ ] GraalVM native-image** (V3.5) — 启动 < 0.1s

### 远期
- **[ ] 任务级回滚** — 写入前自动保存 `<file>.miniclaw.bak`
- **[ ] 自适应检索增强 Memory Paging** — 长历史分块灌向量库，按需召回
- **[ ] 本地嵌入模型语义搜索**
- **[ ] 评测基准** — 20 个编程任务测量完成率/轮次/Token

---

## 技术债

| 项 | 位置 | 状态 |
|----|------|------|
| application.yaml 闲置 | `miniclaw-cli/src/main/resources/` | 待修 |
