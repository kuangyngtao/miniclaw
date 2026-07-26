<#
.SYNOPSIS
    Clawkit release packaging — builds JAR, Windows ZIP, and SHA-256 checksums.
    Called by both CI (release.yml) and local verification. Single source of truth.
.DESCRIPTION
    Accepts -Version and -OutputDir. Copies the shaded JAR, builds a Windows
    package directory (clawkit.jar + clawkit.cmd + README.md), creates the final
    ZIP, unpacks it to a temp dir, smoke-tests the unpacked artifacts, and
    generates SHA256SUMS.txt.  Exits non-zero on any failure.
.PARAMETER Version
    Release version string, e.g. "0.1.0". Required.
.PARAMETER OutputDir
    Directory to write release artifacts into. Default: "dist".
.EXAMPLE
    .\scripts\package-release.ps1 -Version "0.1.0"
    .\scripts\package-release.ps1 -Version "0.1.0" -OutputDir "release-out"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ═══════════════════════════════════════════════════════════════
# Paths
# ═══════════════════════════════════════════════════════════════
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$jarName  = "clawkit-$Version.jar"
$zipName  = "clawkit-$Version-windows.zip"
$winDir   = Join-Path $OutputDir "clawkit-$Version-windows"
$srcJar   = Join-Path $repoRoot "clawkit-cli/target/clawkit-cli-$Version.jar"

if (-not (Test-Path $srcJar)) {
    Write-Error "Shaded JAR not found: $srcJar. Run 'mvn -pl clawkit-cli -am package -DskipTests' first."
    exit 1
}

Write-Host "=== Packaging clawkit $Version ==="

# ═══════════════════════════════════════════════════════════════
# 1. Copy JAR
# ═══════════════════════════════════════════════════════════════
New-Item -ItemType Directory -Force $OutputDir | Out-Null
Copy-Item $srcJar (Join-Path $OutputDir $jarName)

# ═══════════════════════════════════════════════════════════════
# 2. Build Windows package directory
# ═══════════════════════════════════════════════════════════════
New-Item -ItemType Directory -Force $winDir | Out-Null
Copy-Item (Join-Path $OutputDir $jarName) (Join-Path $winDir "clawkit.jar")
Copy-Item (Join-Path $repoRoot "clawkit.cmd") (Join-Path $winDir "clawkit.cmd")
Copy-Item (Join-Path $repoRoot "README.md") (Join-Path $winDir "README.md")

# ═══════════════════════════════════════════════════════════════
# 3. Smoke JAR (before ZIP, catches shading problems early)
# ═══════════════════════════════════════════════════════════════
$jarPath = Join-Path $OutputDir $jarName

Write-Host "Smoke: java -jar ... --help"
$helpOutput = & java -jar $jarPath --help 2>&1
if ($LASTEXITCODE -ne 0) { throw "JAR --help failed: $helpOutput" }

Write-Host "Smoke: java -jar ... --version"
$jarVersion = & java -jar $jarPath --version 2>&1
$expectedJarVersion = "clawkit $Version"
if ($jarVersion -ne $expectedJarVersion) { throw "JAR --version mismatch: got '$jarVersion', expected '$expectedJarVersion'" }

# ═══════════════════════════════════════════════════════════════
# 4. Create ZIP
# ═══════════════════════════════════════════════════════════════
$zipPath = Join-Path $OutputDir $zipName
Compress-Archive -Path "$winDir/*" -DestinationPath $zipPath -Force

# ═══════════════════════════════════════════════════════════════
# 5. Unpack and verify from extracted directory
# ═══════════════════════════════════════════════════════════════
$unpackDir = Join-Path $OutputDir "_unpack-verify"
if (Test-Path $unpackDir) { Remove-Item -Recurse -Force $unpackDir }
Expand-Archive -Path $zipPath -DestinationPath $unpackDir

Write-Host "Smoke: unpacked clawkit.cmd --version"
$windowsVersion = & (Join-Path $unpackDir "clawkit.cmd") --version 2>&1
if ($windowsVersion -ne $expectedJarVersion) { throw "ZIP --version mismatch: got '$windowsVersion', expected '$expectedJarVersion'" }

# Verify both files exist and have content
$unpackedJar = Join-Path $unpackDir "clawkit.jar"
$unpackedCmd = Join-Path $unpackDir "clawkit.cmd"
$unpackedReadme = Join-Path $unpackDir "README.md"
foreach ($f in @($unpackedJar, $unpackedCmd, $unpackedReadme)) {
    if (-not (Test-Path $f)) { throw "Missing in ZIP: $(Split-Path $f -Leaf)" }
    if ((Get-Item $f).Length -eq 0) { throw "Zero-byte file in ZIP: $(Split-Path $f -Leaf)" }
}

# Clean up unpack dir
Remove-Item -Recurse -Force $unpackDir

# ═══════════════════════════════════════════════════════════════
# 6. Generate SHA-256 checksums
# ═══════════════════════════════════════════════════════════════
$checksumPath = Join-Path $OutputDir "SHA256SUMS.txt"
$jarHash = (Get-FileHash $jarPath -Algorithm SHA256).Hash.ToLower()
$zipHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()
@(
    "$jarHash  $jarName"
    "$zipHash  $zipName"
) | Set-Content $checksumPath -Encoding ASCII

# ═══════════════════════════════════════════════════════════════
# 7. Verify checksum file self-consistency
# ═══════════════════════════════════════════════════════════════
Write-Host "Verify: SHA256SUMS.txt"
$verified = 0
Get-Content $checksumPath | ForEach-Object {
    $parts = $_ -split '\s+', 2
    if ($parts.Count -eq 2) {
        $expectedHash = $parts[0]
        $fileName = $parts[1]
        $actualHash = (Get-FileHash (Join-Path $OutputDir $fileName) -Algorithm SHA256).Hash.ToLower()
        if ($actualHash -ne $expectedHash) {
            throw "Checksum mismatch for $fileName : expected $expectedHash, got $actualHash"
        }
        $verified++
    }
}
if ($verified -ne 2) { throw "SHA256SUMS.txt should cover 2 files, covered $verified" }

# ═══════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════
Write-Host ""
Write-Host "=== Release package complete ==="
Write-Host "JAR : $(Join-Path $OutputDir $jarName)"
Write-Host "ZIP : $zipPath"
Write-Host "SHA : $checksumPath"
Get-Content $checksumPath
