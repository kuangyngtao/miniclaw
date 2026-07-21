param(
    [ValidateRange(1, 100)]
    [int]$Repeat = 10,
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Compose = Join-Path $PSScriptRoot "app-down\compose.yaml"
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $PSScriptRoot "reports"
}

Push-Location $RepoRoot
try {
    & mvn -q -pl extensions/clawkit-ops-loop -am package "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
    $Jar = Join-Path $RepoRoot "extensions\clawkit-ops-loop\target\clawkit-ops-loop-0.1.0-all.jar"
    & java -jar $Jar --compose $Compose --output $Output --repeat $Repeat
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
