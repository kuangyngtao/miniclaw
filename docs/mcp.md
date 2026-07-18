# MCP configuration

MCP configuration is loaded from `%USERPROFILE%\.clawkit\mcp.json` and then `.clawkit\mcp.json` in the workspace. Project entries override user entries with the same name.

Use environment references for credentials:

```json
"env": { "TOKEN": "${env:TOKEN}" }
```

Do not place actual tokens in the file. Project MCP servers are untrusted until the user approves startup. In non-interactive environments, enabled servers that require approval cause a readable startup error rather than being started implicitly.

See [`examples/mcp.example.json`](../examples/mcp.example.json).
