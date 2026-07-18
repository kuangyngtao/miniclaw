# Java demo path

From this directory, start clawkit with `--root .` and try:

1. `/plan`, then ask: `Explain Calculator.java and list the tests you would add.`
2. `/ask`, then ask: `Add a subtract method and tests, then run mvn test.` Approve the write and command prompts.
3. Review the diff with: `Show the git diff and summarize the verification evidence.`
4. `/auto` is available for trusted disposable workspaces, but is not required for this demo.

The baseline is deterministic:

```powershell
mvn -B -ntp test
```

No real API key or remote MCP configuration is stored in this directory.
