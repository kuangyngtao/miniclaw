# miniclaw-provider — 大模型适配器模块

## 模块定位

抹平不同 LLM API 差异，向上层（engine）暴露统一的生成接口。对应四层架构**核心引擎层**中的"大模型适配器"部分。

## 已完成

### 核心接口

- [LLMProvider.java](src/main/java/com/miniclaw/provider/LLMProvider.java) — 统一生成接口：
  ```java
  Message generate(List<Message> messages, List<ToolDefinition> availableTools);
  ```
  输入上下文历史 + 可用工具列表，返回解析好的 `Message`（content 或 toolCalls）

### 配置

- [LLMConfig.java](src/main/java/com/miniclaw/provider/LLMConfig.java) — 不可变配置 record，Builder 模式：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| apiKey | (必填) | 环境变量 `MINICLAW_API_KEY` |
| baseUrl | `https://api.anthropic.com/v1` | |
| model | `claude-sonnet-4-6` | |
| connectTimeout | 10s | TCP 连接超时 |
| requestTimeout | 60s | 请求总超时（含 LLM 推理） |
| maxRetries | 3 | 指数退避（2s→4s→8s） |

### 韧性保证（接口契约，实现类负责）

| 能力 | 策略 |
|------|------|
| 超时 | 连接 10s + 请求 60s |
| 重试 | 3 次退避，仅对 HTTP 429/5xx/I/O 超时 |
| 不可恢复 | 401/403/4xx 直接返回错误，不重试 |

## 待完成

- [ ] `ClaudeProvider` — Claude API 实现（HTTP 调用 + JSON 解析 + 重试逻辑）
- [ ] OpenAI 兼容扩展点

## 依赖

```
miniclaw-provider
  ├── miniclaw-tools  (schema types: Message, ToolDefinition)
  ├── Jackson          (JSON 序列化)
  ├── JDK HTTP         (java.net.http)
  └── SLF4J
```
