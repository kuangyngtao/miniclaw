param(
    [int]$Repeat = 20,
    [string]$Case,
    [switch]$AllowRealModel,
    [string]$Output = 'ops-fixtures/reports/ops-0b'
)
$ErrorActionPreference = 'Stop'
if (-not $AllowRealModel) { throw 'Pass -AllowRealModel to authorize billable provider calls.' }
mvn -q -pl extensions/clawkit-ops-loop -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
mvn -q -f ops-fixtures/postgres-lock/order-api/pom.xml package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Fixture package failed' }
$jar = 'extensions/clawkit-ops-loop/target/clawkit-ops-loop-0.1.0-all.jar'
$arguments = @('-cp', $jar, 'com.clawkit.ops.loop.OpsBlindBenchmarkRunner', '--repeat', $Repeat, '--output', $Output, '--allow-real-model')
if ($Case) { $arguments += @('--case', $Case) }
java @arguments
exit $LASTEXITCODE
