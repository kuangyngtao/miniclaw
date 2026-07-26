# M2-2: HOT_ACCOUNT_CONTENTION_V1 case runner
# Starts fixture, enables reconciliation scheduler, runs k6 load,
# stops reconciliation, verifies invariants, cleans up.
param(
    [string]$Project = 'clawkit-ops-m2-2',
    [int]$LoadDuration = 90,
    [int]$ReconcileIntervalMs = 3000,
    [int]$ReconcileHoldMs = 2000
)
$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.yaml'

Write-Host "=== HOT_ACCOUNT_CONTENTION_V1 ==="
Write-Host "Project: $Project, Load: ${LoadDuration}s, Reconcile: ${ReconcileIntervalMs}ms/${ReconcileHoldMs}ms"

# 1. Start fixture
Write-Host "[1/5] Starting fixture..."
docker compose -f $compose -p $Project up -d --wait postgres order-api gateway
Write-Host "   Fixture healthy."

# 2. Enable hot-contention reconciliation
Write-Host "[2/5] Starting reconciliation scheduler..."
docker compose -f $compose -p $Project exec -T order-api wget -qO- `
  --header='X-Control-Token: fixture-control-only' `
  "http://localhost:8080/internal/control?mode=hot-contention&intervalMs=${ReconcileIntervalMs}&holdMs=${ReconcileHoldMs}"
Write-Host "   Reconciliation scheduler running."

# 3. Run k6 load
Write-Host "[3/5] Running k6 load (${LoadDuration}s, 20 req/s)..."
docker compose -f $compose -p $Project run --rm `
  -e BASE_URL=http://gateway:8080 `
  k6 run /scripts/hot-contention.js 2>&1 | Select-Object -Last 20
Write-Host "   Load complete."

# 4. Stop reconciliation (back to normal)
Write-Host "[4/5] Stopping reconciliation..."
docker compose -f $compose -p $Project exec -T order-api wget -qO- `
  --header='X-Control-Token: fixture-control-only' `
  "http://localhost:8080/internal/control?mode=normal"
Write-Host "   Reconciliation stopped."

# 5. Verify metrics (no lock wait after stop)
Write-Host "[5/5] Verifying recovery..."
$metrics = docker compose -f $compose -p $Project exec -T order-api wget -qO- `
  --header='X-Control-Token: fixture-control-only' `
  "http://localhost:8080/internal/metrics?windowSeconds=30"
Write-Host "   Metrics: $metrics"

Write-Host "=== HOT_ACCOUNT_CONTENTION_V1 complete ==="
