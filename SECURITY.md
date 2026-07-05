# Security Policy

miniclaw is an experimental local-first coding agent runtime. Security issues are taken seriously, especially issues involving credential exposure, unsafe tool execution, workspace boundary bypass, command execution, MCP tool risk classification, or session and memory leaks.

## Supported Versions

| Version | Supported |
| --- | --- |
| `master` / active development | Yes |
| Old snapshots | Best effort |

## Reporting a Vulnerability

Do not open a public issue if the report includes credentials, tokens, private configuration, logs, exploit payloads, or private user paths.

Preferred reporting path:

1. Use GitHub private vulnerability reporting for this repository.
2. If private reporting is unavailable, open a public issue with only a high-level summary and no secrets.

Please include:

- Affected component, such as `tools`, `mcp`, `engine`, `context`, `provider`, `cli`, or `im`.
- Reproduction steps using fake tokens and sanitized paths.
- Expected impact.
- Whether credential rotation or repository history cleanup is required.

## Sensitive Data Rules

Never commit:

- API keys or model provider tokens.
- Feishu, Weixin, GitHub, or MCP server secrets.
- `.claude/`, `.miniclaw/`, local config files, logs, or runtime sessions.
- Private keys, keystores, `.env` files, or local credential stores.

If a secret is committed:

1. Rotate or revoke the exposed secret immediately.
2. Remove the file from version control.
3. Rewrite repository history if the repository is public.
4. Review and close GitHub secret scanning alerts only after the secret is revoked.

## Security Scope

High-priority areas:

- Tool permission and approval bypass.
- Path traversal outside the workspace root.
- Unsafe shell command execution.
- MCP tool metadata and risk misclassification.
- Invalid or incomplete audit logs.
- Runtime context leaking into persistent session history.
- Secret exposure in config, logs, test output, or examples.

Security fixes should include regression tests when practical.
