# Configuration

clawkit currently uses DeepSeek through its OpenAI-compatible API. Set the API key only in the process environment:

```powershell
$env:CLAWKIT_API_KEY = "<your-deepseek-key>"
```

Never place credentials in `~/.clawkit/config.yaml`. The loader rejects fields such as `apiKey`, `token`, `secret`, `authorization`, and `appSecret`.

Non-secret settings use this precedence:

1. Explicit CLI option.
2. `CLAWKIT_*` environment variable.
3. `~/.clawkit/config.yaml`.
4. Built-in default.

See [`examples/config.example.yaml`](../examples/config.example.yaml). Use `/config` to view effective values and their sources. The command reports only whether the API key is set; it never prints any part of the key.

The supported endpoint is `https://api.deepseek.com`, the supported protocol is `OPENAI_COMPAT`, and supported model names start with `deepseek-`.
