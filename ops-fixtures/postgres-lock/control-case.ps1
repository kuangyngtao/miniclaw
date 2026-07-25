param(
    [ValidateSet('normal','lock','cpu','connections','stale-log','self-recovered','unknown')]
    [string]$Mode = 'normal',
    [string]$Project = 'clawkit-ops-0b'
)
$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.yaml'
docker compose -f $compose -p $Project exec -T order-api wget -qO- `
  --header='X-Control-Token: fixture-control-only' `
  "http://localhost:8080/internal/control?mode=$Mode"
if ($LASTEXITCODE -ne 0) { throw "fault control failed: $Mode" }
